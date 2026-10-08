package mt.su.nrm.ui;

import mt.su.nrm.model.AuthMethod;
import mt.su.nrm.model.PrivilegeMode;
import mt.su.nrm.model.ServerProfile;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Create/edit dialog for a server profile. Validation is live: problems appear under the fields
 * as soon as the user has touched the form, and OK stays disabled until there are none.
 * The dialog only builds a profile; saving it is the caller's job.
 */
public final class ProfileEditorDialog {

    private final ServerProfile base;
    private final Set<String> takenNames;
    private final Dialog<ServerProfile> dialog = new Dialog<>();

    private final TextField name = new TextField();
    private final TextField host = new TextField();
    private final TextField port = new TextField();
    private final TextField username = new TextField();
    private final ComboBox<AuthMethod> authMethod = new ComboBox<>();
    private final PasswordField password = new PasswordField();
    private final TextField keyPath = new TextField();
    private final Button browse = new Button("Browse");
    private final PasswordField keyPassphrase = new PasswordField();
    private final ComboBox<PrivilegeMode> privilegeMode = new ComboBox<>();
    private final PasswordField sudoPassword = new PasswordField();
    private final TextArea notes = new TextArea();

    private final CheckBox gatewayEnabled = new CheckBox("Connect through an SSH gateway (jump host)");
    private final TextField gatewayHost = new TextField();
    private final TextField gatewayPort = new TextField();
    private final TextField gatewayUsername = new TextField();
    private final ComboBox<AuthMethod> gatewayAuthMethod = new ComboBox<>();
    private final PasswordField gatewayPassword = new PasswordField();
    private final TextField gatewayKeyPath = new TextField();
    private final Button gatewayBrowse = new Button("Browse");
    private final PasswordField gatewayKeyPassphrase = new PasswordField();

    private final PasswordField cloudflareToken = new PasswordField();

    private final TextField nginxBinary = new TextField();
    private final TextField nginxConfDir = new TextField();
    private final TextField certbotBinary = new TextField();
    private final TextField letsEncryptDir = new TextField();
    private final TextField caStorageDir = new TextField();
    private final TextField manualCertDir = new TextField();
    private final TextField remoteTempDir = new TextField();

    private final Label errorLabel = new Label();
    private final BooleanProperty touched = new SimpleBooleanProperty(false);

    /**
     * @param existing   the profile being edited, or null to create one
     * @param takenNames names of the other profiles, for the duplicate-name check
     */
    public ProfileEditorDialog(ServerProfile existing, Set<String> takenNames) {
        this.base = existing == null ? new ServerProfile() : existing;
        this.takenNames = takenNames;
        build(existing == null);
        load(existing == null ? new ProfileForm() : ProfileForm.from(existing));
    }

    /** The dialog's content, for tests. */
    javafx.scene.control.DialogPane dialogPane() {
        return dialog.getDialogPane();
    }

    public Optional<ServerProfile> showAndWait(Window owner) {
        dialog.initOwner(owner);
        return dialog.showAndWait();
    }

    private void build(boolean creating) {
        dialog.setTitle(creating ? "Add Server" : "Edit Server");
        dialog.setResizable(true);
        ButtonType ok = new ButtonType(creating ? "Add" : "Save", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);

        name.setPromptText("e.g. Production web");
        host.setPromptText("hostname or IP address");
        port.setPrefColumnCount(6);
        authMethod.getItems().setAll(AuthMethod.values());
        privilegeMode.getItems().setAll(PrivilegeMode.values());
        password.setPromptText("leave empty to be asked when connecting");
        keyPassphrase.setPromptText("leave empty to be asked when connecting");
        sudoPassword.setPromptText("leave empty to use the login password");
        gatewayHost.setPromptText("hostname or IP address of the gateway");
        gatewayPort.setPrefColumnCount(6);
        gatewayAuthMethod.getItems().setAll(AuthMethod.values());
        gatewayPassword.setPromptText("leave empty to be asked when connecting");
        gatewayKeyPassphrase.setPromptText("leave empty to be asked when connecting");
        notes.setPrefRowCount(3);
        notes.setWrapText(true);

        GridPane connection = grid();
        int r = 0;
        connection.addRow(r++, new Label("Name"), name);
        connection.addRow(r++, new Label("Host"), host);
        connection.addRow(r++, new Label("Port"), port);
        connection.addRow(r++, new Label("Username"), username);
        connection.addRow(r++, new Label("Authentication"), authMethod);
        Label passwordLabel = new Label("Password");
        connection.addRow(r++, passwordLabel, password);
        Label keyLabel = new Label("Private key file");
        GridPane.setHgrow(keyPath, Priority.ALWAYS);
        javafx.scene.layout.HBox keyRow = new javafx.scene.layout.HBox(6, keyPath, browse);
        javafx.scene.layout.HBox.setHgrow(keyPath, Priority.ALWAYS);
        connection.addRow(r++, keyLabel, keyRow);
        Label passphraseLabel = new Label("Key passphrase");
        connection.addRow(r++, passphraseLabel, keyPassphrase);
        connection.addRow(r++, new Label("Root access"), privilegeMode);
        Label sudoLabel = new Label("sudo password");
        connection.addRow(r++, sudoLabel, sudoPassword);
        connection.addRow(r, new Label("Notes"), notes);

        GridPane gateway = grid();
        r = 0;
        gateway.addRow(r++, new Label("Gateway host"), gatewayHost);
        gateway.addRow(r++, new Label("Gateway port"), gatewayPort);
        gateway.addRow(r++, new Label("Gateway username"), gatewayUsername);
        gateway.addRow(r++, new Label("Gateway authentication"), gatewayAuthMethod);
        Label gatewayPasswordLabel = new Label("Gateway password");
        gateway.addRow(r++, gatewayPasswordLabel, gatewayPassword);
        Label gatewayKeyLabel = new Label("Gateway private key");
        javafx.scene.layout.HBox gatewayKeyRow = new javafx.scene.layout.HBox(6, gatewayKeyPath, gatewayBrowse);
        javafx.scene.layout.HBox.setHgrow(gatewayKeyPath, Priority.ALWAYS);
        gateway.addRow(r++, gatewayKeyLabel, gatewayKeyRow);
        Label gatewayPassphraseLabel = new Label("Gateway key passphrase");
        gateway.addRow(r, gatewayPassphraseLabel, gatewayKeyPassphrase);
        gateway.disableProperty().bind(gatewayEnabled.selectedProperty().not());
        VBox gatewayBox = new VBox(8, gatewayEnabled, gateway);
        TitledPane gatewayPane = new TitledPane("Gateway (the server is reached through this SSH host)", gatewayBox);
        gatewayPane.setExpanded(false);

        cloudflareToken.setPromptText("API token with Zone: Read, DNS: Edit and Cloudflare Tunnel: Edit");
        GridPane cloudflare = grid();
        cloudflare.addRow(0, new Label("API token"), cloudflareToken);
        TitledPane cloudflarePane = new TitledPane("Cloudflare (optional: DNS records and tunnel routes)", cloudflare);
        cloudflarePane.setExpanded(false);

        GridPane paths = grid();
        r = 0;
        paths.addRow(r++, new Label("Nginx binary"), nginxBinary);
        paths.addRow(r++, new Label("Nginx config folder"), nginxConfDir);
        paths.addRow(r++, new Label("Certbot binary"), certbotBinary);
        paths.addRow(r++, new Label("Let's Encrypt folder"), letsEncryptDir);
        paths.addRow(r++, new Label("CA storage folder"), caStorageDir);
        paths.addRow(r++, new Label("Manual certificate folder"), manualCertDir);
        paths.addRow(r, new Label("Remote temp folder"), remoteTempDir);
        TitledPane pathsPane = new TitledPane("Paths (defaults suit most Linux servers)", paths);
        pathsPane.setExpanded(false);

        errorLabel.setWrapText(true);
        errorLabel.setStyle("-fx-text-fill: #b00020;");
        errorLabel.setMinHeight(Label.USE_PREF_SIZE);

        VBox form = new VBox(10, connection, gatewayPane, cloudflarePane, pathsPane);
        form.setPadding(new Insets(4, 8, 4, 4));
        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportHeight(460);
        scroll.setMinViewportHeight(200);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        // Errors sit outside the scroll area so they stay visible however far the form is scrolled.
        VBox content = new VBox(10, scroll, errorLabel);
        content.setPadding(new Insets(12));
        content.setPrefWidth(560);
        dialog.getDialogPane().setContent(content);

        // Show only the fields that apply to the chosen authentication and root access modes.
        bindVisible(List.of(passwordLabel, password), authMethod.valueProperty(), v -> v == AuthMethod.PASSWORD);
        bindVisible(List.of(keyLabel, keyRow, passphraseLabel, keyPassphrase),
                authMethod.valueProperty(), v -> v == AuthMethod.PRIVATE_KEY);
        bindVisible(List.of(gatewayPasswordLabel, gatewayPassword),
                gatewayAuthMethod.valueProperty(), v -> v == AuthMethod.PASSWORD);
        bindVisible(List.of(gatewayKeyLabel, gatewayKeyRow, gatewayPassphraseLabel, gatewayKeyPassphrase),
                gatewayAuthMethod.valueProperty(), v -> v == AuthMethod.PRIVATE_KEY);
        bindVisible(List.of(sudoLabel, sudoPassword),
                privilegeMode.valueProperty(), v -> v == PrivilegeMode.SUDO_PASSWORD);

        browse.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Select private key");
            File chosen = chooser.showOpenDialog(dialog.getDialogPane().getScene().getWindow());
            if (chosen != null) {
                keyPath.setText(chosen.getAbsolutePath());
            }
        });

        gatewayBrowse.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Select gateway private key");
            File chosen = chooser.showOpenDialog(dialog.getDialogPane().getScene().getWindow());
            if (chosen != null) {
                gatewayKeyPath.setText(chosen.getAbsolutePath());
            }
        });

        Node okButton = dialog.getDialogPane().lookupButton(ok);
        for (TextField f : List.of(name, host, port, username, keyPath, gatewayHost, gatewayPort, gatewayUsername,
                gatewayKeyPath, nginxBinary, nginxConfDir,
                certbotBinary, letsEncryptDir, caStorageDir, manualCertDir, remoteTempDir)) {
            f.textProperty().addListener((obs, o, n) -> refresh(okButton, true));
        }
        gatewayEnabled.selectedProperty().addListener((obs, o, n) -> {
            // Ticking the box opens the section so its fields are in view. Filling the dialog from a saved server
            // also ticks it, and that must not open anything: every section starts collapsed.
            if (n && !loading) {
                gatewayPane.setExpanded(true);
            }
            refresh(okButton, true);
        });
        gatewayAuthMethod.valueProperty().addListener((obs, o, n) -> refresh(okButton, true));
        authMethod.valueProperty().addListener((obs, o, n) -> refresh(okButton, true));
        privilegeMode.valueProperty().addListener((obs, o, n) -> refresh(okButton, true));

        dialog.setResultConverter(button -> button == ok ? current().applyTo(base) : null);
        dialog.setOnShown(e -> refresh(okButton, false));
    }

    /** True while the fields are being filled from a profile: their change listeners must not run yet. */
    private boolean loading;

    private void load(ProfileForm f) {
        loading = true;
        try {
            fill(f);
        } finally {
            loading = false;
        }
    }

    private void fill(ProfileForm f) {
        name.setText(f.name);
        host.setText(f.host);
        port.setText(f.port);
        username.setText(f.username);
        authMethod.setValue(f.authMethod);
        password.setText(f.password);
        keyPath.setText(f.privateKeyPath);
        keyPassphrase.setText(f.privateKeyPassphrase);
        privilegeMode.setValue(f.privilegeMode);
        sudoPassword.setText(f.sudoPassword);
        notes.setText(f.notes);
        gatewayEnabled.setSelected(f.gatewayEnabled);
        gatewayHost.setText(f.gatewayHost);
        gatewayPort.setText(f.gatewayPort);
        gatewayUsername.setText(f.gatewayUsername);
        gatewayAuthMethod.setValue(f.gatewayAuthMethod);
        gatewayPassword.setText(f.gatewayPassword);
        gatewayKeyPath.setText(f.gatewayPrivateKeyPath);
        gatewayKeyPassphrase.setText(f.gatewayPrivateKeyPassphrase);
        cloudflareToken.setText(f.cloudflareToken);
        nginxBinary.setText(f.nginxBinary);
        nginxConfDir.setText(f.nginxConfDir);
        certbotBinary.setText(f.certbotBinary);
        letsEncryptDir.setText(f.letsEncryptDir);
        caStorageDir.setText(f.caStorageDir);
        manualCertDir.setText(f.manualCertDir);
        remoteTempDir.setText(f.remoteTempDir);
    }

    private ProfileForm current() {
        ProfileForm f = new ProfileForm();
        f.name = name.getText();
        f.host = host.getText();
        f.port = port.getText();
        f.username = username.getText();
        f.authMethod = authMethod.getValue();
        f.password = password.getText();
        f.privateKeyPath = keyPath.getText();
        f.privateKeyPassphrase = keyPassphrase.getText();
        f.privilegeMode = privilegeMode.getValue();
        f.sudoPassword = sudoPassword.getText();
        f.notes = notes.getText();
        f.gatewayEnabled = gatewayEnabled.isSelected();
        f.gatewayHost = gatewayHost.getText();
        f.gatewayPort = gatewayPort.getText();
        f.gatewayUsername = gatewayUsername.getText();
        f.gatewayAuthMethod = gatewayAuthMethod.getValue();
        f.gatewayPassword = gatewayPassword.getText();
        f.gatewayPrivateKeyPath = gatewayKeyPath.getText();
        f.gatewayPrivateKeyPassphrase = gatewayKeyPassphrase.getText();
        f.cloudflareToken = cloudflareToken.getText();
        f.nginxBinary = nginxBinary.getText();
        f.nginxConfDir = nginxConfDir.getText();
        f.certbotBinary = certbotBinary.getText();
        f.letsEncryptDir = letsEncryptDir.getText();
        f.caStorageDir = caStorageDir.getText();
        f.manualCertDir = manualCertDir.getText();
        f.remoteTempDir = remoteTempDir.getText();
        return f;
    }

    /** Always disables OK while invalid; only shows the messages once the user has edited something. */
    private void refresh(Node okButton, boolean userEdit) {
        if (loading || authMethod.getValue() == null || privilegeMode.getValue() == null
                || gatewayAuthMethod.getValue() == null) {
            return;
        }
        if (userEdit) {
            touched.set(true);
        }
        List<String> errors = current().errors(base, takenNames);
        okButton.setDisable(!errors.isEmpty());
        errorLabel.setText(touched.get() ? String.join("\n", errors) : "");
    }

    private static GridPane grid() {
        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(8);
        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(150);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        g.getColumnConstraints().addAll(labels, fields);
        return g;
    }

    private static <T> void bindVisible(List<? extends Node> nodes, ObservableValue<T> value,
                                        java.util.function.Predicate<T> shown) {
        Runnable apply = () -> {
            boolean show = shown.test(value.getValue());
            for (Node n : nodes) {
                n.setVisible(show);
                n.setManaged(show);
            }
        };
        value.addListener((obs, o, n) -> apply.run());
        apply.run();
    }
}
