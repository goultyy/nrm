package mt.su.nrm.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeType;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

import static mt.su.nrm.ui.SiteTile.INK;
import static mt.su.nrm.ui.SiteTile.arrowHead;
import static mt.su.nrm.ui.SiteTile.bar;
import static mt.su.nrm.ui.SiteTile.block;
import static mt.su.nrm.ui.SiteTile.padlock;
import static mt.su.nrm.ui.SiteTile.square;

/**
 * The retro desktop-window pictures shared by the tiles: a grey frame with a hard shadow, a coloured
 * title bar and a blocky glyph for what the tile is about. Site tiles have their own pictures; this
 * draws the parts of a site (General, Locations...) and the server's sections (Load Balancing, Logs...).
 */
final class RetroIcon {

    private RetroIcon() {
    }

    static Color accent(String key) {
        switch (key) {
            case "General":
            case "Global Settings":
                return Color.web("#000080");
            case "Locations":
            case "Load Balancing":
                return Color.web("#008080");
            case "SSL":
            case "SSL Certificates":
                return Color.web("#b8860b");
            case "Headers":
                return Color.web("#800080");
            case "Rewrites":
            case "Pending Changes":
                return Color.web("#c86400");
            case "Limits":
            case "Rate Limits":
                return Color.web("#a00000");
            case "Logging":
            case "Logs":
            case "Cache Zones":
                return Color.web("#006400");
            case "Log Formats":
                return Color.web("#00695c");
            case "Change History":
                return Color.web("#5d4037");
            case "Cloudflare":
                return Color.web("#f38020");
            case "Status":
                return Color.web("#2e7d32");
            case "Real IP":
                return Color.web("#7b1fa2");
            case "DNS Zones":
                return Color.web("#000080");
            case "Tunnels":
                return Color.web("#008080");
            default:
                return Color.web("#808080");
        }
    }

    /** The picture for a key, drawn at 92x68 and scaled; the returned node is centred in a box of the scaled size. */
    static Node build(String key, double scale) {
        Color accent = accent(key);
        Group g = new Group();
        Rectangle shadow = new Rectangle(4, 4, 92, 68);
        shadow.setFill(Color.web("#404040"));
        Rectangle frame = new Rectangle(0, 0, 92, 68);
        frame.setFill(SiteTile.GREY);
        frame.setStroke(INK);
        frame.setStrokeWidth(2);
        frame.setStrokeType(StrokeType.INSIDE);
        Rectangle titleBar = new Rectangle(2, 2, 88, 13);
        titleBar.setFill(accent);
        Rectangle panel = new Rectangle(6, 19, 80, 43);
        panel.setFill(Color.WHITE);
        panel.setStroke(INK);
        panel.setStrokeWidth(1);
        g.getChildren().addAll(shadow, frame, titleBar, square(4, 4, 9, SiteTile.GREY), square(79, 4, 9, SiteTile.GREY),
                bar(18, 6, 56, 1.5, Color.WHITE), bar(18, 9.5, 56, 1.5, Color.WHITE), panel);

        switch (key) {
            case "General":
                g.getChildren().addAll(block(12, 24, 34, 9, accent), bar(12, 38, 60, 3, INK), bar(12, 45, 48, 3, INK),
                        bar(12, 52, 54, 3, INK));
                break;
            case "Locations":
                // A little folder tree.
                g.getChildren().addAll(block(12, 24, 18, 9, accent), bar(20, 33, 2, 24, INK), bar(20, 40, 14, 2, INK),
                        block(34, 36, 18, 9, accent), bar(20, 55, 14, 2, INK), block(34, 51, 18, 9, accent));
                break;
            case "SSL":
            case "SSL Certificates":
                g.getChildren().addAll(padlock(30, 26), bar(56, 30, 24, 3, INK), bar(56, 38, 20, 3, INK));
                break;
            case "Headers":
                g.getChildren().addAll(block(12, 24, 68, 10, accent), bar(12, 40, 60, 3, INK), bar(12, 47, 44, 3, INK),
                        bar(12, 54, 52, 3, INK));
                break;
            case "Rewrites":
                Polygon back = new Polygon(30, 41, 30, 57, 18, 49);
                back.setFill(INK);
                g.getChildren().addAll(bar(12, 29, 44, 5, accent), arrowHead(56, 31.5, accent, 8),
                        bar(30, 47, 44, 5, INK), back);
                break;
            case "Limits":
            case "Rate Limits":
                // A meter with a filled part and a red end.
                g.getChildren().addAll(block(12, 32, 62, 12, Color.WHITE), block(12, 32, 40, 12, accent),
                        block(52, 32, 22, 12, Color.web("#ff4040")), bar(12, 50, 62, 3, INK));
                break;
            case "Logging":
            case "Logs":
                g.getChildren().addAll(square(12, 25, 5, accent), bar(21, 26, 50, 3, INK), square(12, 34, 5, accent),
                        bar(21, 35, 40, 3, INK), square(12, 43, 5, Color.web("#ffcc00")), bar(21, 44, 55, 3, INK),
                        square(12, 52, 5, accent), bar(21, 53, 32, 3, INK));
                break;
            case "Load Balancing":
                // One box fanning out to two.
                g.getChildren().addAll(block(10, 33, 18, 14, accent), bar(28, 39, 8, 2, INK), bar(36, 29, 2, 22, INK),
                        bar(36, 29, 10, 2, INK), bar(36, 49, 10, 2, INK), block(48, 24, 22, 12, accent),
                        block(48, 44, 22, 12, accent));
                break;
            case "Cache Zones":
                // A stack of drives.
                g.getChildren().addAll(block(14, 23, 56, 9, accent), block(14, 35, 56, 9, accent),
                        block(14, 47, 56, 9, accent), square(60, 26, 3, Color.WHITE), square(60, 38, 3, Color.WHITE),
                        square(60, 50, 3, Color.WHITE));
                break;
            case "Global Settings":
                // Three sliders.
                g.getChildren().addAll(bar(12, 27, 60, 3, INK), square(30, 23, 10, accent), bar(12, 40, 60, 3, INK),
                        square(52, 36, 10, accent), bar(12, 53, 60, 3, INK), square(20, 49, 10, accent));
                break;
            case "Cloudflare":
                // A blocky cloud.
                g.getChildren().addAll(block(14, 38, 60, 14, accent), block(22, 28, 24, 16, accent),
                        block(40, 24, 22, 20, accent), bar(15, 39, 58, 12, accent), bar(23, 29, 22, 14, accent),
                        bar(41, 25, 20, 18, accent));
                break;
            case "Log Formats":
                // A log line built from labelled pieces.
                g.getChildren().addAll(block(10, 26, 20, 9, accent), bar(33, 29, 38, 3, INK), block(10, 38, 14, 9, accent),
                        bar(27, 41, 44, 3, INK), block(10, 50, 26, 9, accent), bar(39, 53, 28, 3, INK));
                break;
            case "Change History":
                // Versions stacked in time, with an arrow going back.
                g.getChildren().addAll(block(30, 24, 44, 8, accent), block(24, 35, 44, 8, accent),
                        block(18, 46, 44, 8, accent), arrowHead(8, 50, INK, 5), bar(8, 49, 8, 3, INK));
                break;
            case "Status":
                // A bar chart that is going up.
                g.getChildren().addAll(block(12, 46, 11, 12, accent), block(26, 38, 11, 20, accent),
                        block(40, 30, 11, 28, accent), block(54, 24, 11, 34, accent), bar(10, 60, 62, 2, INK));
                break;
            case "Real IP":
                // A visitor, passing through a proxy, to the server.
                g.getChildren().addAll(block(10, 28, 14, 20, Color.web("#e0e0e0")), arrowHead(26, 38, INK, 5),
                        block(40, 26, 18, 24, accent), arrowHead(60, 38, INK, 5), block(74, 30, 10, 16, Color.web("#e0e0e0")),
                        bar(12, 54, 8, 3, INK), bar(24, 54, 8, 3, INK), bar(36, 54, 8, 3, INK), bar(48, 54, 8, 3, INK));
                break;
            case "DNS Zones":
                // Names on the left, each pointing at an address.
                g.getChildren().addAll(block(12, 24, 22, 9, accent), arrowHead(38, 28.5, INK, 4), bar(48, 27, 26, 3, INK),
                        block(12, 37, 22, 9, accent), arrowHead(38, 41.5, INK, 4), bar(48, 40, 20, 3, INK),
                        block(12, 50, 22, 9, accent), arrowHead(38, 54.5, INK, 4), bar(48, 53, 24, 3, INK));
                break;
            case "Tunnels":
                // Two ends joined by a pipe.
                g.getChildren().addAll(block(8, 26, 16, 30, accent), block(24, 35, 38, 12, Color.web("#e0e0e0")),
                        bar(29, 39, 6, 4, INK), bar(40, 39, 6, 4, INK), bar(51, 39, 6, 4, INK),
                        block(62, 26, 16, 30, accent));
                break;
            case "Pending Changes":
                g.getChildren().addAll(bar(12, 27, 50, 3, INK), bar(12, 35, 40, 3, INK), bar(12, 43, 46, 3, INK),
                        block(58, 38, 20, 18, Color.web("#ffcc00")), bar(66, 41, 4, 8, INK), bar(66, 51, 4, 3, INK));
                break;
            default:
                Text t = new Text(20, 53, "404");
                t.setFont(Font.font("Monospaced", FontWeight.BOLD, 30));
                t.setFill(accent);
                g.getChildren().add(t);
        }
        g.setScaleX(scale);
        g.setScaleY(scale);
        VBox holder = new VBox(new Group(g));
        holder.setAlignment(Pos.CENTER);
        holder.setPadding(new Insets(0, 0, 2, 0));
        return holder;
    }
}
