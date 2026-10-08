package mt.su.nrm.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * One part of a virtual host (General, Locations, SSL...) as a tile in the same retro window style as
 * {@link SiteTile}, at the same size as the site and section tiles.
 */
final class SectionTile extends VBox {

    private static final double WIDTH = SiteTile.WIDTH;
    private static final double ICON_SCALE = 0.62;

    private static final String NORMAL_STYLE = "-fx-background-color: transparent; -fx-background-radius: 6;"
            + "-fx-border-color: transparent; -fx-border-radius: 6; -fx-cursor: hand;";
    private static final String HOVER_STYLE = "-fx-background-color: rgba(60,120,200,0.14); -fx-background-radius: 6;"
            + "-fx-border-color: rgba(60,120,200,0.9); -fx-border-radius: 6; -fx-cursor: hand;";

    private final String title;

    SectionTile(String title) {
        super(2);
        this.title = title;
        setAlignment(Pos.TOP_CENTER);
        setPadding(new Insets(6, 4, 6, 4));
        setPrefWidth(WIDTH);
        setMinWidth(WIDTH);
        setMaxWidth(WIDTH);

        Label name = new Label(title);
        name.setStyle("-fx-font-weight: bold; -fx-font-size: 9.5px;");
        getChildren().addAll(RetroIcon.build(title, ICON_SCALE), name);

        setStyle(NORMAL_STYLE);
        setOnMouseEntered(e -> setStyle(Theme.tint(HOVER_STYLE)));
        setOnMouseExited(e -> setStyle(NORMAL_STYLE));
    }

    String title() {
        return title;
    }
}
