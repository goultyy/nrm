package mt.su.nrm.ui;

import mt.su.nrm.model.ServerProfile;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeType;
import javafx.scene.text.TextAlignment;

/**
 * One saved server as a tile in the same retro style as {@link SiteTile}: a little rack-mounted
 * server with indicator lights, the server's name, and its address underneath. Only the host is
 * shown, never the login name.
 */
final class ServerTile extends VBox {

    static final double WIDTH = 96;
    private static final double ICON_SCALE = 0.55;

    private static final String NORMAL_STYLE = "-fx-background-color: transparent; -fx-background-radius: 6;"
            + "-fx-border-color: transparent; -fx-border-radius: 6;";
    private static final String HOVER_STYLE = "-fx-background-color: rgba(60,120,200,0.10); -fx-background-radius: 6;"
            + "-fx-border-color: transparent; -fx-border-radius: 6;";
    private static final String SELECTED_STYLE = "-fx-background-color: rgba(60,120,200,0.22); -fx-background-radius: 6;"
            + "-fx-border-color: rgba(60,120,200,0.9); -fx-border-radius: 6;";

    private static final Color INK = Color.BLACK;
    private static final Color GREY = Color.web("#c0c0c0");

    private final ServerProfile profile;
    private boolean selected;

    ServerTile(ServerProfile profile) {
        super(2);
        this.profile = profile;
        setAlignment(Pos.TOP_CENTER);
        setPadding(new Insets(4, 3, 4, 3));
        setPrefWidth(WIDTH);
        setMinWidth(WIDTH);
        setMaxWidth(WIDTH);

        Label name = new Label(profile.getName());
        name.setStyle("-fx-font-weight: bold; -fx-font-size: 10px;");
        name.setTextAlignment(TextAlignment.CENTER);
        name.setMaxWidth(WIDTH - 8);
        name.setAlignment(Pos.CENTER);
        Label address = new Label(profile.getHost());
        address.setStyle("-fx-font-size: 9px; -fx-text-fill: #666666;");
        address.setMaxWidth(WIDTH - 8);
        address.setAlignment(Pos.CENTER);

        getChildren().addAll(icon(), name, address);
        Tooltip.install(this, new Tooltip(profile.getName() + "\n" + profile.getHost()
                + (profile.getPort() == 22 ? "" : ":" + profile.getPort())));
        setStyle(NORMAL_STYLE);
        setOnMouseEntered(e -> {
            if (!selected) {
                setStyle(Theme.tint(HOVER_STYLE));
            }
        });
        setOnMouseExited(e -> {
            if (!selected) {
                setStyle(NORMAL_STYLE);
            }
        });
    }

    ServerProfile profile() {
        return profile;
    }

    void setSelected(boolean on) {
        selected = on;
        setStyle(on ? Theme.tint(SELECTED_STYLE) : NORMAL_STYLE);
    }

    /** A rack cabinet with three server units, each with a drive slot and an indicator light. */
    private static Node icon() {
        Group g = new Group();
        Rectangle shadow = new Rectangle(4, 4, 76, 68);
        shadow.setFill(Color.web("#404040"));
        Rectangle cabinet = new Rectangle(0, 0, 76, 68);
        cabinet.setFill(GREY);
        cabinet.setStroke(INK);
        cabinet.setStrokeWidth(2);
        cabinet.setStrokeType(StrokeType.INSIDE);
        g.getChildren().addAll(shadow, cabinet);
        Color[] leds = {Color.web("#00c000"), Color.web("#00c000"), Color.web("#ffcc00")};
        for (int i = 0; i < 3; i++) {
            double y = 6 + i * 20;
            Rectangle unit = new Rectangle(6, y, 64, 16);
            unit.setFill(Color.web("#000080"));
            unit.setStroke(INK);
            unit.setStrokeWidth(1);
            Rectangle slot = new Rectangle(12, y + 5, 30, 6);
            slot.setFill(INK);
            Rectangle slotLine = new Rectangle(14, y + 7, 26, 2);
            slotLine.setFill(Color.web("#808080"));
            Rectangle led = new Rectangle(52, y + 4, 8, 8);
            led.setFill(leds[i]);
            led.setStroke(INK);
            led.setStrokeWidth(1);
            g.getChildren().addAll(unit, slot, slotLine, led);
        }
        g.setScaleX(ICON_SCALE);
        g.setScaleY(ICON_SCALE);
        VBox holder = new VBox(new Group(g));
        holder.setAlignment(Pos.CENTER);
        holder.setPadding(new Insets(0, 0, 2, 0));
        return holder;
    }
}
