package mt.su.nrm.ui;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/** Asks which file to save a server certificate as, with a line of explanation for each. */
final class CertificateFormatDialog {

    /** Remembered for the rest of the session, so saving several certificates the same way is one click each. */
    private static CertificateExportKind last = CertificateExportKind.PEM_FULL;

    private CertificateFormatDialog() {
    }

    static Optional<CertificateExportKind> show(Window owner, String certificateName) {
        Dialog<CertificateExportKind> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Download " + certificateName);
        dialog.setHeaderText(null);
        ButtonType next = new ButtonType("Choose where to save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(next, ButtonType.CANCEL);

        ToggleGroup group = new ToggleGroup();
        Map<CertificateExportKind, RadioButton> buttons = new EnumMap<>(CertificateExportKind.class);
        VBox box = new VBox(10);
        box.setPadding(new Insets(8));
        Label intro = new Label("What do you want to save for " + certificateName + "?");
        intro.setStyle("-fx-font-weight: bold;");
        box.getChildren().add(intro);
        for (CertificateExportKind kind : CertificateExportKind.values()) {
            RadioButton radio = new RadioButton(kind.title());
            radio.setToggleGroup(group);
            radio.setUserData(kind);
            Label what = new Label(kind.description());
            what.setWrapText(true);
            what.setOpacity(0.8);
            what.setPadding(new Insets(0, 0, 0, 24));
            buttons.put(kind, radio);
            if (kind.isKey()) {
                // Set apart and marked: it is the one choice that moves a secret off the server.
                box.getChildren().add(new javafx.scene.control.Separator());
                radio.setStyle("-fx-text-fill: #b00020; -fx-font-weight: bold;");
            }
            box.getChildren().addAll(radio, what);
        }
        buttons.get(last).setSelected(true);
        Label note = new Label("The first four contain only public certificates. The private key is a secret: it stays on "
                + "the server unless you choose it here, and then you are warned first.");
        note.setWrapText(true);
        note.setOpacity(0.8);
        box.getChildren().add(note);
        box.setPrefWidth(520);
        dialog.getDialogPane().setContent(box);
        dialog.setResultConverter(b -> b == next ? (CertificateExportKind) group.getSelectedToggle().getUserData() : null);
        Optional<CertificateExportKind> chosen = dialog.showAndWait();
        // The key is never remembered as the default: it must be chosen on purpose each time.
        chosen.filter(k -> !k.isKey()).ifPresent(k -> last = k);
        return chosen;
    }

    // For tests.

    static CertificateExportKind rememberedChoice() {
        return last;
    }

    static void forget() {
        last = CertificateExportKind.PEM_FULL;
    }
}
