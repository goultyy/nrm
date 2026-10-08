package mt.su.nrm.ui;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;
import java.util.function.Supplier;

/**
 * A simple labelled-fields dialog with live validation: problems are listed under the fields as
 * soon as anything changes and OK stays disabled until there are none.
 */
final class FormDialog {

    private FormDialog() {
    }

    /**
     * Runs {@code onChange} whenever an input inside {@code node} changes. A field may be inside a row or a group (a
     * text box with a Browse button beside it, say), so containers are searched too; without that, a change to such a
     * field never reached the validation and an old error stayed on screen.
     */
    static void watch(Node node, Runnable onChange) {
        if (node instanceof TextArea) {
            ((TextArea) node).textProperty().addListener((obs, o, n) -> onChange.run());
        } else if (node instanceof TextField) {
            ((TextField) node).textProperty().addListener((obs, o, n) -> onChange.run());
        } else if (node instanceof javafx.scene.control.CheckBox) {
            ((javafx.scene.control.CheckBox) node).selectedProperty().addListener((obs, o, n) -> onChange.run());
        } else if (node instanceof ComboBox) {
            ((ComboBox<?>) node).valueProperty().addListener((obs, o, n) -> onChange.run());
        } else if (node instanceof javafx.scene.Parent) {
            for (Node child : ((javafx.scene.Parent) node).getChildrenUnmodifiable()) {
                watch(child, onChange);
            }
        }
    }

    /**
     * Shows the dialog and returns true if the user pressed OK.
     *
     * @param problems     evaluated on every change; returns what is wrong with the current input
     * @param labelsFields alternating label text and field node
     */
    static boolean show(Window owner, String title, String okText, Supplier<List<String>> problems,
                        Object... labelsFields) {
        Dialog<Boolean> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(title);
        dialog.setResizable(true);
        ButtonType ok = new ButtonType(okText, ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        ColumnConstraints label = new ColumnConstraints();
        label.setMinWidth(130);
        ColumnConstraints field = new ColumnConstraints();
        field.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(label, field);

        Label problemLabel = new Label();
        problemLabel.setWrapText(true);
        problemLabel.setStyle("-fx-text-fill: #b00020;");
        problemLabel.setMinHeight(Label.USE_PREF_SIZE);
        Node okButton = dialog.getDialogPane().lookupButton(ok);
        Runnable revalidate = () -> {
            List<String> found = problems.get();
            okButton.setDisable(!found.isEmpty());
            problemLabel.setText(String.join("\n", found));
        };

        for (int i = 0; i + 1 < labelsFields.length; i += 2) {
            Label l = new Label((String) labelsFields[i]);
            Node f = (Node) labelsFields[i + 1];
            if (f instanceof TextArea) {
                javafx.scene.layout.GridPane.setValignment(l, javafx.geometry.VPos.TOP);
            }
            watch(f, revalidate);
            grid.addRow(i / 2, l, f);
        }

        // Long forms scroll instead of growing past the screen; the problems stay visible below.
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportHeight(Math.min(520, labelsFields.length / 2 * 40 + 20));
        scroll.setStyle("-fx-background-color: transparent;");
        VBox content = new VBox(10, scroll, problemLabel);
        content.setPadding(new Insets(12));
        content.setPrefWidth(600);
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(b -> b == ok);
        dialog.setOnShown(e -> revalidate.run());
        return Boolean.TRUE.equals(dialog.showAndWait().orElse(false));
    }
}
