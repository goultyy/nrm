package mt.su.nrm.ui;

import mt.su.nrm.nginx.SiteSummary;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeType;
import javafx.scene.text.TextAlignment;

/**
 * One site as a tile: a little picture of a web page (coloured and drawn to suggest whether it serves
 * files, proxies to another server or redirects, with a padlock when it uses HTTPS), and its name
 * and details underneath, like the large-icon view of a file manager.
 */
final class SiteTile extends VBox {

    static final double WIDTH = 92;
    /** The picture is drawn at 92x68 and scaled down. */
    private static final double ICON_SCALE = 0.62;

    private static final String NORMAL_STYLE = "-fx-background-color: transparent; -fx-background-radius: 6;"
            + "-fx-border-color: transparent; -fx-border-radius: 6;";
    private static final String HOVER_STYLE = "-fx-background-color: rgba(60,120,200,0.10); -fx-background-radius: 6;"
            + "-fx-border-color: transparent; -fx-border-radius: 6;";
    private static final String SELECTED_STYLE = "-fx-background-color: rgba(60,120,200,0.22); -fx-background-radius: 6;"
            + "-fx-border-color: rgba(60,120,200,0.9); -fx-border-radius: 6;";

    private final SiteSummary site;
    private boolean selected;

    SiteTile(SiteSummary site, String file, String status) {
        super(2);
        this.site = site;
        setAlignment(Pos.TOP_CENTER);
        setPadding(new Insets(6, 4, 6, 4));
        setPrefWidth(WIDTH);
        setMinWidth(WIDTH);
        setMaxWidth(WIDTH);

        Label name = new Label(site.name());
        name.setStyle("-fx-font-weight: bold; -fx-font-size: 9.5px;");
        name.setWrapText(false); // one line, cut with an ellipsis; the tooltip has the full name
        name.setTextAlignment(TextAlignment.CENTER);
        name.setMaxWidth(WIDTH - 8);
        name.setPrefWidth(WIDTH - 8);
        name.setAlignment(Pos.CENTER);

        // Just the picture and the name: bindings, folder and the rest are in the tooltip and the Details view.
        getChildren().addAll(icon(site, status), name);

        Tooltip.install(this, new Tooltip(site.allNames() + "\n" + site.bindings()
                + (site.content().isBlank() ? "" : "\n" + site.content())
                + (site.ssl().isBlank() ? "" : "\nSSL: " + site.ssl())
                + "\n" + site.locations() + " location(s)\n" + file
                + (status.isBlank() ? "" : "\n" + status)));
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

    SiteSummary site() {
        return site;
    }

    void setSelected(boolean on) {
        selected = on;
        setStyle(on ? Theme.tint(SELECTED_STYLE) : NORMAL_STYLE);
    }


    // ---------------------------------------------------------------- the picture

    static final Color INK = Color.BLACK;
    static final Color GREY = Color.web("#c0c0c0");

    /**
     * A retro desktop window: square corners, thick black outline, a hard offset shadow, a grey frame
     * with a coloured title bar, and a blocky glyph for what the site does.
     */
    private static Node icon(SiteSummary site, String status) {
        Color accent;
        switch (site.kind()) {
            case PROXY:
                accent = Color.web("#800080");
                break;
            case REDIRECT:
                accent = Color.web("#c86400");
                break;
            case STATIC:
                accent = Color.web("#000080");
                break;
            default:
                accent = Color.web("#808080");
        }
        Group g = new Group();

        Rectangle shadow = new Rectangle(4, 4, 92, 68);
        shadow.setFill(Color.web("#404040"));

        Rectangle frame = new Rectangle(0, 0, 92, 68);
        frame.setFill(GREY);
        frame.setStroke(INK);
        frame.setStrokeWidth(2);
        frame.setStrokeType(StrokeType.INSIDE);

        Rectangle titleBar = new Rectangle(2, 2, 88, 13);
        titleBar.setFill(accent);

        g.getChildren().addAll(shadow, frame, titleBar,
                square(4, 4, 9, GREY), square(79, 4, 9, GREY),
                bar(18, 6, 56, 1.5, Color.WHITE), bar(18, 9.5, 56, 1.5, Color.WHITE));

        Rectangle panel = new Rectangle(6, 19, 80, 43);
        panel.setFill(Color.WHITE);
        panel.setStroke(INK);
        panel.setStrokeWidth(1);
        g.getChildren().add(panel);

        switch (site.kind()) {
            case PROXY:
                g.getChildren().addAll(block(11, 27, 20, 24, accent), block(45, 27, 20, 24, accent),
                        bar(32, 37, 7, 4, INK), arrowHead(38, 39, INK, 7));
                break;
            case REDIRECT:
                g.getChildren().addAll(bar(12, 37, 44, 7, accent), arrowHead(56, 40.5, accent, 13));
                break;
            case STATIC:
                g.getChildren().addAll(block(12, 24, 40, 9, accent), bar(12, 38, 50, 3, INK), bar(12, 45, 42, 3, INK),
                        bar(12, 52, 46, 3, INK));
                break;
            default:
                g.getChildren().addAll(bar(12, 30, 60, 3, Color.GRAY), bar(12, 38, 50, 3, Color.GRAY),
                        bar(12, 46, 54, 3, Color.GRAY));
        }

        if (site.https()) {
            g.getChildren().add(padlock(66, 43));
        }
        if (!status.isBlank() && !status.equals("Read-only")) {
            Rectangle badge = new Rectangle(83, -4, 10, 10);
            badge.setFill(Color.web(status.equals("New") ? "#00a0ff" : "#ffcc00"));
            badge.setStroke(INK);
            badge.setStrokeWidth(1.5);
            g.getChildren().add(badge);
        }
        g.setScaleX(ICON_SCALE);
        g.setScaleY(ICON_SCALE);
        VBox holder = new VBox(new Group(g));
        holder.setAlignment(Pos.CENTER);
        holder.setPadding(new Insets(0, 0, 2, 0));
        return holder;
    }

    /** A small square with a black outline (the window's corner buttons). */
    static Rectangle square(double x, double y, double size, Color fill) {
        Rectangle r = new Rectangle(x, y, size, size);
        r.setFill(fill);
        r.setStroke(INK);
        r.setStrokeWidth(1);
        return r;
    }

    /** A filled block with a black outline. */
    static Rectangle block(double x, double y, double w, double h, Color fill) {
        Rectangle r = new Rectangle(x, y, w, h);
        r.setFill(fill);
        r.setStroke(INK);
        r.setStrokeWidth(1);
        return r;
    }

    /** A flat bar with no outline. */
    static Rectangle bar(double x, double y, double w, double h, Color fill) {
        Rectangle r = new Rectangle(x, y, w, h);
        r.setFill(fill);
        return r;
    }

    static Polygon arrowHead(double x, double y, Color fill) {
        return arrowHead(x, y, fill, 10);
    }

    /** A blocky triangle pointing right, centred vertically on y. */
    static Polygon arrowHead(double x, double y, Color fill, double half) {
        Polygon p = new Polygon(x, y - half, x, y + half, x + half + 4, y);
        p.setFill(fill);
        p.setStroke(INK);
        p.setStrokeWidth(1);
        return p;
    }

    /** A flat yellow padlock with a black outline. */
    static Node padlock(double x, double y) {
        Arc shackle = new Arc(x + 8, y + 3, 5, 5, 0, 180);
        shackle.setType(ArcType.OPEN);
        shackle.setFill(null);
        shackle.setStroke(INK);
        shackle.setStrokeWidth(2.5);
        Rectangle body = new Rectangle(x, y + 3, 16, 13);
        body.setFill(Color.web("#ffd400"));
        body.setStroke(INK);
        body.setStrokeWidth(1.5);
        Rectangle keyhole = new Rectangle(x + 6.5, y + 7, 3, 5);
        keyhole.setFill(INK);
        return new Group(shackle, body, keyhole);
    }
}
