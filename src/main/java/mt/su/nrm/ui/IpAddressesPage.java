package mt.su.nrm.ui;

import mt.su.nrm.model.ServerProfile;
import mt.su.nrm.network.IpOverview;
import mt.su.nrm.network.IpOverview.Configured;
import mt.su.nrm.network.IpOverview.Overview;
import mt.su.nrm.network.IpOverview.Row;
import mt.su.nrm.ssh.Reachability;
import mt.su.nrm.ssh.ExternalAddressService;
import mt.su.nrm.ssh.NetworkService;
import mt.su.nrm.ssh.NetworkService.Facts;
import mt.su.nrm.ssh.NetworkService.LocalAddress;
import mt.su.nrm.ssh.SshExecutor;
import mt.su.nrm.ssh.SshSession;
import mt.su.nrm.util.NetworkSettings;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Every address on the server next to what nginx does with it: the ports it listens on now (read from the server), the
 * sites whose {@code listen} covers it (read from the loaded configuration), and the external address behind a private
 * one. View only: nothing here changes the configuration.
 * <p>
 * The external address cannot be read from the server itself, so "Find external addresses" asks, on request only:
 * first the server's cloud metadata, then a "what is my IP" service through the address in question. Each answer says
 * where it came from. Everything runs off the JavaFX thread through the logged connection.
 */
final class IpAddressesPage extends FeaturePage {

    private Facts facts = Facts.EMPTY;
    private boolean factsLoaded;
    private final Map<String, ExternalAddressService.Found> externals = new LinkedHashMap<>();
    private final List<String> notes = new ArrayList<>();
    private Overview overview = new Overview(List.of(), List.of());

    private final TableView<Row> table = new TableView<>();
    private final Label warnings = new Label();
    private final Label noteText = new Label();
    private final Label detail = new Label();
    private final Label status = new Label();
    private final javafx.scene.control.ProgressIndicator spinner = ProgressDialog.spinner(16);
    private final Button refreshButton = new Button("Refresh");
    private final Button findButton = new Button("Find external addresses");
    private final Button reachButton = new Button("Check from this computer");
    private final Button servicesButton = new Button("Services used");
    private final Button copyButton = new Button("Copy");
    private boolean built;
    private boolean busy;
    private mt.su.nrm.nginx.RemoteConfig factsFor;

    IpAddressesPage(ServerProfile profile, ServerConnection connection) {
        super(profile, connection);
    }

    @Override
    void refresh() {
        if (!ready()) {
            built = false;
            setCenter(notReadyView());
            return;
        }
        if (!built) {
            built = true;
            setCenter(build());
        }
        if (config() != factsFor) {
            // A freshly loaded configuration (after an apply, say) means nginx may be listening somewhere new.
            factsFor = config();
            factsLoaded = false;
        }
        if (!factsLoaded) {
            loadFacts();
        }
        recompute();
    }

    @Override
    void reload() {
        factsLoaded = false;
        refresh();
    }

    // ---------------------------------------------------------------- the page

    private Node build() {
        table.getColumns().addAll(List.of(
                column("Address", 200, r -> r.address().address()),
                column("Kind", 110, r -> r.address().kind() + (r.address().ipv6() ? " IPv6" : " IPv4")),
                column("Interface", 80, r -> r.address().iface()),
                column("nginx listening now", 150, this::listeningText),
                column("Set up in the configuration", 230, r -> configuredText(r, false)),
                column("External address", 170, this::externalText),
                column("Found by", 170, r -> sourceText(r))));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("No addresses read yet."));
        table.setPrefHeight(260);
        table.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> showDetail(n));
        VBox.setVgrow(table, Priority.ALWAYS);

        refreshButton.setOnAction(e -> reload());
        findButton.setOnAction(e -> findExternal());
        reachButton.setOnAction(e -> checkReachability());
        servicesButton.setOnAction(e -> editServices());
        copyButton.setOnAction(e -> copy());
        reachButton.setDisable(true);
        table.getSelectionModel().selectedItemProperty().addListener((obs, o, n) ->
                reachButton.setDisable(n == null || !externals.containsKey(n.address().address()) || busy));
        spinner.setVisible(false);
        HBox bar = new HBox(8, refreshButton, findButton, reachButton, servicesButton, copyButton, spinner);
        bar.setAlignment(Pos.CENTER_LEFT);

        warnings.setWrapText(true);
        warnings.setStyle("-fx-text-fill: #8a6d00;");
        noteText.setWrapText(true);
        noteText.setOpacity(0.85);
        detail.setWrapText(true);
        status.setWrapText(true);
        status.setOpacity(0.75);

        VBox box = new VBox(10, heading("IP addresses"),
                hint("Every address on this server, which ones nginx is listening on now, and which ones your sites are "
                        + "set up to use. A cloud server often has only a private address; \"Find external addresses\" "
                        + "looks up the public address behind each one. This page only shows; it changes nothing."),
                bar, table, detail, warnings, noteText, status);
        box.setPadding(new Insets(12));
        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent;");
        return scroll;
    }

    private static TableColumn<Row, String> column(String title, double width, java.util.function.Function<Row, String> text) {
        TableColumn<Row, String> c = new TableColumn<>(title);
        c.setCellValueFactory(v -> new SimpleStringProperty(text.apply(v.getValue())));
        c.setPrefWidth(width);
        c.setSortable(false);
        return c;
    }

    // ---------------------------------------------------------------- cell text

    private String listeningText(Row r) {
        if (r.listening().isEmpty()) {
            return "-";
        }
        String ports = r.listening().stream().map(String::valueOf).collect(Collectors.joining(", "));
        return r.listeningCertain() ? ports : ports + " (any program)";
    }

    private String configuredText(Row r, boolean full) {
        if (r.configured().isEmpty()) {
            return "-";
        }
        Set<String> parts = new LinkedHashSet<>();
        for (Configured c : r.configured()) {
            parts.add(c.site() + " :" + c.endpoint().port() + (c.ssl() ? " (ssl)" : ""));
        }
        if (!full && parts.size() > 2) {
            List<String> first = parts.stream().limit(2).toList();
            return String.join(", ", first) + " and " + (parts.size() - 2) + " more";
        }
        return String.join(", ", parts);
    }

    private String externalText(Row r) {
        LocalAddress a = r.address();
        ExternalAddressService.Found e = externals.get(a.address());
        if (e != null) {
            return e.address().equals(a.address()) ? "same as the address" : e.address();
        }
        if (a.isLoopback() || a.isLinkLocal()) {
            return "not applicable";
        }
        return a.kind().equals("public") ? "same as the address" : "not looked up";
    }

    private String sourceText(Row r) {
        ExternalAddressService.Found e = externals.get(r.address().address());
        return e == null ? "" : e.source();
    }

    private void showDetail(Row r) {
        if (r == null) {
            detail.setText("");
            return;
        }
        detail.setText(r.address().address() + ": " + (r.configured().isEmpty() ? "no site in the loaded configuration "
                + "listens here." : "sites listening here: " + configuredText(r, true) + "."));
    }

    // ---------------------------------------------------------------- reading

    private void loadFacts() {
        SshSession session = connection.session();
        if (session == null || !connection.isConnected()) {
            status.setText("Connect to the server to read its addresses.");
            return;
        }
        factsLoaded = true;
        setBusy(true, "Reading the server's addresses and listening ports...");
        SshExecutor.submit(() -> NetworkService.read(session)).whenComplete((read, failure) -> Platform.runLater(() -> {
            setBusy(false, failure == null ? "" : "Could not read the addresses: " + failure.getMessage());
            if (failure == null) {
                applyFacts(read);
            }
        }));
    }

    void applyFacts(Facts read) {
        this.facts = read;
        this.factsLoaded = true;
        recompute();
        if (read.addresses().isEmpty()) {
            status.setText("The server reported no addresses (the ip command may not be available).");
        } else if (!read.processKnown()) {
            status.setText("nginx's own sockets can't be told from other programs' without root rights, so every "
                    + "listener is shown and no warning is given about what nginx is or isn't listening on.");
        } else {
            status.setText("");
        }
    }

    private void recompute() {
        if (!built) {
            return;
        }
        overview = IpOverview.build(facts, IpOverview.configuredFrom(config()));
        table.setItems(FXCollections.observableArrayList(overview.rows()));
        warnings.setText(overview.warnings().isEmpty() ? "" : "Worth a look:\n- " + String.join("\n- ", overview.warnings()));
        warnings.setManaged(!overview.warnings().isEmpty());
        warnings.setVisible(!overview.warnings().isEmpty());
        noteText.setText(String.join("\n", notes));
        noteText.setManaged(!notes.isEmpty());
        noteText.setVisible(!notes.isEmpty());
        table.refresh();
    }

    // ---------------------------------------------------------------- external addresses

    private void findExternal() {
        SshSession session = connection.session();
        if (session == null || !connection.isConnected()) {
            Dialogs.info(window(), "Not connected", "Connect to the server first.");
            return;
        }
        if (facts.addresses().isEmpty()) {
            Dialogs.info(window(), "No addresses", "No addresses have been read from the server yet.");
            return;
        }
        List<String> services = NetworkSettings.services(NetworkSettings.file());
        if (!NetworkSettings.confirmed(NetworkSettings.file())) {
            boolean ok = Dialogs.confirm(window(), "Ask an outside service?",
                    "A server behind NAT can't know its own external address. If its cloud's own metadata doesn't say, "
                            + "the app runs a request on the server, through each private address, to this service so "
                            + "that it can report the address it sees:\n\n" + String.join("\n", services)
                            + "\n\nThe service learns the server's public address, which is already public. You can change "
                            + "the list under \"Services used\". The requests show in the command log.", "Go ahead");
            if (!ok) {
                return;
            }
            try {
                NetworkSettings.setConfirmed(NetworkSettings.file(), true);
            } catch (IOException e) {
                // Asked again next time; nothing else depends on it.
            }
        }
        List<LocalAddress> addresses = List.copyOf(facts.addresses());
        setBusy(true, "Looking up external addresses...");
        SshExecutor.submit(() -> ExternalAddressService.lookup(session, addresses, services)).whenComplete((result, failure) ->
                Platform.runLater(() -> {
                    setBusy(false, "");
                    if (failure != null) {
                        status.setText("The lookup stopped: " + failure.getMessage());
                        return;
                    }
                    externals.clear();
                    externals.putAll(result.found());
                    notes.clear();
                    notes.addAll(result.notes());
                    status.setText(result.found().isEmpty() ? "No external address could be found." : "");
                    recompute();
                }));
    }

    private void editServices() {
        EchoServicesDialog.show(window(), NetworkSettings.services(NetworkSettings.file())).ifPresent(list -> {
            try {
                NetworkSettings.saveServices(NetworkSettings.file(), list);
                status.setText("Saved. The next lookup will use these services.");
            } catch (IOException e) {
                Dialogs.error(window(), "Could not save", e.getMessage());
            }
        });
    }

    // ---------------------------------------------------------------- reachability and copying

    private void checkReachability() {
        Row row = table.getSelectionModel().getSelectedItem();
        if (row == null || !externals.containsKey(row.address().address())) {
            return;
        }
        String host = externals.get(row.address().address()).address();
        List<Integer> ports = !row.listening().isEmpty() ? row.listening()
                : row.configured().stream().map(c -> c.endpoint().port()).distinct().toList();
        if (ports.isEmpty()) {
            Dialogs.info(window(), "Nothing to try", "nginx isn't listening on, or set up for, any port at this address.");
            return;
        }
        setBusy(true, "Trying " + host + " from this computer...");
        SshExecutor.submit(() -> Reachability.check(host, ports, connection.log(), Reachability.tcp()))
                .whenComplete((results, failure) -> Platform.runLater(() -> {
                    setBusy(false, "");
                    if (failure != null) {
                        status.setText("The check stopped: " + failure.getMessage());
                        return;
                    }
                    String text = results.stream().map(r -> "port " + r.port() + ": "
                            + (r.open() ? "open" : "no connection (" + r.detail() + ")")).collect(Collectors.joining("\n"));
                    Dialogs.showOutput(window(), Alert.AlertType.INFORMATION, "Reaching " + host + " from this computer",
                            "This tried a plain connection to each port from this computer, sending no data. If this "
                                    + "computer is inside the same network as the server, a closed result can come from "
                                    + "that network rather than from the server's firewall.", text);
                }));
    }

    private void copy() {
        StringBuilder sb = new StringBuilder("Address\tKind\tInterface\tnginx listening\tConfigured\tExternal\tFound by\n");
        for (Row r : overview.rows()) {
            sb.append(r.address().address()).append('\t').append(r.address().kind()).append('\t').append(r.address().iface())
                    .append('\t').append(listeningText(r)).append('\t').append(configuredText(r, true)).append('\t')
                    .append(externalText(r)).append('\t').append(sourceText(r)).append('\n');
        }
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(sb.toString());
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        status.setText("Copied the table.");
    }

    private void setBusy(boolean on, String message) {
        busy = on;
        spinner.setVisible(on);
        refreshButton.setDisable(on);
        findButton.setDisable(on);
        if (on) {
            reachButton.setDisable(true);
        }
        status.setText(message);
    }

    // For tests.

    int rowCount() {
        return table.getItems().size();
    }

    List<String> rowSummaries() {
        return overview.rows().stream().map(r -> r.address().address() + " | " + listeningText(r) + " | "
                + configuredText(r, true) + " | " + externalText(r)).toList();
    }

    String warningsText() {
        return warnings.getText();
    }

    String statusText() {
        return status.getText();
    }
}
