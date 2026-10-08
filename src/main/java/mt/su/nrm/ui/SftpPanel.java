package mt.su.nrm.ui;

import mt.su.nrm.model.ServerProfile;
import mt.su.nrm.ssh.SftpEntry;
import mt.su.nrm.ssh.SftpService;
import mt.su.nrm.ssh.SshExecutor;
import mt.su.nrm.ssh.SshSession;
import mt.su.nrm.util.TextFiles;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * An SFTP client: this computer on the left, the server on the right. Files and whole folders are
 * copied with the buttons between the panes. All server access goes through the logged SSH
 * session, in the background, so the window stays responsive and the command log shows every
 * operation.
 */
final class SftpPanel extends BorderPane {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    /** A row in either pane. */
    record Item(String name, boolean directory, long size, long modified, String permissions) {
    }

    private final ServerProfile profile;
    private final ServerConnection connection;

    private final Pane local = new Pane("This computer", false);
    private final Pane remote = new Pane("Server", true);

    private final Button upload = new Button("Upload  →");
    private final Button download = new Button("←  Download");
    private final ProgressBar progress = new ProgressBar(0);
    private final Label transferText = new Label();
    private final Button cancel = new Button("Cancel");
    private final AtomicBoolean cancelRequested = new AtomicBoolean();
    private boolean transferring;
    /** Files open in an editor window, so the same file isn't opened twice; keyed by side and full path. */
    private final java.util.Map<String, FileEditorWindow> editors = new java.util.HashMap<>();

    SftpPanel(ServerProfile profile, ServerConnection connection) {
        this(profile, connection, null);
    }

    /** @param startRemote the server folder to open first, or null for the login directory */
    SftpPanel(ServerProfile profile, ServerConnection connection, String startRemote) {
        this.profile = profile;
        this.connection = connection;

        upload.setMaxWidth(Double.MAX_VALUE);
        download.setMaxWidth(Double.MAX_VALUE);
        upload.setOnAction(e -> upload());
        download.setOnAction(e -> download());
        VBox middle = new VBox(10, upload, download);
        middle.setAlignment(Pos.CENTER);
        middle.setPadding(new Insets(0, 8, 0, 8));

        HBox panes = new HBox(local, middle, remote);
        HBox.setHgrow(local, Priority.ALWAYS);
        HBox.setHgrow(remote, Priority.ALWAYS);
        panes.setPadding(new Insets(12, 12, 4, 12));
        local.prefWidthProperty().bind(panes.widthProperty().multiply(0.5));
        remote.prefWidthProperty().bind(panes.widthProperty().multiply(0.5));

        progress.setMaxWidth(Double.MAX_VALUE);
        progress.setVisible(false);
        cancel.setVisible(false);
        cancel.setOnAction(e -> cancelRequested.set(true));
        transferText.setOpacity(0.75);
        HBox.setHgrow(progress, Priority.ALWAYS);
        HBox transfer = new HBox(8, progress, cancel);
        transfer.setAlignment(Pos.CENTER_LEFT);
        VBox bottom = new VBox(4, transfer, transferText);
        bottom.setPadding(new Insets(4, 12, 10, 12));

        setCenter(panes);
        setBottom(bottom);

        local.navigate(Path.of(System.getProperty("user.home")).toString());
        if (connection.isConnected() && startRemote != null) {
            remote.navigate(startRemote, true);
        } else if (connection.isConnected()) {
            openRemote();
        } else {
            remote.message("Connect to the server to browse its files.");
            updateButtons();
        }
    }

    /** Shows a server folder, or the nearest one above it that exists. */
    void goTo(String remoteFolder) {
        if (connection.isConnected()) {
            remote.navigate(remoteFolder, true);
        } else {
            remote.message("Connect to the server to browse its files.");
        }
    }

    /** Re-reads both folders. */
    void reload() {
        local.refresh();
        if (connection.isConnected()) {
            if (remote.path == null) {
                openRemote();
            } else {
                remote.refresh();
            }
        }
    }

    private void openRemote() {
        SshSession session = connection.session();
        if (session == null) {
            return;
        }
        remote.busy("Reading the server...");
        SshExecutor.submit(() -> session.sftpResolve("."))
                .whenComplete((home, failure) -> Platform.runLater(() -> {
                    if (failure != null) {
                        remote.message(describe(failure));
                    } else {
                        remote.navigate(home);
                    }
                }));
    }

    private String describe(Throwable failure) {
        return ConnectionManager.describeFailure(profile, connection, failure).replace('\n', ' ');
    }

    private void updateButtons() {
        // The local pane shows the list of drives while its path is empty; nothing can be copied there.
        boolean ready = !transferring && connection.isConnected() && remote.path != null
                && local.path != null && !local.path.isEmpty();
        upload.setDisable(!ready || local.selected().isEmpty());
        download.setDisable(!ready || remote.selected().isEmpty());
    }

    // ---------------------------------------------------------------- transfers

    private void upload() {
        SshSession session = connection.session();
        List<Item> picked = local.selected();
        if (session == null || picked.isEmpty() || local.path == null || remote.path == null) {
            return;
        }
        Set<String> existing = remote.names();
        List<String> clashes = picked.stream().map(Item::name).filter(existing::contains).toList();
        if (!confirmOverwrite(clashes, "on the server")) {
            return;
        }
        Path from = Path.of(local.path);
        String to = remote.path;
        runTransfer("Uploading", picked, listener -> {
            for (Item item : picked) {
                SftpService.upload(session, from.resolve(item.name()), to, listener);
            }
        }, remote);
    }

    private void download() {
        SshSession session = connection.session();
        List<Item> picked = remote.selected();
        if (session == null || picked.isEmpty() || local.path == null || remote.path == null) {
            return;
        }
        Set<String> existing = local.names();
        List<String> clashes = picked.stream().map(Item::name).filter(existing::contains).toList();
        if (!confirmOverwrite(clashes, "on this computer")) {
            return;
        }
        Path to = Path.of(local.path);
        String from = remote.path;
        runTransfer("Downloading", picked, listener -> {
            for (Item item : picked) {
                SftpService.download(session, SftpService.join(from, item.name()), item.name(), item.directory(),
                        to, listener);
            }
        }, local);
    }

    private boolean confirmOverwrite(List<String> clashes, String where) {
        if (clashes.isEmpty()) {
            return true;
        }
        String shown = clashes.stream().limit(8).collect(Collectors.joining("\n"))
                + (clashes.size() > 8 ? "\n... and " + (clashes.size() - 8) + " more" : "");
        return Dialogs.confirm(getScene().getWindow(), "Replace existing items?",
                "These already exist " + where + " and will be overwritten (folders are merged):\n\n" + shown,
                "Replace");
    }

    private interface TransferWork {
        void run(SftpService.Listener listener) throws IOException;
    }

    private void runTransfer(String verb, List<Item> items, TransferWork work, Pane refreshAfter) {
        transferring = true;
        cancelRequested.set(false);
        progress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        progress.setVisible(true);
        cancel.setVisible(true);
        transferText.setText(verb + "...");
        updateButtons();

        AtomicLong bytes = new AtomicLong();
        AtomicLong files = new AtomicLong();
        long[] lastUpdate = {0};
        SftpService.Listener listener = new SftpService.Listener() {
            @Override
            public void item(String description) {
                files.incrementAndGet();
                post(verb + " " + description);
            }

            @Override
            public void bytes(long delta) {
                bytes.addAndGet(delta);
                long now = System.nanoTime();
                if (now - lastUpdate[0] > 150_000_000L) {
                    lastUpdate[0] = now;
                    post(null);
                }
            }

            @Override
            public boolean cancelled() {
                return cancelRequested.get();
            }

            private volatile String current = "";

            private void post(String text) {
                if (text != null) {
                    current = text;
                }
                String shown = current + "   (" + bytesText(bytes.get()) + ")";
                Platform.runLater(() -> transferText.setText(shown));
            }
        };

        SshExecutor.submit(() -> {
            work.run(listener);
            return null;
        }).whenComplete((ignored, failure) -> Platform.runLater(() -> {
            transferring = false;
            progress.setVisible(false);
            cancel.setVisible(false);
            Throwable cause = failure == null ? null : (failure.getCause() != null ? failure.getCause() : failure);
            if (cause == null) {
                transferText.setText(verb.replace("ing", "ed") + " " + files.get() + " item(s), "
                        + bytesText(bytes.get()) + ".");
            } else if (cause instanceof SftpService.CancelledException) {
                transferText.setText("Cancelled after " + bytesText(bytes.get()) + ".");
            } else {
                transferText.setText("Failed: " + describe(failure));
                Dialogs.error(getScene() == null ? null : getScene().getWindow(), verb + " failed",
                        describe(failure));
            }
            refreshAfter.refresh();
            updateButtons();
        }));
    }

    static String bytesText(long n) {
        if (n < 1024) {
            return n + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = n;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format("%.1f %s", value, units[unit]);
    }

    // ---------------------------------------------------------------- one side

    /** A path bar, a file table and the folder actions for one side. */
    private final class Pane extends VBox {
        private final boolean isRemote;
        private final TextField pathField = new TextField();
        private final TableView<Item> table = new TableView<>();
        private final Label status = new Label();
        private final Button up = new Button("Up");
        private final Button reload = new Button("Refresh");
        private final Button newFile = new Button("New file");
        private final Button newFolder = new Button("New folder");
        private final Button rename = new Button("Rename");
        private final Button delete = new Button("Delete");
        private final Button edit = new Button("Edit");
        /** The folder being shown; null before the first listing, and for the local drive list. */
        String path;
        private int generation;
        /** The folder that was asked for but doesn't exist, while the parent is being tried instead. */
        private String notFound = "";

        Pane(String title, boolean isRemote) {
            super(6);
            this.isRemote = isRemote;
            Label heading = new Label(title);
            heading.setStyle("-fx-font-weight: bold;");

            up.setOnAction(e -> goUp());
            reload.setOnAction(e -> refresh());
            newFile.setOnAction(e -> makeFile());
            newFolder.setOnAction(e -> makeFolder());
            rename.setOnAction(e -> renameSelected());
            delete.setOnAction(e -> deleteSelected());
            edit.setOnAction(e -> editSelected());
            pathField.setOnAction(e -> navigate(pathField.getText().strip()));
            HBox.setHgrow(pathField, Priority.ALWAYS);
            HBox bar = new HBox(6, up, pathField, reload);
            bar.setAlignment(Pos.CENTER_LEFT);
            HBox actions = new HBox(6, edit, newFile, newFolder, rename, delete);

            TableColumn<Item, String> name = new TableColumn<>("Name");
            name.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                    (c.getValue().directory() ? "📁 " : "") + c.getValue().name()));
            name.setPrefWidth(230);
            TableColumn<Item, String> size = new TableColumn<>("Size");
            size.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                    c.getValue().directory() ? "" : bytesText(c.getValue().size())));
            size.setPrefWidth(80);
            size.setStyle("-fx-alignment: CENTER-RIGHT;");
            TableColumn<Item, String> modified = new TableColumn<>("Modified");
            modified.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                    c.getValue().modified() <= 0 ? "" : WHEN.format(Instant.ofEpochSecond(c.getValue().modified()))));
            modified.setPrefWidth(120);
            table.getColumns().add(name);
            table.getColumns().add(size);
            table.getColumns().add(modified);
            if (isRemote) {
                TableColumn<Item, String> perms = new TableColumn<>("Permissions");
                perms.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                        c.getValue().permissions()));
                perms.setPrefWidth(90);
                table.getColumns().add(perms);
            }
            table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
            table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
            table.setPlaceholder(new Label(""));
            table.getSelectionModel().getSelectedItems().addListener(
                    (javafx.collections.ListChangeListener<Item>) c -> {
                        boolean one = table.getSelectionModel().getSelectedItems().size() == 1;
                        rename.setDisable(!one || !actionsAvailable());
                        edit.setDisable(!one || !actionsAvailable() || onDriveList()
                                || table.getSelectionModel().getSelectedItem().directory());
                        delete.setDisable(table.getSelectionModel().getSelectedItems().isEmpty()
                                || !actionsAvailable());
                        updateButtons();
                    });
            table.setRowFactory(tv -> {
                javafx.scene.control.TableRow<Item> row = new javafx.scene.control.TableRow<>();
                row.setOnMouseClicked(ev -> {
                    if (ev.getClickCount() == 2 && !row.isEmpty()) {
                        if (row.getItem().directory()) {
                            enter(row.getItem());
                        } else {
                            editSelected();
                        }
                    }
                });
                return row;
            });
            installMenu();
            VBox.setVgrow(table, Priority.ALWAYS);
            status.setOpacity(0.75);
            status.setWrapText(true);
            getChildren().addAll(heading, bar, table, actions, status);
            setEnabledState(false);
        }

        /** The right-click menu, and the same actions on the keyboard. */
        private void installMenu() {
            javafx.scene.control.MenuItem open = new javafx.scene.control.MenuItem("Open");
            open.setOnAction(e -> openSelected());
            javafx.scene.control.MenuItem editItem = new javafx.scene.control.MenuItem("Edit");
            editItem.setOnAction(e -> editSelected());
            javafx.scene.control.MenuItem copy = new javafx.scene.control.MenuItem(isRemote
                    ? "Download to this computer" : "Upload to server");
            copy.setOnAction(e -> {
                if (isRemote) {
                    download();
                } else {
                    upload();
                }
            });
            javafx.scene.control.MenuItem mkfile = new javafx.scene.control.MenuItem("New file");
            mkfile.setOnAction(e -> makeFile());
            javafx.scene.control.MenuItem mkdir = new javafx.scene.control.MenuItem("New folder");
            mkdir.setOnAction(e -> makeFolder());
            javafx.scene.control.MenuItem ren = new javafx.scene.control.MenuItem("Rename");
            ren.setOnAction(e -> renameSelected());
            javafx.scene.control.MenuItem del = new javafx.scene.control.MenuItem("Delete");
            del.setOnAction(e -> deleteSelected());
            javafx.scene.control.MenuItem copyPath = new javafx.scene.control.MenuItem("Copy path");
            copyPath.setOnAction(e -> copyPath());
            javafx.scene.control.MenuItem refreshItem = new javafx.scene.control.MenuItem("Refresh");
            refreshItem.setOnAction(e -> refresh());

            javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu(open, editItem, copy,
                    new javafx.scene.control.SeparatorMenuItem(), mkfile, mkdir, ren, del,
                    new javafx.scene.control.SeparatorMenuItem(), copyPath, refreshItem);
            menu.setOnShowing(e -> {
                List<Item> picked = selected();
                boolean usable = actionsAvailable() && !onDriveList();
                open.setDisable(path == null || picked.size() != 1 || !picked.get(0).directory());
                editItem.setDisable(edit.isDisable());
                copy.setDisable(isRemote ? download.isDisable() : upload.isDisable());
                mkfile.setDisable(!usable);
                mkdir.setDisable(!usable);
                ren.setDisable(!usable || picked.size() != 1);
                del.setDisable(!usable || picked.isEmpty());
                copyPath.setDisable(path == null || path.isEmpty() && picked.isEmpty());
                copyPath.setText(picked.isEmpty() ? "Copy folder path" : "Copy path");
                refreshItem.setDisable(path == null && isRemote);
            });
            table.setContextMenu(menu);

            table.setOnKeyPressed(ev -> {
                boolean usable = actionsAvailable() && !onDriveList();
                switch (ev.getCode()) {
                    case DELETE -> {
                        if (usable) {
                            deleteSelected();
                        }
                    }
                    case F2 -> {
                        if (usable) {
                            renameSelected();
                        }
                    }
                    case ENTER -> openSelected();
                    case BACK_SPACE -> goUp();
                    case F5 -> refresh();
                    default -> { }
                }
            });
        }

        private boolean onDriveList() {
            return !isRemote && path != null && path.isEmpty();
        }

        private void openSelected() {
            List<Item> picked = selected();
            if (path != null && picked.size() == 1 && picked.get(0).directory()) {
                enter(picked.get(0));
            } else if (path != null && picked.size() == 1) {
                editSelected();
            }
        }

        /**
         * Opens the selected file in a text editor window. The file is read, and later written, over the same
         * logged connection as every other explorer action; nothing is edited in place until Save.
         */
        private void editSelected() {
            List<Item> picked = selected();
            if (path == null || picked.size() != 1 || picked.get(0).directory() || onDriveList() || transferring) {
                return;
            }
            Item item = picked.get(0);
            String problem = TextFiles.sizeProblem(item.size());
            if (problem != null) {
                Dialogs.info(getScene().getWindow(), "Can't edit this file", problem);
                return;
            }
            String full = isRemote ? SftpService.join(path, item.name()) : Path.of(path).resolve(item.name()).toString();
            String key = (isRemote ? "server:" : "local:") + full;
            FileEditorWindow already = editors.get(key);
            if (already != null) {
                already.focus();
                return;
            }
            SshSession session = connection.session();
            if (isRemote && session == null) {
                message("Connect to the server to browse its files.");
                return;
            }
            String folder = path;
            status.setText("Opening " + item.name() + "...");
            SshExecutor.submit(() -> isRemote ? session.download(full) : Files.readAllBytes(Path.of(full)))
                    .whenComplete((data, failure) -> Platform.runLater(() -> {
                        if (failure != null) {
                            String message = isRemote ? describe(failure) : rootMessage(failure);
                            status.setText(message);
                            Dialogs.error(getScene().getWindow(), "Could not open " + item.name(), message);
                            return;
                        }
                        TextFiles.Text loaded;
                        try {
                            loaded = TextFiles.decode(data);
                        } catch (IOException e) {
                            status.setText(item.name() + " was not opened.");
                            Dialogs.info(getScene().getWindow(), "Can't edit this file", e.getMessage());
                            return;
                        }
                        String where = (isRemote ? profile.getName() + ":" : "") + full;
                        FileEditorWindow editor = new FileEditorWindow(getScene().getWindow(), where, loaded, bytes -> {
                            if (isRemote) {
                                SshSession now = connection.session();
                                if (now == null) {
                                    throw new IOException("Not connected to the server.");
                                }
                                now.upload(bytes, full);
                            } else {
                                Files.write(Path.of(full), bytes);
                            }
                        }, () -> {
                            if (folder.equals(path)) {
                                refresh();
                            }
                        }, () -> editors.remove(key));
                        editors.put(key, editor);
                        status.setText("Editing " + item.name());
                        editor.show();
                    }));
        }

        /** Puts the selected items' full paths (or the folder's, with nothing selected) on the clipboard. */
        private void copyPath() {
            List<Item> picked = selected();
            String text = picked.isEmpty() ? path : picked.stream().map(i -> isRemote
                    ? SftpService.join(path, i.name()) : Path.of(path).resolve(i.name()).toString())
                    .collect(Collectors.joining("\n"));
            if (text == null || text.isEmpty()) {
                return;
            }
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(text);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
            status.setText("Copied " + (picked.size() > 1 ? picked.size() + " paths" : "the path") + " to the clipboard.");
        }

        private boolean actionsAvailable() {
            return path != null && !transferring;
        }

        private void setEnabledState(boolean on) {
            up.setDisable(!on);
            reload.setDisable(!on);
            newFile.setDisable(!on);
            newFolder.setDisable(!on);
            edit.setDisable(true);
            rename.setDisable(true);
            delete.setDisable(true);
            pathField.setDisable(!on);
        }

        List<Item> selected() {
            return new ArrayList<>(table.getSelectionModel().getSelectedItems());
        }

        Set<String> names() {
            return table.getItems().stream().map(Item::name).collect(Collectors.toSet());
        }

        void message(String text) {
            path = null;
            table.getItems().clear();
            status.setText(text);
            setEnabledState(false);
            updateButtons();
        }

        void busy(String text) {
            status.setText(text);
        }

        void refresh() {
            if (path != null) {
                navigate(path);
            } else if (!isRemote) {
                navigate("");
            }
        }

        private void enter(Item item) {
            navigate(isRemote ? SftpService.join(path, item.name()) : Path.of(path).resolve(item.name()).toString());
        }

        private void goUp() {
            if (path == null) {
                return;
            }
            if (isRemote) {
                navigate(SftpService.parent(path));
            } else {
                Path parent = Path.of(path).getParent();
                navigate(parent == null ? "" : parent.toString()); // "" lists the drives
            }
        }

        /** Lists {@code target} in the background and shows it. Local "" means the list of drives. */
        void navigate(String target) {
            navigate(target, false);
        }

        /**
         * As above; with {@code fallbackToParent} a folder that can't be read (it doesn't exist yet) is
         * replaced by the nearest one above it that can, and the status line says so.
         */
        void navigate(String target, boolean fallbackToParent) {
            SshSession session = connection.session();
            if (isRemote && session == null) {
                message("Connect to the server to browse its files.");
                return;
            }
            int mine = ++generation;
            status.setText("Reading " + (target.isEmpty() ? "drives" : target) + "...");
            SshExecutor.submit(() -> isRemote ? listRemote(session, target) : listLocal(target))
                    .whenComplete((items, failure) -> Platform.runLater(() -> {
                        if (mine != generation) {
                            return; // a newer navigation replaced this one
                        }
                        if (failure != null) {
                            if (fallbackToParent && isRemote && !target.equals("/") && !target.isEmpty()) {
                                notFound = notFound.isEmpty() ? target : notFound;
                                navigate(SftpService.parent(target), true);
                                return;
                            }
                            notFound = "";
                            status.setText(isRemote ? describe(failure) : rootMessage(failure));
                            pathField.setText(path == null ? "" : path);
                            setEnabledState(path != null);
                            updateButtons();
                            return;
                        }
                        path = target.isEmpty() && !isRemote ? "" : target;
                        pathField.setText(path);
                        table.getItems().setAll(items);
                        setEnabledState(true);
                        // The drive list can't be written to; "" is kept as the path so Up and Refresh work.
                        boolean drives = !isRemote && path.isEmpty();
                        newFile.setDisable(drives);
                        newFolder.setDisable(drives);
                        int dirs = (int) items.stream().filter(Item::directory).count();
                        status.setText((notFound.isEmpty() ? "" : notFound + " was not found; showing the nearest "
                                + "folder above it. ") + dirs + " folder(s), " + (items.size() - dirs) + " file(s)");
                        notFound = "";
                        if (drives) {
                            path = "";
                        }
                        updateButtons();
                    }));
        }

        private String rootMessage(Throwable failure) {
            Throwable c = failure.getCause() != null ? failure.getCause() : failure;
            return c instanceof java.nio.file.NoSuchFileException ? "No such folder: " + c.getMessage()
                    : c instanceof java.nio.file.AccessDeniedException ? "Access denied: " + c.getMessage()
                    : String.valueOf(c.getMessage());
        }

        /** Creates an empty file here. An existing file is never overwritten: the server refuses, and so does this. */
        private void makeFile() {
            ask("New file", "File name:", "").ifPresent(name -> {
                String problem = SftpService.fileNameProblem(name);
                if (problem == null && table.getItems().stream().anyMatch(i -> i.name().equals(name))) {
                    problem = name + " already exists in this folder.";
                }
                if (problem != null) {
                    Dialogs.error(getScene().getWindow(), "The file was not created", problem);
                    return;
                }
                operate("Creating file", () -> {
                    if (isRemote) {
                        connection.session().sftpCreateFile(SftpService.join(path, name));
                    } else {
                        Files.createFile(Path.of(path).resolve(name));
                    }
                });
            });
        }

        private void makeFolder() {
            ask("New folder", "Folder name:", "").ifPresent(name -> operate("Creating folder", () -> {
                if (isRemote) {
                    connection.session().sftpMkdir(SftpService.join(path, name));
                } else {
                    Files.createDirectory(Path.of(path).resolve(name));
                }
            }));
        }

        private void renameSelected() {
            List<Item> picked = selected();
            if (picked.size() != 1) {
                return;
            }
            Item item = picked.get(0);
            ask("Rename", "New name:", item.name()).ifPresent(name -> {
                if (name.equals(item.name())) {
                    return;
                }
                operate("Renaming", () -> {
                    if (isRemote) {
                        connection.session().sftpRename(SftpService.join(path, item.name()),
                                SftpService.join(path, name));
                    } else {
                        Files.move(Path.of(path).resolve(item.name()), Path.of(path).resolve(name));
                    }
                });
            });
        }

        private void deleteSelected() {
            List<Item> picked = selected();
            if (picked.isEmpty()) {
                return;
            }
            String list = picked.stream().limit(8).map(Item::name).collect(Collectors.joining("\n"))
                    + (picked.size() > 8 ? "\n... and " + (picked.size() - 8) + " more" : "");
            boolean folders = picked.stream().anyMatch(Item::directory);
            if (!Dialogs.confirm(getScene().getWindow(), "Delete " + picked.size() + " item(s)?",
                    "This permanently deletes, " + (isRemote ? "on the server" : "on this computer")
                            + (folders ? ", including everything inside any folders" : "") + ":\n\n" + list,
                    "Delete")) {
                return;
            }
            SftpService.Listener none = new SftpService.Listener() {
                public void item(String d) { }
                public void bytes(long n) { }
                public boolean cancelled() { return false; }
            };
            operate("Deleting", () -> {
                for (Item item : picked) {
                    if (isRemote) {
                        SftpService.delete(connection.session(), SftpService.join(path, item.name()),
                                item.directory(), none);
                    } else {
                        deleteLocal(Path.of(path).resolve(item.name()));
                    }
                }
            });
        }

        private Optional<String> ask(String title, String prompt, String initial) {
            TextInputDialog dialog = new TextInputDialog(initial);
            dialog.initOwner(getScene().getWindow());
            dialog.setTitle(title);
            dialog.setHeaderText(null);
            dialog.setContentText(prompt);
            return dialog.showAndWait().map(String::strip).filter(s -> !s.isEmpty());
        }

        private interface Op {
            void run() throws IOException;
        }

        /** Runs a folder change in the background, then re-reads the folder. */
        private void operate(String what, Op op) {
            status.setText(what + "...");
            SshExecutor.submit(() -> {
                op.run();
                return null;
            }).whenComplete((ignored, failure) -> Platform.runLater(() -> {
                if (failure != null) {
                    String message = isRemote ? describe(failure) : rootMessage(failure);
                    status.setText(message);
                    Dialogs.error(getScene().getWindow(), what + " failed", message);
                }
                refresh();
            }));
        }
    }

    // ---------------------------------------------------------------- listing helpers

    private static List<Item> listRemote(SshSession session, String dir) throws IOException {
        List<SftpEntry> entries = session.sftpList(dir);
        return sorted(entries.stream().map(e -> new Item(e.name(), e.directory(), e.size(), e.modified(),
                e.permissions())).toList());
    }

    private static List<Item> listLocal(String dir) throws IOException {
        if (dir.isEmpty()) {
            List<Item> drives = new ArrayList<>();
            for (Path root : FileSystems.getDefault().getRootDirectories()) {
                drives.add(new Item(root.toString(), true, 0, 0, ""));
            }
            return drives;
        }
        List<Item> items = new ArrayList<>();
        try (Stream<Path> stream = Files.list(Path.of(dir))) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                try {
                    boolean directory = Files.isDirectory(p);
                    items.add(new Item(p.getFileName().toString(), directory, directory ? 0 : Files.size(p),
                            Files.getLastModifiedTime(p).toMillis() / 1000, ""));
                } catch (IOException unreadable) {
                    // skip entries that can't be read (system files, broken links)
                }
            }
        }
        return sorted(items);
    }

    private static List<Item> sorted(List<Item> items) {
        List<Item> copy = new ArrayList<>(items);
        copy.sort(Comparator.comparing((Item i) -> !i.directory())
                .thenComparing(i -> i.name().toLowerCase(java.util.Locale.ROOT)));
        return copy;
    }

    private static void deleteLocal(Path target) throws IOException {
        if (Files.isDirectory(target) && !Files.isSymbolicLink(target)) {
            try (Stream<Path> children = Files.list(target)) {
                for (Path child : (Iterable<Path>) children::iterator) {
                    deleteLocal(child);
                }
            }
        }
        Files.delete(target);
    }
}
