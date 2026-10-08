package mt.su.nrm.ui;

import mt.su.nrm.config.ProfileRepository;
import mt.su.nrm.model.ServerProfile;
import mt.su.nrm.model.ToolStatus;
import mt.su.nrm.nginx.RemoteConfig;
import mt.su.nrm.nginx.SiteSummary;
import mt.su.nrm.nginx.VirtualHost;
import mt.su.nrm.util.ThemeMode;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToolBar;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Window;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The main window, in the style of IIS Manager: a menu bar and toolbar on top, the server tree on
 * the left, tabbed properties in the middle, an Actions pane on the right, the command log below
 * and a status bar at the bottom. The server selector is the property panel of the tree's root.
 */
public final class MainWindow extends BorderPane {

    /** What a tree row stands for. */
    enum NavKind {
        ROOT, SERVER, VHOSTS, SITE, UPSTREAMS, CACHE, LIMITS, LOG_FORMATS, PENDING, HISTORY, CERTIFICATES,
        CLOUDFLARE, CF_ZONES, CF_ZONE, CF_TUNNELS, CF_TUNNEL, LOGS, FILES, GLOBAL, FEATURE
    }

    /**
     * @param host the virtual host, for a {@link NavKind#SITE} entry; null otherwise
     * @param item the zone or tunnel, for a {@link NavKind#CF_ZONE} or {@link NavKind#CF_TUNNEL} entry; null otherwise
     */
    record NavNode(NavKind kind, ServerProfile profile, VirtualHost host, Object item) {
        NavNode(NavKind kind, ServerProfile profile) {
            this(kind, profile, null, null);
        }

        NavNode(NavKind kind, ServerProfile profile, VirtualHost host) {
            this(kind, profile, host, null);
        }

        NavNode(NavKind kind, ServerProfile profile, Object item) {
            this(kind, profile, null, item);
        }

        @Override
        public String toString() {
            switch (kind) {
                case VHOSTS:
                    return "Virtual Hosts";
                case SITE:
                    return SiteSummary.of(host.read()).name();
                case ROOT:
                    return "Servers";
                case SERVER:
                    return profile.getName();
                case UPSTREAMS:
                    return "Load Balancing";
                case CACHE:
                    return "Cache Zones";
                case LIMITS:
                    return "Rate Limits";
                case LOG_FORMATS:
                    return "Log Formats";
                case PENDING:
                    return "Pending Changes";
                case HISTORY:
                    return "Change History";
                case CERTIFICATES:
                    return "SSL Certificates";
                case CLOUDFLARE:
                    return "Cloudflare";
                case CF_ZONES:
                    return "DNS Zones";
                case CF_TUNNELS:
                    return "Tunnels";
                case CF_ZONE:
                    return ((mt.su.nrm.cloudflare.Zone) item).name();
                case CF_TUNNEL:
                    return ((mt.su.nrm.cloudflare.Tunnel) item).name();
                case FEATURE:
                    return ((Feature) item).title();
                case LOGS:
                    return "Logs";
                case FILES:
                    return "File Explorer";
                default:
                    return "Global Settings";
            }
        }
    }

    private final ProfileRepository repository;
    private final ConnectionManager connections;

    private final TreeView<NavNode> tree = new TreeView<>();
    private final TabPane properties = new TabPane();
    private final VBox actions = new VBox(8);
    private final BorderPane logHolder = new BorderPane();
    private final SplitPane vertical = new SplitPane();
    private final Label status = new Label("Ready");
    private final Label connectionStatus = new Label("Not connected");
    private final CheckMenuItem showLog = new CheckMenuItem("Command log");
    private final ServerSelectorView selector;

    private Button connectButton;
    private Button disconnectButton;

    /** Kept so the listener can be detached when the selected server changes. */
    private ServerConnection watched;
    private final ChangeListener<ServerConnection.State> stateListener = (obs, o, n) -> onConnectionStateChanged();
    /** Keeps the "Pending Changes (n)" label in the tree current. */
    private final ChangeListener<Number> pendingListener = (obs, o, n) -> {
        tree.refresh();
        updateSiteItems();
    };
    /** Keeps the "Cloudflare (n)" label in the tree current. */
    private final ChangeListener<Number> cloudflareListener = (obs, o, n) -> tree.refresh();
    /** Lists the zones and tunnels under DNS Zones and Tunnels once a Cloudflare session is open. */
    private final ChangeListener<mt.su.nrm.cloudflare.CloudflareSession> cloudflareSessionListener =
            (obs, o, n) -> updateCloudflareItems();
    /** Fills the Virtual Hosts entry once the configuration has been read. */
    private final ChangeListener<ServerConnection.ConfigState> configListener = (obs, o, n) -> updateSiteItems();

    /** The panels currently shown, so the Actions pane can drive them. */
    private ServerOverviewPanel overviewPanel;
    private PendingChangesPanel pendingPanel;
    private LogsPanel logsPanel;
    private HistoryPanel historyPanel;
    private HttpObjectsPanel<?> objectsPanel;
    private SslPanel certificatesPanel;
    private CloudflarePage cloudflarePage;
    private FeaturePage featurePage;
    private SftpPanel sftpPanel;

    public MainWindow(ProfileRepository repository) {
        this.repository = repository;
        this.connections = new ConnectionManager(repository);
        this.selector = new ServerSelectorView(repository, this::connect, this::rebuildTree);
        // Turning an add-on on or off under View > Features adds or removes its entry in the tree.
        Features.addListener(this::rebuildTree);

        tree.setShowRoot(true);
        tree.setCellFactory(v -> new NavCell());
        tree.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> onSelectionChanged(n));
        tree.setMinWidth(150);

        properties.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        actions.setFillWidth(true);
        actions.setPadding(new Insets(12));
        actions.setMinWidth(170);
        actions.setPrefWidth(190);
        Label actionsHeading = new Label("Actions");
        actionsHeading.setStyle("-fx-font-weight: bold;");
        VBox actionsPane = new VBox(actionsHeading, actions);
        actionsPane.setPadding(new Insets(10, 0, 0, 0));

        SplitPane horizontal = new SplitPane(tree, properties, actionsPane);
        horizontal.setDividerPositions(0.15, 0.80);
        SplitPane.setResizableWithParent(tree, false);
        SplitPane.setResizableWithParent(actionsPane, false);

        vertical.setOrientation(Orientation.VERTICAL);
        vertical.getItems().add(horizontal);
        showLog.setSelected(true);
        showLog.selectedProperty().addListener((obs, o, n) -> updateLogVisibility());

        setTop(new VBox(buildMenuBar(), buildToolBar()));
        setCenter(vertical);
        setBottom(buildStatusBar());

        rebuildTree();
        tree.getSelectionModel().select(tree.getRoot());
        updateLogVisibility();
    }

    /** False if the user is warned about unapplied changes and chooses to stay. Call before closing the window. */
    public boolean canClose() {
        for (ServerProfile profile : repository.list()) {
            if (connections.of(profile.getId()).pendingCountProperty().get() > 0) {
                return Dialogs.confirm(getScene().getWindow(), "Discard pending changes?",
                        "There are changes to " + profile.getName() + " that haven't been applied. "
                                + "Closing throws them away.", "Close and discard");
            }
        }
        return true;
    }

    /** Closes every open session; call when the window closes. */
    public void shutdown() {
        connections.disconnectAll();
    }

    // ---------------------------------------------------------------- menu, toolbar, status bar

    private MenuBar buildMenuBar() {
        MenuItem manage = new MenuItem("Manage Servers");
        manage.setAccelerator(new KeyCodeCombination(KeyCode.M, KeyCombination.CONTROL_DOWN));
        manage.setOnAction(e -> tree.getSelectionModel().select(tree.getRoot()));
        MenuItem connect = new MenuItem("Connect");
        connect.setAccelerator(new KeyCodeCombination(KeyCode.K, KeyCombination.CONTROL_DOWN));
        connect.setOnAction(e -> connectSelected());
        MenuItem disconnect = new MenuItem("Disconnect");
        disconnect.setAccelerator(new KeyCodeCombination(KeyCode.K, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN));
        disconnect.setOnAction(e -> disconnectSelected());
        MenuItem exit = new MenuItem("Exit");
        exit.setAccelerator(new KeyCodeCombination(KeyCode.Q, KeyCombination.CONTROL_DOWN));
        exit.setOnAction(e -> {
            // Goes through the window's close request, so pending changes are asked about and the position saved.
            Window w = getScene().getWindow();
            w.fireEvent(new javafx.stage.WindowEvent(w, javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST));
        });
        Menu file = new Menu("File", null, manage, new SeparatorMenuItem(), connect, disconnect,
                new SeparatorMenuItem(), exit);

        showLog.setAccelerator(new KeyCodeCombination(KeyCode.L, KeyCombination.CONTROL_DOWN));
        MenuItem refresh = new MenuItem("Refresh");
        refresh.setAccelerator(new KeyCodeCombination(KeyCode.F5));
        refresh.setOnAction(e -> refreshCurrent());
        MenuItem features = new MenuItem("Features");
        features.setOnAction(e -> FeaturesDialog.show(getScene() == null ? null : getScene().getWindow()));
        Menu view = new Menu("View", null, showLog, refresh, new SeparatorMenuItem(), themeMenu(), features);

        MenuItem openLog = new MenuItem("Open application log");
        openLog.setOnAction(e -> openApplicationLog());
        MenuItem about = new MenuItem("About");
        about.setOnAction(e -> {
            Alert a = new Alert(Alert.AlertType.INFORMATION,
                    "SUMMIT (c) 2026 (Summit is an operating brand of CasnCorp).\n\n"
                            + "Version " + mt.su.nrm.util.AppInfo.version() + "\n"
                            + "Java " + System.getProperty("java.version") + "\n\n"
                            + "Saved servers: " + mt.su.nrm.util.AppDirs.profileStoreFile() + "\n"
                            + "Log file: " + mt.su.nrm.util.AppLog.file() + "\n\n"
                            + "Profiles are encrypted for your Windows account. Private keys on your servers "
                            + "stay there unless you choose to download one, after a warning.",
                    ButtonType.OK);
            a.setTitle("About");
            a.setHeaderText(mt.su.nrm.util.AppInfo.NAME);
            a.initOwner(getScene().getWindow());
            a.show();
        });
        Menu help = new Menu("Help", null, openLog, about);
        return new MenuBar(file, view, help);
    }

    /** View > Theme: System (follows Windows), Light or Dark. The choice is saved and applies at once. */
    private static Menu themeMenu() {
        ToggleGroup group = new ToggleGroup();
        Menu theme = new Menu("Theme");
        for (ThemeMode mode : ThemeMode.values()) {
            RadioMenuItem item = new RadioMenuItem(mode.label());
            item.setToggleGroup(group);
            item.setSelected(mode == Theme.mode());
            item.setOnAction(e -> Theme.setMode(mode));
            theme.getItems().add(item);
        }
        return theme;
    }

    /** F5: re-reads what is on screen from the server (asking first if that would discard pending changes). */
    private void refreshCurrent() {
        NavNode node = currentNode();
        if (node == null || node.profile() == null || !connections.of(node.profile().getId()).isConnected()) {
            rebuildTree();
            return;
        }
        if (node.kind() == NavKind.CERTIFICATES && certificatesPanel != null) {
            certificatesPanel.reload();
            return;
        }
        if (cloudflarePage != null) {
            cloudflarePage.reload();
            return;
        }
        if (featurePage != null) {
            featurePage.reload();
            return;
        }
        ServerConnection connection = connections.of(node.profile().getId());
        if (connection.pendingCountProperty().get() > 0 && !Dialogs.confirm(getScene().getWindow(),
                "Discard pending changes?", "Reloading from the server throws away the changes you haven't applied yet.",
                "Discard and reload")) {
            return;
        }
        status.setText("Reading the nginx configuration from " + node.profile().getName() + "...");
        connections.loadConfig(node.profile(), () -> status.setText("Connected to " + node.profile().getName()));
    }

    /** Opens the log file in the system's default viewer. */
    private void openApplicationLog() {
        java.nio.file.Path log = mt.su.nrm.util.AppLog.file();
        if (!java.nio.file.Files.exists(log)) {
            Dialogs.info(getScene().getWindow(), "No log yet", "Nothing has been logged yet.\n" + log);
            return;
        }
        try {
            java.awt.Desktop.getDesktop().open(log.toFile());
        } catch (java.io.IOException | RuntimeException e) {
            Dialogs.info(getScene().getWindow(), "Log file", "Open this file to see the log:\n" + log);
        }
    }

    private ToolBar buildToolBar() {
        Button servers = new Button("Servers");
        servers.setOnAction(e -> tree.getSelectionModel().select(tree.getRoot()));
        connectButton = new Button("Connect");
        connectButton.setOnAction(e -> connectSelected());
        disconnectButton = new Button("Disconnect");
        disconnectButton.setOnAction(e -> disconnectSelected());
        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> refreshCurrent());
        return new ToolBar(servers, new javafx.scene.control.Separator(Orientation.VERTICAL),
                connectButton, disconnectButton, refresh);
    }

    private Node buildStatusBar() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, status, spacer, connectionStatus);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(4, 10, 4, 10));
        bar.setStyle("-fx-border-color: -fx-box-border transparent transparent transparent;");
        return bar;
    }

    private void updateLogVisibility() {
        boolean show = showLog.isSelected();
        vertical.getItems().remove(logHolder);
        if (show) {
            logHolder.setMinHeight(80);
            vertical.getItems().add(logHolder);
            vertical.setDividerPositions(0.72);
        }
    }

    // ---------------------------------------------------------------- tree

    private void rebuildTree() {
        UUID selectedServer = currentServerId();
        NavKind selectedKind = currentNode() == null ? NavKind.ROOT : currentNode().kind();
        // Several add-ons can be on, so the selection must come back to the same one, not just the first.
        Object selectedFeature = currentNode() != null && currentNode().kind() == NavKind.FEATURE
                ? currentNode().item() : null;
        // The lists of sites, zones and tunnels come and go, so the selection falls back to the entry above them.
        if (selectedKind == NavKind.SITE) {
            selectedKind = NavKind.VHOSTS;
        } else if (selectedKind == NavKind.CF_ZONE) {
            selectedKind = NavKind.CF_ZONES;
        } else if (selectedKind == NavKind.CF_TUNNEL) {
            selectedKind = NavKind.CF_TUNNELS;
        }
        // Groups start closed; what the user opened stays open when the tree is rebuilt.
        java.util.Set<String> opened = new java.util.HashSet<>();
        collectExpanded(tree.getRoot(), opened);

        TreeItem<NavNode> root = new TreeItem<>(new NavNode(NavKind.ROOT, null));
        root.setExpanded(true);
        for (ServerProfile profile : repository.list()) {
            TreeItem<NavNode> server = new TreeItem<>(new NavNode(NavKind.SERVER, profile));
            if (connections.of(profile.getId()).isConnected()) {
                TreeItem<NavNode> vhosts = new TreeItem<>(new NavNode(NavKind.VHOSTS, profile));
                vhosts.getChildren().addAll(siteItems(profile));
                server.getChildren().addAll(List.of(
                        vhosts,
                        new TreeItem<>(new NavNode(NavKind.UPSTREAMS, profile)),
                        new TreeItem<>(new NavNode(NavKind.CACHE, profile)),
                        new TreeItem<>(new NavNode(NavKind.LIMITS, profile)),
                        new TreeItem<>(new NavNode(NavKind.LOG_FORMATS, profile)),
                        new TreeItem<>(new NavNode(NavKind.PENDING, profile)),
                        new TreeItem<>(new NavNode(NavKind.HISTORY, profile)),
                        new TreeItem<>(new NavNode(NavKind.CERTIFICATES, profile)),
                        cloudflareItem(profile),
                        new TreeItem<>(new NavNode(NavKind.LOGS, profile)),
                        new TreeItem<>(new NavNode(NavKind.FILES, profile)),
                        new TreeItem<>(new NavNode(NavKind.GLOBAL, profile))));
                server.getChildren().addAll(featureItems(profile));
                // Not forced open: a server opens when it is the one selected or connected (see onSelectionChanged),
                // and otherwise keeps the state the user left it in, so servers never all expand together.
            }
            root.getChildren().add(server);
        }
        restoreExpanded(root, opened);
        tree.setRoot(root);

        TreeItem<NavNode> restore = selectedFeature == null ? null
                : findFeatureItem(root, selectedServer, selectedFeature);
        if (restore == null) {
            restore = findItem(root, selectedServer, selectedKind);
        }
        tree.getSelectionModel().select(restore != null ? restore : root);
        selector.refresh();
    }

    /** One tree entry for each add-on that is switched on (View > Features), after the built-in sections. */
    private List<TreeItem<NavNode>> featureItems(ServerProfile profile) {
        List<TreeItem<NavNode>> items = new java.util.ArrayList<>();
        for (Feature feature : Features.enabled()) {
            items.add(new TreeItem<>(new NavNode(NavKind.FEATURE, profile, feature)));
        }
        return items;
    }

    private static TreeItem<NavNode> findFeatureItem(TreeItem<NavNode> root, UUID serverId, Object feature) {
        for (TreeItem<NavNode> server : root.getChildren()) {
            if (server.getValue().profile() != null && server.getValue().profile().getId().equals(serverId)) {
                for (TreeItem<NavNode> child : server.getChildren()) {
                    if (child.getValue().kind() == NavKind.FEATURE && feature.equals(child.getValue().item())) {
                        return child;
                    }
                }
            }
        }
        return null;
    }

    /** Opens an add-on from its tile on the overview by selecting it in the tree. */
    private void selectFeature(UUID serverId, Feature feature) {
        TreeItem<NavNode> item = findFeatureItem(tree.getRoot(), serverId, feature);
        if (item != null) {
            tree.getSelectionModel().select(item);
        }
    }

    private static String expansionKey(NavNode node) {
        return node.kind() + ":" + (node.profile() == null ? "" : node.profile().getId());
    }

    /** Remembers which entries are open (by kind and server; the entries below a group are leaves). */
    private static void collectExpanded(TreeItem<NavNode> item, java.util.Set<String> into) {
        if (item == null) {
            return;
        }
        if (item.isExpanded() && !item.isLeaf()) {
            into.add(expansionKey(item.getValue()));
        }
        item.getChildren().forEach(child -> collectExpanded(child, into));
    }

    private static void restoreExpanded(TreeItem<NavNode> item, java.util.Set<String> opened) {
        if (!item.isLeaf() && opened.contains(expansionKey(item.getValue()))) {
            item.setExpanded(true);
        }
        item.getChildren().forEach(child -> restoreExpanded(child, opened));
    }

    /** Cloudflare with its DNS Zones and Tunnels, listing the zones and tunnels once a session is open. */
    private TreeItem<NavNode> cloudflareItem(ServerProfile profile) {
        TreeItem<NavNode> cloudflare = new TreeItem<>(new NavNode(NavKind.CLOUDFLARE, profile));
        // Without an API token Cloudflare isn't enabled: no dropdown, and its page explains how to turn it on.
        if (profile.getCloudflareToken() == null || profile.getCloudflareToken().isBlank()) {
            return cloudflare;
        }
        TreeItem<NavNode> zones = new TreeItem<>(new NavNode(NavKind.CF_ZONES, profile));
        TreeItem<NavNode> tunnels = new TreeItem<>(new NavNode(NavKind.CF_TUNNELS, profile));
        zones.getChildren().addAll(cloudflareChildren(profile, NavKind.CF_ZONE));
        tunnels.getChildren().addAll(cloudflareChildren(profile, NavKind.CF_TUNNEL));
        cloudflare.getChildren().addAll(List.of(zones, tunnels));
        return cloudflare;
    }

    /** One entry per zone ({@code CF_ZONE}) or tunnel ({@code CF_TUNNEL}) the open session can see. */
    private List<TreeItem<NavNode>> cloudflareChildren(ServerProfile profile, NavKind kind) {
        List<TreeItem<NavNode>> items = new java.util.ArrayList<>();
        mt.su.nrm.cloudflare.CloudflareSession session = connections.of(profile.getId()).cloudflare();
        if (session != null) {
            List<?> found = kind == NavKind.CF_ZONE ? session.zones() : session.tunnels();
            for (Object item : found) {
                items.add(new TreeItem<>(new NavNode(kind, profile, item)));
            }
        }
        return items;
    }

    /**
     * Brings the zones and tunnels listed in the tree up to date after a Cloudflare session opens or is replaced,
     * keeping the selection on the same zone or tunnel when it is still there.
     */
    private void updateCloudflareItems() {
        NavNode current = currentNode();
        if (watched == null || current == null || current.profile() == null || tree.getRoot() == null) {
            return;
        }
        ServerProfile profile = current.profile();
        Object selectedItem = current.kind() == NavKind.CF_ZONE || current.kind() == NavKind.CF_TUNNEL
                ? current.item() : null;
        TreeItem<NavNode> zones = findItem(tree.getRoot(), profile.getId(), NavKind.CF_ZONES);
        TreeItem<NavNode> tunnels = findItem(tree.getRoot(), profile.getId(), NavKind.CF_TUNNELS);
        if (zones == null || zones.getValue().kind() != NavKind.CF_ZONES
                || tunnels == null || tunnels.getValue().kind() != NavKind.CF_TUNNELS) {
            return;
        }
        zones.getChildren().setAll(cloudflareChildren(profile, NavKind.CF_ZONE));
        tunnels.getChildren().setAll(cloudflareChildren(profile, NavKind.CF_TUNNEL));
        if (selectedItem != null) {
            TreeItem<NavNode> parent = current.kind() == NavKind.CF_ZONE ? zones : tunnels;
            parent.getChildren().stream().filter(i -> selectedItem.equals(i.getValue().item())).findFirst()
                    .ifPresentOrElse(i -> tree.getSelectionModel().select(i), () -> tree.getSelectionModel().select(parent));
        }
    }

    /** Opens a zone or tunnel from the DNS Zones or Tunnels page by selecting it in the tree. */
    private void selectCloudflareItem(UUID serverId, NavKind parentKind, Object item) {
        TreeItem<NavNode> parent = findItem(tree.getRoot(), serverId, parentKind);
        if (parent == null || parent.getValue().kind() != parentKind) {
            return;
        }
        parent.getChildren().stream().filter(i -> item.equals(i.getValue().item())).findFirst()
                .ifPresent(i -> tree.getSelectionModel().select(i));
    }

    private List<TreeItem<NavNode>> siteItems(ServerProfile profile) {
        List<TreeItem<NavNode>> items = new java.util.ArrayList<>();
        RemoteConfig config = connections.of(profile.getId()).config();
        if (config != null) {
            for (VirtualHost host : config.virtualHosts()) {
                items.add(new TreeItem<>(new NavNode(NavKind.SITE, profile, host)));
            }
        }
        return items;
    }

    /**
     * Brings the virtual hosts listed under Virtual Hosts up to date (after the configuration loads, or a
     * site is added, deleted or renamed) without moving the selection unless the selected site is gone.
     */
    private void updateSiteItems() {
        NavNode current = currentNode();
        if (watched == null || current == null || current.profile() == null || tree.getRoot() == null) {
            return;
        }
        TreeItem<NavNode> vhosts = findItem(tree.getRoot(), current.profile().getId(), NavKind.VHOSTS);
        if (vhosts == null || vhosts.getValue().kind() != NavKind.VHOSTS) {
            return;
        }
        List<TreeItem<NavNode>> fresh = siteItems(current.profile());
        boolean same = fresh.size() == vhosts.getChildren().size();
        for (int i = 0; same && i < fresh.size(); i++) {
            NavNode a = fresh.get(i).getValue();
            NavNode b = vhosts.getChildren().get(i).getValue();
            same = a.host().equals(b.host()) && a.toString().equals(b.toString());
        }
        if (same) {
            return;
        }
        VirtualHost selectedHost = current.kind() == NavKind.SITE ? current.host() : null;
        vhosts.getChildren().setAll(fresh);
        if (selectedHost != null) {
            fresh.stream().filter(i -> i.getValue().host().equals(selectedHost)).findFirst()
                    .ifPresentOrElse(i -> tree.getSelectionModel().select(i), () -> tree.getSelectionModel().select(vhosts));
        }
    }

    /** Selects a section (such as Logs) under a server in the tree, if it is there. */
    private void selectSection(UUID serverId, NavKind kind) {
        TreeItem<NavNode> item = findItem(tree.getRoot(), serverId, kind);
        if (item != null && item.getValue().kind() == kind) {
            tree.getSelectionModel().select(item);
        }
    }

    private static TreeItem<NavNode> findItem(TreeItem<NavNode> item, UUID serverId, NavKind kind) {
        if (kind == NavKind.ROOT || serverId == null) {
            return item;
        }
        for (TreeItem<NavNode> server : item.getChildren()) {
            if (server.getValue().profile().getId().equals(serverId)) {
                if (kind == NavKind.SERVER) {
                    return server;
                }
                for (TreeItem<NavNode> child : server.getChildren()) {
                    TreeItem<NavNode> found = findKind(child, kind);
                    if (found != null) {
                        return found;
                    }
                }
                return server;
            }
        }
        return null;
    }

    /** The first entry of the given kind in this entry's subtree (the entry itself included), or null. */
    private static TreeItem<NavNode> findKind(TreeItem<NavNode> item, NavKind kind) {
        if (item.getValue().kind() == kind) {
            return item;
        }
        for (TreeItem<NavNode> child : item.getChildren()) {
            TreeItem<NavNode> found = findKind(child, kind);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private NavNode currentNode() {
        TreeItem<NavNode> item = tree.getSelectionModel().getSelectedItem();
        return item == null ? null : item.getValue();
    }

    private UUID currentServerId() {
        NavNode node = currentNode();
        return node == null || node.profile() == null ? null : node.profile().getId();
    }

    // ---------------------------------------------------------------- selection

    private void onSelectionChanged(TreeItem<NavNode> item) {
        // Whatever is selected must be visible: open the groups above it (a zone opened from its tile, say).
        if (item != null) {
            for (TreeItem<NavNode> parent = item.getParent(); parent != null; parent = parent.getParent()) {
                parent.setExpanded(true);
            }
            // Choosing a connected server opens that server, and only that one.
            if (item.getValue() != null && item.getValue().kind() == NavKind.SERVER && !item.isLeaf()) {
                item.setExpanded(true);
            }
        }
        if (watched != null) {
            watched.stateProperty().removeListener(stateListener);
            watched.pendingCountProperty().removeListener(pendingListener);
            watched.configStateProperty().removeListener(configListener);
            watched.cloudflarePendingProperty().removeListener(cloudflareListener);
            watched.cloudflareProperty().removeListener(cloudflareSessionListener);
            watched = null;
        }
        overviewPanel = null;
        pendingPanel = null;
        objectsPanel = null;
        certificatesPanel = null;
        cloudflarePage = null;
        featurePage = null;
        sftpPanel = null;
        if (item == null) {
            return;
        }
        NavNode node = item.getValue();
        if (node.profile() != null) {
            watched = connections.of(node.profile().getId());
            ServerProfile owner = node.profile();
            watched.onOpenFileTransfer(path -> openFileTransferWindow(owner, path));
            watched.stateProperty().addListener(stateListener);
            watched.pendingCountProperty().addListener(pendingListener);
            watched.configStateProperty().addListener(configListener);
            watched.cloudflarePendingProperty().addListener(cloudflareListener);
            watched.cloudflareProperty().addListener(cloudflareSessionListener);
            logHolder.setCenter(new CommandLogPanel(watched.log()));
        } else {
            logHolder.setCenter(new Label("Select a server to see its command log."));
        }
        showProperties(node);
        showActions(node);
        updateStatus();
    }

    /** One File Explorer window per server, created on first use and shown at {@code folder}. */
    private final java.util.Map<UUID, FileTransferWindow> transferWindows = new java.util.HashMap<>();

    private void openFileTransferWindow(ServerProfile profile, String folder) {
        transferWindows.computeIfAbsent(profile.getId(), id -> new FileTransferWindow(
                getScene() == null ? null : getScene().getWindow(), profile, connections.of(id), folder))
                .show(folder);
    }

    private void onConnectionStateChanged() {
        rebuildTree();
        updateStatus();
    }

    private void updateStatus() {
        NavNode node = currentNode();
        boolean hasServer = node != null && node.profile() != null;
        ServerConnection.State state = hasServer
                ? connections.of(node.profile().getId()).state() : ServerConnection.State.DISCONNECTED;
        connectButton.setDisable(!hasServer || state != ServerConnection.State.DISCONNECTED);
        disconnectButton.setDisable(!hasServer || state != ServerConnection.State.CONNECTED);
        if (!hasServer) {
            connectionStatus.setText("No server selected");
        } else {
            switch (state) {
                case CONNECTED:
                    connectionStatus.setText("Connected to " + node.profile().displayAddress());
                    break;
                case CONNECTING:
                    connectionStatus.setText("Connecting to " + node.profile().displayAddress() + "...");
                    break;
                default:
                    connectionStatus.setText("Not connected");
            }
        }
    }

    // ---------------------------------------------------------------- properties

    private void showProperties(NavNode node) {
        properties.getTabs().clear();
        switch (node.kind()) {
            case ROOT:
                properties.getTabs().add(tab("Servers", selector));
                break;
            case SERVER:
            case VHOSTS:
            case SITE:
                overviewPanel = new ServerOverviewPanel(node.profile(), connections.of(node.profile().getId()),
                        connections, overview(node.profile()));
                overviewPanel.onOpenSection(kind -> selectSection(node.profile().getId(), kind));
                overviewPanel.onShowCloudflareItem((kind, item) -> selectCloudflareItem(node.profile().getId(), kind, item));
                overviewPanel.onOpenFeature(feature -> selectFeature(node.profile().getId(), feature));
                overviewPanel.onSiteClosed(() -> {
                    if (currentNode() != null && currentNode().kind() == NavKind.SITE) {
                        selectSection(node.profile().getId(), NavKind.VHOSTS);
                    }
                    updateSiteItems();
                });
                if (node.kind() == NavKind.SITE) {
                    overviewPanel.openSite(node.host());
                }
                overviewPanel.onViewLogs(site -> {
                    selectSection(node.profile().getId(), NavKind.LOGS);
                    if (logsPanel != null) {
                        logsPanel.select(site);
                    }
                });
                properties.getTabs().add(tab(node.kind() == NavKind.SITE ? SiteSummary.of(node.host().read()).name()
                        : "Overview", overviewPanel));
                break;
            case PENDING:
                pendingPanel = new PendingChangesPanel(node.profile(), connections.of(node.profile().getId()), connections);
                properties.getTabs().add(tab("Pending Changes", pendingPanel));
                break;
            case HISTORY:
                historyPanel = new HistoryPanel(node.profile(), connections.of(node.profile().getId()), connections);
                properties.getTabs().add(tab("Change History", historyPanel));
                break;
            case CERTIFICATES:
                properties.getTabs().add(tab("SSL Certificates",
                        certificatesPanel = new SslPanel(node.profile(), connections.of(node.profile().getId()))));
                break;
            case CLOUDFLARE: {
                ServerConnection cf = connections.of(node.profile().getId());
                CloudflareHomePage home = new CloudflareHomePage(node.profile(), cf);
                home.onOpenSection(kind -> selectSection(node.profile().getId(), kind));
                cloudflarePage = home;
                properties.getTabs().add(tab("Cloudflare", home));
                break;
            }
            case CF_ZONES:
            case CF_TUNNELS: {
                boolean zonesList = node.kind() == NavKind.CF_ZONES;
                CloudflareListPage list = new CloudflareListPage(node.profile(),
                        connections.of(node.profile().getId()),
                        zonesList ? CloudflareListPage.Kind.ZONES : CloudflareListPage.Kind.TUNNELS);
                list.onOpen(item -> selectCloudflareItem(node.profile().getId(), node.kind(), item));
                cloudflarePage = list;
                properties.getTabs().add(tab(zonesList ? "DNS Zones" : "Tunnels", list));
                break;
            }
            case CF_ZONE:
                cloudflarePage = new CloudflareZonePage(node.profile(), connections.of(node.profile().getId()),
                        (mt.su.nrm.cloudflare.Zone) node.item());
                properties.getTabs().add(tab(node.toString(), cloudflarePage));
                break;
            case CF_TUNNEL:
                cloudflarePage = new CloudflareTunnelPage(node.profile(), connections.of(node.profile().getId()),
                        (mt.su.nrm.cloudflare.Tunnel) node.item());
                properties.getTabs().add(tab(node.toString(), cloudflarePage));
                break;
            case FEATURE: {
                Feature feature = (Feature) node.item();
                Node page = feature.page(node.profile(), connections.of(node.profile().getId()));
                featurePage = page instanceof FeaturePage fp ? fp : null;
                properties.getTabs().add(tab(feature.title(), page));
                break;
            }
            case LOGS:
                logsPanel = new LogsPanel(node.profile(), connections.of(node.profile().getId()));
                properties.getTabs().add(tab("Logs", logsPanel));
                break;
            case FILES:
                sftpPanel = new SftpPanel(node.profile(), connections.of(node.profile().getId()));
                properties.getTabs().add(tab("File Explorer", sftpPanel));
                break;
            case UPSTREAMS:
                objectsPanel = new UpstreamsPanel(node.profile(), connections.of(node.profile().getId()), connections);
                properties.getTabs().add(tab("Load Balancing", objectsPanel));
                break;
            case CACHE:
                objectsPanel = new CacheZonesPanel(node.profile(), connections.of(node.profile().getId()), connections);
                properties.getTabs().add(tab("Cache Zones", objectsPanel));
                break;
            case LIMITS:
                objectsPanel = new LimitZonesPanel(node.profile(), connections.of(node.profile().getId()), connections);
                properties.getTabs().add(tab("Rate Limits", objectsPanel));
                break;
            case LOG_FORMATS:
                objectsPanel = new LogFormatsPanel(node.profile(), connections.of(node.profile().getId()), connections);
                properties.getTabs().add(tab("Log Formats", objectsPanel));
                break;
            default:
                properties.getTabs().add(tab("Global Settings",
                        new GlobalSettingsPanel(node.profile(), connections.of(node.profile().getId()), connections)));
        }
    }

    private static Tab tab(String title, Node content) {
        Tab t = new Tab(title, content);
        t.setClosable(false);
        return t;
    }

    private static Node placeholder(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        VBox box = new VBox(label);
        box.setPadding(new Insets(16));
        return box;
    }

    private Node overview(ServerProfile p) {
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(8);
        grid.setPadding(new Insets(16));
        int row = 0;
        row = addRow(grid, row, "Name", p.getName());
        row = addRow(grid, row, "Address", p.displayAddress());
        row = addRow(grid, row, "Authentication", p.getAuthMethod().displayName());
        row = addRow(grid, row, "Root access", p.getPrivilegeMode().displayName());
        row = addRow(grid, row, "Config layout", p.getDetectedLayout().displayName());
        row = addRow(grid, row, "openssl", describe(p.getOpensslStatus()));
        row = addRow(grid, row, "certbot", describe(p.getCertbotStatus()));
        row = addRow(grid, row, "Host key", p.getHostKeyFingerprint() == null
                ? "Not pinned yet (pinned on first connection)" : p.getHostKeyFingerprint());
        if (p.getNotes() != null && !p.getNotes().isBlank()) {
            addRow(grid, row, "Notes", p.getNotes());
        }
        return grid;
    }

    private static int addRow(GridPane grid, int row, String label, String value) {
        Label name = new Label(label);
        name.setStyle("-fx-font-weight: bold;");
        Label text = new Label(value);
        text.setWrapText(true);
        text.setMaxWidth(520);
        grid.addRow(row, name, text);
        return row + 1;
    }

    private static String describe(ToolStatus s) {
        if (s == null) {
            return "Not checked yet";
        }
        return s.available()
                ? (s.version() == null ? "Found" : s.version()) + " (" + s.path() + ")"
                : "Not found";
    }

    // ---------------------------------------------------------------- actions

    private void showActions(NavNode node) {
        actions.getChildren().clear();
        switch (node.kind()) {
            case ROOT:
                actions.getChildren().add(new Label("Use the buttons under the list to add, edit or connect."));
                ((Label) actions.getChildren().get(0)).setWrapText(true);
                break;
            case SERVER:
            case VHOSTS:
            case SITE:
                boolean connected = connections.of(node.profile().getId()).isConnected();
                actions.getChildren().addAll(
                        action("Connect", this::connectSelected, connected),
                        action("Disconnect", this::disconnectSelected, !connected),
                        action("Manage servers", () -> tree.getSelectionModel().select(tree.getRoot()), false));
                if (connected) {
                    actions.getChildren().addAll(new javafx.scene.control.Separator(),
                            action("Add virtual host", () -> { if (overviewPanel != null) overviewPanel.addHost(); }, false),
                            action("Delete virtual host", () -> { if (overviewPanel != null) overviewPanel.deleteSelected(); }, false),
                            action("Add to Cloudflare", () -> { if (overviewPanel != null) overviewPanel.addToCloudflare(); }, false),
                            action("View logs", () -> {
                                String site = overviewPanel == null ? null : overviewPanel.selectedSiteName();
                                ServerProfile owner = currentNode().profile();
                                selectSection(owner.getId(), NavKind.LOGS);
                                if (logsPanel != null && site != null) {
                                    logsPanel.select(site);
                                }
                            }, false),
                            action("Refresh from server", () -> { if (overviewPanel != null) overviewPanel.refreshFromServer(); }, false));
                }
                break;
            case UPSTREAMS:
            case CACHE:
            case LIMITS:
            case LOG_FORMATS:
                actions.getChildren().addAll(
                        action("Add", () -> { if (objectsPanel != null) objectsPanel.addItem(); }, false),
                        action("Edit", () -> { if (objectsPanel != null) objectsPanel.editSelected(); }, false),
                        action("Delete", () -> { if (objectsPanel != null) objectsPanel.deleteSelected(); }, false));
                break;
            case CERTIFICATES:
                actions.getChildren().add(
                        action("Refresh certificates", () -> { if (certificatesPanel != null) certificatesPanel.reload(); }, false));
                break;
            case CLOUDFLARE:
            case CF_ZONES:
            case CF_TUNNELS:
            case CF_ZONE:
            case CF_TUNNEL:
                showCloudflareActions(node);
                break;
            case FEATURE:
                actions.getChildren().add(
                        action("Reload", () -> { if (featurePage != null) featurePage.reload(); }, false));
                break;
            case LOGS:
                actions.getChildren().add(
                        action("Refresh logs", () -> { if (logsPanel != null) logsPanel.reload(); }, false));
                break;
            case HISTORY:
                actions.getChildren().add(
                        action("Refresh history", () -> { if (historyPanel != null) historyPanel.reload(); }, false));
                break;
            case FILES:
                actions.getChildren().add(
                        action("Refresh both sides", () -> { if (sftpPanel != null) sftpPanel.reload(); }, false));
                break;
            case PENDING:
                Button applyAction = action("Validate and apply", () -> { if (pendingPanel != null) pendingPanel.apply(); }, false);
                Button discardAction = action("Discard all", () -> { if (pendingPanel != null) pendingPanel.discardAll(); }, false);
                // Same rule as the buttons under the list: nothing to apply means nothing to click.
                if (pendingPanel != null) {
                    applyAction.disableProperty().bind(pendingPanel.nothingToApply());
                    discardAction.disableProperty().bind(pendingPanel.nothingToApply());
                }
                actions.getChildren().addAll(applyAction, discardAction);
                break;
            default:
                actions.getChildren().add(action("Refresh", this::rebuildTree, false));
        }
    }

    /** The Cloudflare pages share Review and apply / Discard all; a zone or tunnel adds its own Add, Edit and Delete. */
    private void showCloudflareActions(NavNode node) {
        if (node.kind() == NavKind.CF_ZONE) {
            actions.getChildren().addAll(
                    action("Add DNS record", () -> { if (cloudflarePage != null) cloudflarePage.add(); }, false),
                    action("Edit", () -> { if (cloudflarePage != null) cloudflarePage.editSelected(); }, false),
                    action("Delete", () -> { if (cloudflarePage != null) cloudflarePage.deleteSelected(); }, false),
                    new javafx.scene.control.Separator());
        } else if (node.kind() == NavKind.CF_ZONES || node.kind() == NavKind.CF_TUNNELS) {
            actions.getChildren().addAll(
                    action(node.kind() == NavKind.CF_ZONES ? "New DNS zone" : "New tunnel",
                            () -> { if (cloudflarePage != null) cloudflarePage.add(); }, false),
                    new javafx.scene.control.Separator());
        } else if (node.kind() == NavKind.CF_TUNNEL) {
            actions.getChildren().addAll(
                    action("Publish application", () -> { if (cloudflarePage != null) cloudflarePage.add(); }, false),
                    action("Edit", () -> { if (cloudflarePage != null) cloudflarePage.editSelected(); }, false),
                    action("Remove", () -> { if (cloudflarePage != null) cloudflarePage.deleteSelected(); }, false),
                    new javafx.scene.control.Separator());
        }
        Button review = action("Review and apply", () -> { if (cloudflarePage != null) cloudflarePage.reviewAndApply(); }, false);
        Button discard = action("Discard all", () -> { if (cloudflarePage != null) cloudflarePage.discardAll(); }, false);
        if (cloudflarePage != null) {
            // Same rule as the buttons on the page: nothing staged, or a call running, means nothing to click.
            review.disableProperty().bind(cloudflarePage.nothingToApply());
            discard.disableProperty().bind(cloudflarePage.nothingToApply());
        } else {
            review.setDisable(true);
            discard.setDisable(true);
        }
        actions.getChildren().addAll(review, discard,
                action("Reload from Cloudflare", () -> { if (cloudflarePage != null) cloudflarePage.reload(); }, false));
        // Deleting the zone or tunnel itself is kept apart from the buttons that change what is inside it.
        if (node.kind() == NavKind.CF_ZONE || node.kind() == NavKind.CF_TUNNEL) {
            actions.getChildren().addAll(new javafx.scene.control.Separator(),
                    action(node.kind() == NavKind.CF_ZONE ? "Delete this zone" : "Delete this tunnel",
                            () -> { if (cloudflarePage != null) cloudflarePage.removeThis(); }, false));
        }
    }

    /** The right-click menu of a Cloudflare entry in the tree, or null for any other entry. */
    private ContextMenu cloudflareMenu(NavNode node, TreeItem<NavNode> item) {
        if (node == null || node.profile() == null) {
            return null;
        }
        ServerConnection connection = connections.of(node.profile().getId());
        Runnable open = () -> tree.getSelectionModel().select(item);
        // "New ..." acts on the page for that entry, which exists once the entry is selected.
        Runnable add = () -> {
            tree.getSelectionModel().select(item);
            if (cloudflarePage != null) {
                cloudflarePage.add();
            }
        };
        Runnable reload = () -> {
            tree.getSelectionModel().select(item);
            if (cloudflarePage != null) {
                cloudflarePage.reload();
            }
        };
        Window owner = getScene() == null ? null : getScene().getWindow();
        switch (node.kind()) {
            case CLOUDFLARE:
                return new ContextMenu(menuItem("Open", open), menuItem("Reload from Cloudflare", reload));
            case CF_ZONES:
                return new ContextMenu(menuItem("Open", open), menuItem("New DNS zone", add),
                        new SeparatorMenuItem(), menuItem("Reload from Cloudflare", reload));
            case CF_TUNNELS:
                return new ContextMenu(menuItem("Open", open), menuItem("New tunnel", add),
                        new SeparatorMenuItem(), menuItem("Reload from Cloudflare", reload));
            case CF_ZONE:
                return new ContextMenu(menuItem("Open", open), new SeparatorMenuItem(),
                        menuItem("Delete this zone", () -> CloudflareRemoval.zone(owner, connection,
                                (mt.su.nrm.cloudflare.Zone) node.item(), () -> { })));
            case CF_TUNNEL:
                return new ContextMenu(menuItem("Open", open), new SeparatorMenuItem(),
                        menuItem("Delete this tunnel", () -> CloudflareRemoval.tunnel(owner, connection,
                                (mt.su.nrm.cloudflare.Tunnel) node.item(), () -> { })));
            default:
                return null;
        }
    }

    private static MenuItem menuItem(String text, Runnable run) {
        MenuItem item = new MenuItem(text);
        item.setOnAction(e -> run.run());
        return item;
    }

    private static Button action(String text, Runnable run, boolean disabled) {
        Button b = new Button(text);
        b.setMaxWidth(Double.MAX_VALUE);
        b.setDisable(disabled);
        b.setOnAction(e -> run.run());
        return b;
    }

    // ---------------------------------------------------------------- connecting

    private void connectSelected() {
        NavNode node = currentNode();
        if (node != null && node.profile() != null) {
            connect(node.profile());
        }
    }

    private void connect(ServerProfile profile) {
        status.setText("Connecting to " + profile.getName() + "...");
        connections.connect(getScene().getWindow(), profile, ok -> {
            status.setText(ok ? "Connected to " + profile.getName() : "Ready");
            rebuildTree();
            if (ok) {
                Optional<TreeItem<NavNode>> server = tree.getRoot().getChildren().stream()
                        .filter(i -> i.getValue().profile().getId().equals(profile.getId())).findFirst();
                server.ifPresent(i -> tree.getSelectionModel().select(i));
            }
        });
        // Show the connecting state straight away.
        updateStatus();
    }

    private void disconnectSelected() {
        NavNode node = currentNode();
        if (node == null || node.profile() == null) {
            return;
        }
        ServerProfile profile = node.profile();
        if (connections.of(profile.getId()).pendingCountProperty().get() > 0 && !Dialogs.confirm(getScene().getWindow(),
                "Discard pending changes?", "Disconnecting throws away the changes to " + profile.getName()
                        + " that you haven't applied yet.", "Disconnect and discard")) {
            return;
        }
        connections.disconnect(profile.getId(), () -> {
            status.setText("Disconnected from " + profile.getName());
            rebuildTree();
        });
    }

    // ---------------------------------------------------------------- tree cell

    private final class NavCell extends TreeCell<NavNode> {
        NavCell() {
            // Right-click on a Cloudflare entry: open it, add to it, or delete it. Other entries have no menu.
            setOnContextMenuRequested(e -> {
                ContextMenu menu = cloudflareMenu(getItem(), getTreeItem());
                if (menu != null) {
                    menu.show(this, e.getScreenX(), e.getScreenY());
                    e.consume();
                }
            });
            // Double-clicking a virtual host (or the Virtual Hosts entry) opens its page in the window, even when
            // it is already selected; it doesn't collapse the list.
            addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
                NavNode n = getItem();
                if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY && e.getClickCount() == 2 && n != null
                        && (n.kind() == NavKind.VHOSTS || n.kind() == NavKind.SITE)) {
                    e.consume();
                    tree.getSelectionModel().select(getTreeItem());
                    onSelectionChanged(getTreeItem());
                }
            });
        }

        @Override
        protected void updateItem(NavNode node, boolean empty) {
            super.updateItem(node, empty);
            if (empty || node == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            setText(node.toString());
            if (node.kind() == NavKind.PENDING) {
                int pending = connections.of(node.profile().getId()).pendingCountProperty().get();
                setText(pending == 0 ? "Pending Changes" : "Pending Changes (" + pending + ")");
                setStyle(pending == 0 ? "" : "-fx-font-weight: bold;");
                setGraphic(null);
                return;
            }
            if (node.kind() == NavKind.CLOUDFLARE) {
                int staged = connections.of(node.profile().getId()).cloudflarePendingProperty().get();
                setText(staged == 0 ? "Cloudflare" : "Cloudflare (" + staged + ")");
                setStyle(staged == 0 ? "" : "-fx-font-weight: bold;");
                setGraphic(null);
                return;
            }
            setStyle("");
            if (node.kind() == NavKind.SERVER) {
                ServerConnection.State state = connections.of(node.profile().getId()).state();
                Circle dot = new Circle(5);
                dot.setFill(state == ServerConnection.State.CONNECTED ? Color.web("#2e9e4f")
                        : state == ServerConnection.State.CONNECTING ? Color.web("#e0a800") : Color.web("#9aa0a6"));
                setGraphic(dot);
            } else {
                setGraphic(null);
            }
        }
    }

}
