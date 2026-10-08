package mt.su.nrm.ui;

import mt.su.nrm.model.ServerProfile;
import mt.su.nrm.nginx.GlobalConfig;
import mt.su.nrm.nginx.GlobalSettings;
import mt.su.nrm.nginx.RemoteConfig;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Global settings: the server-wide directives of the main nginx.conf (workers, compression
 * defaults, logging, TLS defaults), and the detected layout of the configuration folder.
 */
final class GlobalSettingsPanel extends BorderPane {

    private static final String UNSET = "(not set)";

    private final ServerProfile profile;
    private final ServerConnection connection;
    private final ConnectionManager manager;
    private final javafx.beans.value.ChangeListener<ServerConnection.ConfigState> stateListener =
            (obs, o, n) -> refresh();

    private final TextField workerProcesses = field("auto or a number");
    private final TextField workerConnections = field("e.g. 1024");
    private final TextField errorLog = field("/var/log/nginx/error.log warn");
    private final ComboBox<String> sendfile = onOff();
    private final ComboBox<String> tcpNopush = onOff();
    private final TextField keepaliveTimeout = field("e.g. 65s");
    private final TextField clientMaxBodySize = field("e.g. 1m");
    private final ComboBox<String> serverTokens = choice("on", "off", "build");
    private final TextField sslProtocols = field("TLSv1.2 TLSv1.3");
    private final ComboBox<String> sslPreferServerCiphers = onOff();
    private final TextField accessLog = field("/var/log/nginx/access.log");
    private final ComboBox<String> gzip = onOff();
    private final TextField gzipCompLevel = field("1 - 9");
    private final TextField gzipMinLength = field("e.g. 256");
    private final TextField gzipTypes = field("text/css application/javascript application/json");
    private final Label problems = new Label();
    private final Button apply = new Button("Apply to pending changes");
    private boolean loading;

    GlobalSettingsPanel(ServerProfile profile, ServerConnection connection, ConnectionManager manager) {
        this.profile = profile;
        this.connection = connection;
        this.manager = manager;
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) {
                connection.configStateProperty().removeListener(stateListener);
            } else {
                connection.configStateProperty().addListener(stateListener);
                refresh();
            }
        });
        refresh();
    }

    private void refresh() {
        switch (connection.configState()) {
            case LOADING:
                setCenter(centered(ProgressDialog.spinner(48), new Label("Reading the nginx configuration...")));
                break;
            case LOADED:
                setCenter(loadedView());
                break;
            case FAILED:
                Button retry = new Button("Try again");
                retry.setOnAction(e -> manager.loadConfig(profile, () -> { }));
                Label error = new Label("The configuration could not be read:\n" + connection.configError());
                error.setWrapText(true);
                setCenter(centered(error, retry));
                break;
            default:
                setCenter(centered(new Label("The configuration has not been loaded.")));
        }
    }

    private Node loadedView() {
        RemoteConfig config = connection.config();
        GlobalConfig global = config.global();
        if (global == null) {
            return centered(new Label("The main nginx.conf has no http block, so there is nothing to edit here."));
        }
        String readOnly = config.mainReadOnlyReason();

        Label layout = new Label("Detected layout: " + config.layout().displayName() + "   (config folder "
                + config.confDir() + ")");
        layout.setStyle("-fx-font-weight: bold;");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        ColumnConstraints label = new ColumnConstraints();
        label.setMinWidth(190);
        ColumnConstraints f = new ColumnConstraints();
        f.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(label, f);
        int row = 0;
        row = section(grid, row, "Workers and logging");
        row = add(grid, row, "Worker processes", workerProcesses);
        row = add(grid, row, "Connections per worker", workerConnections);
        row = add(grid, row, "Error log", errorLog);
        row = add(grid, row, "Access log", accessLog);
        row = section(grid, row, "HTTP defaults");
        row = add(grid, row, "sendfile", sendfile);
        row = add(grid, row, "tcp_nopush", tcpNopush);
        row = add(grid, row, "Keepalive timeout", keepaliveTimeout);
        row = add(grid, row, "Maximum upload size", clientMaxBodySize);
        row = add(grid, row, "Show nginx version (server_tokens)", serverTokens);
        row = section(grid, row, "TLS defaults");
        row = add(grid, row, "SSL protocols", sslProtocols);
        row = add(grid, row, "Prefer server ciphers", sslPreferServerCiphers);
        row = section(grid, row, "Compression defaults");
        row = add(grid, row, "gzip", gzip);
        row = add(grid, row, "Level", gzipCompLevel);
        row = add(grid, row, "Minimum length", gzipMinLength);
        add(grid, row, "Types", gzipTypes);

        Button reset = new Button("Reset");
        reset.setOnAction(e -> load(global.read()));
        apply.setDisable(readOnly != null);
        apply.setOnAction(e -> {
            GlobalSettings s = current();
            List<String> found = s.problems();
            if (!found.isEmpty()) {
                return;
            }
            global.apply(s);
            connection.configChanged();
            load(global.read());
        });
        problems.setWrapText(true);
        problems.setStyle("-fx-text-fill: #b00020;");
        HBox buttons = new HBox(8, apply, reset);
        buttons.setAlignment(Pos.CENTER_LEFT);

        Label note = new Label(readOnly != null ? readOnly
                : "Empty fields are left out of nginx.conf (nginx then uses its own default). "
                + "Changes stay pending until you apply them.");
        note.setWrapText(true);
        note.setOpacity(0.8);

        load(global.read());
        for (TextField t : List.of(workerProcesses, workerConnections, errorLog, keepaliveTimeout, clientMaxBodySize,
                sslProtocols, accessLog, gzipCompLevel, gzipMinLength, gzipTypes)) {
            t.textProperty().addListener((obs, o, n) -> validate());
        }
        for (ComboBox<String> c : List.of(sendfile, tcpNopush, serverTokens, sslPreferServerCiphers, gzip)) {
            c.valueProperty().addListener((obs, o, n) -> validate());
        }
        validate();

        VBox box = new VBox(12, layout, grid, problems, buttons, note);
        box.setPadding(new Insets(14));
        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        return scroll;
    }

    // ---------------------------------------------------------------- data

    private void load(GlobalSettings s) {
        loading = true;
        try {
            workerProcesses.setText(s.workerProcesses);
            workerConnections.setText(s.workerConnections);
            errorLog.setText(s.errorLog);
            sendfile.setValue(orUnset(s.sendfile));
            tcpNopush.setValue(orUnset(s.tcpNopush));
            keepaliveTimeout.setText(s.keepaliveTimeout);
            clientMaxBodySize.setText(s.clientMaxBodySize);
            serverTokens.setValue(orUnset(s.serverTokens));
            sslProtocols.setText(s.sslProtocols);
            sslPreferServerCiphers.setValue(orUnset(s.sslPreferServerCiphers));
            accessLog.setText(s.accessLog);
            gzip.setValue(orUnset(s.gzip));
            gzipCompLevel.setText(s.gzipCompLevel);
            gzipMinLength.setText(s.gzipMinLength);
            gzipTypes.setText(s.gzipTypes);
        } finally {
            loading = false;
        }
        validate();
    }

    private GlobalSettings current() {
        GlobalSettings s = new GlobalSettings();
        s.workerProcesses = workerProcesses.getText().strip();
        s.workerConnections = workerConnections.getText().strip();
        s.errorLog = errorLog.getText().strip();
        s.sendfile = value(sendfile);
        s.tcpNopush = value(tcpNopush);
        s.keepaliveTimeout = keepaliveTimeout.getText().strip();
        s.clientMaxBodySize = clientMaxBodySize.getText().strip();
        s.serverTokens = value(serverTokens);
        s.sslProtocols = sslProtocols.getText().strip();
        s.sslPreferServerCiphers = value(sslPreferServerCiphers);
        s.accessLog = accessLog.getText().strip();
        s.gzip = value(gzip);
        s.gzipCompLevel = gzipCompLevel.getText().strip();
        s.gzipMinLength = gzipMinLength.getText().strip();
        s.gzipTypes = gzipTypes.getText().strip();
        return s;
    }

    private void validate() {
        if (loading) {
            return;
        }
        List<String> found = current().problems();
        problems.setText(String.join("\n", found));
        RemoteConfig config = connection.config();
        apply.setDisable(!found.isEmpty() || (config != null && config.mainReadOnlyReason() != null));
    }

    // ---------------------------------------------------------------- widgets

    private static String orUnset(String v) {
        return v == null || v.isEmpty() ? UNSET : v;
    }

    private static String value(ComboBox<String> box) {
        return box.getValue() == null || box.getValue().equals(UNSET) ? "" : box.getValue();
    }

    private static TextField field(String prompt) {
        TextField t = new TextField();
        t.setPromptText(prompt);
        return t;
    }

    private static ComboBox<String> onOff() {
        return choice("on", "off");
    }

    private static ComboBox<String> choice(String... values) {
        ComboBox<String> c = new ComboBox<>(FXCollections.observableArrayList());
        c.getItems().add(UNSET);
        c.getItems().addAll(values);
        c.setValue(UNSET);
        return c;
    }

    private static int section(GridPane grid, int row, String title) {
        Label l = new Label(title);
        l.setStyle("-fx-font-weight: bold;");
        l.setPadding(new Insets(row == 0 ? 0 : 8, 0, 0, 0));
        grid.add(l, 0, row, 2, 1);
        return row + 1;
    }

    private static int add(GridPane grid, int row, String label, Node field) {
        grid.addRow(row, new Label(label), field);
        return row + 1;
    }

    private static Node centered(Node... nodes) {
        VBox box = new VBox(14, nodes);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(30));
        return box;
    }
}
