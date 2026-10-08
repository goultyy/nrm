package mt.su.nrm.ui;

import mt.su.nrm.cloudflare.CloudflareSession;
import mt.su.nrm.cloudflare.CloudflareWorkspace;
import mt.su.nrm.cloudflare.DnsRecord;
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
import javafx.scene.layout.VBox;
import javafx.scene.layout.Priority;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One DNS zone: its records, with Add, Edit and Delete that stage changes (shown in bold) until they are applied. */
final class CloudflareZonePage extends CloudflarePage {

    private final Zone zone;
    private final TableView<DnsRecord> table = new TableView<>();
    private CloudflareSession session;
    private Map<String, String> status = Map.of();

    CloudflareZonePage(ServerProfile profile, ServerConnection connection, Zone zone) {
        super(profile, connection);
        this.zone = zone;

        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("No DNS records in this zone."));
        column(table, "Type", 70, DnsRecord::type);
        column(table, "Name", 230, DnsRecord::name);
        column(table, "Points to", 280, DnsRecord::content);
        column(table, "Proxy", 80, r -> List.of("A", "AAAA", "CNAME").contains(r.type())
                ? (r.proxied() ? "Proxied" : "DNS only") : "");
        column(table, "Staged", 80, r -> status.getOrDefault(r.id(), ""));
        table.setRowFactory(tv -> {
            TableRow<DnsRecord> row = new TableRow<>() {
                @Override
                protected void updateItem(DnsRecord item, boolean empty) {
                    super.updateItem(item, empty);
                    setStyle(!empty && item != null && status.containsKey(item.id()) ? "-fx-font-weight: bold;" : "");
                }
            };
            // Right-click selects the row under the pointer, then offers what can be done with it.
            row.setOnContextMenuRequested(e -> {
                if (!row.isEmpty()) {
                    table.getSelectionModel().select(row.getItem());
                }
                showMenu(row, e, recordMenu());
            });
            return row;
        });
        table.setOnContextMenuRequested(e -> showMenu(table, e, recordMenu()));
        table.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                editSelected();
            }
        });
    }

    private javafx.scene.control.MenuItem[] recordMenu() {
        boolean none = table.getSelectionModel().getSelectedItem() == null;
        return new javafx.scene.control.MenuItem[] {
                menuItem("Add DNS record", this::add, false), menuSeparator(),
                menuItem("Edit", this::editSelected, none), menuItem("Delete", this::deleteSelected, none),
                menuSeparator(), menuItem("Reload from Cloudflare", this::reload, false)};
    }

    @Override
    void removeThis() {
        CloudflareRemoval.zone(window(), connection, zone, () -> { });
    }

    @Override
    void start() {
        setCenter(centered(ProgressDialog.spinner(48), new Label("Reading " + zone.name() + " from Cloudflare...")));
        hub.whenReady(profile, opened -> {
            session = opened;
            load(false);
        }, problem -> setCenter(message(problem)));
    }

    private void load(boolean force) {
        hub.ensureZoneLoaded(session, zone, force, this::render, problem -> {
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
        if (session.workspace().isDnsLoaded(zone) && !session.workspace().dnsChanges(zone).isEmpty()
                && !Dialogs.confirm(window(), "Reload " + zone.name() + "?",
                "Reloading discards the staged changes for this zone.", "Discard and reload")) {
            return;
        }
        load(true);
    }

    private void render() {
        CloudflareWorkspace workspace = session.workspace();
        if (workspace.isDnsLoaded(zone)) {
            Map<String, String> staged = new LinkedHashMap<>();
            for (CloudflareWorkspace.DnsChange c : workspace.dnsChanges(zone)) {
                if (c.kind() == CloudflareWorkspace.Kind.CREATE) {
                    staged.put(c.after().id(), "New");
                } else if (c.kind() == CloudflareWorkspace.Kind.UPDATE) {
                    staged.put(c.after().id(), "Changed");
                }
            }
            status = staged;
            table.setItems(FXCollections.observableArrayList(workspace.dnsRecords(zone)));
        } else {
            status = Map.of();
            table.setItems(FXCollections.observableArrayList());
        }

        Button add = new Button("Add DNS record");
        add.setOnAction(e -> add());
        Button edit = new Button("Edit");
        edit.setOnAction(e -> editSelected());
        Button delete = new Button("Delete");
        delete.setOnAction(e -> deleteSelected());
        edit.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull().or(hub.busyProperty()));
        delete.disableProperty().bind(edit.disableProperty());
        add.disableProperty().bind(hub.busyProperty());
        HBox buttons = new HBox(8, add, edit, delete);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(10, heading(zone.name()), table, buttons, stagedBar(), busyIndicator());
        box.setPadding(new Insets(12));
        VBox.setVgrow(table, Priority.ALWAYS);
        setCenter(box);
    }

    // ---------------------------------------------------------------- the actions

    @Override
    void add() {
        if (!ready()) {
            return;
        }
        CloudflareDialogs.dnsRecord(window(), zone, session.workspace(), null).ifPresent(record ->
                stage(() -> session.workspace().stageDnsCreate(zone, record)));
    }

    @Override
    void editSelected() {
        DnsRecord record = table.getSelectionModel().getSelectedItem();
        if (!ready() || record == null) {
            return;
        }
        CloudflareDialogs.dnsRecord(window(), zone, session.workspace(), record).ifPresent(edited ->
                stage(() -> session.workspace().stageDnsUpdate(zone, edited)));
    }

    @Override
    void deleteSelected() {
        DnsRecord record = table.getSelectionModel().getSelectedItem();
        if (!ready() || record == null) {
            return;
        }
        if (Dialogs.confirm(window(), "Delete " + record.type() + " " + record.name() + "?",
                "It is staged for deletion. Nothing is sent until you apply.", "Delete")) {
            stage(() -> session.workspace().stageDnsDelete(zone, record.id()));
        }
    }

    private boolean ready() {
        return session != null && session.workspace().isDnsLoaded(zone) && !hub.busyProperty().get();
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

    /** For tests: how many records are listed right now. */
    int rowCount() {
        return table.getItems().size();
    }
}
