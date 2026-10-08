package mt.su.nrm.ui;

import mt.su.nrm.model.ServerProfile;
import mt.su.nrm.ssh.Credentials;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;

import java.util.List;
import java.util.Optional;

/** Asks for the secrets a profile doesn't store. Nothing entered here is saved. */
public final class CredentialPrompt {

    private CredentialPrompt() {
    }

    /** Returns the completed credentials, or empty if the user cancelled. */
    public static Optional<Credentials> ask(Window owner, ServerProfile profile, Credentials stored) {
        List<Credentials.Missing> missing = Credentials.missing(profile, stored);
        if (missing.isEmpty()) {
            return Optional.of(stored);
        }

        PasswordField login = new PasswordField();
        PasswordField passphrase = new PasswordField();
        PasswordField sudo = new PasswordField();
        PasswordField gatewayLogin = new PasswordField();
        PasswordField gatewayPassphrase = new PasswordField();

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(12));
        int row = 0;
        if (missing.contains(Credentials.Missing.GATEWAY_PASSWORD)) {
            grid.addRow(row++, new Label("Password for gateway " + profile.gatewayDisplayAddress()), gatewayLogin);
        }
        if (missing.contains(Credentials.Missing.GATEWAY_KEY_PASSPHRASE)) {
            gatewayPassphrase.setPromptText("leave empty if the key has none");
            grid.addRow(row++, new Label("Passphrase for the gateway private key"), gatewayPassphrase);
        }
        if (missing.contains(Credentials.Missing.LOGIN_PASSWORD)) {
            grid.addRow(row++, new Label("Password for " + profile.displayAddress()), login);
        }
        if (missing.contains(Credentials.Missing.KEY_PASSPHRASE)) {
            passphrase.setPromptText("leave empty if the key has none");
            grid.addRow(row++, new Label("Passphrase for the private key"), passphrase);
        }
        if (missing.contains(Credentials.Missing.SUDO_PASSWORD)) {
            sudo.setPromptText("leave empty to use the login password");
            grid.addRow(row, new Label("sudo password"), sudo);
        }

        Dialog<Credentials> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Credentials for " + profile.getName());
        dialog.setHeaderText("These are used for this connection only and are not saved.");
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(button -> {
            if (button != ButtonType.OK) {
                return null;
            }
            String password = missing.contains(Credentials.Missing.LOGIN_PASSWORD)
                    ? login.getText() : stored.password();
            String keyPassphrase = missing.contains(Credentials.Missing.KEY_PASSPHRASE)
                    ? passphrase.getText() : stored.keyPassphrase();
            // An empty sudo entry means "reuse the login password", which Credentials does for null.
            String sudoPassword = missing.contains(Credentials.Missing.SUDO_PASSWORD)
                    ? (sudo.getText().isEmpty() ? null : sudo.getText()) : stored.rawSudoPassword();
            String gatewayPassword = missing.contains(Credentials.Missing.GATEWAY_PASSWORD)
                    ? gatewayLogin.getText() : stored.gatewayPassword();
            String gatewayKeyPassphrase = missing.contains(Credentials.Missing.GATEWAY_KEY_PASSPHRASE)
                    ? gatewayPassphrase.getText() : stored.gatewayKeyPassphrase();
            return new Credentials(password, keyPassphrase, sudoPassword, gatewayPassword, gatewayKeyPassphrase);
        });
        return dialog.showAndWait();
    }
}
