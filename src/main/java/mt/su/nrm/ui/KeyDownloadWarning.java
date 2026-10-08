package mt.su.nrm.ui;

import mt.su.nrm.ssh.KeyExportService;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;
import java.util.Optional;

/**
 * The warning shown before a private key is downloaded. A private key is what makes a server the owner of its
 * certificate: anyone who holds it can pose as the site. The dialog says that plainly, shows which key file will be
 * read, and does not let you continue until you have ticked that you understand. Cancel is the default.
 */
final class KeyDownloadWarning {

    private KeyDownloadWarning() {
    }

    /** The risks, in plain words. Tested so the warning can't quietly lose a point. */
    static List<String> dangers() {
        return List.of(
                "Anyone who has this file can pretend to be your website. Browsers will trust them, because the "
                        + "certificate is genuine and the key is the proof.",
                "If they can also watch the traffic, they may be able to read it.",
                "If the file is ever copied, leaked or stolen, the only cure is to replace the certificate and key and "
                        + "revoke the old one. You cannot take a copy back.",
                "Do not email it, put it in chat, a ticket, a shared or cloud-synced folder, or a git repository. Keep it "
                        + "in a password manager or an encrypted folder, and delete copies you no longer need.",
                "Often a safer way is to give the other machine its own new certificate and key, so the key never has to "
                        + "move at all.");
    }

    /** What happens to the key if you go on. */
    static List<String> handling() {
        return List.of(
                "The key is read on the server as root and sent over the encrypted SSH connection.",
                "It is written only to the file you choose next, and that file is set so only your Windows account can open it.",
                "This app does not keep it or write it to its log. The command log records that a key was downloaded, "
                        + "and which file, but not the key.");
    }

    /** Whether the Continue button may be pressed. */
    static boolean canContinue(boolean understood, String keyPath) {
        return understood && KeyExportService.pathProblem(keyPath) == null;
    }

    /**
     * @param candidates key files to start from, most likely first (the field is editable)
     * @return the key path to download, or empty if cancelled
     */
    static Optional<String> show(Window owner, String certificateName, List<String> candidates) {
        Dialog<String> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Download a private key");
        dialog.setHeaderText("Download the private key of " + certificateName + "?");
        ButtonType go = new ButtonType("Download the key", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(go, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(ButtonType.CANCEL).requestFocus();

        Label danger = new Label("A private key is a secret. Read this first.");
        danger.setStyle("-fx-font-weight: bold; -fx-text-fill: #b00020; -fx-font-size: 14px;");
        VBox box = new VBox(8, danger);
        for (String d : dangers()) {
            Label l = new Label("•  " + d);
            l.setWrapText(true);
            box.getChildren().add(l);
        }
        Label how = new Label("What this does");
        how.setStyle("-fx-font-weight: bold;");
        box.getChildren().add(how);
        for (String h : handling()) {
            Label l = new Label("•  " + h);
            l.setWrapText(true);
            l.setOpacity(0.85);
            box.getChildren().add(l);
        }
        TextField path = new TextField(candidates.isEmpty() ? "" : candidates.get(0));
        path.setPromptText("/etc/letsencrypt/live/example.com/privkey.pem");
        Label pathHint = new Label("Key file on the server. The server checks that it belongs to this certificate and "
                + "refuses if it does not.");
        pathHint.setWrapText(true);
        pathHint.setOpacity(0.8);
        CheckBox understood = new CheckBox("I understand the risks and I need this key file.");
        understood.setStyle("-fx-font-weight: bold;");
        box.getChildren().addAll(new Label("Key file"), path, pathHint, understood);
        box.setPadding(new Insets(6));
        box.setPrefWidth(560);
        dialog.getDialogPane().setContent(box);

        javafx.scene.Node goButton = dialog.getDialogPane().lookupButton(go);
        Runnable check = () -> goButton.setDisable(!canContinue(understood.isSelected(), path.getText()));
        understood.selectedProperty().addListener((obs, o, n) -> check.run());
        path.textProperty().addListener((obs, o, n) -> check.run());
        check.run();

        dialog.setResultConverter(b -> b == go ? path.getText() : null);
        return dialog.showAndWait();
    }
}
