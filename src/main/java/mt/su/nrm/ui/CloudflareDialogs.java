package mt.su.nrm.ui;

import mt.su.nrm.cloudflare.CloudflareWorkspace;
import mt.su.nrm.cloudflare.DnsRecord;
import mt.su.nrm.cloudflare.Zone;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** The small dialogs of the Cloudflare screen. They only collect and check input; nothing is sent from here. */
final class CloudflareDialogs {

    private CloudflareDialogs() {
    }

    /** What the route dialog collected; {@code zone} is null when an existing route was edited. */
    record RouteInput(Zone zone, String hostname, String path, String service) {
    }

    /**
     * "app" in zone example.com becomes app.example.com, "@" or an empty name is the zone itself, and a name
     * that already ends in the zone is kept.
     */
    static String fullName(String typed, Zone zone) {
        String name = typed.strip().toLowerCase(Locale.ROOT);
        if (name.isEmpty() || name.equals("@")) {
            return zone.name();
        }
        if (name.equals(zone.name()) || name.endsWith("." + zone.name())) {
            return name;
        }
        return name + "." + zone.name();
    }

    /** A hostname for a route: anything with a dot is taken as typed, a bare label is put under the zone. */
    static String routeHostname(String typed, Zone zone) {
        String name = typed.strip().toLowerCase(Locale.ROOT);
        if (name.isEmpty() || name.contains(".") || zone == null) {
            return name;
        }
        return name + "." + zone.name();
    }

    // ---------------------------------------------------------------- DNS record

    /** Adds ({@code existing} null) or edits a record. Problems are shown as the user types. */
    static Optional<DnsRecord> dnsRecord(Window owner, Zone zone, CloudflareWorkspace workspace, DnsRecord existing) {
        boolean editing = existing != null;
        Dialog<DnsRecord> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(editing ? "Edit DNS record" : "Add DNS record");
        ButtonType ok = new ButtonType(editing ? "Save" : "Add", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);

        ComboBox<String> type = new ComboBox<>(FXCollections.observableArrayList("A", "AAAA", "CNAME", "TXT"));
        if (editing && !type.getItems().contains(existing.type())) {
            type.getItems().add(existing.type());
        }
        type.setValue(editing ? existing.type() : "CNAME");
        type.setDisable(editing);

        TextField name = new TextField(editing ? existing.name() : "");
        name.setPromptText("app  (becomes app." + zone.name() + ")");
        TextField content = new TextField(editing ? existing.content() : "");
        CheckBox proxied = new CheckBox("Proxied through Cloudflare");
        proxied.setSelected(editing ? existing.proxied() : true);
        TextField comment = new TextField(editing ? existing.comment() : "");
        comment.setPromptText("optional");
        Label error = new Label();
        error.setWrapText(true);
        error.setStyle("-fx-text-fill: #b00020;");
        error.setMinHeight(Label.USE_PREF_SIZE);

        GridPane grid = grid();
        grid.addRow(0, new Label("Type"), type);
        grid.addRow(1, new Label("Name"), name);
        grid.addRow(2, new Label("Points to"), content);
        grid.addRow(3, new Label(""), proxied);
        grid.addRow(4, new Label("Comment"), comment);
        Label hint = new Label("Nothing is sent to Cloudflare until you review and apply the staged changes.");
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill: #666666;");
        VBox box = new VBox(10, grid, error, hint);
        box.setPadding(new Insets(12));
        box.setPrefWidth(460);
        dialog.getDialogPane().setContent(box);

        Node okButton = dialog.getDialogPane().lookupButton(ok);
        boolean[] touched = {false};
        Runnable check = () -> {
            boolean proxiable = List.of("A", "AAAA", "CNAME").contains(type.getValue());
            proxied.setDisable(!proxiable);
            DnsRecord candidate = buildDns(zone, existing, type, name, content, proxied, comment);
            List<String> problems = workspace.dnsProblems(zone, candidate);
            error.setText(touched[0] ? String.join("\n", problems) : "");
            okButton.setDisable(!problems.isEmpty());
        };
        for (javafx.beans.value.ObservableValue<?> property : List.of(type.valueProperty(), name.textProperty(),
                content.textProperty(), proxied.selectedProperty(), comment.textProperty())) {
            property.addListener((obs, o, n) -> {
                touched[0] = true;
                check.run();
            });
        }
        check.run();

        dialog.setResultConverter(button -> button == ok
                ? buildDns(zone, existing, type, name, content, proxied, comment) : null);
        return dialog.showAndWait();
    }

    private static DnsRecord buildDns(Zone zone, DnsRecord existing, ComboBox<String> type, TextField name,
                                      TextField content, CheckBox proxied, TextField comment) {
        boolean proxiable = List.of("A", "AAAA", "CNAME").contains(type.getValue());
        boolean isProxied = proxiable && proxied.isSelected();
        // Proxied records need automatic TTL; otherwise an edited record keeps the TTL it had.
        int ttl = isProxied || existing == null ? DnsRecord.TTL_AUTOMATIC : existing.ttl();
        return new DnsRecord(existing == null ? "" : existing.id(), type.getValue(), fullName(name.getText(), zone),
                content.getText().strip(), isProxied, ttl, comment.getText().strip());
    }

    // ---------------------------------------------------------------- route

    /**
     * Publishes ({@code existing} false) or edits a route. When editing, the hostname is fixed: to move an
     * application to another hostname, remove the route and publish it again.
     */
    static Optional<RouteInput> route(Window owner, List<Zone> zones, Zone preferred, String tunnelName,
                                      boolean editing, String hostname, String path, String service) {
        Dialog<RouteInput> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(editing ? "Edit published application" : "Publish application");
        ButtonType ok = new ButtonType(editing ? "Save" : "Publish", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);

        // The zone only matters for the DNS record of a new application; an edited route keeps its hostname.
        ComboBox<Zone> zoneBox = new ComboBox<>(FXCollections.observableArrayList(zones));
        zoneBox.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Zone z) {
                return z == null ? "" : z.name();
            }

            @Override
            public Zone fromString(String s) {
                return null;
            }
        });
        zoneBox.setValue(preferred != null ? preferred : zones.isEmpty() ? null : zones.get(0));
        zoneBox.setMaxWidth(Double.MAX_VALUE);

        TextField host = new TextField(hostname);
        host.setPromptText("app  (goes under the zone) or a full hostname");
        host.setDisable(editing);
        TextField pathField = new TextField(path);
        pathField.setPromptText("optional, e.g. ^/api/");
        TextField serviceField = new TextField(service);
        serviceField.setPromptText("http://localhost:8080");
        Label error = new Label();
        error.setWrapText(true);
        error.setStyle("-fx-text-fill: #b00020;");
        error.setMinHeight(Label.USE_PREF_SIZE);

        GridPane grid = grid();
        int row = 0;
        if (!editing) {
            grid.addRow(row++, new Label("Zone"), zoneBox);
        }
        grid.addRow(row++, new Label("Hostname"), host);
        grid.addRow(row++, new Label("Path"), pathField);
        grid.addRow(row, new Label("Service"), serviceField);
        Label hint = new Label(editing
                ? "Changes the route on tunnel " + tunnelName + ". Nothing is sent until you apply."
                : "Adds a route on tunnel " + tunnelName + " and a proxied CNAME for the hostname in the chosen "
                + "zone. Nothing is sent until you apply.");
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill: #666666;");
        VBox box = new VBox(10, grid, error, hint);
        box.setPadding(new Insets(12));
        box.setPrefWidth(480);
        dialog.getDialogPane().setContent(box);

        Node okButton = dialog.getDialogPane().lookupButton(ok);
        boolean[] touched = {false};
        Runnable check = () -> {
            Zone zone = zoneBox.getValue();
            String fullHost = routeHostname(host.getText(), zone);
            List<String> problems = new ArrayList<>(CloudflareWorkspace.routeProblems(fullHost,
                    pathField.getText().strip(), serviceField.getText()));
            if (!editing) {
                if (zone == null) {
                    problems.add("Choose a zone for the DNS record.");
                } else if (!fullHost.isEmpty() && !fullHost.equals(zone.name()) && !fullHost.endsWith("." + zone.name())) {
                    problems.add("The hostname must end in ." + zone.name() + ". Choose the matching zone.");
                }
            }
            error.setText(touched[0] ? String.join("\n", problems) : "");
            okButton.setDisable(!problems.isEmpty());
        };
        for (TextField f : List.of(host, pathField, serviceField)) {
            f.textProperty().addListener((obs, o, n) -> {
                touched[0] = true;
                check.run();
            });
        }
        zoneBox.valueProperty().addListener((obs, o, n) -> check.run());
        // Typing a full hostname picks the zone it belongs to (the longest matching name wins).
        host.textProperty().addListener((obs, o, n) -> {
            String typed = n.strip().toLowerCase(Locale.ROOT);
            zones.stream().filter(z -> typed.equals(z.name()) || typed.endsWith("." + z.name()))
                    .max(java.util.Comparator.comparingInt(z -> z.name().length()))
                    .ifPresent(zoneBox::setValue);
        });
        check.run();

        dialog.setResultConverter(button -> button == ok
                ? new RouteInput(editing ? null : zoneBox.getValue(), routeHostname(host.getText(), zoneBox.getValue()),
                pathField.getText().strip(), serviceField.getText().strip()) : null);
        return dialog.showAndWait();
    }

    /**
     * Asks before removing a route; the answer says whether its DNS record should go too. Empty means cancelled.
     *
     * @param zoneName the zone the hostname is in, or null if the token can't see one (then there is no DNS to remove)
     */
    static Optional<Boolean> confirmUnpublish(Window owner, String hostname, String zoneName) {
        Dialog<Boolean> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Remove published application");
        ButtonType remove = new ButtonType("Remove", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(remove, ButtonType.CANCEL);
        Label text = new Label("Stop publishing " + hostname + "?\nNothing is sent until you apply.");
        text.setWrapText(true);
        CheckBox dns = new CheckBox("Also delete its DNS record in " + zoneName
                + " (only if it points at this tunnel and no other route uses the hostname)");
        dns.setSelected(true);
        dns.setWrapText(true);
        VBox box = new VBox(12, text);
        if (zoneName != null) {
            box.getChildren().add(dns);
        } else {
            dns.setSelected(false);
        }
        box.setPadding(new Insets(12));
        box.setPrefWidth(440);
        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().lookupButton(ButtonType.CANCEL).requestFocus();
        dialog.setResultConverter(button -> button == remove ? dns.isSelected() : null);
        return dialog.showAndWait();
    }

    // ---------------------------------------------------------------- new zones and tunnels

    /** What the new-zone dialog collected. */
    record NewZoneInput(String accountId, String name) {
    }

    /** What the new-tunnel dialog collected. */
    record NewTunnelInput(String accountId, String name) {
    }

    /** Asks for a domain to add to the account. Unlike other changes this happens on Cloudflare straight away. */
    static Optional<NewZoneInput> newZone(Window owner, List<String> accountIds,
                                          java.util.function.Function<String, String> accountLabel,
                                          java.util.Collection<String> existingZones) {
        return newObject(owner, "Add a DNS zone", "Add domain", "example.com", "Domain", accountIds, accountLabel,
                name -> mt.su.nrm.cloudflare.NewObjectChecks.zoneProblems(name, existingZones),
                "This adds the domain to your Cloudflare account right now; it is not staged. The zone stays pending, "
                        + "and nothing changes for visitors, until you change the domain's nameservers at its registrar "
                        + "to the ones Cloudflare gives you (shown next).")
                .map(in -> new NewZoneInput(in[0], in[1].strip().toLowerCase(Locale.ROOT)));
    }

    /** Asks for the name of a new tunnel. Created on Cloudflare straight away. */
    static Optional<NewTunnelInput> newTunnel(Window owner, List<String> accountIds,
                                              java.util.function.Function<String, String> accountLabel,
                                              java.util.Collection<String> existingTunnels) {
        return newObject(owner, "Create a tunnel", "Create tunnel", "e.g. web-edge", "Name", accountIds, accountLabel,
                name -> mt.su.nrm.cloudflare.NewObjectChecks.tunnelProblems(name, existingTunnels),
                "This creates the tunnel on Cloudflare right now; it is not staged. It stays inactive until cloudflared "
                        + "runs on a server with the token you are shown next. Its routes are managed from here.")
                .map(in -> new NewTunnelInput(in[0], in[1].strip()));
    }

    /** Shared by both: a name, the account when there are several, live checking, and an explanation. */
    private static Optional<String[]> newObject(Window owner, String title, String okText, String prompt, String label,
                                                List<String> accountIds,
                                                java.util.function.Function<String, String> accountLabel,
                                                java.util.function.Function<String, List<String>> problemsOf,
                                                String explanation) {
        Dialog<String[]> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(title);
        ButtonType ok = new ButtonType(okText, ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);

        TextField name = new TextField();
        name.setPromptText(prompt);
        ComboBox<String> account = new ComboBox<>(FXCollections.observableArrayList(accountIds));
        account.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(String id) {
                return id == null ? "" : accountLabel.apply(id);
            }

            @Override
            public String fromString(String s) {
                return null;
            }
        });
        account.setValue(accountIds.get(0));
        account.setMaxWidth(Double.MAX_VALUE);
        Label error = new Label();
        error.setWrapText(true);
        error.setStyle("-fx-text-fill: #b00020;");
        error.setMinHeight(Label.USE_PREF_SIZE);
        Label hint = new Label(explanation);
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill: #666666;");

        GridPane grid = grid();
        int row = 0;
        if (accountIds.size() > 1) {
            grid.addRow(row++, new Label("Account"), account);
        }
        grid.addRow(row, new Label(label), name);
        VBox box = new VBox(10, grid, error, hint);
        box.setPadding(new Insets(12));
        box.setPrefWidth(480);
        dialog.getDialogPane().setContent(box);

        Node okButton = dialog.getDialogPane().lookupButton(ok);
        boolean[] touched = {false};
        Runnable check = () -> {
            List<String> problems = problemsOf.apply(name.getText());
            error.setText(touched[0] ? String.join("\n", problems) : "");
            okButton.setDisable(!problems.isEmpty());
        };
        name.textProperty().addListener((obs, o, n) -> {
            touched[0] = true;
            check.run();
        });
        check.run();
        dialog.setResultConverter(button -> button == ok ? new String[] {account.getValue(), name.getText()} : null);
        return dialog.showAndWait();
    }

    /**
     * Asks before something is deleted for good: the details of what will be lost, and the object's name typed in
     * full before the button works, so a mis-click or a habit of pressing Enter can't delete anything.
     */
    static boolean confirmRemoval(Window owner, String title, String details, String nameToType, String buttonText) {
        Dialog<Boolean> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(title);
        ButtonType delete = new ButtonType(buttonText, ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(delete, ButtonType.CANCEL);

        Label headline = new Label(title);
        headline.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #b00020;");
        headline.setWrapText(true);
        Label text = new Label(details);
        text.setWrapText(true);
        Label ask = new Label("To confirm, type  " + nameToType + "  below.");
        ask.setWrapText(true);
        TextField typed = new TextField();
        typed.setPromptText(nameToType);
        VBox box = new VBox(12, headline, text, ask, typed);
        box.setPadding(new Insets(12));
        box.setPrefWidth(500);
        dialog.getDialogPane().setContent(box);

        Node deleteButton = dialog.getDialogPane().lookupButton(delete);
        deleteButton.setDisable(true);
        typed.textProperty().addListener((obs, o, n) -> deleteButton.setDisable(!n.strip().equals(nameToType)));
        dialog.getDialogPane().lookupButton(ButtonType.CANCEL).requestFocus();
        dialog.setResultConverter(button -> button == delete);
        return Boolean.TRUE.equals(dialog.showAndWait().orElse(false));
    }

    /** Tells the user the domain was added and what to do at the registrar. */
    static void zoneCreated(Window owner, mt.su.nrm.cloudflare.ZoneCreated created) {
        Dialogs.showOutput(owner, javafx.scene.control.Alert.AlertType.INFORMATION,
                created.zone().name() + " was added to Cloudflare",
                "Its status is \"" + created.status() + "\". To activate it, change the domain's nameservers at its "
                        + "registrar to these:",
                created.nameServers().isEmpty() ? "(Cloudflare did not list nameservers; see the zone in the dashboard.)"
                        : String.join("\n", created.nameServers()));
    }

    /** Shows the install command, with the tunnel's run token in it. The token is a secret. */
    static void tunnelCreated(Window owner, mt.su.nrm.cloudflare.TunnelCreated created) {
        Dialogs.showOutput(owner, javafx.scene.control.Alert.AlertType.INFORMATION,
                "Tunnel " + created.tunnel().name() + " was created",
                "It stays inactive until cloudflared runs with its token. On a Linux server that has cloudflared, run "
                        + "the command below. The token lets anyone run this tunnel, so treat it like a password; "
                        + "it is shown here and kept out of the command log.",
                created.installCommand());
    }

    // ---------------------------------------------------------------- review

    /** Lists everything that will be sent and asks for the go-ahead. */
    static boolean review(Window owner, List<String> lines) {
        Dialog<Boolean> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Review Cloudflare changes");
        dialog.setResizable(true);
        ButtonType apply = new ButtonType("Apply to Cloudflare", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(apply, ButtonType.CANCEL);

        Label intro = new Label(lines.size() + (lines.size() == 1 ? " change" : " changes")
                + " will be sent to Cloudflare. DNS and tunnel changes take effect on live traffic straight away.");
        intro.setWrapText(true);
        TextArea area = new TextArea(String.join("\n", lines));
        area.setEditable(false);
        area.setPrefRowCount(Math.min(14, Math.max(4, lines.size() + 1)));
        area.setPrefColumnCount(80);
        area.setStyle("-fx-font-family: 'Consolas', 'Menlo', monospace;");
        VBox.setVgrow(area, Priority.ALWAYS);
        VBox box = new VBox(10, intro, area);
        box.setPadding(new Insets(12));
        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().lookupButton(ButtonType.CANCEL).requestFocus();
        dialog.setResultConverter(button -> button == apply);
        return dialog.showAndWait().orElse(false);
    }

    private static GridPane grid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        javafx.scene.layout.ColumnConstraints labels = new javafx.scene.layout.ColumnConstraints();
        javafx.scene.layout.ColumnConstraints fields = new javafx.scene.layout.ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        fields.setFillWidth(true);
        grid.getColumnConstraints().addAll(labels, fields);
        return grid;
    }
}
