package mt.su.nrm.ui;

import mt.su.nrm.ssl.CertificateInfo;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * A read-only window with everything the server reported about one certificate: who it is for, who
 * issued it, when it is valid, its fingerprint and key details. Only public information is shown;
 * the private key is never read.
 */
final class CertificateViewDialog {

    private CertificateViewDialog() {
    }

    static void show(Window owner, CertificateInfo c, String usedBy) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Certificate");
        dialog.setResizable(true);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        Label title = new Label(c.error() != null ? "Unreadable file" : c.commonName());
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        Label status = new Label(CertificateViewBase.statusText(c) + (c.authority() ? "  -  certificate authority" : ""));
        switch (c.status(CertificateViewBase.CLOCK)) {
            case EXPIRED:
                status.setStyle("-fx-text-fill: #b00020; -fx-font-weight: bold;");
                break;
            case EXPIRING:
                status.setStyle("-fx-text-fill: #b26a00; -fx-font-weight: bold;");
                break;
            default:
                status.setOpacity(0.8);
        }

        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(4);
        ColumnConstraints label = new ColumnConstraints();
        label.setMinWidth(110);
        ColumnConstraints value = new ColumnConstraints();
        value.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(label, value);

        int row = 0;
        if (c.error() != null) {
            row = add(grid, row, "File", c.path());
            row = add(grid, row, "Problem", c.error());
        } else {
            row = section(grid, row, "Issued to");
            row = add(grid, row, "Common name", c.subjectField("CN"));
            row = add(grid, row, "Organisation", c.subjectField("O"));
            row = add(grid, row, "Unit", c.subjectField("OU"));
            row = add(grid, row, "Country", c.subjectField("C"));
            row = add(grid, row, "State", c.subjectField("ST"));
            row = add(grid, row, "City", c.subjectField("L"));
            row = add(grid, row, "E-mail", c.subjectField("emailAddress"));
            row = add(grid, row, "Valid for", String.join("\n", c.names()));
            row = section(grid, row, "Issued by");
            row = add(grid, row, "Issuer", c.selfSigned() ? "itself (self-signed)" : c.issuer());
            row = section(grid, row, "Validity");
            row = add(grid, row, "Valid from", c.notBefore() == null ? "" : c.notBefore().toString());
            row = add(grid, row, "Valid until", c.notAfter() == null ? "" : c.notAfter().toString());
            row = section(grid, row, "Technical");
            row = add(grid, row, "Serial", c.serial());
            row = add(grid, row, "Public key", c.keyInfo());
            row = add(grid, row, "Signature", c.signature());
            row = add(grid, row, "SHA-256", c.fingerprint());
            row = section(grid, row, "On the server");
            row = add(grid, row, "File", c.path());
            row = add(grid, row, "Used by", usedBy == null || usedBy.isBlank() ? "no virtual host" : usedBy);
        }

        Button copy = new Button("Copy details");
        copy.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(c.details());
            Clipboard.getSystemClipboard().setContent(content);
            copy.setText("Copied");
        });

        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(380);
        scroll.setPadding(new Insets(0, 8, 0, 0));
        VBox box = new VBox(10, title, status, scroll, new HBox(copy));
        box.setPadding(new Insets(8));
        box.setPrefWidth(620);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        dialog.getDialogPane().setContent(box);
        dialog.showAndWait();
    }

    private static int section(GridPane grid, int row, String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold;");
        GridPane.setMargin(l, new Insets(8, 0, 0, 0));
        grid.add(l, 0, row, 2, 1);
        return row + 1;
    }

    /** Adds a label and a value the user can select and copy; blank values are left out. */
    private static int add(GridPane grid, int row, String name, String text) {
        if (text == null || text.isBlank()) {
            return row;
        }
        Label l = new Label(name);
        l.setOpacity(0.75);
        GridPane.setValignment(l, javafx.geometry.VPos.TOP);
        Node v;
        if (text.contains("\n") || (text.length() > 60 && text.contains(" "))) {
            Label wrapped = new Label(text);
            wrapped.setWrapText(true);
            wrapped.setMaxWidth(Double.MAX_VALUE);
            v = wrapped;
        } else {
            // A read-only field, so long values (paths, fingerprints) scroll instead of being cut off and can be copied.
            javafx.scene.control.TextField field = new javafx.scene.control.TextField(text);
            field.setEditable(false);
            field.setStyle("-fx-background-color: transparent; -fx-background-insets: 0; -fx-padding: 0;"
                    + " -fx-focus-color: transparent; -fx-faint-focus-color: transparent;");
            v = field;
        }
        grid.addRow(row, l, v);
        return row + 1;
    }
}
