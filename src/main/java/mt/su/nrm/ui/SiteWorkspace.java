package mt.su.nrm.ui;

import mt.su.nrm.nginx.VhostSettings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Map;
import java.util.function.Consumer;

/**
 * A virtual host opened inside the main window: first a grid of retro icons for its parts (General,
 * Locations, SSL...), then the chosen part loaded below a breadcrumb. It edits through
 * {@link VirtualHostEditor}, so the checks are the same as in the dialog; "Keep changes" hands the
 * edited settings back and they become a pending change like any other.
 */
final class SiteWorkspace extends BorderPane {

    /** The parts of a site, grouped as they appear in the icon grid. */
    private static final Map<String, java.util.List<String>> CATEGORIES = new java.util.LinkedHashMap<>();

    static {
        CATEGORIES.put("Site", java.util.List.of("General", "Locations"));
        CATEGORIES.put("Security", java.util.List.of("SSL", "Headers", "Real IP", "Limits"));
        CATEGORIES.put("Traffic", java.util.List.of("Rewrites", "Error pages"));
        CATEGORIES.put("Monitoring", java.util.List.of("Logging"));
    }

    private final VirtualHostEditor editor;
    private final String siteName;
    private final Runnable onClose;
    private final Button keep = new Button("Keep changes");

    /**
     * @param onSave  receives the edited settings when the user keeps the changes
     * @param onClose runs when the user leaves the site (after keeping or discarding)
     */
    SiteWorkspace(String siteName, VirtualHostEditor editor, Consumer<VhostSettings> onSave, Runnable onClose) {
        this.editor = editor;
        this.siteName = siteName;
        this.onClose = onClose;
        editor.embedIn(() -> getScene() == null ? null : getScene().getWindow());

        keep.setDefaultButton(false);
        keep.disableProperty().bind(editor.invalidProperty());
        keep.setOnAction(e -> {
            onSave.accept(editor.result());
            onClose.run();
        });
        Button discard = new Button("Discard");
        discard.setOnAction(e -> onClose.run());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8, spacer, keep, discard);
        bar.setAlignment(Pos.CENTER_RIGHT);
        bar.setPadding(new Insets(8, 12, 8, 12));
        setBottom(new VBox(4, editor.notices(), bar));
        BorderPane.setMargin(getBottom(), new Insets(0, 12, 0, 12));

        sceneProperty().addListener((obs, o, n) -> {
            if (n != null) {
                editor.validateNow();
            }
        });
        showGrid();
    }

    // ---------------------------------------------------------------- views

    private void showGrid() {
        Label heading = new Label(siteName);
        heading.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        Label hint = new Label("Choose a part of this site to change. Nothing is sent to the server until you keep "
                + "the changes and apply them under Pending Changes.");
        hint.setWrapText(true);
        hint.setOpacity(0.75);

        VBox tiles = new VBox(10);
        tiles.setPadding(new Insets(8, 0, 0, 0));
        for (Map.Entry<String, java.util.List<String>> category : CATEGORIES.entrySet()) {
            FlowPane row = new FlowPane(8, 8);
            for (String title : category.getValue()) {
                if (!editor.sections().containsKey(title)) {
                    continue;
                }
                SectionTile tile = new SectionTile(title);
                tile.setOnMouseClicked(e -> showSection(title));
                row.getChildren().add(tile);
            }
            Label name = new Label(category.getKey());
            name.setStyle("-fx-font-weight: bold;");
            Separator line = new Separator();
            tiles.getChildren().addAll(name, line, row);
        }
        ScrollPane scroll = new ScrollPane(tiles);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent;");

        VBox box = new VBox(8, breadcrumb(null), heading, hint, scroll);
        box.setPadding(new Insets(10, 16, 6, 16));
        VBox.setVgrow(scroll, Priority.ALWAYS);
        setCenter(box);
    }

    private void showSection(String title) {
        Node content = editor.sections().get(title);
        VBox box = new VBox(6, breadcrumb(title), content);
        box.setPadding(new Insets(10, 16, 6, 16));
        VBox.setVgrow(content, Priority.ALWAYS);
        setCenter(box);
    }

    private Node breadcrumb(String section) {
        Button sites = new Button("◄ Sites");
        sites.setOnAction(e -> onClose.run());
        HBox crumbs = new HBox(6, sites);
        crumbs.setAlignment(Pos.CENTER_LEFT);
        if (section != null) {
            Button site = new Button(siteName);
            site.setOnAction(e -> showGrid());
            crumbs.getChildren().addAll(new Label("›"), site, new Label("›"), boldLabel(section));
        }
        return crumbs;
    }

    private static Label boldLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold;");
        return l;
    }

    /** Kept for callers that want to know which parts exist. */
    Map<String, Node> parts() {
        return editor.sections();
    }
}
