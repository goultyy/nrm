package mt.su.nrm.ui;

import mt.su.nrm.model.ServerProfile;
import mt.su.nrm.nginx.RemoteConfig;
import mt.su.nrm.nginx.VhostSettings;
import mt.su.nrm.nginx.VirtualHost;
import mt.su.nrm.ssh.CertificateService;
import mt.su.nrm.ssh.SshExecutor;
import mt.su.nrm.ssh.SshSession;
import mt.su.nrm.ssl.CertificateInfo;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Common ground of the SSL screens: they all show a view of the same certificate list (read from
 * the server once and shared through the connection), handle "not connected" and "loading" the same
 * way, and can run server work behind a progress window. Subclasses decide what to show and which
 * buttons to offer.
 */
abstract class CertificateViewBase extends BorderPane {

    static final Clock CLOCK = Clock.systemUTC();

    /** One table row. */
    record Row(CertificateInfo info, String usedBy) {
    }

    final ServerProfile profile;
    final ServerConnection connection;
    final TableView<Row> table = new TableView<>();
    final TextArea details = new TextArea();

    private final javafx.beans.value.ChangeListener<Number> versionListener = (obs, o, n) -> render();
    private final javafx.beans.value.ChangeListener<ServerConnection.State> stateListener = (obs, o, n) -> render();

    CertificateViewBase(ServerProfile profile, ServerConnection connection) {
        this.profile = profile;
        this.connection = connection;
        details.setEditable(false);
        details.setPrefRowCount(9);
        details.setStyle("-fx-font-family: 'Consolas', 'Menlo', monospace;");
        table.getSelectionModel().selectedItemProperty().addListener((obs, o, n) ->
                details.setText(n == null ? "" : n.info().details()));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED, e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY && e.getClickCount() == 2) {
                viewSelected();
            }
        });
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) {
                connection.certificatesVersionProperty().removeListener(versionListener);
                connection.stateProperty().removeListener(stateListener);
            } else {
                connection.certificatesVersionProperty().addListener(versionListener);
                connection.stateProperty().addListener(stateListener);
                if (connection.certificates() == null && connection.isConnected() && !connection.certificatesLoading()) {
                    reload();
                }
                render();
            }
        });
    }

    /** Called once the certificates are known: the content to show. */
    abstract Node content(List<CertificateInfo> all);

    // ---------------------------------------------------------------- rendering

    final void render() {
        if (!connection.isConnected()) {
            setCenter(centered(new Label("Connect to the server to see its certificates.")));
        } else if (connection.certificates() == null) {
            setCenter(centered(ProgressDialog.spinner(48), new Label("Reading certificates from the server...")));
        } else {
            setCenter(content(connection.certificates()));
        }
    }

    /** A table of certificates with a details pane below it, filled from {@code visible}. */
    final Node tableWithDetails(List<CertificateInfo> visible, Node buttons, Node note) {
        Map<String, Set<String>> usedBy = usedByPath();
        List<Row> rows = new ArrayList<>();
        for (CertificateInfo c : visible) {
            rows.add(new Row(c, String.join(", ", usedBy.getOrDefault(c.path(), Set.of()))));
        }
        table.setItems(FXCollections.observableArrayList(rows));
        VBox box = new VBox(10, table, details, buttons, note);
        box.setPadding(new Insets(12));
        VBox.setVgrow(table, Priority.ALWAYS);
        return box;
    }

    static TableColumn<Row, String> column(String title, double width, java.util.function.Function<Row, String> v) {
        TableColumn<Row, String> c = new TableColumn<>(title);
        c.setPrefWidth(width);
        c.setCellValueFactory(cell -> new javafx.beans.property.SimpleStringProperty(v.apply(cell.getValue())));
        return c;
    }

    /** The standard columns: names, issuer, expiry, coloured status, key, used by. */
    final void addStandardColumns() {
        table.getColumns().add(column("Names", 220, r -> r.info().error() != null ? "(unreadable)"
                : String.join(", ", r.info().displayNames())));
        table.getColumns().add(column("Issuer", 130, r -> issuerName(r.info())));
        table.getColumns().add(column("Expires", 95, r -> r.info().notAfter() == null ? ""
                : r.info().notAfter().toString().substring(0, 10)));
        TableColumn<Row, String> status = column("Status", 120, r -> statusText(r.info()));
        status.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(String text, boolean empty) {
                super.updateItem(text, empty);
                setText(empty ? null : text);
                Row row = empty || getTableRow() == null ? null : getTableRow().getItem();
                if (row == null) {
                    setStyle("");
                    return;
                }
                switch (row.info().status(CLOCK)) {
                    case EXPIRED:
                        setStyle("-fx-text-fill: #b00020; -fx-font-weight: bold;");
                        break;
                    case EXPIRING:
                        setStyle("-fx-text-fill: #b26a00; -fx-font-weight: bold;");
                        break;
                    default:
                        setStyle("");
                }
            }
        });
        table.getColumns().add(status);
        table.getColumns().add(column("Key", 90, r -> r.info().keyInfo()));
        table.getColumns().add(column("Used by", 140, Row::usedBy));
        table.getColumns().add(column("File", 230, r -> r.info().path()));
    }

    static String issuerName(CertificateInfo c) {
        if (c.error() != null) {
            return "";
        }
        if (c.selfSigned()) {
            return "self-signed";
        }
        String cn = c.issuerField("CN");
        return cn.isEmpty() ? c.issuerField("O").isEmpty() ? c.issuer() : c.issuerField("O") : cn;
    }

    static String statusText(CertificateInfo c) {
        switch (c.status(CLOCK)) {
            case EXPIRED:
                return "Expired " + (-c.daysLeft(CLOCK)) + " day(s) ago";
            case EXPIRING:
                return c.daysLeft(CLOCK) + " day(s) left";
            case OK:
                return c.daysLeft(CLOCK) + " days left";
            default:
                return c.error() == null ? "Unknown" : "Not a certificate";
        }
    }

    static Node centered(Node... nodes) {
        VBox box = new VBox(14, nodes);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(30));
        return box;
    }

    // ---------------------------------------------------------------- the CA

    /** Every certificate authority on the server: each ca.crt in the CA folder or one level below it. */
    final List<CertificateInfo> authorities() {
        if (connection.certificates() == null) {
            return List.of();
        }
        String base = profile.getPaths().getCaStorageDir().replaceAll("/+$", "");
        return connection.certificates().stream()
                .filter(c -> c.error() == null && c.path().endsWith("/ca.crt") && c.path().startsWith(base + "/"))
                .toList();
    }

    /** The folder names already used by authorities ("" is an older single CA directly in the folder). */
    final Set<String> authorityFolders() {
        Set<String> folders = new java.util.HashSet<>();
        String base = profile.getPaths().getCaStorageDir().replaceAll("/+$", "");
        for (CertificateInfo c : authorities()) {
            String dir = c.path().substring(0, c.path().length() - "/ca.crt".length());
            folders.add(dir.equals(base) ? "" : dir.substring(base.length() + 1));
        }
        return folders;
    }

    /** A name for the authority in lists: its common name and folder. */
    final String authorityLabel(CertificateInfo ca) {
        String base = profile.getPaths().getCaStorageDir().replaceAll("/+$", "");
        String dir = ca.path().substring(0, ca.path().length() - "/ca.crt".length());
        return ca.commonName() + (ca.selfSigned() ? "" : " [intermediate]")
                + (dir.equals(base) ? "" : "   (" + dir.substring(base.length() + 1) + ")");
    }

    /** The authority that signed this one (matched by issuer), or null for a root or if the parent is not on this server. */
    static CertificateInfo parentOf(CertificateInfo ca, List<CertificateInfo> cas) {
        if (ca.selfSigned()) {
            return null;
        }
        return cas.stream().filter(p -> p != ca && p.subject().equals(ca.issuer())).findFirst().orElse(null);
    }

    /** The authorities this one has signed. */
    static List<CertificateInfo> childrenOf(CertificateInfo ca, List<CertificateInfo> cas) {
        return cas.stream().filter(c -> c != ca && !c.selfSigned() && c.issuer().equals(ca.subject())).toList();
    }

    /** Roots first, each followed by the authorities it signed (and theirs), so a list reads as a tree. */
    static List<CertificateInfo> authorityTree(List<CertificateInfo> cas) {
        List<CertificateInfo> ordered = new ArrayList<>();
        for (CertificateInfo c : cas) {
            if (parentOf(c, cas) == null) {
                addWithChildren(c, cas, ordered);
            }
        }
        return ordered;
    }

    private static void addWithChildren(CertificateInfo ca, List<CertificateInfo> cas, List<CertificateInfo> out) {
        if (out.contains(ca)) {
            return;
        }
        out.add(ca);
        for (CertificateInfo child : childrenOf(ca, cas)) {
            addWithChildren(child, cas, out);
        }
    }

    /** How many authorities sit above this one. */
    static int depthOf(CertificateInfo ca, List<CertificateInfo> cas) {
        int depth = 0;
        for (CertificateInfo p = parentOf(ca, cas); p != null && depth < 20; p = parentOf(p, cas)) {
            depth++;
        }
        return depth;
    }

    /** The certificates signed by this authority (matched by issuer). */
    static List<CertificateInfo> issuedBy(CertificateInfo ca, List<CertificateInfo> all) {
        return all.stream()
                .filter(c -> c.error() == null && !c.authority() && !c.selfSigned() && c.issuer().equals(ca.subject()))
                .toList();
    }

    final boolean hasCa() {
        return !authorities().isEmpty();
    }

    /** Opens the read-only certificate window for the selected row. */
    final void viewSelected() {
        Row row = table.getSelectionModel().getSelectedItem();
        if (row != null) {
            CertificateViewDialog.show(window(), row.info(), row.usedBy());
        }
    }

    /** Right-click on a certificate: View, Use in a virtual host, Delete (the row under the pointer is selected first). */
    final void installContextMenu() {
        table.setRowFactory(t -> {
            javafx.scene.control.TableRow<Row> r = new javafx.scene.control.TableRow<>();
            r.setOnContextMenuRequested(e -> {
                if (r.isEmpty()) {
                    return;
                }
                table.getSelectionModel().select(r.getIndex());
                javafx.scene.control.MenuItem view = new javafx.scene.control.MenuItem("View");
                view.setOnAction(a -> viewSelected());
                javafx.scene.control.MenuItem use = new javafx.scene.control.MenuItem("Use in virtual host");
                use.setOnAction(a -> useInHost());
                javafx.scene.control.MenuItem download = new javafx.scene.control.MenuItem("Download");
                download.setOnAction(a -> downloadSelected());
                download.setDisable(r.getItem().info().error() != null || r.getItem().info().authority());
                javafx.scene.control.MenuItem delete = new javafx.scene.control.MenuItem("Delete");
                delete.setOnAction(a -> deleteSelectedCertificate());
                javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu(view, use, download,
                        new javafx.scene.control.SeparatorMenuItem(), delete);
                menu.show(r, e.getScreenX(), e.getScreenY());
                e.consume();
            });
            return r;
        });
    }

    /**
     * Saves the selected server certificate with its chain, in the format the user picks. Only public
     * certificates are fetched; the private key stays on the server (and is cut out on the server if the file
     * has one in it).
     */
    final void downloadSelected() {
        Row row = table.getSelectionModel().getSelectedItem();
        if (row == null) {
            return;
        }
        CertificateInfo c = row.info();
        if (c.error() != null || c.authority()) {
            Dialogs.info(window(), "Not a server certificate", "Choose a server certificate. A certificate authority is "
                    + "downloaded from the Authority tab.");
            return;
        }
        // Which file to make is asked first, in a list, rather than left to the save dialog's file-type drop-down.
        java.util.Optional<CertificateExportKind> chosen = CertificateFormatDialog.show(window(), c.commonName());
        if (chosen.isEmpty()) {
            return;
        }
        CertificateExportKind kind = chosen.get();
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Save: " + kind.title());
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(kind.filterName(), "*" + kind.extension()));
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("All files", "*.*"));
        chooser.setInitialFileName(kind.suggestedFileName(c.commonName()));
        java.io.File picked = chooser.showSaveDialog(window());
        if (picked == null) {
            return;
        }
        java.io.File target = new java.io.File(picked.getParentFile(), kind.withExtension(picked.getName()));
        List<CertificateInfo> known = connection.certificates() == null ? List.of() : connection.certificates();
        work("Downloading", "Fetching the certificate and its chain (public part only)...",
                () -> CertificateService.fetchExport(connection.session(), profile.getPaths(), c, known), export -> {
                    try {
                        CertificateExportKind.Output out = kind.render(export.certificate(), export.chain());
                        java.nio.file.Files.write(target.toPath(), out.bytes());
                        Dialogs.info(window(), "Saved", target.getName() + " was saved. " + out.note()
                                + "\n\nNo private key was downloaded.");
                    } catch (CertificateExportKind.NothingToSave e) {
                        Dialogs.info(window(), "There is nothing to save", c.commonName() + ": " + e.getMessage());
                    } catch (java.io.IOException e) {
                        Dialogs.error(window(), "Could not save the file", e.getMessage());
                    }
                });
    }

    /** A "Download" button that is only enabled while a server certificate (not a CA) is selected. */
    final javafx.scene.control.Button downloadButton() {
        javafx.scene.control.Button download = new javafx.scene.control.Button("Download");
        download.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(() -> {
            Row r = table.getSelectionModel().getSelectedItem();
            return r == null || r.info().error() != null || r.info().authority();
        }, table.getSelectionModel().selectedItemProperty()));
        download.setOnAction(e -> downloadSelected());
        return download;
    }

    /** A "View" button that is only enabled while a row is selected. */
    final javafx.scene.control.Button viewButton() {
        javafx.scene.control.Button view = new javafx.scene.control.Button("View");
        view.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        view.setOnAction(e -> viewSelected());
        return view;
    }

    // ---------------------------------------------------------------- server work

    /** Re-reads the certificates from the server; every SSL view redraws when it arrives. */
    final void reload() {
        SshSession session = connection.session();
        if (session == null || connection.certificatesLoading()) {
            return;
        }
        Set<String> inUse = usedByPath().keySet();
        connection.setCertificatesLoading(true);
        connection.setCertificates(null);
        SshExecutor.submit(() -> CertificateService.list(session, profile.getPaths(), inUse))
                .whenComplete((list, failure) -> Platform.runLater(() -> {
                    connection.setCertificatesLoading(false);
                    if (failure != null) {
                        connection.setCertificates(List.of());
                        fail("The certificates could not be read", failure);
                    } else {
                        connection.setCertificates(list);
                    }
                }));
    }

    final <T> void work(String title, String message, Callable<T> task, Consumer<T> onSuccess) {
        if (connection.session() == null) {
            Dialogs.error(window(), "Not connected", "Connect to the server first.");
            return;
        }
        ProgressDialog progress = ProgressDialog.show(window(), title, message);
        SshExecutor.submit(task).whenComplete((result, failure) -> Platform.runLater(() -> {
            progress.close();
            if (failure != null) {
                fail(title + " failed", failure);
            } else {
                onSuccess.accept(result);
            }
        }));
    }

    final void fail(String header, Throwable failure) {
        Dialogs.showOutput(window(), Alert.AlertType.ERROR, header, "The server reported a problem.",
                ConnectionManager.describeFailure(profile, connection, failure));
    }

    // ---------------------------------------------------------------- deleting

    /**
     * Deletes the selected certificate: an issued certificate (with its key, if asked) or a Let's
     * Encrypt certificate (through certbot). Certificates that a virtual host uses are refused, since
     * nginx would stop starting; the CA files are deleted from the Authority tab instead.
     */
    final void deleteSelectedCertificate() {
        Row row = table.getSelectionModel().getSelectedItem();
        if (row == null) {
            return;
        }
        CertificateInfo c = row.info();
        if (!row.usedBy().isBlank()) {
            Dialogs.info(window(), "This certificate is in use", "It is used by " + row.usedBy() + ". Change those virtual hosts to "
                    + "use another certificate and apply the change first, then delete it.");
            return;
        }
        String letsEncrypt = profile.getPaths().getLetsEncryptDir().replaceAll("/+$", "") + "/live/";
        if (c.path().startsWith(letsEncrypt) && !CertificatesPanel.lineageOf(c.path()).isEmpty()) {
            String lineage = CertificatesPanel.lineageOf(c.path());
            if (!Dialogs.confirm(window(), "Delete the Let's Encrypt certificate \"" + lineage + "\"?",
                    "certbot removes its certificate, key and renewal settings from the server. This happens immediately "
                            + "and can't be undone (you can request a new one later).", "Delete")) {
                return;
            }
            work("Deleting", "Deleting the certificate with certbot...",
                    () -> mt.su.nrm.ssh.CertbotService.delete(connection.session(), profile.getPaths(), lineage),
                    result -> finishDelete(result.ok(), "Certificate deleted", result.output()));
            return;
        }
        if (mt.su.nrm.ssh.CaService.isDeletableCertificate(profile.getPaths(), c.path())) {
            javafx.scene.control.CheckBox alsoKey = new javafx.scene.control.CheckBox("Also delete its private key (recommended)");
            alsoKey.setSelected(true);
            Label what = new Label(c.commonName() + "   (" + c.path() + ")");
            what.setWrapText(true);
            Label warning = new Label("The files are deleted from the server immediately and can't be recovered.");
            warning.setWrapText(true);
            if (!FormDialog.show(window(), "Delete certificate", "Delete", java.util.List::of, "Certificate", what,
                    "", alsoKey, "", warning)) {
                return;
            }
            boolean key = alsoKey.isSelected();
            work("Deleting", "Deleting the certificate on the server...",
                    () -> mt.su.nrm.ssh.CaService.deleteCertificate(connection.session(), profile.getPaths(), c.path(), key),
                    result -> finishDelete(result.ok(), "Certificate deleted", result.output()));
            return;
        }
        Dialogs.info(window(), "This certificate can't be deleted here", "Only certificates issued by your own CAs and Let's "
                + "Encrypt certificates can be deleted. Certificates in other places belong to something else on the server.");
    }

    final void finishDelete(boolean ok, String successHeader, String output) {
        if (ok) {
            reload();
        } else {
            Dialogs.showOutput(window(), Alert.AlertType.ERROR, "Nothing was deleted", "The server reported a problem.", output);
        }
    }

    // ---------------------------------------------------------------- use in a virtual host

    /** Certificate path -> the virtual hosts whose ssl_certificate points at it. */
    final Map<String, Set<String>> usedByPath() {
        Map<String, Set<String>> map = new HashMap<>();
        RemoteConfig config = connection.config();
        if (config != null) {
            for (VirtualHost h : config.virtualHosts()) {
                String cert = h.read().sslCertificate;
                if (!cert.isBlank()) {
                    map.computeIfAbsent(cert, k -> new LinkedHashSet<>()).add(h.displayName());
                }
            }
        }
        return map;
    }

    final void useInHost() {
        Row row = table.getSelectionModel().getSelectedItem();
        RemoteConfig config = connection.config();
        if (row == null || config == null) {
            return;
        }
        if (row.info().error() != null || row.info().authority()) {
            Dialogs.info(window(), "Not a server certificate", "Choose a server certificate, not a CA or an unreadable file.");
            return;
        }
        List<VirtualHost> hosts = new ArrayList<>();
        for (VirtualHost h : config.virtualHosts()) {
            if (config.readOnlyReason(h) == null) {
                hosts.add(h);
            }
        }
        if (hosts.isEmpty()) {
            Dialogs.info(window(), "No virtual hosts", "There is no editable virtual host to use it on.");
            return;
        }
        ChoiceDialog<String> choose = new ChoiceDialog<>();
        for (VirtualHost h : hosts) {
            choose.getItems().add(h.displayName() + "   (" + h.file().path() + ")");
        }
        choose.setSelectedItem(choose.getItems().get(0));
        choose.setTitle("Use certificate");
        choose.setHeaderText("Which virtual host should use " + row.info().commonName() + "?");
        choose.initOwner(window());
        String picked = choose.showAndWait().orElse(null);
        if (picked == null) {
            return;
        }
        VirtualHost host = hosts.get(choose.getItems().indexOf(picked));

        TextInputDialog keyDialog = new TextInputDialog(keyPathFor(row.info().path()));
        keyDialog.setTitle("Certificate key");
        keyDialog.setHeaderText("Path of the private key on the server");
        keyDialog.setContentText("Key file:");
        keyDialog.initOwner(window());
        String key = keyDialog.showAndWait().orElse(null);
        if (key == null || key.isBlank()) {
            return;
        }
        VhostSettings settings = host.read();
        settings.sslCertificate = row.info().path();
        settings.sslCertificateKey = key.strip();
        if (!settings.usesSsl()) {
            settings.listens.add(new VhostSettings.ListenSpec("443", "ssl"));
        }
        host.apply(settings);
        connection.configChanged();
        Dialogs.info(window(), "Pending change created", host.displayName() + " now uses this certificate"
                + (row.info().status(CLOCK) == CertificateInfo.Status.OK ? "" : " (note its status)")
                + ". Review and apply it under Pending Changes.");
        render();
    }

    /** Best guess at the key that goes with a certificate file. */
    static String keyPathFor(String certPath) {
        if (certPath.endsWith("/fullchain.pem")) {
            return certPath.substring(0, certPath.length() - "fullchain.pem".length()) + "privkey.pem";
        }
        if (certPath.endsWith("/cert.pem")) {
            return certPath.substring(0, certPath.length() - "cert.pem".length()) + "privkey.pem";
        }
        int dot = certPath.lastIndexOf('.');
        return (dot > certPath.lastIndexOf('/') ? certPath.substring(0, dot) : certPath) + ".key";
    }

    static Integer parseInt(String s) {
        try {
            return Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    final Window window() {
        return getScene() == null ? null : getScene().getWindow();
    }
}
