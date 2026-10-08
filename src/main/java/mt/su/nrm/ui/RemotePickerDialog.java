package mt.su.nrm.ui;

import mt.su.nrm.ssh.RemotePaths;
import mt.su.nrm.ssh.SftpEntry;
import mt.su.nrm.ssh.SftpService;
import mt.su.nrm.ssh.SshExecutor;
import mt.su.nrm.ui.RemotePickerModel.Kind;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Choose a folder (or, for a file field, a file) on the server: a cut-down version of the File Explorer's server side.
 * Only the server is shown, so a path on this computer can't be picked by mistake. Listing goes through the logged SFTP
 * session off the JavaFX thread, so a slow server never freezes the window.
 * <p>
 * In folder mode files can be shown for orientation (the "Show files" box) but are greyed out and can't be chosen. In
 * file mode the box starts on and only a file can be chosen; folders are for walking to it.
 */
final class RemotePickerDialog {

    /** What the picker needs from the server. Blocking; called off the JavaFX thread. */
    interface FileSystem {
        List<SftpEntry> list(String dir) throws IOException;

        void mkdir(String dir) throws IOException;
    }

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final FileSystem fs;
    private final Kind kind;
    private final String preselect;
    private final String start;

    private final TextField path = new TextField();
    private final TableView<SftpEntry> table = new TableView<>();
    private final CheckBox showFiles = new CheckBox("Show files");
    private final Label status = new Label();
    private final Label willChoose = new Label();
    private final Button up = new Button("Up");
    private final Button refresh = new Button("Refresh");
    private final Button newFolder = new Button("New folder");
    private final javafx.scene.control.ProgressIndicator spinner = ProgressDialog.spinner(16);
    private final SimpleBooleanProperty busy = new SimpleBooleanProperty();

    private String current = "/";
    private List<SftpEntry> entries = List.of();
    private Node selectButton;
    private Dialog<String> dialog;

    RemotePickerDialog(FileSystem fs, String fieldValue, Kind kind) {
        this.fs = fs;
        this.kind = kind;
        this.start = RemotePickerModel.startFolder(fieldValue, kind);
        this.preselect = RemotePickerModel.preselect(fieldValue, kind);
        build();
    }

    /** @return the chosen path, or empty if cancelled */
    Optional<String> showAndWait(Window owner) {
        dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(kind == Kind.FILE ? "Choose a file on the server" : "Choose a folder on the server");
        dialog.setHeaderText(null);
        dialog.setResizable(true);
        ButtonType select = new ButtonType("Select", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(select, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(content());
        selectButton = dialog.getDialogPane().lookupButton(select);
        busy.addListener((obs, o, n) -> refreshSelectState());
        refreshSelectState();
        dialog.setResultConverter(b -> b == select ? chosenNow() : null);
        dialog.setOnShown(e -> goTo(start, true));
        return dialog.showAndWait();
    }

    // ---------------------------------------------------------------- building

    private Node content() {
        return root;
    }

    private VBox root;

    private void build() {
        path.setPromptText("/var/www");
        path.setOnAction(e -> goTo(path.getText(), false));
        Button go = new Button("Go");
        go.setOnAction(e -> goTo(path.getText(), false));
        up.setOnAction(e -> goTo(RemotePaths.parent(current), false));
        refresh.setOnAction(e -> goTo(current, false));
        newFolder.setOnAction(e -> createFolder());
        // Up is not bound: it is also off at the root, which refreshSelectState works out.
        for (Button b : List.of(go, refresh, newFolder)) {
            b.disableProperty().bind(busy);
        }
        spinner.visibleProperty().bind(busy);
        HBox.setHgrow(path, Priority.ALWAYS);
        HBox bar = new HBox(6, up, path, go, refresh, spinner);
        bar.setAlignment(Pos.CENTER_LEFT);

        TableColumn<SftpEntry, String> name = new TableColumn<>("Name");
        name.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name() + (c.getValue().directory() ? "/" : "")));
        name.setPrefWidth(300);
        name.setSortable(false);
        TableColumn<SftpEntry, String> size = new TableColumn<>("Size");
        size.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().directory() ? ""
                : LogAnalysisView.size(c.getValue().size())));
        size.setPrefWidth(80);
        size.setSortable(false);
        TableColumn<SftpEntry, String> when = new TableColumn<>("Changed");
        when.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().modified() <= 0 ? ""
                : WHEN.format(Instant.ofEpochSecond(c.getValue().modified()))));
        when.setPrefWidth(130);
        when.setSortable(false);
        table.getColumns().addAll(List.of(name, size, when));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("This folder is empty."));
        table.setPrefSize(560, 320);
        table.setRowFactory(t -> {
            TableRow<SftpEntry> row = new TableRow<>() {
                @Override
                protected void updateItem(SftpEntry item, boolean empty) {
                    super.updateItem(item, empty);
                    // In folder mode a file is only there for orientation, so it is greyed out and can't be picked.
                    setDisable(item != null && kind == Kind.FOLDER && !item.directory());
                    setOpacity(item != null && kind == Kind.FOLDER && !item.directory() ? 0.55 : 1.0);
                }
            };
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    open(row.getItem());
                }
            });
            return row;
        });
        table.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> refreshSelectState());
        VBox.setVgrow(table, Priority.ALWAYS);

        showFiles.setSelected(RemotePickerModel.showFilesByDefault(kind));
        showFiles.setOnAction(e -> showEntries(null));
        showFiles.setTooltip(new javafx.scene.control.Tooltip(kind == Kind.FILE
                ? "Turn off to see only folders."
                : "Files are shown greyed out, to help you find your way. Only a folder can be chosen."));

        status.setWrapText(true);
        status.setOpacity(0.8);
        willChoose.setStyle("-fx-font-weight: bold;");
        willChoose.setWrapText(true);
        HBox tools = new HBox(10, showFiles, newFolder);
        tools.setAlignment(Pos.CENTER_LEFT);

        Label hint = new Label(kind == Kind.FILE
                ? "Double-click a folder to open it, and a file to choose it. This lists the server, not this computer."
                : "Double-click a folder to open it. Select chooses the folder you are in, or the one you have highlighted. "
                + "This lists the server, not this computer.");
        hint.setWrapText(true);
        hint.setOpacity(0.8);

        root = new VBox(8, hint, bar, table, tools, willChoose, status);
        root.setPadding(new Insets(6));
        root.setPrefWidth(600);
    }

    // ---------------------------------------------------------------- navigation

    /**
     * Opens a folder. With {@code nearest} (the first time, from what was typed in the field) a folder that doesn't exist
     * or can't be read falls back to the closest one above it that can.
     */
    void goTo(String wanted, boolean nearest) {
        if (busy.get()) {
            return;
        }
        String target = RemotePaths.normalize(wanted);
        if (!RemotePaths.usable(target)) {
            status.setText("That isn't a usable path.");
            return;
        }
        busy.set(true);
        status.setText("Reading " + target + "...");
        List<String> chain = nearest ? RemotePickerModel.fallbacks(target) : List.of(target);
        SshExecutor.submit(() -> {
            IOException last = null;
            for (String candidate : chain) {
                try {
                    return new Listing(candidate, fs.list(candidate));
                } catch (IOException e) {
                    last = e;
                }
            }
            throw last == null ? new IOException("Nothing to read.") : last;
        }).whenComplete((listing, failure) -> Platform.runLater(() -> {
            busy.set(false);
            if (failure != null) {
                Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                status.setText("Could not read " + target + ": " + cause.getMessage()
                        + (nearest ? "" : " You can type another path and press Go."));
                refreshSelectState();
                return;
            }
            current = listing.path();
            entries = listing.entries();
            path.setText(current);
            showEntries(nearest ? preselect : null);
            status.setText(listing.path().equals(target) ? "" : target + " doesn't exist or can't be read, so this is the "
                    + "nearest folder above it.");
        }));
    }

    private record Listing(String path, List<SftpEntry> entries) {
    }

    private void showEntries(String selectName) {
        List<SftpEntry> rows = RemotePickerModel.visible(entries, showFiles.isSelected());
        table.setItems(FXCollections.observableArrayList(rows));
        if (selectName != null) {
            for (SftpEntry e : rows) {
                if (e.name().equals(selectName)) {
                    table.getSelectionModel().select(e);
                    table.scrollTo(e);
                    break;
                }
            }
        }
        refreshSelectState();
    }

    private void open(SftpEntry entry) {
        if (entry.directory()) {
            goTo(SftpService.join(current, entry.name()), false);
        } else if (kind == Kind.FILE && dialog != null) {
            table.getSelectionModel().select(entry);
            dialog.setResult(chosenNow());
            dialog.close();
        }
    }

    private void createFolder() {
        TextInputDialog ask = new TextInputDialog();
        ask.initOwner(root.getScene() == null ? null : root.getScene().getWindow());
        ask.setTitle("New folder");
        ask.setHeaderText(null);
        ask.setContentText("Name of the new folder in " + current + ":");
        ask.showAndWait().map(String::strip).filter(s -> !s.isEmpty()).ifPresent(name -> {
            String problem = SftpService.fileNameProblem(name);
            if (problem != null) {
                Dialogs.error(root.getScene() == null ? null : root.getScene().getWindow(), "Can't create that folder", problem);
                return;
            }
            String parent = current;
            String full = SftpService.join(parent, name);
            busy.set(true);
            status.setText("Creating " + full + "...");
            SshExecutor.submit(() -> {
                fs.mkdir(full);
                return fs.list(parent);
            }).whenComplete((listing, failure) -> Platform.runLater(() -> {
                busy.set(false);
                if (failure != null) {
                    Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                    status.setText("Could not create " + full + ": " + cause.getMessage());
                    return;
                }
                entries = listing;
                status.setText("Created " + full + ".");
                showEntries(name);
            }));
        });
    }

    // ---------------------------------------------------------------- state

    private String chosenNow() {
        return RemotePickerModel.chosen(kind, current, table.getSelectionModel().getSelectedItem());
    }

    private void refreshSelectState() {
        String chosen = chosenNow();
        willChoose.setText(chosen == null ? (kind == Kind.FILE ? "Highlight a file to choose it." : "")
                : "Will choose: " + chosen);
        up.setDisable(busy.get() || current.equals("/"));
        if (selectButton != null) {
            selectButton.setDisable(busy.get() || chosen == null);
        }
    }

    // For tests.

    Node contentForTest() {
        return root;
    }

    String currentFolder() {
        return current;
    }

    List<String> rowNames() {
        return table.getItems().stream().map(e -> e.name() + (e.directory() ? "/" : "")).toList();
    }

    void select(String name) {
        table.getItems().stream().filter(e -> e.name().equals(name)).findFirst()
                .ifPresent(e -> table.getSelectionModel().select(e));
    }

    String chosen() {
        return chosenNow();
    }

    boolean isBusy() {
        return busy.get();
    }

    void setShowFiles(boolean on) {
        showFiles.setSelected(on);
        showEntries(null);
    }

    String statusText() {
        return status.getText();
    }

    boolean upEnabled() {
        return !up.isDisable();
    }

    /** The grey-out rule for a row, as the row factory applies it. */
    boolean rowDisabled(String name) {
        return table.getItems().stream().anyMatch(e -> e.name().equals(name) && kind == Kind.FOLDER && !e.directory());
    }
}
