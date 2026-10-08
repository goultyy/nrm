package mt.su.nrm.ui;

import mt.su.nrm.cloudflare.CloudflareSession;
import mt.su.nrm.model.ServerProfile;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/**
 * The Cloudflare page: two icons, DNS Zones and Tunnels, each opening the list of its kind. Opening the page also
 * connects to Cloudflare, so the zones and tunnels the token can see appear under those entries in the tree.
 */
final class CloudflareHomePage extends CloudflarePage {

    private final Label status = new Label();
    private Consumer<MainWindow.NavKind> onOpenSection = kind -> { };

    CloudflareHomePage(ServerProfile profile, ServerConnection connection) {
        super(profile, connection);
    }

    /** What opening one of the icons does; the main window selects that entry in the tree. */
    void onOpenSection(Consumer<MainWindow.NavKind> action) {
        this.onOpenSection = action;
    }

    @Override
    void start() {
        String token = profile.getCloudflareToken();
        if (token == null || token.isBlank()) {
            setCenter(notSetUp());
            return;
        }
        NavTile zones = tile("DNS Zones", "The domains in your Cloudflare account and their DNS records. "
                + "Double-click to open.", MainWindow.NavKind.CF_ZONES);
        NavTile tunnels = tile("Tunnels", "Cloudflare Tunnels and the applications they publish. "
                + "Double-click to open.", MainWindow.NavKind.CF_TUNNELS);
        FlowPane row = new FlowPane(10, 10, zones, tunnels);
        row.setPadding(new Insets(8));

        VBox box = new VBox(8, heading("Cloudflare"), new Separator(), row);
        box.setPadding(new Insets(6, 16, 12, 16));
        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent;");
        setCenter(scroll);

        // The connection status sits in a small bar along the bottom, out of the way of the icons.
        status.setWrapText(true);
        status.setOpacity(0.75);
        status.setStyle("-fx-font-size: 10.5px;");
        VBox bar = new VBox(4, new Separator(), status, busyIndicator());
        bar.setPadding(new Insets(0, 16, 6, 16));
        setBottom(bar);
        connect();
    }

    /** The page shown when no API token is saved: the Cloudflare picture at the left, a headline and one line. */
    private static javafx.scene.Node notSetUp() {
        Label title = new Label("Cloudflare isn't set up for this server");
        title.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: #b26a00;");
        title.setWrapText(true);
        Label reason = new Label("No API key is present.");
        reason.setStyle("-fx-font-size: 14px;");

        // The picture is wrapped so it sits at the left edge, in line with the text, not in the middle.
        HBox icon = new HBox(RetroIcon.build("Cloudflare", 1.4));
        icon.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        VBox box = new VBox(14, icon, title, reason);
        box.setAlignment(javafx.geometry.Pos.TOP_LEFT);
        box.setPadding(new Insets(28, 36, 28, 36));
        return new ScrollPane(box) {
            {
                setFitToWidth(true);
                setStyle("-fx-background-color: transparent;");
            }
        };
    }

    private NavTile tile(String title, String tooltip, MainWindow.NavKind kind) {
        NavTile tile = new NavTile(title, tooltip);
        tile.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                onOpenSection.accept(kind);
            }
        });
        tile.setOnContextMenuRequested(e -> showMenu(tile, e,
                menuItem("Open", () -> onOpenSection.accept(kind), false)));
        return tile;
    }

    private void connect() {
        status.setText("");
        hub.whenReady(profile, this::connected, problem -> status.setText(problem));
    }

    private void connected(CloudflareSession session) {
        status.setText("Connected to Cloudflare: " + count(session.zones().size(), "zone") + " and "
                + count(session.tunnels().size(), "tunnel") + " visible to this token.");
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    @Override
    void showCurrent() {
        // Nothing here depends on what is staged.
    }

    @Override
    void reload() {
        if (dropSession()) {
            connect();
        }
    }
}
