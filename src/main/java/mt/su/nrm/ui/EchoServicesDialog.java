package mt.su.nrm.ui;

import mt.su.nrm.util.NetworkSettings;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The "what is my IP" services the app may ask from a server to learn its external address, in the order they are
 * tried. One address per line; only plain https addresses are accepted. Changes the app-wide setting, not one server's.
 */
final class EchoServicesDialog {

    private EchoServicesDialog() {
    }

    /** @return the list to save, or empty if cancelled */
    static Optional<List<String>> show(Window owner, List<String> current) {
        Dialog<List<String>> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Services that report an external address");
        dialog.setHeaderText(null);
        ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);

        Label intro = new Label("When a server's own cloud can't say what its external address is, the app asks one of "
                + "these services from the server, through the address in question. They are tried in this order and "
                + "the first answer is used. Only https:// addresses are accepted. This applies to every server.");
        intro.setWrapText(true);
        TextArea area = new TextArea(String.join("\n", current));
        area.setPrefRowCount(6);
        area.setPrefColumnCount(48);
        area.setStyle("-fx-font-family: 'Consolas', 'Menlo', monospace;");
        Label problems = new Label();
        problems.setWrapText(true);
        problems.setStyle("-fx-text-fill: #b00020;");
        Button reset = new Button("Reset to the defaults");
        reset.setOnAction(e -> area.setText(String.join("\n", NetworkSettings.DEFAULT_SERVICES)));

        javafx.scene.Node saveButton = dialog.getDialogPane().lookupButton(save);
        Runnable check = () -> {
            List<String> p = NetworkSettings.problems(lines(area.getText()));
            problems.setText(String.join("\n", p));
            problems.setManaged(!p.isEmpty());
            saveButton.setDisable(!p.isEmpty());
        };
        area.textProperty().addListener((obs, o, n) -> check.run());
        check.run();

        VBox box = new VBox(10, intro, area, reset, problems);
        box.setPadding(new Insets(8));
        box.setPrefWidth(520);
        dialog.getDialogPane().setContent(box);
        dialog.setResultConverter(b -> b == save ? lines(area.getText()) : null);
        return dialog.showAndWait();
    }

    static List<String> lines(String text) {
        return Arrays.stream(text.split("\\R")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
