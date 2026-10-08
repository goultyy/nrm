package mt.su.nrm.ui;

import mt.su.nrm.nginx.VhostSettings;
import mt.su.nrm.security.SecurityHeaders;
import mt.su.nrm.security.SecurityHeaders.Choice;
import mt.su.nrm.security.SecurityHeaders.Header;
import mt.su.nrm.security.SecurityHeaders.Level;
import mt.su.nrm.security.SecurityHeaders.Row;
import mt.su.nrm.security.SecurityHeaders.State;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Adds browser security headers to a site: pick a set (Basic, Recommended, Strict) or tick headers one by one, see what
 * each does and what it can break, and see what the site has now. The values can be edited. Nothing is applied until
 * the dialog is accepted, and then only into the Headers tab, to be reviewed like any other edit.
 */
final class SecurityHeadersDialog {

    private final VhostSettings site;
    private final List<Row> review;
    private final List<CheckBox> ticks = new ArrayList<>();
    private final List<TextField> values = new ArrayList<>();
    private final Label warnings = new Label();
    private final Label problem = new Label();
    private final ComboBox<Level> preset = new ComboBox<>();
    private javafx.scene.Node okButton;
    private boolean applyingPreset;

    private SecurityHeadersDialog(VhostSettings site, String currentHeaderLines) {
        this.site = site;
        this.review = SecurityHeaders.review(currentHeaderLines);
    }

    /** @return the headers to add or replace, or empty if cancelled or none were ticked */
    static Optional<List<Choice>> show(Window owner, VhostSettings site, String currentHeaderLines) {
        return new SecurityHeadersDialog(site, currentHeaderLines).run(owner);
    }

    private Optional<List<Choice>> run(Window owner) {
        Dialog<List<Choice>> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Security headers");
        dialog.setHeaderText(null);
        dialog.setResizable(true);
        ButtonType add = new ButtonType("Add to site", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(add, ButtonType.CANCEL);
        okButton = dialog.getDialogPane().lookupButton(add);

        Label intro = new Label("Security headers tell browsers to refuse risky behaviour. Pick a set or tick the ones you "
                + "want, check what each can break, then add them. They are added to this site's Headers tab, where you "
                + "can still change or remove them before saving.");
        intro.setWrapText(true);

        preset.getItems().addAll(Level.BASIC, Level.RECOMMENDED, Level.STRICT);
        preset.setPromptText("Choose a set");
        preset.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Level l) {
                return l == null ? "" : l.name().charAt(0) + l.name().substring(1).toLowerCase();
            }

            @Override
            public Level fromString(String s) {
                return null;
            }
        });
        Label presetInfo = new Label();
        presetInfo.setWrapText(true);
        presetInfo.setOpacity(0.8);
        preset.setOnAction(e -> {
            Level level = preset.getValue();
            if (level == null) {
                return;
            }
            presetInfo.setText(SecurityHeaders.describe(level));
            applyingPreset = true;
            List<Header> included = SecurityHeaders.preset(level);
            for (int i = 0; i < review.size(); i++) {
                Header h = review.get(i).header();
                boolean in = included.contains(h) && (!h.needsHttps() || SecurityHeaders.usesHttps(site));
                ticks.get(i).setSelected(in);
                if (in) {
                    values.get(i).setText(h.valueAt(level));
                }
            }
            applyingPreset = false;
            refresh();
        });
        HBox presetRow = new HBox(8, new Label("Start from"), preset, presetInfo);
        presetRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(presetInfo, Priority.ALWAYS);

        VBox list = new VBox(10);
        list.setPadding(new Insets(4));
        for (Row row : review) {
            list.getChildren().add(rowNode(row));
        }
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(330);
        scroll.setPrefViewportWidth(700);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        warnings.setWrapText(true);
        warnings.setStyle("-fx-text-fill: #8a6d00;");
        problem.setWrapText(true);
        problem.setStyle("-fx-text-fill: #b00020;");
        VBox content = new VBox(10, intro, presetRow, scroll, warnings, problem);
        content.setPadding(new Insets(8));
        dialog.getDialogPane().setContent(content);

        dialog.setResultConverter(button -> button == add ? chosen() : null);
        refresh();
        return dialog.showAndWait().filter(list2 -> !list2.isEmpty());
    }

    private Node rowNode(Row row) {
        Header h = row.header();
        CheckBox tick = new CheckBox(h.name());
        tick.setStyle("-fx-font-weight: bold;");
        TextField value = new TextField(h.value());
        value.setStyle("-fx-font-family: 'Consolas', 'Menlo', monospace;");
        value.setDisable(true);
        value.disableProperty().bind(tick.selectedProperty().not());
        HBox.setHgrow(value, Priority.ALWAYS);
        ticks.add(tick);
        values.add(value);
        tick.selectedProperty().addListener((obs, o, n) -> {
            if (!applyingPreset) {
                refresh();
            }
        });
        value.textProperty().addListener((obs, o, n) -> {
            if (!applyingPreset) {
                refresh();
            }
        });

        Label state = new Label(stateText(row));
        state.setOpacity(0.85);
        Label why = new Label(h.why());
        why.setWrapText(true);
        Label risk = new Label("Can break: " + h.risk());
        risk.setWrapText(true);
        risk.setStyle("-fx-text-fill: #8a6d00;");
        HBox top = new HBox(10, tick, state);
        top.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(3, top, value, why, risk);
        box.setPadding(new Insets(0, 0, 6, 0));
        return box;
    }

    private static String stateText(Row row) {
        String text = switch (row.state()) {
            case MISSING -> "not set on this site";
            case SET -> "already set";
            case DIFFERENT -> "set to \"" + row.current() + "\" (will be replaced)";
        };
        if (row.state() != State.MISSING && row.withoutAlways()) {
            text += ", but not sent with error pages (no \"always\")";
        }
        return text;
    }

    private List<Choice> chosen() {
        List<Choice> chosen = new ArrayList<>();
        for (int i = 0; i < review.size(); i++) {
            if (ticks.get(i).isSelected()) {
                chosen.add(new Choice(review.get(i).header().name(), values.get(i).getText().strip()));
            }
        }
        return chosen;
    }

    private void refresh() {
        List<Choice> chosen = chosen();
        String first = null;
        for (Choice c : chosen) {
            String p = SecurityHeaders.problem(c);
            if (p != null) {
                first = p;
                break;
            }
        }
        problem.setText(first == null ? "" : first);
        problem.setManaged(first != null);
        List<String> w = SecurityHeaders.warnings(site, chosen);
        warnings.setText(String.join("\n\n", w));
        warnings.setManaged(!w.isEmpty());
        if (okButton != null) {
            okButton.setDisable(chosen.isEmpty() || first != null);
        }
    }

    // For tests.

    static Supplier<List<Choice>> forTest(VhostSettings site, String lines, List<String> tick) {
        SecurityHeadersDialog d = new SecurityHeadersDialog(site, lines);
        for (Row row : d.review) {
            d.rowNode(row);
        }
        for (int i = 0; i < d.review.size(); i++) {
            d.ticks.get(i).setSelected(tick.contains(d.review.get(i).header().name()));
        }
        return d::chosen;
    }
}
