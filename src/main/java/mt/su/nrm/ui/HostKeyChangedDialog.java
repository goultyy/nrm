package mt.su.nrm.ui;

import mt.su.nrm.config.ProfileRepository;
import mt.su.nrm.config.ProfileStoreException;
import mt.su.nrm.model.ServerProfile;
import mt.su.nrm.ssh.HostKeyChangedException;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.Window;

import java.net.InetAddress;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The warning shown when a server presents a different host key than the one pinned. The connection has
 * already been refused; this shows exactly what changed (the address it now resolves to, the old and new
 * fingerprints and the key type) and lets the user decide, deliberately, to trust the new key. Cancel is
 * the default so a stray Enter never accepts it.
 */
final class HostKeyChangedDialog {

    private HostKeyChangedDialog() {
    }

    /** Asks whether to trust the new key; true only if the user chose "Trust the new key". */
    static boolean confirm(Window owner, ServerProfile profile, HostKeyChangedException changed) {
        ButtonType trust = new ButtonType("Trust the new key and connect", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.WARNING, "", trust, cancel);
        alert.setTitle("NRM");
        alert.setHeaderText((changed.isGateway() ? "Gateway host key changed for " : "Host key changed for ")
                + profile.getName());
        alert.initOwner(owner);
        alert.setContentText("The server presented a different identity than the one saved the first time you connected. "
                + "This is expected if it was reinstalled or its SSH keys were regenerated. If you didn't expect a "
                + "change, someone may be intercepting the connection, so cancel and check with the server's administrator.");

        Label resolved = new Label("looking up...");
        GridPane details = new GridPane();
        details.setHgap(10);
        details.setVgap(6);
        ColumnConstraints names = new ColumnConstraints();
        ColumnConstraints values = new ColumnConstraints();
        values.setHgrow(Priority.ALWAYS);
        details.getColumnConstraints().addAll(names, values);
        int row = 0;
        String shownHost = changed.isGateway() ? profile.getGatewayHost() : profile.getHost();
        int shownPort = changed.isGateway() ? profile.getGatewayPort() : profile.getPort();
        details.addRow(row++, new Label(changed.isGateway() ? "Gateway" : "Server"),
                new Label(shownHost + ":" + shownPort));
        details.addRow(row++, new Label("Address now"), resolved);
        if (!changed.keyType().isEmpty()) {
            details.addRow(row++, new Label("Key type"), new Label(changed.keyType()));
        }
        details.addRow(row++, new Label("Saved key"), selectable(changed.pinned()));
        details.addRow(row, new Label("New key"), selectable(changed.presented()));
        details.setPadding(new Insets(6, 0, 0, 0));

        // Looking the name up can be slow, so it never runs on the UI thread.
        CompletableFuture.supplyAsync(() -> {
            try {
                return InetAddress.getByName(shownHost).getHostAddress();
            } catch (java.io.IOException | RuntimeException e) {
                return "could not be resolved";
            }
        }).thenAccept(address -> Platform.runLater(() -> resolved.setText(address)));

        Dialogs.tighten(alert, details);
        alert.getDialogPane().lookupButton(cancel).requestFocus();
        Optional<ButtonType> answer = alert.showAndWait();
        return answer.filter(b -> b == trust).isPresent();
    }

    /** Replaces the pinned key on the stored profile with the new one; null (after telling the user) if it can't be saved. */
    static ServerProfile pinNewKey(ProfileRepository repository, ServerProfile profile, HostKeyChangedException changed) {
        Optional<ServerProfile> current = repository.find(profile.getId());
        if (current.isEmpty()) {
            return null;
        }
        ServerProfile updated = current.get();
        if (changed.isGateway()) {
            updated.setGatewayHostKeyFingerprint(changed.presented());
        } else {
            updated.setHostKeyFingerprint(changed.presented());
        }
        try {
            return repository.update(updated);
        } catch (ProfileStoreException | RuntimeException e) {
            Dialogs.error(null, "Could not save the new host key", e.getMessage());
            return null;
        }
    }

    /** A read-only field, so the fingerprint can be selected and copied. */
    private static TextField selectable(String text) {
        TextField field = new TextField(text);
        field.setEditable(false);
        field.setPrefColumnCount(40);
        field.setStyle("-fx-font-family: 'Consolas', 'Menlo', monospace; -fx-background-color: transparent;"
                + " -fx-padding: 0;");
        return field;
    }
}
