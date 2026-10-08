package mt.su.nrm.ui;

import mt.su.nrm.ui.LineEntries.AccessLog;
import mt.su.nrm.ui.LineEntries.ErrorPage;
import mt.su.nrm.ui.LineEntries.LimitConn;
import mt.su.nrm.ui.LineEntries.Listen;
import mt.su.nrm.ui.LineEntries.LimitReq;
import mt.su.nrm.ui.LineEntries.Rewrite;
import mt.su.nrm.ui.LineEntries.RewriteKind;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The small "add or edit one entry" dialogs behind {@link LineListEditor}: rewrite rules, request and
 * connection limits, access logs and error pages. Each shows plain fields, explains them, previews the
 * exact nginx line it will write and won't accept an entry with problems. A line the dialog can't
 * understand is offered as plain text instead, so nothing is reinterpreted.
 */
final class EntryDialogs {

    private EntryDialogs() {
    }

    /** A value with the words the user sees for it. */
    private record Choice(String value, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    /** The common shell: content on top, then problems, then the preview of the line. */
    private static final class Form {
        final Dialog<String> dialog = new Dialog<>();
        final Label preview = new Label();
        final Label problems = new Label();
        private final Supplier<List<String>> check;
        private final Supplier<String> line;
        private Node ok;

        Form(Window owner, String title, String heading, Node content, Supplier<String> line,
             Supplier<List<String>> check) {
            this.line = line;
            this.check = check;
            dialog.initOwner(owner);
            dialog.setTitle(title);
            dialog.setResizable(true);
            ButtonType accept = new ButtonType("OK", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
            dialog.getDialogPane().getButtonTypes().addAll(accept, ButtonType.CANCEL);
            Label head = new Label(heading);
            head.setWrapText(true);
            head.setMaxWidth(520);
            problems.setWrapText(true);
            problems.setMaxWidth(520);
            problems.setStyle("-fx-text-fill: #b00020;");
            problems.managedProperty().bind(problems.textProperty().isNotEmpty());
            preview.setStyle("-fx-font-family: 'Consolas', 'Menlo', monospace; -fx-background-color: #00000010; -fx-padding: 6;");
            preview.setWrapText(true);
            preview.setMaxWidth(520);
            VBox box = new VBox(10, head, content, problems, new Label("This will be written as:"), preview);
            box.setPadding(new Insets(8));
            box.setPrefWidth(560);
            dialog.getDialogPane().setContent(box);
            ok = dialog.getDialogPane().lookupButton(accept);
            dialog.setResultConverter(b -> b == accept ? line.get() : null);
            refresh();
        }

        void refresh() {
            List<String> issues = check.get();
            problems.setText(String.join("\n", issues));
            preview.setText(issues.isEmpty() ? line.get() : "");
            ok.setDisable(!issues.isEmpty());
        }

        /** Re-checks whenever one of these controls changes. */
        void watch(Node... controls) {
            for (Node n : controls) {
                if (n instanceof TextInputControl t) {
                    t.textProperty().addListener((obs, o, v) -> refresh());
                } else if (n instanceof ComboBox<?> c) {
                    c.valueProperty().addListener((obs, o, v) -> refresh());
                    if (c.isEditable()) {
                        c.getEditor().textProperty().addListener((obs, o, v) -> refresh());
                    }
                } else if (n instanceof CheckBox c) {
                    c.selectedProperty().addListener((obs, o, v) -> refresh());
                }
            }
        }

        Optional<String> show() {
            return dialog.showAndWait();
        }
    }

    private static GridPane grid() {
        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(8);
        return g;
    }

    private static Label hint(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setMaxWidth(520);
        l.setOpacity(0.7);
        return l;
    }

    /** For a line the friendly dialog can't read: edit it as it is. */
    private static Optional<String> raw(Window owner, String title, String initial) {
        TextField field = new TextField(initial);
        Form form = new Form(owner, title, "This entry uses options the simple dialog doesn't cover, so it is shown as it "
                + "is written.", field, () -> field.getText().strip(),
                () -> field.getText().isBlank() ? List.of("The entry can't be empty.") : List.of());
        form.watch(field);
        return form.show();
    }

    private static ComboBox<String> zoneBox(Collection<String> zones, String selected) {
        List<String> names = new ArrayList<>(new java.util.TreeSet<>(zones));
        if (!selected.isBlank() && !names.contains(selected)) {
            names.add(selected);
        }
        ComboBox<String> box = new ComboBox<>(FXCollections.observableArrayList(names));
        box.setEditable(true);
        box.setValue(selected);
        box.setPromptText(names.isEmpty() ? "no zones defined yet" : "choose a zone");
        box.setPrefWidth(260);
        return box;
    }

    private static String text(ComboBox<String> box) {
        return box.getEditor().getText() == null ? "" : box.getEditor().getText().strip();
    }

    // ------------------------------------------------------------------ rewrites

    static Optional<String> rewrite(Window owner, String initial) {
        Rewrite start = initial.isBlank() ? Rewrite.redirect("301", "https://$host$request_uri") : LineEntries.parseRewrite(initial);
        if (start == null) {
            return raw(owner, "Rewrite rule", initial);
        }
        ToggleGroup kinds = new ToggleGroup();
        RadioButton rewriteKind = new RadioButton("Change the address inside the server (rewrite)");
        RadioButton redirectKind = new RadioButton("Send visitors to another address (redirect)");
        RadioButton statusKind = new RadioButton("Answer with a status code (block, gone, ...)");
        rewriteKind.setUserData(RewriteKind.REWRITE);
        redirectKind.setUserData(RewriteKind.REDIRECT);
        statusKind.setUserData(RewriteKind.STATUS);
        for (RadioButton b : List.of(rewriteKind, redirectKind, statusKind)) {
            b.setToggleGroup(kinds);
        }
        (start.kind() == RewriteKind.REWRITE ? rewriteKind : start.kind() == RewriteKind.REDIRECT ? redirectKind : statusKind)
                .setSelected(true);

        ComboBox<Choice> preset = new ComboBox<>(FXCollections.observableArrayList(
                new Choice("", "Start from a common case..."),
                new Choice("https", "Send everything to HTTPS"),
                new Choice("move", "Move a page to a new address"),
                new Choice("domain", "Move the whole site to another domain"),
                new Choice("gone", "Say a page is gone (410)"),
                new Choice("block", "Block a path (403)")));
        preset.getSelectionModel().selectFirst();

        TextField pattern = new TextField(start.pattern());
        pattern.setPromptText("^/old-page$   (a regular expression)");
        TextField replacement = new TextField(start.replacement());
        replacement.setPromptText("/new-page   ($1 is the first (...) of the pattern)");
        ComboBox<Choice> flag = new ComboBox<>(FXCollections.observableArrayList(
                new Choice("", "Carry on with the next rule"),
                new Choice("last", "Stop here and look for a matching location again"),
                new Choice("break", "Stop here and serve it"),
                new Choice("redirect", "Send a temporary redirect (302)"),
                new Choice("permanent", "Send a permanent redirect (301)")));
        flag.getSelectionModel().select(flag.getItems().stream().filter(c -> c.value().equals(start.flag())).findFirst()
                .orElse(flag.getItems().get(0)));
        ComboBox<Choice> code = new ComboBox<>(FXCollections.observableArrayList(
                new Choice("301", "301 - permanent (search engines follow it)"),
                new Choice("302", "302 - temporary"),
                new Choice("303", "303 - see other"),
                new Choice("307", "307 - temporary, keeps POST as POST"),
                new Choice("308", "308 - permanent, keeps POST as POST")));
        code.getSelectionModel().select(code.getItems().stream()
                .filter(c -> c.value().equals(start.kind() == RewriteKind.REDIRECT ? start.code() : "301")).findFirst()
                .orElse(code.getItems().get(0)));
        TextField target = new TextField(start.kind() == RewriteKind.REDIRECT ? start.target() : "");
        target.setPromptText("https://example.com$request_uri");
        TextField status = new TextField(start.kind() == RewriteKind.STATUS ? start.code() : "403");
        status.setPrefColumnCount(5);
        TextField reply = new TextField(start.kind() == RewriteKind.STATUS ? start.target() : "");
        reply.setPromptText("optional text to send back");

        GridPane rewriteFields = grid();
        rewriteFields.addRow(0, new Label("Addresses matching"), pattern);
        rewriteFields.addRow(1, new Label("Rewrite to"), replacement);
        rewriteFields.addRow(2, new Label("Then"), flag);
        rewriteFields.add(hint("Example: match ^/blog/(.*)$ and rewrite to /news/$1 to serve /blog/a from /news/a."), 1, 3);
        GridPane redirectFields = grid();
        redirectFields.addRow(0, new Label("Redirect type"), code);
        redirectFields.addRow(1, new Label("Send visitors to"), target);
        redirectFields.add(hint("$host is the name the visitor typed and $request_uri the rest of the address."), 1, 2);
        GridPane statusFields = grid();
        statusFields.addRow(0, new Label("Status code"), status);
        statusFields.addRow(1, new Label("Reply text"), reply);
        for (GridPane g : List.of(rewriteFields, redirectFields, statusFields)) {
            g.getColumnConstraints().addAll(new javafx.scene.layout.ColumnConstraints(130),
                    new javafx.scene.layout.ColumnConstraints(200, 380, Double.MAX_VALUE));
            javafx.scene.layout.GridPane.setHgrow(g, javafx.scene.layout.Priority.ALWAYS);
        }

        Supplier<Rewrite> current = () -> {
            RewriteKind kind = (RewriteKind) kinds.getSelectedToggle().getUserData();
            return switch (kind) {
                case REWRITE -> Rewrite.rewrite(pattern.getText().strip(), replacement.getText().strip(),
                        flag.getValue() == null ? "" : flag.getValue().value());
                case REDIRECT -> Rewrite.redirect(code.getValue().value(), target.getText().strip());
                case STATUS -> Rewrite.status(status.getText().strip(), reply.getText().strip());
            };
        };
        VBox content = new VBox(10, preset, rewriteKind, redirectKind, statusKind, rewriteFields, redirectFields, statusFields);
        Form form = new Form(owner, initial.isBlank() ? "Add Rewrite Rule" : "Edit Rewrite Rule",
                "Rules run in order, top to bottom, for every request to this site.", content,
                () -> LineEntries.formatRewrite(current.get()), () -> LineEntries.rewriteProblems(current.get()));
        Runnable showFields = () -> {
            RewriteKind kind = (RewriteKind) kinds.getSelectedToggle().getUserData();
            show(rewriteFields, kind == RewriteKind.REWRITE);
            show(redirectFields, kind == RewriteKind.REDIRECT);
            show(statusFields, kind == RewriteKind.STATUS);
            form.dialog.getDialogPane().getScene().getWindow().sizeToScene();
        };
        kinds.selectedToggleProperty().addListener((obs, o, n) -> {
            showFields.run();
            form.refresh();
        });
        preset.valueProperty().addListener((obs, o, n) -> {
            switch (n.value()) {
                case "https" -> {
                    redirectKind.setSelected(true);
                    code.getSelectionModel().select(0);
                    target.setText("https://$host$request_uri");
                }
                case "move" -> {
                    rewriteKind.setSelected(true);
                    pattern.setText("^/old-page$");
                    replacement.setText("/new-page");
                    flag.getSelectionModel().select(4);
                }
                case "domain" -> {
                    redirectKind.setSelected(true);
                    code.getSelectionModel().select(0);
                    target.setText("https://new-domain.example$request_uri");
                }
                case "gone" -> {
                    statusKind.setSelected(true);
                    status.setText("410");
                }
                case "block" -> {
                    statusKind.setSelected(true);
                    status.setText("403");
                }
                default -> { }
            }
            form.refresh();
        });
        form.watch(pattern, replacement, flag, code, target, status, reply);
        form.dialog.setOnShown(e -> showFields.run());
        show(rewriteFields, start.kind() == RewriteKind.REWRITE);
        show(redirectFields, start.kind() == RewriteKind.REDIRECT);
        show(statusFields, start.kind() == RewriteKind.STATUS);
        return form.show();
    }

    private static void show(Node n, boolean visible) {
        n.setVisible(visible);
        n.setManaged(visible);
    }

    // ------------------------------------------------------------------ limit_req

    static Optional<String> limitReq(Window owner, String initial, Collection<String> zones) {
        LimitReq start = initial.isBlank() ? new LimitReq("", "10", true, "", List.of()) : LineEntries.parseLimitReq(initial);
        if (start == null) {
            return raw(owner, "Request limit", initial);
        }
        ComboBox<String> zone = zoneBox(zones, start.zone());
        TextField burst = new TextField(start.burst());
        burst.setPrefColumnCount(5);
        CheckBox nodelay = new CheckBox("Serve burst requests straight away (nodelay)");
        nodelay.setSelected(start.nodelay());
        GridPane g = grid();
        g.addRow(0, new Label("Rate zone"), zone);
        g.add(hint("The zone sets how many requests per second each visitor may make. Create zones under Rate Limits."), 1, 1);
        g.addRow(2, new Label("Allow a burst of"), new HBox(8, burst, new Label("extra requests")));
        g.add(hint("Requests above the rate wait in a queue of this size instead of being rejected. Leave empty or 0 "
                + "to reject them at once."), 1, 3);
        g.add(nodelay, 1, 4);
        Supplier<LimitReq> current = () -> new LimitReq(text(zone), burst.getText().strip(), nodelay.isSelected(),
                start.delay(), start.extra());
        Form form = new Form(owner, initial.isBlank() ? "Add Request Limit" : "Edit Request Limit",
                "Slow down visitors who send too many requests (limit_req).", g,
                () -> LineEntries.formatLimitReq(current.get()),
                () -> LineEntries.limitReqProblems(text(zone), burst.getText().strip()));
        form.watch(zone, burst, nodelay);
        return form.show();
    }

    // ------------------------------------------------------------------ limit_conn

    static Optional<String> limitConn(Window owner, String initial, Collection<String> zones) {
        LimitConn start = initial.isBlank() ? new LimitConn("", "10") : LineEntries.parseLimitConn(initial);
        if (start == null) {
            return raw(owner, "Connection limit", initial);
        }
        ComboBox<String> zone = zoneBox(zones, start.zone());
        TextField max = new TextField(start.max());
        max.setPrefColumnCount(5);
        GridPane g = grid();
        g.addRow(0, new Label("Connection zone"), zone);
        g.addRow(1, new Label("At most"), new HBox(8, max, new Label("connections at once")));
        g.add(hint("Each visitor (as the zone defines them) may hold this many connections open at the same time. "
                + "Create zones under Rate Limits."), 1, 2);
        Form form = new Form(owner, initial.isBlank() ? "Add Connection Limit" : "Edit Connection Limit",
                "Stop one visitor from holding too many connections (limit_conn).", g,
                () -> LineEntries.formatLimitConn(new LimitConn(text(zone), max.getText().strip())),
                () -> LineEntries.limitConnProblems(text(zone), max.getText().strip()));
        form.watch(zone, max);
        return form.show();
    }

    // ------------------------------------------------------------------ access_log

    /**
     * @param formats the log formats to offer: nginx's {@code combined} and those defined on the server (see Log
     *                Formats); a name typed in that isn't among them is still accepted
     */
    static Optional<String> accessLog(Window owner, String initial, String suggestedName, List<String> formats,
                                      ServerAccess access) {
        AccessLog start = initial.isBlank()
                ? new AccessLog(false, "/var/log/nginx/" + suggestedName + ".access.log", "combined", List.of())
                : LineEntries.parseAccessLog(initial);
        if (start == null) {
            return raw(owner, "Access log", initial);
        }
        CheckBox off = new CheckBox("Don't keep an access log");
        off.setSelected(start.off());
        TextField path = new TextField(start.path());
        path.setPromptText("/var/log/nginx/site.access.log");
        ComboBox<String> format = new ComboBox<>(FXCollections.observableArrayList(formats));
        format.setEditable(true);
        format.setValue(start.format());
        GridPane g = grid();
        g.add(off, 1, 0);
        javafx.scene.layout.HBox pathRow = FileTransferLinks.beside(path, () -> access, true);
        g.addRow(1, new Label("Log file"), pathRow);
        g.addRow(2, new Label("Format"), format);
        g.add(hint("\"combined\" is nginx's standard format. The others are the formats defined on this server; make "
                + "your own under Log Formats."), 1, 3);
        g.getColumnConstraints().addAll(new javafx.scene.layout.ColumnConstraints(90),
                new javafx.scene.layout.ColumnConstraints(200, 560, Double.MAX_VALUE));
        pathRow.disableProperty().bind(off.selectedProperty());
        format.disableProperty().bind(off.selectedProperty());
        Supplier<AccessLog> current = () -> new AccessLog(off.isSelected(), path.getText().strip(), text(format), start.extra());
        Form form = new Form(owner, initial.isBlank() ? "Add Access Log" : "Edit Access Log",
                "Where to record each visit to this site.", g, () -> LineEntries.formatAccessLog(current.get()),
                () -> LineEntries.accessLogProblems(current.get()));
        form.watch(off, path, format);
        return form.show();
    }

    // ------------------------------------------------------------------ error_page

    static Optional<String> errorPage(Window owner, String initial) {
        ErrorPage start = initial.isBlank() ? new ErrorPage(List.of("404"), "", "/404.html") : LineEntries.parseErrorPage(initial);
        if (start == null) {
            return raw(owner, "Error page", initial);
        }
        List<String> common = List.of("400", "401", "403", "404", "429", "500", "502", "503", "504");
        List<CheckBox> boxes = new ArrayList<>();
        FlowPane flow = new FlowPane(10, 6);
        flow.setPrefWrapLength(460);
        for (String c : common) {
            CheckBox b = new CheckBox(LineEntries.statusName(c));
            b.setSelected(start.codes().contains(c));
            b.setUserData(c);
            boxes.add(b);
            flow.getChildren().add(b);
        }
        List<String> others = new ArrayList<>(start.codes());
        others.removeAll(common);
        TextField more = new TextField(String.join(" ", others));
        more.setPromptText("other codes, e.g. 405 410");
        TextField target = new TextField(start.target());
        target.setPromptText("/404.html");
        TextField response = new TextField(start.responseCode());
        response.setPromptText("optional, e.g. 200");
        response.setPrefColumnCount(6);
        GridPane g = grid();
        g.addRow(0, new Label("When the server answers"), flow);
        g.addRow(1, new Label("Other codes"), more);
        g.addRow(2, new Label("Show this page"), target);
        g.add(hint("A page on this site (/404.html), a named location (@fallback) or a full address (https://...)."), 1, 3);
        g.addRow(4, new Label("Change the status to"), response);
        g.getColumnConstraints().addAll(new javafx.scene.layout.ColumnConstraints(150),
                new javafx.scene.layout.ColumnConstraints(200, 400, Double.MAX_VALUE));
        Supplier<List<String>> codes = () -> {
            List<String> all = new ArrayList<>();
            for (CheckBox b : boxes) {
                if (b.isSelected()) {
                    all.add((String) b.getUserData());
                }
            }
            for (String c : more.getText().strip().split("[\\s,]+")) {
                if (!c.isEmpty() && !all.contains(c)) {
                    all.add(c);
                }
            }
            return all;
        };
        Supplier<List<String>> check = () -> {
            List<String> problems = new ArrayList<>();
            for (String c : codes.get()) {
                if (!c.matches("\\d{3}")) {
                    problems.add("\"" + c + "\" is not a three-digit status code.");
                }
            }
            if (!response.getText().isBlank() && !response.getText().strip().matches("\\d{3}")) {
                problems.add("The changed status must be a three-digit code.");
            }
            if (problems.isEmpty()) {
                problems.addAll(LineEntries.errorPageProblems(new ErrorPage(codes.get(), response.getText().strip(),
                        target.getText().strip())));
            }
            return problems;
        };
        Form form = new Form(owner, initial.isBlank() ? "Add Error Page" : "Edit Error Page",
                "Show your own page when the server would otherwise show its default error.", g,
                () -> LineEntries.formatErrorPage(new ErrorPage(codes.get(), response.getText().strip(),
                        target.getText().strip())), check);
        form.watch(more, target, response);
        form.watch(boxes.toArray(new Node[0]));
        return form.show();
    }

    // ------------------------------------------------------------------ listen

    static Optional<String> listen(Window owner, String initial) {
        Listen start = initial.isBlank() ? new Listen("", "80", false, false, false, List.of()) : LineEntries.parseListen(initial);
        if (start == null) {
            return raw(owner, "Listen", initial);
        }
        ComboBox<Choice> preset = new ComboBox<>(FXCollections.observableArrayList(
                new Choice("", "Start from a common case..."),
                new Choice("http", "Web site (HTTP, port 80)"),
                new Choice("https", "Secure web site (HTTPS, port 443, with HTTP/2)"),
                new Choice("http6", "Web site over IPv6 (port 80)"),
                new Choice("https6", "Secure web site over IPv6 (port 443)")));
        preset.getSelectionModel().selectFirst();
        ComboBox<Choice> where = new ComboBox<>(FXCollections.observableArrayList(
                new Choice("4", "All IPv4 addresses (the usual choice)"),
                new Choice("6", "All IPv6 addresses"),
                new Choice("ip", "One specific address...")));
        boolean specific = !start.host().isEmpty() && !start.host().equals("[::]");
        where.getSelectionModel().select(specific ? 2 : start.host().equals("[::]") ? 1 : 0);
        TextField address = new TextField(specific ? start.host() : "");
        address.setPromptText("192.0.2.10");
        address.disableProperty().bind(where.valueProperty().isNotEqualTo(where.getItems().get(2)));
        TextField port = new TextField(start.port());
        port.setPrefColumnCount(6);
        CheckBox ssl = new CheckBox("Secure connection (HTTPS)");
        ssl.setSelected(start.ssl());
        CheckBox http2 = new CheckBox("Allow HTTP/2 (faster page loading; needs HTTPS)");
        http2.setSelected(start.http2());
        http2.disableProperty().bind(ssl.selectedProperty().not());
        CheckBox def = new CheckBox("Answer requests that match no other site (default_server)");
        def.setSelected(start.defaultServer());
        GridPane g = grid();
        g.addRow(0, new Label("Listen on"), where);
        g.addRow(1, new Label("Address"), address);
        g.addRow(2, new Label("Port"), new HBox(8, port, new Label("80 for HTTP, 443 for HTTPS")));
        g.add(ssl, 1, 3);
        g.add(http2, 1, 4);
        g.add(def, 1, 5);
        g.add(hint("Only one site per port should be the default. It receives requests whose address doesn't match any "
                + "site's server name, such as visitors who use the server's IP address. Leave it off for normal sites."), 1, 6);
        Supplier<Listen> current = () -> new Listen(
                where.getValue().value().equals("6") ? "[::]" : where.getValue().value().equals("ip") ? address.getText().strip() : "",
                port.getText().strip(), ssl.isSelected(), ssl.isSelected() && http2.isSelected(), def.isSelected(), start.extra());
        Supplier<List<String>> check = () -> {
            List<String> problems = new ArrayList<>(LineEntries.listenProblems(current.get()));
            if (where.getValue().value().equals("ip") && address.getText().isBlank()) {
                problems.add("Enter the address to listen on.");
            }
            return problems;
        };
        Form form = new Form(owner, initial.isBlank() ? "Add Listener" : "Edit Listener",
                "Which address and port this site answers on.", new VBox(10, preset, g),
                () -> LineEntries.formatListen(current.get()), check);
        preset.valueProperty().addListener((obs, o, n) -> {
            switch (n.value()) {
                case "http", "http6" -> {
                    where.getSelectionModel().select(n.value().equals("http") ? 0 : 1);
                    port.setText("80");
                    ssl.setSelected(false);
                }
                case "https", "https6" -> {
                    where.getSelectionModel().select(n.value().equals("https") ? 0 : 1);
                    port.setText("443");
                    ssl.setSelected(true);
                    http2.setSelected(true);
                }
                default -> { }
            }
            form.refresh();
        });
        where.valueProperty().addListener((obs, o, n) -> form.refresh());
        ssl.selectedProperty().addListener((obs, o, n) -> form.refresh());
        form.watch(address, port, http2, def);
        return form.show();
    }
}
