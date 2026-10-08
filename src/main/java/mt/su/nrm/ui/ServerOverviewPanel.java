package mt.su.nrm.ui;

import mt.su.nrm.model.ServerProfile;
import mt.su.nrm.nginx.ConfigFile;
import mt.su.nrm.nginx.PendingChange;
import mt.su.nrm.nginx.RemoteConfig;
import mt.su.nrm.nginx.SiteSummary;
import mt.su.nrm.nginx.VirtualHost;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What you see when you click a server: its facts at the top and, once connected, a table of its
 * virtual hosts in columns like the Sites list of IIS Manager (site, bindings, content, SSL,
 * locations, file, status).
 */
final class ServerOverviewPanel extends BorderPane {

    private record Row(VirtualHost host, SiteSummary site, String file, String status) {
    }

    private final ServerProfile profile;
    private final ServerConnection connection;
    private final ConnectionManager manager;
    private final Node facts;
    /** The tile the user last clicked, for the Actions pane (Delete, View logs). */
    private Row selectedRow;
    /** The existing site currently open in the editor, if any (not a new one being added). */
    private VirtualHost openHost;
    /** The site currently opened in the window (its parts as icons), or null for the list of sites. */
    private Node workspace;

    private final javafx.beans.value.ChangeListener<Object> listener = (obs, o, n) -> render();

    ServerOverviewPanel(ServerProfile profile, ServerConnection connection, ConnectionManager manager, Node facts) {
        this.profile = profile;
        this.connection = connection;
        this.manager = manager;
        this.facts = facts;

        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) {
                connection.stateProperty().removeListener(listener);
                connection.configStateProperty().removeListener(listener);
                connection.pendingCountProperty().removeListener(listener);
            } else {
                connection.stateProperty().addListener(listener);
                connection.configStateProperty().addListener(listener);
                connection.pendingCountProperty().addListener(listener);
                render();
            }
        });
        render();
    }

    private void render() {
        if (!connection.isConnected() || connection.configState() != ServerConnection.ConfigState.LOADED) {
            workspace = null;
        }
        if (workspace != null) {
            setTop(null);
            setCenter(workspace);
            return;
        }
        VBox top = new VBox(facts);
        top.setPadding(new Insets(0));
        setTop(top);

        if (!connection.isConnected()) {
            setCenter(message("Connect to this server to see its virtual hosts here."));
            return;
        }
        switch (connection.configState()) {
            case LOADING:
                setCenter(centered(ProgressDialog.spinner(48), new Label("Reading the nginx configuration...")));
                break;
            case LOADED:
                setCenter(sites(connection.config()));
                break;
            case FAILED:
                Button retry = new Button("Try again");
                retry.setOnAction(e -> manager.loadConfig(profile, () -> { }));
                Label error = new Label("The configuration could not be read:\n" + connection.configError());
                error.setWrapText(true);
                setCenter(centered(error, retry));
                break;
            default:
                setCenter(message("The configuration has not been loaded."));
        }
    }

    private java.util.function.Consumer<String> onViewLogs = name -> { };
    private java.util.function.Consumer<MainWindow.NavKind> onOpenSection = kind -> { };
    private Runnable onSiteClosed = () -> { };

    /** What opening one of the section tiles does; the main window selects that entry in the tree. */
    void onOpenSection(java.util.function.Consumer<MainWindow.NavKind> action) {
        this.onOpenSection = action;
    }

    /** Runs when a site opened in the window is closed, so the tree can go back to the Virtual Hosts entry. */
    void onSiteClosed(Runnable action) {
        this.onSiteClosed = action;
    }

    /** Opens this site's parts in the window (used when a site is chosen in the tree). */
    void openSite(VirtualHost host) {
        if (connection.config() != null && connection.config().virtualHosts().contains(host)) {
            // (VirtualHost compares by server block, since virtualHosts() builds new wrappers each call.)
            editSite(host);
        }
    }

    /** What "View logs" in the right-click menu does; the main window switches to the Logs screen. */
    void onViewLogs(java.util.function.Consumer<String> action) {
        this.onViewLogs = action;
    }

    private static MenuItem item(String text, Runnable run) {
        MenuItem i = new MenuItem(text);
        i.setOnAction(e -> run.run());
        return i;
    }

    // ---------------------------------------------------------------- actions (also used by the Actions pane)

    /** Opens an empty virtual host in the window; keeping it creates a pending new file. */
    void addHost() {
        RemoteConfig config = connection.config();
        if (config == null) {
            return;
        }
        mt.su.nrm.nginx.VhostSettings initial = new mt.su.nrm.nginx.VhostSettings();
        initial.listens.add(new mt.su.nrm.nginx.VhostSettings.ListenSpec("80"));
        List<mt.su.nrm.nginx.VhostSettings> others = new ArrayList<>();
        config.virtualHosts().forEach(h -> others.add(h.read()));
        VirtualHostEditor editor = new VirtualHostEditor(initial, others, List.of(), true, ZoneNames.of(config),
                new ConnectionServerAccess(profile, connection));
        workspace = new SiteWorkspace("New virtual host", editor, settings -> {
            String name = settings.serverNames.isEmpty() ? "new-site" : settings.serverNames.get(0);
            try {
                config.createVirtualHost(name).apply(settings);
            } catch (IllegalArgumentException e) {
                Dialogs.error(getScene().getWindow(), "Can't add this virtual host", e.getMessage());
                return;
            }
            connection.configChanged();
        }, () -> {
            workspace = null;
            render();
            onSiteClosed.run();
        });
        render();
    }

    void deleteSelected() {
        RemoteConfig config = connection.config();
        if (config == null) {
            return;
        }
        // The site open in the window (chosen in the tree) wins over a highlighted tile.
        VirtualHost target = openHost != null ? openHost : selectedRow == null ? null : selectedRow.host();
        if (target == null) {
            Dialogs.info(getScene().getWindow(), "Select a virtual host first",
                    "Click the virtual host you want to delete, then choose Delete again.");
            return;
        }
        String reason = config.readOnlyReason(target);
        if (reason != null) {
            Dialogs.info(getScene().getWindow(), "This virtual host can't be deleted here", reason);
            return;
        }
        String name = SiteSummary.of(target.read()).name();
        if (Dialogs.confirm(getScene().getWindow(), "Delete " + name + "?",
                "The virtual host is removed from the pending changes. Nothing on the server changes until you "
                        + "apply, and the old file is backed up first.", "Delete")) {
            boolean wasOpen = openHost != null;
            config.deleteVirtualHost(target);
            selectedRow = null;
            openHost = null;
            workspace = null;
            connection.configChanged();
            render();
            if (wasOpen) {
                onSiteClosed.run();
            }
        }
    }

    private java.util.function.Consumer<Feature> onOpenFeature = feature -> { };

    /** What opening an add-on's tile does; the main window selects that add-on in the tree. */
    void onOpenFeature(java.util.function.Consumer<Feature> action) {
        this.onOpenFeature = action;
    }

    private java.util.function.BiConsumer<MainWindow.NavKind, Object> onShowCloudflareItem = (kind, item) -> { };

    /** What happens once a site is staged in Cloudflare; the main window opens the zone or tunnel page. */
    void onShowCloudflareItem(java.util.function.BiConsumer<MainWindow.NavKind, Object> action) {
        this.onShowCloudflareItem = action;
    }

    /** Publishes the selected (or open) virtual host through Cloudflare, with a wizard. */
    void addToCloudflare() {
        VirtualHost target = openHost != null ? openHost : selectedRow == null ? null : selectedRow.host();
        if (target == null) {
            Dialogs.info(getScene().getWindow(), "Select a virtual host first",
                    "Click the virtual host you want to add to Cloudflare, then choose Add to Cloudflare again.");
            return;
        }
        CloudflareVhostFlow.start(getScene().getWindow(), profile, connection, target.read(), onShowCloudflareItem);
    }

    void refreshFromServer() {
        if (connection.pendingCountProperty().get() > 0 && !Dialogs.confirm(getScene().getWindow(),
                "Discard pending changes?", "Reloading from the server throws away the changes you haven't applied yet.",
                "Discard and reload")) {
            return;
        }
        manager.loadConfig(profile, () -> { });
    }

    /** The first server name of the selected site, or null if none is selected. */
    String selectedSiteName() {
        return selectedRow == null ? null : selectedRow.site().name();
    }

    /** Opens the site in the window; changes become pending changes. */
    private void editSite(VirtualHost host) {
        RemoteConfig config = connection.config();
        if (config == null) {
            return;
        }
        String reason = config.readOnlyReason(host);
        if (reason != null) {
            // The panel isn't on screen yet when the tree opens a site, so there may be no window.
            Dialogs.info(getScene() == null ? null : getScene().getWindow(), "This virtual host can't be edited here", reason);
            return;
        }
        List<mt.su.nrm.nginx.VhostSettings> others = new ArrayList<>();
        for (VirtualHost h : config.virtualHosts()) {
            if (h.block() != host.block()) {
                others.add(h.read());
            }
        }
        VirtualHostEditor editor = new VirtualHostEditor(host.read(), others, host.unmanaged(), false, ZoneNames.of(config),
                new ConnectionServerAccess(profile, connection));
        String name = SiteSummary.of(host.read()).name();
        openHost = host;
        workspace = new SiteWorkspace(name, editor, settings -> {
            host.apply(settings);
            connection.configChanged();
        }, () -> {
            workspace = null;
            openHost = null;
            render();
            onSiteClosed.run();
        });
        render();
    }

    private Node sites(RemoteConfig config) {
        Map<ConfigFile, String> pending = new HashMap<>();
        for (PendingChange c : config.pendingChanges()) {
            pending.put(c.file(), c.kind() == PendingChange.Kind.CREATE ? "New" : "Modified");
        }
        List<Row> rows = new ArrayList<>();
        for (VirtualHost host : config.virtualHosts()) {
            String status = config.readOnlyReason(host) != null ? "Read-only" : pending.getOrDefault(host.file(), "");
            rows.add(new Row(host, SiteSummary.of(host.read()), host.file().path(), status));
        }
        selectedRow = null;
        Label heading = new Label("Virtual hosts (" + rows.size() + ")");
        heading.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");

        // Icon view: one tile per site, wrapping into rows as wide as the window allows.
        FlowPane tiles = new FlowPane(10, 10);
        tiles.setPadding(new Insets(8));
        // Clicking empty space, or pressing Esc, clears the selection.
        tiles.setOnMouseClicked(e -> {
            if (e.getTarget() == tiles) {
                tiles.getChildren().forEach(n -> ((SiteTile) n).setSelected(false));
                selectedRow = null;
            }
        });
        tiles.setOnContextMenuRequested(e -> {
            if (e.getTarget() == tiles) {
                tiles.getChildren().forEach(n -> ((SiteTile) n).setSelected(false));
                selectedRow = null;
                ContextMenu menu = new ContextMenu(item("Add virtual host", this::addHost),
                        new SeparatorMenuItem(), item("Refresh from server", this::refreshFromServer));
                menu.show(tiles, e.getScreenX(), e.getScreenY());
                e.consume();
            }
        });
        tiles.setFocusTraversable(true);
        tiles.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                tiles.getChildren().forEach(n -> ((SiteTile) n).setSelected(false));
            }
        });
        List<SiteTile> tileList = new ArrayList<>();
        for (Row row : rows) {
            SiteTile tile = new SiteTile(row.site(), row.file(), row.status());
            tileList.add(tile);
            tile.setOnMouseClicked(e -> {
                selectedRow = row;
                tileList.forEach(t -> t.setSelected(t == tile));
                tiles.requestFocus();
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                    editSite(row.host());
                    // The editor is closed (saved, cancelled or Esc): nothing stays highlighted.
                    tileList.forEach(t -> t.setSelected(false));
                }
            });
            tile.setOnContextMenuRequested(e -> {
                selectedRow = row;
                tileList.forEach(t -> t.setSelected(t == tile));
                boolean editable = connection.config() != null && connection.config().readOnlyReason(row.host()) == null;
                MenuItem open = item("Open", () -> editSite(row.host()));
                MenuItem delete = item("Delete", this::deleteSelected);
                delete.setDisable(!editable);
                ContextMenu menu = new ContextMenu(open, new SeparatorMenuItem(),
                        item("View logs", () -> onViewLogs.accept(row.site().name())),
                        item("Add to Cloudflare", this::addToCloudflare), delete);
                menu.show(tile, e.getScreenX(), e.getScreenY());
                e.consume();
            });
            tiles.getChildren().add(tile);
        }
        Label none = new Label("No virtual hosts were found on this server.");
        none.setOpacity(0.75);
        Node tileView = rows.isEmpty() ? none : tiles;

        Button add = new Button("Add virtual host");
        add.setOnAction(e -> addHost());
        HBox bar = new HBox(12, heading, add);
        bar.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(8, bar, new javafx.scene.control.Separator(), tileView, sections(config));
        box.setPadding(new Insets(6, 16, 12, 16));
        // One scroll area for the sites and the server's sections below them.
        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent;");
        return scroll;
    }

    /** What each grid of section tiles holds, in the order shown: the same entries as the tree on the left. */
    private static final java.util.Map<String, List<MainWindow.NavKind>> SECTION_GROUPS = new java.util.LinkedHashMap<>();

    static {
        SECTION_GROUPS.put("Traffic", List.of(MainWindow.NavKind.UPSTREAMS, MainWindow.NavKind.CACHE, MainWindow.NavKind.LIMITS,
                MainWindow.NavKind.CLOUDFLARE));
        SECTION_GROUPS.put("Security", List.of(MainWindow.NavKind.CERTIFICATES));
        SECTION_GROUPS.put("Server", List.of(MainWindow.NavKind.GLOBAL, MainWindow.NavKind.LOGS,
                MainWindow.NavKind.LOG_FORMATS, MainWindow.NavKind.PENDING, MainWindow.NavKind.HISTORY));
    }

    private static String tileTitle(MainWindow.NavKind kind) {
        switch (kind) {
            case UPSTREAMS:
                return "Load Balancing";
            case CACHE:
                return "Cache Zones";
            case LIMITS:
                return "Rate Limits";
            case CERTIFICATES:
                return "SSL Certificates";
            case CLOUDFLARE:
                return "Cloudflare";
            case LOGS:
                return "Logs";
            case LOG_FORMATS:
                return "Log Formats";
            case PENDING:
                return "Pending Changes";
            case HISTORY:
                return "Change History";
            default:
                return "Global Settings";
        }
    }

    private static String tileHint(MainWindow.NavKind kind) {
        switch (kind) {
            case UPSTREAMS:
                return "Groups of servers that share the load";
            case CACHE:
                return "Places nginx keeps copies of responses";
            case LIMITS:
                return "Limits on requests and connections";
            case CERTIFICATES:
                return "Certificates, Let's Encrypt and your own CAs";
            case CLOUDFLARE:
                return "DNS zones and tunnels that publish your applications";
            case LOGS:
                return "Access and error logs";
            case LOG_FORMATS:
                return "What each line of an access log says";
            case PENDING:
                return "Changes waiting to be applied";
            case HISTORY:
                return "Go back to an earlier version";
            default:
                return "Workers, compression and other server-wide settings";
        }
    }

    /** The tree's other entries as tiles under headings, so everything can be reached from the overview too. */
    private Node sections(RemoteConfig config) {
        VBox all = new VBox(6);
        all.setPadding(new Insets(10, 0, 0, 0));
        for (java.util.Map.Entry<String, List<MainWindow.NavKind>> group : SECTION_GROUPS.entrySet()) {
            Label heading = new Label(group.getKey());
            heading.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
            FlowPane row = new FlowPane(10, 10);
            row.setPadding(new Insets(4, 8, 8, 8));
            for (MainWindow.NavKind kind : group.getValue()) {
                NavTile tile = new NavTile(tileTitle(kind), tileHint(kind) + ". Double-click to open.");
                if (kind == MainWindow.NavKind.PENDING) {
                    int pending = connection.pendingCountProperty().get();
                    tile.setBadge(pending == 0 ? "" : "(" + pending + ")");
                } else if (kind == MainWindow.NavKind.CLOUDFLARE) {
                    int staged = connection.cloudflarePendingProperty().get();
                    tile.setBadge(staged == 0 ? "" : "(" + staged + ")");
                }
                tile.setOnMouseClicked(e -> {
                    if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                        onOpenSection.accept(kind);
                    }
                });
                row.getChildren().add(tile);
            }
            all.getChildren().addAll(heading, new javafx.scene.control.Separator(), row);
        }
        // Add-ons the user has switched on (View > Features) get a group of their own.
        List<Feature> addOns = Features.enabled();
        if (!addOns.isEmpty()) {
            Label heading = new Label("Add-ons");
            heading.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
            FlowPane row = new FlowPane(10, 10);
            row.setPadding(new Insets(4, 8, 8, 8));
            for (Feature feature : addOns) {
                NavTile tile = new NavTile(feature.iconKey(), feature.title(), feature.description()
                        + " Double-click to open.");
                tile.setOnMouseClicked(e -> {
                    if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                        onOpenFeature.accept(feature);
                    }
                });
                row.getChildren().add(tile);
            }
            all.getChildren().addAll(heading, new javafx.scene.control.Separator(), row);
        }
        return all;
    }

    private static Node message(String text) {
        Label l = new Label(text);
        l.setOpacity(0.75);
        VBox box = new VBox(l);
        box.setPadding(new Insets(16));
        return box;
    }

    private static Node centered(Node... nodes) {
        VBox box = new VBox(14, nodes);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(30));
        return box;
    }
}
