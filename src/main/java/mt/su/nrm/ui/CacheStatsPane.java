package mt.su.nrm.ui;

import mt.su.nrm.model.ServerProfile;
import mt.su.nrm.nginx.CacheStats;
import mt.su.nrm.nginx.CacheStatsLogging;
import mt.su.nrm.nginx.RemoteConfig;
import mt.su.nrm.nginx.VhostSettings;
import mt.su.nrm.nginx.LocationSettings;
import mt.su.nrm.nginx.VirtualHost;
import mt.su.nrm.ssh.LogService;
import mt.su.nrm.ssh.SshExecutor;
import mt.su.nrm.ssh.SshSession;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Optional cache hit/miss statistics under the Cache Zones table. Off until the user turns it on: that adds a
 * log format and a separate log file as ordinary pending changes (see {@link CacheStatsLogging}), and the
 * numbers are counted from the end of that log, fetched over the logged SSH connection in the background.
 */
final class CacheStatsPane extends VBox {

    private static final List<Integer> LINE_CHOICES = List.of(1000, 5000, 20000);
    private static final String ALL_SITES = "All sites";

    private final ServerProfile profile;
    private final ServerConnection connection;
    private final RemoteConfig config;
    private final Runnable changed;
    private final Window owner;

    private final TableView<CacheStats.HostStats> table = new TableView<>();
    private final Label status = new Label();
    private final ComboBox<Integer> lines = new ComboBox<>(FXCollections.observableArrayList(LINE_CHOICES));
    private final Map<String, String> zonesOfHost = new LinkedHashMap<>();
    private boolean busy;

    /**
     * @param changed runs after the configuration was edited (the panel then redraws and the pending changes update)
     */
    CacheStatsPane(ServerProfile profile, ServerConnection connection, RemoteConfig config, Window owner, Runnable changed) {
        super(8);
        this.profile = profile;
        this.connection = connection;
        this.config = config;
        this.owner = owner;
        this.changed = changed;
        setPadding(new Insets(10, 0, 0, 0));

        Label title = new Label("Cache statistics");
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        status.setWrapText(true);
        status.setOpacity(0.8);
        getChildren().addAll(title, status);

        if (!CacheStatsLogging.isEnabled(config)) {
            Button enable = new Button("Turn on cache statistics");
            enable.setOnAction(e -> enable());
            Label what = new Label("Optional. Counts HIT, MISS, EXPIRED, BYPASS and the rest from a separate log file, so "
                    + "you can see how well each site is caching. Nothing is changed on the server until you apply.");
            what.setWrapText(true);
            getChildren().addAll(what, enable);
            status.setManaged(false);
            status.setVisible(false);
            return;
        }

        buildZoneMap();
        buildTable();
        lines.setValue(5000);
        lines.setPrefWidth(90);
        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> refresh());
        refresh.setDisable(!connection.isConnected());
        HBox bar = new HBox(8, refresh, new Label("Count the last"), lines, new Label("logged requests"));
        bar.setAlignment(Pos.CENTER_LEFT);
        int uncovered = CacheStatsLogging.uncovered(config);
        if (uncovered > 0) {
            Button update = new Button("Include " + uncovered + " more block(s)");
            update.setOnAction(e -> enable());
            bar.getChildren().add(update);
        }
        Button off = new Button("Turn off");
        off.setOnAction(e -> disable());
        bar.getChildren().add(off);
        getChildren().addAll(bar, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        status.setText(connection.isConnected()
                ? "Press Refresh to count the log. Requests are only logged once the change has been applied."
                : "Connect to the server to read the statistics.");
        if (connection.isConnected()) {
            refresh();
        }
    }

    // ---------------------------------------------------------------- turning it on and off

    private void enable() {
        String reason = config.mainReadOnlyReason();
        if (reason != null) {
            Dialogs.info(owner, "Can't turn this on here", reason);
            return;
        }
        if (!Dialogs.confirm(owner, "Turn on cache statistics?",
                "This adds, as pending changes:\n"
                        + "  - a log format named " + CacheStatsLogging.FORMAT + " (" + CacheStatsLogging.FORMAT_TEXT + ")\n"
                        + "  - a map so only requests that use a cache are logged\n"
                        + "  - an extra access_log to " + CacheStatsLogging.LOG_PATH + " in the http block and next to "
                        + "every site or location that has its own access_log (your existing logs are not changed)\n\n"
                        + "Nothing changes on the server until you apply. The log grows with cached traffic, and a normal "
                        + "logrotate setup for /var/log/nginx/*.log covers it.", "Turn on")) {
            return;
        }
        CacheStatsLogging.Result r = CacheStatsLogging.enable(config);
        if (!r.ok()) {
            Dialogs.error(owner, "Can't turn on cache statistics", r.problem());
            return;
        }
        List<String> notes = new ArrayList<>();
        if (r.readOnly() > 0) {
            notes.add(r.readOnly() + " block(s) are in read-only files and won't be counted.");
        }
        if (r.loggingOff() > 0) {
            notes.add(r.loggingOff() + " block(s) have access_log off and were left alone.");
        }
        changed.run();
        if (!notes.isEmpty()) {
            Dialogs.info(owner, "Added to the pending changes", String.join("\n", notes)
                    + "\n\nReview and apply them from the Pending Changes screen.");
        }
    }

    private void disable() {
        if (!Dialogs.confirm(owner, "Turn off cache statistics?",
                "The log format, the map and the extra access_log lines are removed as pending changes. The log file "
                        + "already on the server is left where it is.", "Turn off")) {
            return;
        }
        CacheStatsLogging.disable(config);
        changed.run();
    }

    // ---------------------------------------------------------------- reading

    private void refresh() {
        SshSession session = connection.session();
        if (session == null || busy) {
            return;
        }
        busy = true;
        int count = lines.getValue() == null ? 5000 : lines.getValue();
        status.setText("Reading " + CacheStatsLogging.LOG_PATH + "...");
        SshExecutor.submit(() -> LogService.tail(session, CacheStatsLogging.LOG_PATH,
                        List.of(CacheStatsLogging.LOG_PATH), count, ""))
                .whenComplete((text, failure) -> Platform.runLater(() -> {
                    busy = false;
                    if (failure != null) {
                        Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                        table.setItems(FXCollections.observableArrayList());
                        if (cause instanceof java.io.IOException && cause.getMessage() != null
                                && cause.getMessage().startsWith("There is no log")) {
                            status.setText("Nothing has been logged yet. Apply the pending changes, then wait for some "
                                    + "cached requests, and press Refresh.");
                        } else {
                            status.setText(ConnectionManager.describeFailure(profile, connection, failure).replace('\n', ' '));
                        }
                        return;
                    }
                    show(CacheStats.parse(text));
                }));
    }

    private void show(CacheStats stats) {
        List<CacheStats.HostStats> rows = new ArrayList<>(stats.hosts());
        if (rows.size() > 1) {
            rows.add(stats.totals());
        }
        table.setItems(FXCollections.observableArrayList(rows));
        String when = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        if (stats.totals().total() == 0) {
            status.setText("No cached requests in the log yet - updated " + when + ".");
            return;
        }
        status.setText(stats.totals().total() + " cached request(s) from " + stats.firstTime() + " to " + stats.lastTime()
                + (stats.skipped() > 0 ? " (" + stats.skipped() + " unreadable line(s) skipped)" : "")
                + " - updated " + when + ". Hit ratio counts HIT, STALE, UPDATING and REVALIDATED as served from the cache.");
    }

    // ---------------------------------------------------------------- table

    /** Which cache zone(s) each host's locations use, so a host's numbers can be tied to a zone. */
    private void buildZoneMap() {
        for (VirtualHost host : config.virtualHosts()) {
            VhostSettings s = host.read();
            TreeSet<String> zones = new TreeSet<>();
            for (LocationSettings l : s.locations) {
                if (!l.proxyCache.isBlank() && !l.proxyCache.equals("off")) {
                    zones.add(l.proxyCache);
                }
            }
            if (!zones.isEmpty()) {
                for (String name : s.serverNames) {
                    zonesOfHost.putIfAbsent(name.toLowerCase(java.util.Locale.ROOT), String.join(", ", zones));
                }
            }
        }
    }

    private void buildTable() {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("No statistics to show yet."));
        table.setPrefHeight(180);
        column("Site", 170, h -> h.host());
        column("Zone", 110, h -> h.host().equals(ALL_SITES) ? "" : zonesOfHost.getOrDefault(h.host(), ""));
        column("Requests", 80, h -> String.valueOf(h.total()));
        column("HIT", 70, h -> String.valueOf(h.count("HIT")));
        column("MISS", 70, h -> String.valueOf(h.count("MISS")));
        column("EXPIRED", 75, h -> String.valueOf(h.count("EXPIRED")));
        column("STALE", 70, h -> String.valueOf(h.count("STALE")));
        column("BYPASS", 75, h -> String.valueOf(h.count("BYPASS")));
        column("Other", 65, h -> String.valueOf(h.count("UPDATING") + h.count("REVALIDATED") + h.other()));
        column("Hit ratio", 80, h -> String.format("%.1f%%", h.hitRatio() * 100));
    }

    private void column(String title, double width, Function<CacheStats.HostStats, String> value) {
        TableColumn<CacheStats.HostStats, String> c = new TableColumn<>(title);
        c.setPrefWidth(width);
        c.setCellValueFactory(cell -> new javafx.beans.property.SimpleStringProperty(value.apply(cell.getValue())));
        table.getColumns().add(c);
    }
}
