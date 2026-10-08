package mt.su.nrm.ui;

import mt.su.nrm.nginx.VhostSettings;
import mt.su.nrm.nginx.VhostValidator;
import mt.su.nrm.nginx.VhostValidator.Issue;
import mt.su.nrm.nginx.VhostValidator.Severity;
import mt.su.nrm.security.SecurityHeaders;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The virtual host editor: General, Locations, SSL, Headers, Rewrites, Limits and Logging tabs. It
 * validates as you type (problems are listed at the bottom and errors disable Save) and returns
 * the edited settings; writing them into the config is the caller's job.
 */
public final class VirtualHostEditor {

    private final Dialog<VhostSettings> dialog = new Dialog<>();
    private final VhostSettings base;
    private final List<VhostSettings> others;
    private final ZoneNames zones;

    private final TextField serverNames = new TextField();
    private final TextArea listens = area(3, "One per line, e.g.\n80\n443 ssl http2\n[::]:443 ssl http2");
    private final TextField root = new TextField();
    /** The server behind this editor, for the "Open in File Explorer" buttons. */
    private final ServerAccess fileAccess;
    private final TextField index = new TextField();

    private final TextField sslCertificate = new TextField();
    private final TextField sslCertificateKey = new TextField();
    private final TextField sslProtocols = new TextField();
    private final TextField sslCiphers = new TextField();

    private final TextArea headers = area(8, "One per line: Name value [always]\ne.g. X-Frame-Options DENY always");
    private final TextArea rewrites = area(8,
            "One per line, in the order they run:\nrewrite ^/old/(.*)$ /new/$1 permanent\nreturn 301 https://example.com$request_uri");

    private final TextField clientMaxBodySize = new TextField();
    private final TextField limitRate = new TextField();
    private final TextArea limitReq = area(3, "One per line, e.g. zone=perip burst=10 nodelay");
    private final TextArea limitConn = area(3, "One per line, e.g. perip 10");

    private final TextArea accessLogs = area(3, "One per line, e.g. /var/log/nginx/site.access.log main\nor: off");
    private final TextField errorLog = new TextField();
    private final TextArea errorPages = area(4, "One per line, e.g. 404 /404.html");

    private final LocationsPane locationsPane;
    private final Label problems = new Label();
    private final Label preserved = new Label();
    private boolean loading = true;

    /**
     * @param initial     the settings to edit (not modified)
     * @param others      settings of the other virtual hosts on this server, for conflict warnings
     * @param unmanaged   statements in this block that the editor leaves alone, for the notice on the General tab
     * @param creating    true for a new virtual host
     */
    public VirtualHostEditor(VhostSettings initial, List<VhostSettings> others, List<String> unmanaged,
                             boolean creating) {
        this(initial, others, unmanaged, creating, ZoneNames.NONE);
    }

    /** @param zones names of the limit and cache zones defined on the server, to warn about unknown references */
    public VirtualHostEditor(VhostSettings initial, List<VhostSettings> others, List<String> unmanaged,
                             boolean creating, ZoneNames zones) {
        this(initial, others, unmanaged, creating, zones, ServerAccess.NONE);
    }

    VirtualHostEditor(VhostSettings initial, List<VhostSettings> others, List<String> unmanaged,
                      boolean creating, ZoneNames zones, ServerAccess access) {
        this.fileAccess = access;
        this.base = initial.copy();
        this.others = others;
        this.zones = zones;
        this.locationsPane = new LocationsPane(base.locations, zones, this::revalidate);
        locationsPane.useServer(access, () -> serverNames.getText().strip().split("\\s+")[0]);

        dialog.setTitle(creating ? "Add Virtual Host" : "Edit Virtual Host");
        dialog.setResizable(true);
        ButtonType save = new ButtonType(creating ? "Add" : "OK", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);

        serverNames.setPromptText("example.com www.example.com");
        root.setPromptText("/var/www/example.com");
        index.setPromptText("index.html index.htm");
        sslCertificate.setPromptText("/etc/letsencrypt/live/example.com/fullchain.pem");
        sslCertificateKey.setPromptText("/etc/letsencrypt/live/example.com/privkey.pem");
        sslProtocols.setPromptText("TLSv1.2 TLSv1.3");
        sslCiphers.setPromptText("leave empty for the nginx default");
        clientMaxBodySize.setPromptText("e.g. 20m (0 = unlimited)");
        limitRate.setPromptText("e.g. 500k");
        errorLog.setPromptText("/var/log/nginx/site.error.log warn");

        sections.put("General", general());
        sections.put("Locations", locationsPane);
        sections.put("SSL", sslPane(access));
        sections.put("Headers", headersPane());
        realIpPane = new RealIpPane(base.realIp, access, this::revalidate);
        sections.put("Real IP", realIpPane);
        sections.put("Rewrites", new LineListEditor(rewrites,
                "Rewrites and redirects change or answer requests before they reach a location, in order from top to bottom. "
                        + "Use them to move pages, send visitors to HTTPS or block addresses.",
                LineEntries::describeRewrite, EntryDialogs::rewrite));
        sections.put("Limits", limitsPane(zones));
        sections.put("Logging", loggingPane());
        sections.put("Error pages", new LineListEditor(errorPages,
                "Show your own page when the server would otherwise show a plain error such as 404 Not Found or 502 Bad Gateway.",
                LineEntries::describeErrorPage, EntryDialogs::errorPage));

        preserved.setWrapText(true);
        preserved.setOpacity(0.75);
        if (!unmanaged.isEmpty()) {
            preserved.setText(unmanaged.size() + " other setting(s) in this block are kept exactly as they are:\n"
                    + String.join("\n", unmanaged.subList(0, Math.min(6, unmanaged.size())))
                    + (unmanaged.size() > 6 ? "\n..." : ""));
        }
        problems.setWrapText(true);
        problems.setMinHeight(Label.USE_PREF_SIZE);

        load(VhostForm.from(base));
        wire();
        loading = false;

        Node saveButton = dialog.getDialogPane().lookupButton(save);
        dialog.setResultConverter(button -> button == save ? current() : null);
        dialog.setOnShown(e -> revalidate());
        this.saveButton = saveButton;
    }

    private RealIpPane realIpPane;
    private Node saveButton;
    private final java.util.Map<String, Node> sections = new java.util.LinkedHashMap<>();
    private final javafx.beans.property.SimpleBooleanProperty invalid = new javafx.beans.property.SimpleBooleanProperty();
    private java.util.function.Supplier<Window> embeddedWindow;

    public Optional<VhostSettings> showAndWait(Window owner) {
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        sections.forEach((title, node) -> tabs.getTabs().add(tab(title, node)));
        tabs.setPrefSize(720, 470);
        VBox content = new VBox(10, tabs, preserved, problems);
        content.setPadding(new Insets(8));
        VBox.setVgrow(tabs, Priority.ALWAYS);
        dialog.getDialogPane().setContent(content);
        dialog.initOwner(owner);
        return dialog.showAndWait();
    }

    // ---------------------------------------------------------------- embedding in the main window

    /** The editor's sections by title, in display order, for showing inside another window instead of the dialog. */
    java.util.Map<String, Node> sections() {
        return sections;
    }

    /** The notice about statements kept as they are, and the list of problems, for showing below a section. */
    Node notices() {
        return new VBox(4, preserved, problems);
    }

    /** True while the settings have errors (warnings don't count). */
    javafx.beans.property.ReadOnlyBooleanProperty invalidProperty() {
        return invalid;
    }

    /** Sets the window used to own dialogs opened from the sections when they are not inside the dialog. */
    void embedIn(java.util.function.Supplier<Window> window) {
        this.embeddedWindow = window;
    }

    /** The settings as edited so far. */
    VhostSettings result() {
        return current();
    }

    void validateNow() {
        revalidate();
    }

    private Window owner() {
        if (embeddedWindow != null) {
            return embeddedWindow.get();
        }
        return dialog.getDialogPane().getScene().getWindow();
    }

    // ---------------------------------------------------------------- building

    private Node general() {
        Label hint = new Label("Server names are separated by spaces.");
        hint.setOpacity(0.7);
        LineListEditor listenList = new LineListEditor(listens,
                "The addresses and ports this site answers on. Most sites need one for HTTP (port 80) and, with a "
                        + "certificate, one for HTTPS (port 443).",
                LineEntries::describeListen, EntryDialogs::listen);
        listenList.compact();
        VBox box = new VBox(8, form(new String[] {"Server names", "Document root", "Index files"},
                serverNames, FileTransferLinks.beside(root, () -> fileAccess, false), index), heading("Listen"), listenList, hint);
        return box;
    }

    /** The site's first name, as a file-name-safe word for suggesting log files. */
    private String siteSlug() {
        String first = serverNames.getText().strip().split("\s+")[0];
        return first.isEmpty() || first.equals("_") ? "default" : first.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private Node sslPane(ServerAccess access) {
        Button choose = new Button("Choose from server");
        choose.setOnAction(e -> access.listCertificates(owner(), certs -> chooseCertificate(certs)));
        Button importCert = new Button("Import existing certificate");
        importCert.setOnAction(e -> CertificateImportDialog.run(owner(), access, result -> {
            sslCertificate.setText(result.certPath());
            sslCertificateKey.setText(result.keyPath());
        }));
        Button view = new Button("View certificate");
        view.disableProperty().bind(sslCertificate.textProperty().isEmpty());
        view.setOnAction(e -> access.listCertificates(owner(), certs -> {
            String path = sslCertificate.getText().strip();
            certs.stream().filter(c -> c.path().equals(path)).findFirst().ifPresentOrElse(
                    c -> CertificateViewDialog.show(owner(), c, usedBy(path)),
                    () -> Dialogs.info(owner(), "Certificate not found", access.connected()
                            ? "No certificate was found at " + path + " on the server (it may be a new file that is not applied yet)."
                            : "Connect to the server to view its certificates."));
        }));
        Label hint = new Label("Choose a certificate that is already on the server (Let's Encrypt, your own CA, or imported), "
                + "or import one you already own from files on this computer. Both fill in the paths below.");
        hint.setWrapText(true);
        hint.setOpacity(0.8);
        VBox box = new VBox(4, new HBox(8, choose, importCert, view), hint,
                form(new String[] {"Certificate", "Certificate key", "Protocols", "Ciphers"},
                        FileTransferLinks.beside(sslCertificate, () -> fileAccess, true),
                        FileTransferLinks.beside(sslCertificateKey, () -> fileAccess, true), sslProtocols, sslCiphers));
        box.setPadding(new Insets(12, 0, 0, 0));
        VBox.setMargin(hint, new Insets(0, 12, 0, 12));
        HBox.setMargin(choose, new Insets(0, 0, 0, 12));
        return box;
    }

    /** The sites using this certificate file: this one (as edited) and any other on the server. */
    private String usedBy(String path) {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        String own = serverNames.getText().strip();
        names.add(own.isEmpty() ? "this virtual host" : own.split("\\s+")[0]);
        for (VhostSettings o : others) {
            if (path.equals(o.sslCertificate) && !o.serverNames.isEmpty()) {
                names.add(o.serverNames.get(0));
            }
        }
        return String.join(", ", names);
    }

    /** Lets the user pick one of the server's certificates and fills in the certificate and key paths. */
    private void chooseCertificate(java.util.List<mt.su.nrm.ssl.CertificateInfo> certs) {
        java.util.List<mt.su.nrm.ssl.CertificateInfo> usable = certs.stream()
                .filter(c -> c.error() == null && !c.authority()).toList();
        javafx.scene.control.Dialog<mt.su.nrm.ssl.CertificateInfo> pick = new javafx.scene.control.Dialog<>();
        pick.initOwner(owner());
        pick.setTitle("Certificates on the server");
        javafx.scene.control.TableView<mt.su.nrm.ssl.CertificateInfo> table = new javafx.scene.control.TableView<>(
                javafx.collections.FXCollections.observableArrayList(usable));
        table.setPlaceholder(new Label("No server certificates found (or not connected)."));
        addColumn(table, "Name", 170, c -> c.commonName());
        addColumn(table, "Names", 200, c -> String.join(", ", c.names()));
        addColumn(table, "Expires", 90, c -> c.notAfter() == null ? "" : c.notAfter().toString().substring(0, 10));
        addColumn(table, "File", 260, c -> c.path());
        table.setPrefSize(720, 300);
        pick.getDialogPane().setContent(table);
        ButtonType use = new ButtonType("Use", ButtonBar.ButtonData.OK_DONE);
        pick.getDialogPane().getButtonTypes().addAll(use, ButtonType.CANCEL);
        pick.getDialogPane().lookupButton(use).disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        pick.setResultConverter(b -> b == use ? table.getSelectionModel().getSelectedItem() : null);
        pick.showAndWait().ifPresent(c -> {
            sslCertificate.setText(c.path());
            sslCertificateKey.setText(CertificateViewBase.keyPathFor(c.path()));
        });
    }

    private static void addColumn(javafx.scene.control.TableView<mt.su.nrm.ssl.CertificateInfo> table, String title,
                                  double width, java.util.function.Function<mt.su.nrm.ssl.CertificateInfo, String> value) {
        javafx.scene.control.TableColumn<mt.su.nrm.ssl.CertificateInfo, String> col = new javafx.scene.control.TableColumn<>(title);
        col.setPrefWidth(width);
        col.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(value.apply(c.getValue())));
        table.getColumns().add(col);
    }

    private Node limitsPane(ZoneNames zones) {
        LineListEditor req = new LineListEditor(limitReq,
                "Slow down visitors who send too many requests. Zones are defined under Rate Limits.",
                LineEntries::describeLimitReq, (w, l) -> EntryDialogs.limitReq(w, l, zones.request()));
        LineListEditor conn = new LineListEditor(limitConn,
                "Stop one visitor from holding too many connections open.",
                LineEntries::describeLimitConn, (w, l) -> EntryDialogs.limitConn(w, l, zones.connection()));
        req.compact();
        conn.compact();
        VBox box = new VBox(6, form(new String[] {"Max upload size", "Rate limit"}, clientMaxBodySize, limitRate),
                heading("Request limits"), req, heading("Connection limits"), conn);
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(box);
        scroll.setFitToWidth(true);
        return scroll;
    }

    private Node loggingPane() {
        LineListEditor access = new LineListEditor(accessLogs,
                "Where visits to this site are recorded. Add more than one to keep separate logs, or choose \"no access log\".",
                LineEntries::describeAccessLog, (w, l) -> EntryDialogs.accessLog(w, l, siteSlug(),
                        fileAccess.logFormatNames()));
        access.compact();

        TextField path = new TextField();
        path.setPromptText("/var/log/nginx/" + "site.error.log");
        javafx.scene.control.ComboBox<String> level = new javafx.scene.control.ComboBox<>(
                javafx.collections.FXCollections.observableArrayList("", "debug", "info", "notice", "warn", "error", "crit",
                        "alert", "emerg"));
        level.setPromptText("default (error)");
        boolean[] syncing = {false};
        Runnable toField = () -> {
            if (!syncing[0]) {
                syncing[0] = true;
                String lv = level.getValue() == null ? "" : level.getValue();
                errorLog.setText(path.getText().strip().isEmpty() ? "" : (path.getText().strip() + (lv.isEmpty() ? "" : " " + lv)));
                syncing[0] = false;
            }
        };
        errorLog.textProperty().addListener((obs, o, n) -> {
            if (!syncing[0]) {
                syncing[0] = true;
                String[] parts = n == null ? new String[0] : n.strip().split("\s+");
                path.setText(parts.length > 0 ? parts[0] : "");
                level.setValue(parts.length > 1 ? parts[1] : "");
                syncing[0] = false;
            }
        });
        path.textProperty().addListener((obs, o, n) -> toField.run());
        level.valueProperty().addListener((obs, o, n) -> toField.run());
        Label hint = new Label("Errors are recorded at this level and above; \"warn\" is a good choice for most sites.");
        hint.setWrapText(true);
        hint.setOpacity(0.7);
        VBox box = new VBox(6, heading("Access logs"), access, heading("Error log"),
                form(new String[] {"Log file", "Level"}, path, level), hint);
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(box);
        scroll.setFitToWidth(true);
        return scroll;
    }

    private static Label heading(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold;");
        l.setPadding(new Insets(8, 12, 0, 12));
        return l;
    }

    /** The Headers tab: the header lines, with a button to add browser security headers from a guided list. */
    private Node headersPane() {
        Button security = new Button("Security headers");
        security.setOnAction(e -> {
            Window owner = headers.getScene() == null ? null : headers.getScene().getWindow();
            SecurityHeadersDialog.show(owner, current(), headers.getText()).ifPresent(chosen ->
                    headers.setText(SecurityHeaders.merge(headers.getText(), chosen)));
        });
        Label hint = new Label("Add the usual browser protections (HTTPS-only, no framing, no content sniffing and more), "
                + "with what each one can break.");
        hint.setWrapText(true);
        hint.setOpacity(0.8);
        HBox bar = new HBox(10, security, hint);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(12, 12, 0, 12));
        HBox.setHgrow(hint, Priority.ALWAYS);
        return new VBox(bar, form(new String[] {"add_header"}, headers));
    }

    private static Node form(String[] labels, Node... fields) {
        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(8);
        g.setPadding(new Insets(12));
        ColumnConstraints label = new ColumnConstraints();
        label.setMinWidth(120);
        ColumnConstraints field = new ColumnConstraints();
        field.setHgrow(Priority.ALWAYS);
        g.getColumnConstraints().addAll(label, field);
        for (int i = 0; i < fields.length; i++) {
            Label l = new Label(i < labels.length ? labels[i] : "");
            GridPane.setValignment(l, javafx.geometry.VPos.TOP);
            g.addRow(i, l, fields[i]);
        }
        return g;
    }

    private static Tab tab(String title, Node content) {
        Tab t = new Tab(title, content);
        t.setClosable(false);
        return t;
    }

    private static TextArea area(int rows, String prompt) {
        TextArea a = new TextArea();
        a.setPrefRowCount(rows);
        a.setPromptText(prompt);
        a.setStyle("-fx-font-family: 'Consolas', 'Menlo', monospace;");
        return a;
    }

    private void load(VhostForm f) {
        serverNames.setText(f.serverNames);
        listens.setText(f.listens);
        root.setText(f.root);
        index.setText(f.index);
        sslCertificate.setText(f.sslCertificate);
        sslCertificateKey.setText(f.sslCertificateKey);
        sslProtocols.setText(f.sslProtocols);
        sslCiphers.setText(f.sslCiphers);
        headers.setText(f.headers);
        rewrites.setText(f.rewrites);
        clientMaxBodySize.setText(f.clientMaxBodySize);
        limitRate.setText(f.limitRate);
        limitReq.setText(f.limitReq);
        limitConn.setText(f.limitConn);
        accessLogs.setText(f.accessLogs);
        errorLog.setText(f.errorLog);
        errorPages.setText(f.errorPages);
    }

    private void wire() {
        for (TextField f : List.of(serverNames, root, index, sslCertificate, sslCertificateKey, sslProtocols,
                sslCiphers, clientMaxBodySize, limitRate, errorLog)) {
            f.textProperty().addListener((obs, o, n) -> revalidate());
        }
        for (TextArea a : List.of(listens, headers, rewrites, limitReq, limitConn, accessLogs, errorPages)) {
            a.textProperty().addListener((obs, o, n) -> revalidate());
        }
    }

    // ---------------------------------------------------------------- reading and validating

    private VhostForm form() {
        VhostForm f = new VhostForm();
        f.serverNames = serverNames.getText();
        f.listens = listens.getText();
        f.root = root.getText();
        f.index = index.getText();
        f.sslCertificate = sslCertificate.getText();
        f.sslCertificateKey = sslCertificateKey.getText();
        f.sslProtocols = sslProtocols.getText();
        f.sslCiphers = sslCiphers.getText();
        f.headers = headers.getText();
        f.rewrites = rewrites.getText();
        f.clientMaxBodySize = clientMaxBodySize.getText();
        f.limitRate = limitRate.getText();
        f.limitReq = limitReq.getText();
        f.limitConn = limitConn.getText();
        f.accessLogs = accessLogs.getText();
        f.errorLog = errorLog.getText();
        f.errorPages = errorPages.getText();
        return f;
    }

    private VhostSettings current() {
        VhostSettings s = form().applyTo(base);
        s.locations = locationsPane.locations();
        s.realIp = realIpPane.value();
        return s;
    }

    private void revalidate() {
        if (loading || saveButton == null) {
            return;
        }
        VhostSettings s = current();
        List<Issue> issues = new ArrayList<>(VhostValidator.validate(s));
        issues.addAll(VhostValidator.checkConflicts(s, others));
        issues.addAll(VhostValidator.checkReferences(s, zones.request(), zones.connection(), zones.cache()));
        saveButton.setDisable(VhostValidator.hasErrors(issues));
        invalid.set(VhostValidator.hasErrors(issues));
        StringBuilder text = new StringBuilder();
        for (Issue i : issues) {
            text.append(i.severity() == Severity.ERROR ? "Error" : "Warning").append(" - ").append(i.where())
                    .append(": ").append(i.message()).append('\n');
        }
        problems.setText(text.toString().strip());
        problems.setStyle(VhostValidator.hasErrors(issues) ? "-fx-text-fill: #b00020;" : "-fx-text-fill: #8a6d00;");
    }
}
