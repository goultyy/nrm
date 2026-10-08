package mt.su.nrm.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;

/**
 * One of the server's sections (Load Balancing, SSL Certificates, Logs...) as a tile, the same size and
 * style as a {@link SiteTile}, for the overview. It stands for the matching entry in the tree.
 */
final class NavTile extends VBox {

    private static final double ICON_SCALE = 0.62;

    private static final String NORMAL_STYLE = "-fx-background-color: transparent; -fx-background-radius: 6;"
            + "-fx-border-color: transparent; -fx-border-radius: 6; -fx-cursor: hand;";
    private static final String HOVER_STYLE = "-fx-background-color: rgba(60,120,200,0.10); -fx-background-radius: 6;"
            + "-fx-border-color: transparent; -fx-border-radius: 6; -fx-cursor: hand;";

    private final Label name = new Label();
    private final String title;

    NavTile(String title, String tooltip) {
        this(title, title, tooltip);
    }

    /** A tile whose picture is chosen by {@code iconKey} but which shows {@code title} (such as a zone's name). */
    NavTile(String iconKey, String title, String tooltip) {
        super(2);
        this.title = title;
        setAlignment(Pos.TOP_CENTER);
        setPadding(new Insets(6, 4, 6, 4));
        setPrefWidth(SiteTile.WIDTH);
        setMinWidth(SiteTile.WIDTH);
        setMaxWidth(SiteTile.WIDTH);

        name.setText(title);
        name.setStyle("-fx-font-weight: bold; -fx-font-size: 9.5px;");
        name.setWrapText(false);
        name.setTextAlignment(TextAlignment.CENTER);
        name.setMaxWidth(SiteTile.WIDTH - 8);
        name.setPrefWidth(SiteTile.WIDTH - 8);
        name.setAlignment(Pos.CENTER);
        getChildren().addAll(RetroIcon.build(iconKey, ICON_SCALE), name);
        Tooltip.install(this, new Tooltip(tooltip));

        setStyle(NORMAL_STYLE);
        setOnMouseEntered(e -> setStyle(Theme.tint(HOVER_STYLE)));
        setOnMouseExited(e -> setStyle(NORMAL_STYLE));
    }

    String title() {
        return title;
    }

    /** Adds a note after the name, such as the number of pending changes. */
    void setBadge(String text) {
        name.setText(text.isEmpty() ? title : title + " " + text);
        name.setStyle("-fx-font-weight: bold; -fx-font-size: 9.5px;" + (text.isEmpty() ? "" : "-fx-text-fill: #b26a00;"));
    }
}
