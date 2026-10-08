package mt.su.nrm.ui;

import mt.su.nrm.cloudflare.CloudflareSession;
import mt.su.nrm.cloudflare.CloudflareWorkspace;
import mt.su.nrm.cloudflare.Tunnel;
import mt.su.nrm.cloudflare.Zone;
import mt.su.nrm.model.ServerProfile;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/**
 * The DNS Zones page or the Tunnels page: one tile for each zone or tunnel the token can see, like the Virtual
 * Hosts page has one for each site. Opening a tile shows that zone's records or that tunnel's routes.
 */
final class CloudflareListPage extends CloudflarePage {

    enum Kind { ZONES, TUNNELS }

    private final Kind kind;
    private Consumer<Object> onOpen = item -> { };

    CloudflareListPage(ServerProfile profile, ServerConnection connection, Kind kind) {
        super(profile, connection);
        this.kind = kind;
    }

    /** What opening a tile does; the main window selects that zone or tunnel in the tree. */
    void onOpen(Consumer<Object> action) {
        this.onOpen = action;
    }

    @Override
    void start() {
        setCenter(centered(ProgressDialog.spinner(48), new Label("Connecting to Cloudflare...")));
        hub.whenReady(profile, this::render, problem -> setCenter(message(problem)));
    }

    @Override
    void showCurrent() {
        CloudflareSession session = connection.cloudflare();
        if (session != null) {
            render(session);
        }
    }

    @Override
    void reload() {
        if (dropSession()) {
            start();
        }
    }

    private void render(CloudflareSession session) {
        CloudflareWorkspace workspace = session.workspace();
        FlowPane tiles = new FlowPane(10, 10);
        tiles.setPadding(new Insets(8));
        int count;
        String none;
        if (kind == Kind.ZONES) {
            count = session.zones().size();
            none = "This token can't see any zones (it needs Zone: Read).";
            for (Zone zone : session.zones()) {
                NavTile tile = new NavTile("DNS Zones", zone.name(), zone.name() + "\nDouble-click to open its DNS records.");
                int staged = workspace.isDnsLoaded(zone) ? workspace.dnsChanges(zone).size() : 0;
                tile.setBadge(staged == 0 ? "" : "(" + staged + ")");
                open(tile, zone);
                tiles.getChildren().add(tile);
            }
        } else {
            count = session.tunnels().size();
            none = "This token can't see any tunnels (it needs Cloudflare Tunnel: Edit).";
            for (Tunnel tunnel : session.tunnels()) {
                NavTile tile = new NavTile("Tunnels", tunnel.name(), tunnel.name() + "\nStatus: " + tunnel.status()
                        + "\nDouble-click to open its published applications.");
                if (!tunnel.status().equals("healthy")) {
                    tile.setBadge("(" + tunnel.status() + ")");
                } else if (workspace.isTunnelLoaded(tunnel) && !workspace.routeStatus(tunnel).isEmpty()) {
                    tile.setBadge("(" + workspace.routeStatus(tunnel).size() + ")");
                }
                open(tile, tunnel);
                tiles.getChildren().add(tile);
            }
        }
        Label noneLabel = new Label(none);
        noneLabel.setOpacity(0.75);
        Node tileView = count == 0 ? noneLabel : tiles;
        Button create = new Button(kind == Kind.ZONES ? "New DNS zone" : "New tunnel");
        create.setOnAction(e -> add());
        create.disableProperty().bind(hub.busyProperty());
        HBox bar = new HBox(12, heading((kind == Kind.ZONES ? "DNS Zones" : "Tunnels") + " (" + count + ")"), create);
        bar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        VBox box = new VBox(8, bar, new Separator(), tileView, busyIndicator());
        box.setPadding(new Insets(6, 16, 12, 16));
        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent;");
        // Right-clicking empty space (a tile handles its own click first): add one, or read the list again.
        scroll.setOnContextMenuRequested(e -> showMenu(scroll, e,
                menuItem(kind == Kind.ZONES ? "New DNS zone" : "New tunnel", this::add, false), menuSeparator(),
                menuItem("Reload from Cloudflare", this::reload, false)));
        setCenter(scroll);
    }

    /** A new DNS zone, or a new tunnel. These are created on Cloudflare at once; they can't be staged. */
    @Override
    void add() {
        CloudflareSession session = connection.cloudflare();
        if (session == null || hub.busyProperty().get()) {
            return;
        }
        if (session.accountIds().isEmpty()) {
            Dialogs.info(window(), "Can't tell which account to use", "This app learns your Cloudflare account from "
                    + "the zones the token can see, and it can see none. Give the token Zone: Read, then reload.");
            return;
        }
        if (kind == Kind.ZONES) {
            CloudflareDialogs.newZone(window(), session.accountIds(), session::accountLabel,
                    session.zones().stream().map(Zone::name).toList()).ifPresent(in ->
                    hub.run("Adding " + in.name() + " to Cloudflare...", () -> session.createZone(in.accountId(), in.name()),
                            created -> {
                                CloudflareSession now = connection.cloudflare() != null ? connection.cloudflare() : session;
                                connection.setCloudflare(now.withZone(created.zone()));
                                showCurrent();
                                CloudflareDialogs.zoneCreated(window(), created);
                                onOpen.accept(created.zone());
                            }, problem -> error("The domain was not added", problem)));
        } else {
            CloudflareDialogs.newTunnel(window(), session.accountIds(), session::accountLabel,
                    session.tunnels().stream().map(Tunnel::name).toList()).ifPresent(in ->
                    hub.run("Creating tunnel " + in.name() + "...", () -> session.createTunnel(in.accountId(), in.name()),
                            created -> {
                                CloudflareSession now = connection.cloudflare() != null ? connection.cloudflare() : session;
                                connection.setCloudflare(now.withTunnel(created.tunnel(), created.accountId()));
                                showCurrent();
                                CloudflareDialogs.tunnelCreated(window(), created);
                                onOpen.accept(created.tunnel());
                            }, problem -> error("The tunnel was not created", problem)));
        }
    }

    private void open(NavTile tile, Object item) {
        tile.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                onOpen.accept(item);
            }
        });
        tile.setOnContextMenuRequested(e -> showMenu(tile, e,
                menuItem("Open", () -> onOpen.accept(item), false), menuSeparator(),
                item instanceof Zone zone
                        ? menuItem("Delete zone", () -> CloudflareRemoval.zone(window(), connection, zone,
                                this::showCurrent), false)
                        : menuItem("Delete tunnel", () -> CloudflareRemoval.tunnel(window(), connection,
                                (Tunnel) item, this::showCurrent), false)));
    }

    /** For tests: how many tiles are shown. */
    int tileCount() {
        if (!(getCenter() instanceof ScrollPane scroll) || !(scroll.getContent() instanceof VBox box)) {
            return 0;
        }
        for (Node n : box.getChildren()) {
            if (n instanceof FlowPane flow) {
                return flow.getChildren().size();
            }
        }
        return 0;
    }
}
