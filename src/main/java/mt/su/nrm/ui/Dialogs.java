package mt.su.nrm.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.layout.VBox;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Priority;
import javafx.stage.Window;

/** Small helpers for the message boxes several screens share. */
final class Dialogs {

    private Dialogs() {
    }

    /** A message with long output (such as nginx's error text) in a scrollable, selectable box. */
    static void showOutput(Window owner, Alert.AlertType type, String header, String message, String output) {
        Alert alert = new Alert(type, message, ButtonType.OK);
        alert.setTitle("NRM");
        alert.setHeaderText(header);
        alert.initOwner(owner);
        if (output != null && !output.isBlank()) {
            TextArea area = new TextArea(output);
            area.setEditable(false);
            area.setPrefRowCount(12);
            area.setPrefColumnCount(70);
            area.setStyle("-fx-font-family: 'Consolas', 'Menlo', monospace;");
            javafx.scene.layout.GridPane.setVgrow(area, Priority.ALWAYS);
            alert.getDialogPane().setExpandableContent(area);
            alert.getDialogPane().setExpanded(true);
        }
        alert.setResizable(true);
        Dialogs.tighten(alert);
        alert.showAndWait();
    }

    static boolean confirm(Window owner, String header, String message, String confirmText) {
        ButtonType yes = new ButtonType(confirmText, javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, yes, ButtonType.CANCEL);
        alert.setTitle("NRM");
        alert.setHeaderText(header);
        alert.initOwner(owner);
        alert.getDialogPane().lookupButton(ButtonType.CANCEL).requestFocus();
        Dialogs.tighten(alert);
        return alert.showAndWait().filter(b -> b == yes).isPresent();
    }

    static void info(Window owner, String header, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        alert.setTitle("NRM");
        alert.setHeaderText(header);
        alert.initOwner(owner);
        Dialogs.tighten(alert);
        alert.showAndWait();
    }

    static void error(Window owner, String header, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.setTitle("NRM");
        alert.setHeaderText(header);
        alert.initOwner(owner);
        Dialogs.tighten(alert);
        alert.showAndWait();
    }

    /**
     * Trims an alert to its content: no large header band or icon, just a bold headline over the message with
     * modest padding. Call after the header, message and any expandable content are set, before showing it.
     */
    static void tighten(Alert alert) {
        tighten(alert, null);
    }

    /** As {@link #tighten(Alert)}, with an extra node (such as a details grid) under the message. */
    static void tighten(Alert alert, Node extra) {
        String header = alert.getHeaderText();
        String message = alert.getContentText();
        alert.setHeaderText(null);
        alert.setGraphic(null);
        VBox box = new VBox(6);
        if (header != null && !header.isBlank()) {
            Label h = new Label(header);
            h.setWrapText(true);
            h.setMaxWidth(460);
            String color = alert.getAlertType() == Alert.AlertType.ERROR ? "#b00020"
                    : alert.getAlertType() == Alert.AlertType.WARNING ? "#8a6d00" : "-fx-text-base-color";
            h.setStyle("-fx-font-weight: bold; -fx-text-fill: " + color + ";");
            box.getChildren().add(h);
        }
        if (message != null && !message.isBlank()) {
            Label m = new Label(message);
            m.setWrapText(true);
            m.setMaxWidth(460);
            m.setMinHeight(Region.USE_PREF_SIZE);
            box.getChildren().add(m);
        }
        if (extra != null) {
            box.getChildren().add(extra);
        }
        HBox row = new HBox(12, icon(alert.getAlertType()), box);
        row.setAlignment(javafx.geometry.Pos.TOP_LEFT);
        row.setPadding(new Insets(6, 8, 0, 8));
        alert.getDialogPane().setContent(row);
        alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
    }

    /** A small round badge: red ! for errors, amber ! for warnings, blue i for information, blue ? for questions. */
    private static Node icon(Alert.AlertType type) {
        String colour;
        String glyph;
        switch (type) {
            case ERROR -> {
                colour = "#c62828";
                glyph = "!";
            }
            case WARNING -> {
                colour = "#e08a00";
                glyph = "!";
            }
            case CONFIRMATION -> {
                colour = "#1565c0";
                glyph = "?";
            }
            default -> {
                colour = "#1565c0";
                glyph = "i";
            }
        }
        Circle disc = new Circle(15, Color.web(colour));
        Label mark = new Label(glyph);
        mark.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 18px;");
        StackPane badge = new StackPane(disc, mark);
        badge.setMinSize(30, 30);
        badge.setMaxSize(30, 30);
        return badge;
    }
}
