package mt.su.nrm.ui;

import mt.su.nrm.cloudflare.CloudflareSession;
import mt.su.nrm.cloudflare.CloudflareWorkspace;
import mt.su.nrm.cloudflare.IngressRule;
import mt.su.nrm.cloudflare.Tunnel;
import mt.su.nrm.cloudflare.Zone;
import mt.su.nrm.model.ServerProfile;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One tunnel: the applications it publishes. Publishing adds a route and the proxied CNAME that points a hostname at
 * the tunnel, in one staged step; nothing is sent until the staged changes are applied.
 */
final class CloudflareTunnelPage extends CloudflarePage {

    private final Tunnel tunnel;
    private final TableView<IngressRule> table = new TableView<>();
    private CloudflareSession session;
    private Map<String, String> status = Map.of();

    CloudflareTunnelPage(ServerProfile profile, ServerConnection connection, Tunnel tunnel) {
        super(profile, connection);
        this.tunnel = tunnel;

        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("No published applications on this tunnel."));
        column(table, "Hostname", 260, IngressRule::hostname);
        column(table, "Path", 110, IngressRule::path);
        column(table, "Service", 280, IngressRule::service);
        column(table, "Staged", 80, r -> status.getOrDefault(CloudflareWorkspace.routeKey(r), ""));
        table.setRowFactory(tv -> {
            TableRow<IngressRule> row = new TableRow<>() {
                @Override
                protected void updateItem(IngressRule item, boolean empty) {
                    super.updateItem(item, empty);
                    setStyle(!empty && item != null && status.containsKey(CloudflareWorkspace.routeKey(item))
                            ? "-fx-font-weight: bold;" : "");
                }
            };
            // Right-click selects the row under the pointer, then offers what can be done with it.
            row.setOnContextMenuRequested(e -> {
                if (!row.isEmpty()) {
                    table.getSelectionModel().select(row.getItem());
                }
                showMenu(row, e, routeMenu());
            });
            return row;
        });
        table.setOnContextMenuRequested(e -> showMenu(table, e, routeMenu()));
        table.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                editSelected();
            }
        });
    }

    private javafx.scene.control.MenuItem[] routeMenu() {
        boolean none = table.getSelectionModel().getSelectedItem() == null;
        return new javafx.scene.control.MenuItem[] {
                menuItem("Publish application", this::add, false), menuSeparator(),
                menuItem("Edit", this::editSelected, none), menuItem("Remove", this::deleteSelected, none),
                menuSeparator(), menuItem("Reload from Cloudflare", this::reload, false)};
    }

    @Override
    void removeThis() {
        CloudflareRemoval.tunnel(window(), connection, tunnel, () -> { });
    }

    @Override
    void start() {
        setCenter(centered(ProgressDialog.spinner(48), new Label("Reading " + tunnel.name() + " from Cloudflare...")));
        hub.whenReady(profile, opened -> {
            session = opened;
            load(false);
        }, problem -> setCenter(message(problem)));
    }

    private void load(boolean force) {
        CloudflareWorkspace workspace = session.workspace();
        if (!force && workspace.isTunnelLoaded(tunnel)) {
            render();
            return;
        }
        hub.run("Reading " + tunnel.name() + " from Cloudflare...", () -> {
            workspace.loadTunnel(session.accountOf(tunnel), tunnel);
            return null;
        }, done -> render(), problem -> {
            render();
            error("Could not read from Cloudflare", problem);
        });
    }

    @Override
    void showCurrent() {
        if (session != null) {
            load(false);
        }
    }

    @Override
    void reload() {
        if (session == null) {
            start();
            return;
        }
        if (session.workspace().isTunnelLoaded(tunnel) && !session.workspace().routeStatus(tunnel).isEmpty()
                && !Dialogs.confirm(window(), "Reload " + tunnel.name() + "?",
                "Reloading discards the staged changes for this tunnel's routes.", "Discard and reload")) {
            return;
        }
        load(true);
    }

    private void render() {
        CloudflareWorkspace workspace = session.workspace();
        Label notice = null;
        if (workspace.isTunnelLoaded(tunnel)) {
            status = workspace.routeStatus(tunnel);
            table.setItems(FXCollections.observableArrayList(workspace.tunnelConfig(tunnel).routes()));
            if (!workspace.tunnelConfig(tunnel).isRemotelyManaged()) {
                notice = new Label("This tunnel is configured by a file on its server, so its routes can't be changed "
                        + "from here.");
                notice.setWrapText(true);
                notice.setStyle("-fx-text-fill: #8a6d00;");
            }
        } else {
            status = Map.of();
            table.setItems(FXCollections.observableArrayList());
        }

        Button publish = new Button("Publish application");
        publish.setOnAction(e -> add());
        Button edit = new Button("Edit");
        edit.setOnAction(e -> editSelected());
        Button remove = new Button("Remove");
        remove.setOnAction(e -> deleteSelected());
        edit.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull().or(hub.busyProperty()));
        remove.disableProperty().bind(edit.disableProperty());
        publish.disableProperty().bind(hub.busyProperty());
        HBox buttons = new HBox(8, publish, edit, remove);
        buttons.setAlignment(Pos.CENTER_LEFT);

        Label title = heading(tunnel.name() + "  (" + tunnel.status() + ")");
        VBox box = new VBox(10, title);
        if (notice != null) {
            box.getChildren().add(notice);
        }
        box.getChildren().addAll(table, buttons, stagedBar(), busyIndicator());
        box.setPadding(new Insets(12));
        VBox.setVgrow(table, Priority.ALWAYS);
        setCenter(box);
    }

    // ---------------------------------------------------------------- the actions

    /** Publishes an application: a route on this tunnel plus the proxied CNAME for its hostname. */
    @Override
    void add() {
        if (!ready()) {
            return;
        }
        // A zone added since this page opened lives in the connection's newer session.
        CloudflareSession current = connection.cloudflare() != null ? connection.cloudflare() : session;
        CloudflareDialogs.route(window(), current.zones(), null, tunnel.name(), false, "", "", "http://localhost:80")
                .ifPresent(r -> hub.ensureZoneLoaded(session, r.zone(), false,
                        () -> stage(() -> session.workspace().stagePublish(r.zone(), tunnel, r.hostname(), r.path(),
                                r.service())),
                        problem -> error("Could not read " + r.zone().name() + " from Cloudflare", problem)));
    }

    @Override
    void editSelected() {
        IngressRule route = table.getSelectionModel().getSelectedItem();
        if (!ready() || route == null) {
            return;
        }
        CloudflareDialogs.route(window(), java.util.List.of(), null, tunnel.name(), true, route.hostname(),
                route.path(), route.service()).ifPresent(r -> {
                    // Keep whatever else the route carries (origin settings) and change only path and service.
                    Map<String, Object> raw = new LinkedHashMap<>(route.raw());
                    if (r.path().isEmpty()) {
                        raw.remove("path");
                    } else {
                        raw.put("path", r.path());
                    }
                    raw.put("service", r.service());
                    stage(() -> session.workspace().stageRouteReplace(tunnel, route.hostname(), route.path(),
                            new IngressRule(raw)));
                });
    }

    @Override
    void deleteSelected() {
        IngressRule route = table.getSelectionModel().getSelectedItem();
        if (!ready() || route == null) {
            return;
        }
        Zone zone = zoneOf(route.hostname());
        CloudflareDialogs.confirmUnpublish(window(), route.hostname(), zone == null ? null : zone.name())
                .ifPresent(removeDns -> {
                    Runnable remove = () -> stage(() -> session.workspace().stageUnpublish(zone, tunnel,
                            route.hostname(), route.path(), removeDns));
                    if (removeDns && zone != null) {
                        hub.ensureZoneLoaded(session, zone, false, remove,
                                problem -> error("Could not read " + zone.name() + " from Cloudflare", problem));
                    } else {
                        remove.run();
                    }
                });
    }

    /** The zone a hostname belongs to (the longest matching zone name), or null if the token sees none. */
    private Zone zoneOf(String hostname) {
        String host = hostname.toLowerCase(Locale.ROOT);
        Zone best = null;
        for (Zone z : session.zones()) {
            if ((host.equals(z.name()) || host.endsWith("." + z.name()))
                    && (best == null || z.name().length() > best.name().length())) {
                best = z;
            }
        }
        return best;
    }

    private boolean ready() {
        if (session == null || !session.workspace().isTunnelLoaded(tunnel) || hub.busyProperty().get()) {
            return false;
        }
        if (!session.workspace().tunnelConfig(tunnel).isRemotelyManaged()) {
            Dialogs.info(window(), "Routes can't be changed here", "This tunnel is configured by a file on its server, "
                    + "so Cloudflare ignores route changes made through its API.");
            return false;
        }
        return true;
    }

    private void stage(Runnable staging) {
        try {
            staging.run();
        } catch (IllegalArgumentException | IllegalStateException e) {
            error("That can't be staged", e.getMessage());
        }
        hub.changed();
        render();
    }

    /** For tests: how many routes are listed right now. */
    int rowCount() {
        return table.getItems().size();
    }
}
