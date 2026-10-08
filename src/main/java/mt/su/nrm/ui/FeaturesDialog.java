package mt.su.nrm.ui;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** View > Features: turn the optional add-ons on or off. Everything starts off. */
final class FeaturesDialog {

    private FeaturesDialog() {
    }

    static void show(Window owner) {
        Dialog<List<String>> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Features");
        ButtonType ok = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);

        Map<Feature, CheckBox> boxes = new LinkedHashMap<>();
        VBox content = new VBox(14);
        content.setPadding(new Insets(14));
        content.setPrefWidth(520);
        Label intro = new Label("Optional add-ons. Each one you turn on gets its own entry under every connected "
                + "server. They change a server's configuration only through the pending changes, like everything "
                + "else.");
        intro.setWrapText(true);
        content.getChildren().add(intro);
        for (Feature feature : Features.all()) {
            CheckBox box = new CheckBox(feature.title());
            box.setStyle("-fx-font-weight: bold;");
            box.setSelected(Features.isEnabled(feature));
            Label what = new Label(feature.description());
            what.setWrapText(true);
            what.setOpacity(0.8);
            what.setPadding(new Insets(0, 0, 0, 24));
            boxes.put(feature, box);
            content.getChildren().addAll(box, what);
        }
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(button -> button == ok
                ? boxes.entrySet().stream().filter(e -> e.getValue().isSelected()).map(e -> e.getKey().id()).toList()
                : null);
        dialog.showAndWait().ifPresent(Features::setEnabled);
    }
}
