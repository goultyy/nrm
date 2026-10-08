package mt.su.nrm.ui;

import mt.su.nrm.nginx.LocationSettings;
import mt.su.nrm.nginx.LocationSettings.Type;
import mt.su.nrm.nginx.Units;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * The Locations tab of the virtual host editor, and the location editor itself: the list of
 * locations on the left; on the right the selected one's settings in tabs for what it does
 * (static files, reverse proxy, PHP / FastCGI or redirect), who may use it, limits, compression, caching and
 * error pages.
 */
final class LocationsPane extends BorderPane {

    private static final List<String> MODIFIERS = List.of("(prefix)", "=", "~", "~*", "^~");

    private final ObservableList<LocationSettings> items = FXCollections.observableArrayList();
    private final ListView<LocationSettings> list = new ListView<>(items);
    private final Runnable onChange;

    // Handling
    private final ComboBox<String> modifier = new ComboBox<>(FXCollections.observableArrayList(MODIFIERS));
    private final TextField path = new TextField();
    private final ComboBox<Type> type = new ComboBox<>(FXCollections.observableArrayList(Type.values()));
    private final TextField root = new TextField();
    private final TextField alias = new TextField();
    private final TextField index = new TextField();
    private final TextField tryFiles = new TextField();
    private final ComboBox<String> proxyBox = new ComboBox<>();
    private final TextField proxyPass = proxyBox.getEditor();
    private final TextArea proxyHeaders = area(4, "One header per line, e.g.\nHost $host\nX-Real-IP $remote_addr");
    private final TextField proxyReadTimeout = new TextField();
    private final ComboBox<String> redirectCode =
            new ComboBox<>(FXCollections.observableArrayList("301", "302", "303", "307", "308"));
    private final TextField redirectTarget = new TextField();
    private final ComboBox<String> fastcgiBox = new ComboBox<>();
    private final TextField fastcgiPass = fastcgiBox.getEditor();
    private final TextField fastcgiInclude = new TextField();
    private final TextArea fastcgiParams = area(3, "One per line, e.g.\nSCRIPT_FILENAME $document_root$fastcgi_script_name");
    private final TextField fastcgiReadTimeout = new TextField();
    private final TextField phpTryFiles = new TextField();

    // Access
    private final TextField authBasic = new TextField();
    private final TextField authBasicUserFile = new TextField();
    private final TextArea accessRules = area(4, "One per line, first match wins, e.g.\nallow 10.0.0.0/8\ndeny all");

    // Limits, compression, caching, errors
    private final TextArea limitReq = area(3, "One per line, e.g. zone=perip burst=10 nodelay");
    private final TextArea limitConn = area(3, "One per line, e.g. perip 10");
    private final ComboBox<String> gzip = new ComboBox<>(FXCollections.observableArrayList("(not set)", "on", "off"));
    private final TextField gzipTypes = new TextField();
    private final TextField gzipMinLength = new TextField();
    private final TextField gzipCompLevel = new TextField();
    private final ComboBox<String> cacheBox = new ComboBox<>();
    /** The Caching tab's content; disabled unless the location is a reverse proxy. */
    private GridPane cachingGrid;
    private final TextField proxyCache = cacheBox.getEditor();
    private final ZoneNames zones;
    private final TextArea proxyCacheValid = area(3, "One per line, e.g. 200 302 10m");
    private final TextArea errorPages = area(3, "One per line, e.g. 502 503 /down.html");

    private final GridPane staticFields = grid();
    private final GridPane proxyFields = grid();
    private final GridPane redirectFields = grid();
    private final GridPane fastcgiFields = grid();
    private final TabPane detail = new TabPane();

    /** True while the fields are being filled from a location, so that doesn't count as an edit. */
    private boolean loading;

    private ServerAccess serverAccess = ServerAccess.NONE;
    private java.util.function.Supplier<String> siteName = () -> "";

    /** Connects the access wizard to the server and to the name of the site being edited. */
    void useServer(ServerAccess access, java.util.function.Supplier<String> site) {
        this.serverAccess = access;
        this.siteName = site;
    }

    LocationsPane(List<LocationSettings> locations, ZoneNames zones, Runnable onChange) {
        this.zones = zones;
        this.onChange = onChange;
        items.setAll(locations);

        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(LocationSettings l, boolean empty) {
                super.updateItem(l, empty);
                setText(empty || l == null ? null : l.title() + "   -   " + l.type);
            }
        });
        list.setPlaceholder(new Label("No locations. Add one below."));
        list.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> show(n));

        Button addProxy = new Button("Add proxy");
        addProxy.setOnAction(e -> add(LocationSettings.newProxy("/", "http://127.0.0.1:3000")));
        Button addStatic = new Button("Add static");
        addStatic.setOnAction(e -> add(LocationSettings.newStatic("/")));
        Button addRedirect = new Button("Add redirect");
        addRedirect.setOnAction(e -> add(LocationSettings.newRedirect("/", "https://example.com$request_uri")));
        Button addPhp = new Button("Add PHP");
        addPhp.setOnAction(e -> addPhp());
        Button remove = new Button("Remove");
        remove.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        remove.setOnAction(e -> {
            LocationSettings selected = list.getSelectionModel().getSelectedItem();
            if (selected != null) {
                items.remove(selected);
                this.onChange.run();
            }
        });
        FlowPane buttons = new FlowPane(6, 6, addProxy, addStatic, addPhp, addRedirect, remove);
        VBox left = new VBox(8, list, buttons);
        left.setPadding(new Insets(8));
        VBox.setVgrow(list, Priority.ALWAYS);

        buildDetail();
        SplitPane split = new SplitPane(left, detail);
        split.setDividerPositions(0.34);
        setCenter(split);
        show(null);
    }

    /** The locations as currently edited, in order. */
    List<LocationSettings> locations() {
        return new ArrayList<>(items);
    }

    private void add(LocationSettings l) {
        items.add(l);
        list.getSelectionModel().select(l);
        onChange.run();
    }

    /**
     * Adds a PHP location. Connected, it first looks at the server: if PHP-FPM is missing or stopped it
     * offers to install and start it, then fills in the address it found. Not connected, it adds the
     * usual Debian/Ubuntu defaults for the user to adjust.
     */
    private void addPhp() {
        if (!serverAccess.connected()) {
            add(LocationSettings.newPhp("unix:/run/php/php-fpm.sock", "snippets/fastcgi-php.conf"));
            return;
        }
        javafx.stage.Window owner = getScene().getWindow();
        serverAccess.phpStatus(owner, status -> {
            if (status == null) {
                return;
            }
            if (status.ready()) {
                addPhpFrom(status);
                return;
            }
            String problem = status.installed()
                    ? "PHP-FPM is installed on this server but isn't running."
                    : "PHP-FPM isn't installed on this server.";
            if (status.packageManager().isEmpty()) {
                Dialogs.error(owner, "PHP is not ready", problem + " This server's package manager wasn't recognised, "
                        + "so NRM can't install it. Install PHP-FPM on the server, then try again.");
                return;
            }
            String action = status.installed() ? "Start PHP-FPM now?" : "Install PHP-FPM with " + status.packageManager()
                    + " and start it now? This runs as root on the server and is shown in the command log.";
            if (!Dialogs.confirm(owner, problem, action, status.installed() ? "Start" : "Install")) {
                return;
            }
            serverAccess.installPhp(owner, status.packageManager(), ok -> {
                if (ok) {
                    serverAccess.phpStatus(owner, after -> {
                        if (after != null) {
                            addPhpFrom(after);
                        }
                    });
                }
            });
        });
    }

    /** Fills the PHP address drop-down with what the server has. */
    private void detectPhpAddresses() {
        serverAccess.phpStatus(getScene().getWindow(), status -> {
            if (status == null) {
                return;
            }
            fastcgiBox.getItems().setAll(status.endpoints());
            if (status.endpoints().isEmpty()) {
                Dialogs.info(getScene().getWindow(), "No PHP address found", status.installed()
                        ? "PHP-FPM is installed but no socket or port 9000 listener was found. Is it running?"
                        : "PHP-FPM isn't installed on this server. Use Add PHP to install it.");
            } else {
                fastcgiBox.show();
            }
        });
    }

    private void addPhpFrom(mt.su.nrm.ssh.PhpService.Status status) {
        String endpoint = status.preferredEndpoint();
        if (endpoint.isEmpty()) {
            Dialogs.error(getScene().getWindow(), "PHP address not found", "PHP-FPM is running but NRM couldn't find "
                    + "where it listens. A location was added with a common default; check the address on the Handling tab.");
            endpoint = "unix:/run/php/php-fpm.sock";
        }
        add(LocationSettings.newPhp(endpoint, status.snippet()));
        fastcgiBox.getItems().setAll(status.endpoints());
    }

    // ---------------------------------------------------------------- building

    private void buildDetail() {
        modifier.setValue(MODIFIERS.get(0));
        redirectCode.setValue("301");
        gzip.setValue("(not set)");
        proxyPass.setPromptText("http://127.0.0.1:3000 or a load balancing group");
        proxyBox.setEditable(true);
        for (String u : sorted(zones.upstreams())) {
            proxyBox.getItems().add("http://" + u);
        }
        cacheBox.setEditable(true);
        cacheBox.getItems().add("off");
        cacheBox.getItems().addAll(sorted(zones.cache()));
        tryFiles.setPromptText("$uri $uri/ =404");
        redirectTarget.setPromptText("https://example.com$request_uri");
        proxyReadTimeout.setPromptText("60s");
        authBasic.setPromptText("realm shown in the login box, or off");
        authBasicUserFile.setPromptText("/etc/nginx/.htpasswd");
        gzipTypes.setPromptText("text/css application/javascript application/json");
        gzipMinLength.setPromptText("256");
        gzipCompLevel.setPromptText("1 - 9");
        proxyCache.setPromptText("choose a cache zone, or off");

        GridPane general = grid();
        general.addRow(0, new Label("Match"), modifier);
        general.addRow(1, new Label("Path"), path);
        general.addRow(2, new Label("Does"), type);
        staticFields.addRow(0, new Label("Root folder"), FileTransferLinks.beside(root, () -> serverAccess, false));
        staticFields.addRow(1, new Label("Alias folder"), FileTransferLinks.beside(alias, () -> serverAccess, false));
        staticFields.addRow(2, new Label("Index files"), index);
        staticFields.addRow(3, new Label("If a file is missing"), new TryFilesEditor(tryFiles, TryFilesEditor.STATIC_PRESETS));
        proxyFields.addRow(0, new Label("Forward to"), proxyBox);
        proxyFields.addRow(1, new Label("Read timeout"), proxyReadTimeout);
        proxyFields.addRow(2, new Label("Headers"), proxyHeaders);
        GridPane.setValignment(proxyFields.getChildren().get(4), VPos.TOP);
        redirectFields.addRow(0, new Label("Status"), redirectCode);
        redirectFields.addRow(1, new Label("Redirect to"), redirectTarget);
        fastcgiBox.setEditable(true);
        fastcgiPass.setPromptText("unix:/run/php/php8.2-fpm.sock or 127.0.0.1:9000");
        fastcgiInclude.setPromptText("snippets/fastcgi-php.conf or fastcgi_params");
        phpTryFiles.setPromptText("$uri =404 (only run scripts that exist)");
        fastcgiReadTimeout.setPromptText("60s");
        Button detectPhp = new Button("Detect");
        detectPhp.setOnAction(e -> {
            if (!serverAccess.connected()) {
                Dialogs.info(getScene().getWindow(), "Not connected", "Connect to the server to look for PHP-FPM.");
                return;
            }
            detectPhpAddresses();
        });
        HBox fastcgiAddress = new HBox(6, fastcgiBox, detectPhp);
        HBox.setHgrow(fastcgiBox, Priority.ALWAYS);
        fastcgiBox.setMaxWidth(Double.MAX_VALUE);
        fastcgiFields.addRow(0, new Label("PHP address"), fastcgiAddress);
        fastcgiFields.addRow(1, new Label("Include file"), fastcgiInclude);
        fastcgiFields.addRow(2, new Label("If a script is missing"), new TryFilesEditor(phpTryFiles, TryFilesEditor.PHP_PRESETS));
        fastcgiFields.addRow(3, new Label("Read timeout"), fastcgiReadTimeout);
        fastcgiFields.addRow(4, new Label("Parameters"), fastcgiParams);
        GridPane.setValignment(fastcgiFields.getChildren().get(8), VPos.TOP);
        VBox handling = new VBox(14, general, staticFields, proxyFields, fastcgiFields, redirectFields);
        handling.setPadding(new Insets(4));

        Button accessWizard = new Button("Set up access control");
        accessWizard.setOnAction(e -> {
            LocationSettings sel = list.getSelectionModel().getSelectedItem();
            if (sel == null) {
                return;
            }
            edited();
            AccessWizard.run(getScene().getWindow(), sel, siteName.get(), serverAccess, policy -> {
                policy.applyTo(sel);
                show(sel);
                onChange.run();
            });
        });
        GridPane access = grid();
        access.add(new VBox(4, accessWizard, WizardParts.hint("The easy way: choose who may open this location. "
                + "The fields below are the raw settings, for experts.")), 0, 0, 2, 1);
        access.addRow(1, new Label("Login realm"), authBasic);
        access.addRow(2, new Label("Password file"), FileTransferLinks.beside(authBasicUserFile, () -> serverAccess, true));
        access.addRow(3, top("Allow / deny"), accessRules);

        LineListEditor limitReqList = new LineListEditor(limitReq,
                "Protect this location from being overwhelmed. Requests over a zone's rate are rejected, or queued if you allow a burst.",
                LineEntries::describeLimitReq, (w, l) -> EntryDialogs.limitReq(w, l, zones.request()));
        LineListEditor limitConnList = new LineListEditor(limitConn,
                "Stop one visitor from holding too many connections open.",
                LineEntries::describeLimitConn, (w, l) -> EntryDialogs.limitConn(w, l, zones.connection()));
        limitReqList.compact();
        limitConnList.compact();
        Label reqHeading = new Label("Request limits");
        reqHeading.setStyle("-fx-font-weight: bold;");
        Label connHeading = new Label("Connection limits");
        connHeading.setStyle("-fx-font-weight: bold;");
        VBox limits = new VBox(6, reqHeading, limitReqList, connHeading, limitConnList);

        GridPane compression = grid();
        compression.addRow(0, new Label("Gzip"), gzip);
        compression.addRow(1, new Label("Types"), gzipTypes);
        compression.addRow(2, new Label("Minimum length"), gzipMinLength);
        compression.addRow(3, new Label("Level"), gzipCompLevel);

        GridPane caching = grid();
        int cr = 0;
        Label cacheIntro = new Label("Store copies of the backend's responses so repeat requests are answered by nginx "
                + "without asking the backend again. Caching only works on reverse proxy locations (it is switched off for static "
                + "files, PHP and redirects, which nginx serves directly) and uses a zone from Cache Zones.");
        cacheIntro.setWrapText(true);
        cacheIntro.setOpacity(0.8);
        cachingGrid = caching;
        caching.add(cacheIntro, 0, cr++, 2, 1);
        caching.addRow(cr++, new Label("Cache zone"), cacheBox);
        caching.add(hint(zones.cache().isEmpty()
                ? "No cache zones are defined yet. Create one under Cache Zones first."
                : "Choose a zone, or off to switch caching off for this location."), 1, cr++);

        ComboBox<String> responses = new ComboBox<>(FXCollections.observableArrayList(
                "Successful responses (200 301 302)", "Not found (404)", "Any response"));
        responses.setValue(responses.getItems().get(0));
        TextField cacheAmount = new TextField("10");
        cacheAmount.setPrefColumnCount(4);
        ComboBox<Units.TimeUnit> cacheUnit = new ComboBox<>(FXCollections.observableArrayList(Units.TimeUnit.values()));
        cacheUnit.setValue(Units.TimeUnit.MINUTES);
        Button addValid = new Button("Add cache time");
        addValid.setOnAction(e -> {
            String a = cacheAmount.getText().strip();
            if (a.matches("[1-9]\\d*") && cacheUnit.getValue() != null) {
                String codes = responses.getValue().startsWith("Successful") ? "200 301 302 "
                        : responses.getValue().startsWith("Not found") ? "404 " : "any ";
                append(proxyCacheValid, codes + new Units.Duration(Long.parseLong(a), cacheUnit.getValue()).format());
            }
        });
        caching.addRow(cr++, new Label("Keep fresh for"), new HBox(8, responses, cacheAmount, cacheUnit, addValid));
        caching.add(hint("How long a stored response is served without checking the backend again. "
                + "Add a line for each kind of response you want cached."), 1, cr++);
        caching.addRow(cr, top("Cache times in use"), proxyCacheValid);

        LineListEditor errors = new LineListEditor(errorPages,
                "Show your own page when this location would otherwise show a plain error such as 404 or 502.",
                LineEntries::describeErrorPage, EntryDialogs::errorPage);

        detail.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        detail.getTabs().addAll(tab("Handling", handling), tab("Access", access), tab("Limits", limits),
                tab("Compression", compression), tab("Caching", caching), tab("Errors", errors));

        for (TextField f : List.of(path, root, alias, index, tryFiles, proxyPass, proxyReadTimeout, redirectTarget,
                authBasic, authBasicUserFile, gzipTypes, gzipMinLength, gzipCompLevel, proxyCache, fastcgiPass,
                fastcgiInclude, fastcgiReadTimeout, phpTryFiles)) {
            f.textProperty().addListener((obs, o, n) -> edited());
        }
        for (TextArea a : List.of(proxyHeaders, fastcgiParams, accessRules, limitReq, limitConn, proxyCacheValid, errorPages)) {
            a.textProperty().addListener((obs, o, n) -> edited());
        }
        modifier.valueProperty().addListener((obs, o, n) -> edited());
        redirectCode.valueProperty().addListener((obs, o, n) -> edited());
        gzip.valueProperty().addListener((obs, o, n) -> edited());
        type.valueProperty().addListener((obs, o, n) -> {
            showTypeFields(n);
            edited();
        });
    }

    private static Label hint(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setOpacity(0.7);
        return l;
    }

    private static List<String> sorted(java.util.Collection<String> names) {
        List<String> sorted = new ArrayList<>(names);
        java.util.Collections.sort(sorted);
        return sorted;
    }

    /** Adds a line to a list-style text area. */
    private static void append(TextArea area, String line) {
        String current = area.getText();
        area.setText(current == null || current.isBlank() ? line : current.stripTrailing() + "\n" + line);
    }

    private static Label top(String text) {
        Label l = new Label(text);
        GridPane.setValignment(l, VPos.TOP);
        return l;
    }

    private static Tab tab(String title, Node content) {
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(content);
        scroll.setFitToWidth(true);
        Tab t = new Tab(title, scroll);
        t.setClosable(false);
        return t;
    }

    private static TextArea area(int rows, String prompt) {
        TextArea a = new TextArea();
        a.setPrefRowCount(rows);
        a.setPromptText(prompt);
        a.setStyle("-fx-font-family: 'Consolas', 'Menlo', monospace;");
        return a;
    }

    // ---------------------------------------------------------------- data flow

    private void show(LocationSettings l) {
        loading = true;
        try {
            detail.setDisable(l == null);
            if (l == null) {
                showTypeFields(null);
                return;
            }
            modifier.setValue(l.modifier.isEmpty() ? MODIFIERS.get(0) : l.modifier);
            path.setText(l.path);
            type.setValue(l.type);
            root.setText(l.root);
            alias.setText(l.alias);
            index.setText(l.index);
            tryFiles.setText(l.tryFiles);
            proxyPass.setText(l.proxyPass);
            proxyReadTimeout.setText(l.proxyReadTimeout);
            proxyHeaders.setText(String.join("\n", l.proxySetHeaders));
            phpTryFiles.setText(l.tryFiles);
            fastcgiPass.setText(l.fastcgiPass);
            fastcgiInclude.setText(l.fastcgiInclude);
            fastcgiParams.setText(String.join("\n", l.fastcgiParams));
            fastcgiReadTimeout.setText(l.fastcgiReadTimeout);
            redirectCode.setValue(l.redirectCode.isEmpty() ? "301" : l.redirectCode);
            redirectTarget.setText(l.redirectTarget);
            authBasic.setText(l.authBasic);
            authBasicUserFile.setText(l.authBasicUserFile);
            accessRules.setText(String.join("\n", l.accessRules));
            limitReq.setText(String.join("\n", l.limitReq));
            limitConn.setText(String.join("\n", l.limitConn));
            gzip.setValue(l.gzip.isEmpty() ? "(not set)" : l.gzip);
            gzipTypes.setText(l.gzipTypes);
            gzipMinLength.setText(l.gzipMinLength);
            gzipCompLevel.setText(l.gzipCompLevel);
            proxyCache.setText(l.proxyCache);
            proxyCacheValid.setText(String.join("\n", l.proxyCacheValid));
            errorPages.setText(String.join("\n", l.errorPages));
            showTypeFields(l.type);
        } finally {
            loading = false;
        }
    }

    private void showTypeFields(Type t) {
        setVisible(staticFields, t == Type.STATIC);
        setVisible(proxyFields, t == Type.PROXY);
        setVisible(fastcgiFields, t == Type.FASTCGI);
        setVisible(redirectFields, t == Type.REDIRECT);
        // proxy_cache only works when nginx forwards the request; on a static, PHP or redirect location it does nothing.
        if (cachingGrid != null) {
            cachingGrid.setDisable(t != Type.PROXY);
        }
    }

    private static void setVisible(GridPane pane, boolean visible) {
        pane.setVisible(visible);
        pane.setManaged(visible);
    }

    /** Copies the fields into the selected location. */
    private void edited() {
        LocationSettings l = list.getSelectionModel().getSelectedItem();
        if (loading || l == null) {
            return;
        }
        l.modifier = MODIFIERS.get(0).equals(modifier.getValue()) || modifier.getValue() == null ? "" : modifier.getValue();
        l.path = path.getText().strip();
        l.type = type.getValue() == null ? l.type : type.getValue();
        l.root = root.getText().strip();
        l.alias = alias.getText().strip();
        l.index = index.getText().strip();
        l.tryFiles = (l.type == Type.FASTCGI ? phpTryFiles : tryFiles).getText().strip();
        l.fastcgiPass = fastcgiPass.getText().strip();
        l.fastcgiInclude = fastcgiInclude.getText().strip();
        l.fastcgiParams = VhostForm.entries(fastcgiParams.getText());
        l.fastcgiReadTimeout = fastcgiReadTimeout.getText().strip();
        l.proxyPass = proxyPass.getText().strip();
        l.proxyReadTimeout = proxyReadTimeout.getText().strip();
        l.proxySetHeaders = VhostForm.entries(proxyHeaders.getText());
        l.redirectCode = redirectCode.getValue() == null ? "301" : redirectCode.getValue();
        l.redirectTarget = redirectTarget.getText().strip();
        l.authBasic = authBasic.getText().strip();
        l.authBasicUserFile = authBasicUserFile.getText().strip();
        l.accessRules = VhostForm.entries(accessRules.getText());
        l.limitReq = VhostForm.entries(limitReq.getText());
        l.limitConn = VhostForm.entries(limitConn.getText());
        l.gzip = gzip.getValue() == null || gzip.getValue().startsWith("(") ? "" : gzip.getValue();
        l.gzipTypes = gzipTypes.getText().strip();
        l.gzipMinLength = gzipMinLength.getText().strip();
        l.gzipCompLevel = gzipCompLevel.getText().strip();
        l.proxyCache = proxyCache.getText().strip();
        l.proxyCacheValid = VhostForm.entries(proxyCacheValid.getText());
        l.errorPages = VhostForm.entries(errorPages.getText());
        list.refresh();
        onChange.run();
    }

    private static GridPane grid() {
        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(8);
        g.setPadding(new Insets(8));
        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(110);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        g.getColumnConstraints().addAll(labels, fields);
        return g;
    }
}
