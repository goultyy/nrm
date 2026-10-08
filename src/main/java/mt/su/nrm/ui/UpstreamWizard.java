package mt.su.nrm.ui;

import mt.su.nrm.nginx.UpstreamServer;
import mt.su.nrm.nginx.UpstreamSettings;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Guides the creation or editing of a load balancing group: what it is called and how traffic is
 * shared, which servers are in it (one row each, no nginx syntax), and connection reuse, ending
 * with a review of the exact block that will be added.
 */
final class UpstreamWizard {

    /** How traffic is shared, in plain words. */
    private enum Balancing {
        ROUND_ROBIN("Share evenly (round robin)", "",
                "Each server gets requests in turn. A good default when the servers are alike."),
        LEAST_CONN("Send to the least busy server", "least_conn",
                "Each request goes to the server with the fewest active connections. Good when requests take very different times."),
        IP_HASH("Keep each visitor on the same server", "ip_hash",
                "Requests from the same IP address always reach the same server, so sessions stay put."),
        HASH("Keep by something else (URL, cookie...)", "hash",
                "Requests with the same key value always reach the same server, for example the same URL, which helps caches."),
        RANDOM("Pick a server at random", "random", "Each request goes to a randomly chosen server."),
        CUSTOM("Something else", "", "Type the nginx balancing directive yourself, for example: least_time header.");

        private final String label;
        private final String directive;
        private final String description;

        Balancing(String label, String directive, String description) {
            this.label = label;
            this.directive = directive;
            this.description = description;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** One editable server row. */
    private static final class ServerRow {
        final TextField address = new TextField();
        final TextField port = new TextField();
        final TextField weight = new TextField();
        final ComboBox<UpstreamServer.Role> role = new ComboBox<>(FXCollections.observableArrayList(UpstreamServer.Role.values()));
        final TextField maxFails = new TextField();
        final TextField failTimeout = new TextField();
        final Button remove = new Button("Remove");
        final HBox box;
        private final List<String> extra;

        ServerRow(UpstreamServer s) {
            address.setPromptText("10.0.0.1 or app1.internal");
            address.setPrefColumnCount(16);
            port.setPromptText("80");
            port.setPrefColumnCount(5);
            weight.setPromptText("1");
            weight.setPrefColumnCount(4);
            maxFails.setPromptText("1");
            maxFails.setPrefColumnCount(4);
            failTimeout.setPromptText("10s");
            failTimeout.setPrefColumnCount(5);
            address.setText(s.address);
            port.setText(s.port);
            weight.setText(s.weight);
            role.setValue(s.role);
            maxFails.setText(s.maxFails);
            failTimeout.setText(s.failTimeout);
            extra = new ArrayList<>(s.extra);
            box = new HBox(6, address, port, weight, role, maxFails, failTimeout, remove);
            box.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(address, Priority.ALWAYS);
        }

        UpstreamServer read() {
            UpstreamServer s = new UpstreamServer();
            s.address = address.getText().strip();
            s.port = port.getText().strip();
            s.weight = weight.getText().strip();
            s.role = role.getValue() == null ? UpstreamServer.Role.ACTIVE : role.getValue();
            s.maxFails = maxFails.getText().strip();
            s.failTimeout = failTimeout.getText().strip();
            s.extra.addAll(extra);
            return s;
        }
    }

    private UpstreamWizard() {
    }

    /**
     * @param s          the group to create or edit; updated only if the wizard is finished
     * @param takenNames names of the other upstream groups
     * @return true if the user finished the wizard
     */
    static boolean run(Window owner, UpstreamSettings s, Set<String> takenNames, boolean creating) {
        // ---- page 1: name and method
        TextField name = new TextField(s.name);
        name.setPromptText("e.g. app_backend (letters, digits, dots, dashes, underscores)");
        ComboBox<Balancing> balancing = new ComboBox<>(FXCollections.observableArrayList(Balancing.values()));
        Label balancingHelp = WizardParts.hint("");
        ComboBox<String> hashKey = new ComboBox<>(FXCollections.observableArrayList("$request_uri", "$cookie_session",
                "$http_x_user_id", "$remote_addr"));
        hashKey.setEditable(true);
        CheckBox consistent = new CheckBox("Keep most visitors on their server when servers are added or removed (consistent)");
        consistent.setSelected(true);
        TextField custom = new TextField();
        custom.setPromptText("least_time header");
        readMethod(s.method, balancing, hashKey, consistent, custom);

        Runnable showMethodFields = () -> {
            Balancing b = balancing.getValue();
            balancingHelp.setText(b == null ? "" : b.description);
            hashKey.setVisible(b == Balancing.HASH);
            hashKey.setManaged(b == Balancing.HASH);
            consistent.setVisible(b == Balancing.HASH);
            consistent.setManaged(b == Balancing.HASH);
            custom.setVisible(b == Balancing.CUSTOM);
            custom.setManaged(b == Balancing.CUSTOM);
        };
        balancing.valueProperty().addListener((obs, o, n) -> showMethodFields.run());
        showMethodFields.run();

        GridPane page1 = WizardParts.form();
        int r = WizardParts.row(page1, 0, "Group name", name);
        r = WizardParts.row(page1, r, "How to share traffic", balancing);
        r = WizardParts.note(page1, r, balancingHelp);
        r = WizardParts.row(page1, r, "Hash key", hashKey);
        r = WizardParts.row(page1, r, "", consistent);
        WizardParts.row(page1, r, "Directive", custom);

        // ---- page 2: servers
        VBox rows = new VBox(6);
        List<ServerRow> serverRows = new ArrayList<>();
        java.util.function.Consumer<UpstreamServer> add = server -> {
            ServerRow row = new ServerRow(server);
            serverRows.add(row);
            rows.getChildren().add(row.box);
            row.remove.setOnAction(e -> {
                serverRows.remove(row);
                rows.getChildren().remove(row.box);
            });
        };
        for (String line : s.servers) {
            add.accept(UpstreamServer.parse(line));
        }
        if (serverRows.isEmpty()) {
            add.accept(new UpstreamServer());
        }
        Button addServer = new Button("Add another server");
        addServer.setOnAction(e -> add.accept(new UpstreamServer()));
        HBox header = new HBox(6, headerLabel("Address", 190), headerLabel("Port", 64), headerLabel("Weight", 54),
                headerLabel("Role", 200), headerLabel("Failures", 54), headerLabel("Retry after", 64));
        header.setPadding(new Insets(0, 0, 0, 2));
        VBox page2 = new VBox(8, header, rows, addServer, WizardParts.hint(
                "Weight: a server with weight 3 gets three times the traffic of one with weight 1. "
                        + "Failures / Retry after: how many failed attempts before a server is skipped, and for how long "
                        + "(leave empty for nginx's defaults: 1 failure, 10 seconds). "
                        + "A backup server only receives traffic when all the active ones are unavailable."));
        page2.setPadding(new Insets(6));

        // ---- page 3: connections
        CheckBox reuse = new CheckBox("Reuse connections to the servers (recommended for web traffic)");
        TextField keepalive = new TextField(s.keepalive.isBlank() ? "32" : s.keepalive);
        keepalive.setPrefColumnCount(6);
        reuse.setSelected(!s.keepalive.isBlank() || creating);
        keepalive.disableProperty().bind(reuse.selectedProperty().not());
        GridPane page3 = WizardParts.form();
        r = WizardParts.note(page3, 0, WizardParts.hint("Opening a new connection for every request is slow. "
                + "With connection reuse, nginx keeps some idle connections open to the servers and sends new requests "
                + "through them. When you use this with a reverse proxy location, nginx also needs HTTP/1.1 and an empty "
                + "Connection header, which the proxy location's default headers already handle."));
        r = WizardParts.row(page3, r, "", reuse);
        WizardParts.row(page3, r, "Idle connections to keep", keepalive);

        java.util.function.Supplier<UpstreamSettings> read = () -> {
            UpstreamSettings u = new UpstreamSettings();
            u.name = name.getText().strip();
            u.method = methodText(balancing.getValue(), hashKey, consistent, custom);
            for (ServerRow row : serverRows) {
                UpstreamServer server = row.read();
                if (!server.isBlank()) {
                    u.servers.add(server.format());
                }
            }
            u.keepalive = reuse.isSelected() ? keepalive.getText().strip() : "";
            return u;
        };

        List<WizardDialog.Page> pages = List.of(
                new WizardDialog.Page("Name and method",
                        "Give the group a name, and choose how requests are shared between its servers.", page1, () -> {
                            List<String> problems = new ArrayList<>();
                            String n = name.getText().strip();
                            if (!n.matches("[A-Za-z0-9_.-]+")) {
                                problems.add("The name may use letters, digits, dots, dashes and underscores.");
                            } else if (takenNames.contains(n)) {
                                problems.add("A group named \"" + n + "\" already exists.");
                            }
                            Balancing b = balancing.getValue();
                            if (b == Balancing.HASH && !hashKey.getEditor().getText().strip().startsWith("$")) {
                                problems.add("The hash key must be an nginx variable such as $request_uri.");
                            }
                            if (b == Balancing.CUSTOM && custom.getText().isBlank()) {
                                problems.add("Type the balancing directive, or choose another method.");
                            }
                            return problems;
                        }),
                new WizardDialog.Page("Servers",
                        "Add the servers that should receive the traffic. You only need an address; everything else is optional.",
                        page2, () -> {
                            List<String> problems = new ArrayList<>();
                            int active = 0;
                            int filled = 0;
                            for (ServerRow row : serverRows) {
                                UpstreamServer server = row.read();
                                if (server.isBlank()) {
                                    continue;
                                }
                                filled++;
                                problems.addAll(server.problems());
                                if (server.role == UpstreamServer.Role.ACTIVE) {
                                    active++;
                                }
                            }
                            if (filled == 0) {
                                problems.add("Add at least one server.");
                            } else if (active == 0) {
                                problems.add("At least one server must be active (not just backup or down).");
                            }
                            return problems;
                        }),
                new WizardDialog.Page("Connections", "Optionally keep connections to the servers open between requests.",
                        page3, () -> reuse.isSelected() && !keepalive.getText().strip().matches("[1-9]\\d{0,4}")
                        ? List.of("Enter a whole number of connections to keep, or turn connection reuse off.") : List.of()));

        boolean finished = WizardDialog.show(owner, creating ? "New load balancing group" : "Edit load balancing group",
                creating ? "Add group" : "Save", pages, () -> read.get().preview(),
                () -> "Next: in a virtual host, add a location of type Reverse proxy and set \"Forward to\" to "
                        + "http://" + (name.getText().isBlank() ? "<name>" : name.getText().strip()) + ".");
        if (finished) {
            UpstreamSettings u = read.get();
            s.name = u.name;
            s.method = u.method;
            s.servers = u.servers;
            s.keepalive = u.keepalive;
        }
        return finished;
    }

    private static Label headerLabel(String text, double width) {
        Label l = new Label(text);
        l.setPrefWidth(width);
        l.setStyle("-fx-font-weight: bold;");
        return l;
    }

    /** Fills the method controls from an existing directive text. */
    private static void readMethod(String method, ComboBox<Balancing> balancing, ComboBox<String> hashKey,
                                   CheckBox consistent, TextField custom) {
        String m = method.strip();
        if (m.isEmpty()) {
            balancing.setValue(Balancing.ROUND_ROBIN);
        } else if (m.equals("least_conn")) {
            balancing.setValue(Balancing.LEAST_CONN);
        } else if (m.equals("ip_hash")) {
            balancing.setValue(Balancing.IP_HASH);
        } else if (m.equals("random")) {
            balancing.setValue(Balancing.RANDOM);
        } else if (m.startsWith("hash ")) {
            balancing.setValue(Balancing.HASH);
            String[] parts = m.split("\\s+");
            hashKey.getEditor().setText(parts.length > 1 ? parts[1] : "");
            consistent.setSelected(parts.length > 2 && parts[2].equals("consistent"));
        } else {
            balancing.setValue(Balancing.CUSTOM);
            custom.setText(m);
        }
        if (hashKey.getEditor().getText().isEmpty()) {
            hashKey.getEditor().setText("$request_uri");
        }
    }

    private static String methodText(Balancing b, ComboBox<String> hashKey, CheckBox consistent, TextField custom) {
        if (b == null) {
            return "";
        }
        switch (b) {
            case HASH:
                return "hash " + hashKey.getEditor().getText().strip() + (consistent.isSelected() ? " consistent" : "");
            case CUSTOM:
                return custom.getText().strip();
            default:
                return b.directive;
        }
    }
}
