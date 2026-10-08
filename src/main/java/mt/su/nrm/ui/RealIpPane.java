package mt.su.nrm.ui;

import mt.su.nrm.nginx.RealIp;
import mt.su.nrm.nginx.VhostSettings.RealIpSpec;
import mt.su.nrm.proxy.ProxyPreset;
import mt.su.nrm.proxy.ProxyPresets;
import mt.su.nrm.ssh.SshExecutor;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * The Real IP part of a site: which proxy reaches this site, so nginx logs and limits the visitor instead of the proxy.
 * Different sites can sit behind different proxies, so each has its own settings (starting from a preset for the kind
 * of proxy); a site without its own uses the http-level ones, which the pane shows. nginx allows one header per site,
 * so the choice here is one proxy kind per site.
 * <p>
 * The settings are part of the site's edit: they are checked by the editor like everything else and become a pending
 * change when the site is kept.
 */
final class RealIpPane extends VBox {

    private final ServerAccess access;
    private final Runnable onChange;
    private final RealIpSpec original;
    private final boolean inheritedRecursive;

    private final CheckBox enabled = new CheckBox("This site is reached through a proxy");
    private final ComboBox<ProxyPreset> preset = new ComboBox<>();
    private final Label presetInfo = hint("");
    private final TextField header = new TextField();
    private final CheckBox recursive = new CheckBox("Look past trusted addresses when the header lists several");
    private final TextArea sources = new TextArea();
    private final Button fetch = new Button("Fetch the proxy's published ranges");
    private final Label fetched = hint("");
    private final GridPane form = new GridPane();
    private boolean filling;

    /**
     * @param initial what the site has now, or null
     * @param onChange runs when the user changes anything, so the editor can re-check
     */
    RealIpPane(RealIpSpec initial, ServerAccess access, Runnable onChange) {
        super(10);
        this.access = access;
        this.onChange = onChange;
        this.original = initial == null ? null : initial.copy();
        Optional<RealIpSpec> inherited = access.inheritedRealIp();
        this.inheritedRecursive = inherited.map(r -> r.recursive).orElse(false);

        Label intro = hint("When visitors reach nginx through a proxy (Cloudflare, a load balancer, another nginx), nginx "
                + "sees the proxy's address for every request. Name the proxy's addresses and the header that carries "
                + "the visitor's, and logs, rate limits and access rules use the visitor instead. Each site has its own "
                + "settings, so different sites can sit behind different proxies. A site can read only one header; for "
                + "several proxies in one chain use X-Forwarded-For with the option to look past trusted addresses.");
        Label inheritedNote = hint(inherited.map(r -> "The http level already sets: nginx " + RealIp.describe(r)
                + ". A site that sets its own here replaces those for this site only.")
                .orElse("Nothing is set at the http level, so a site without its own sees the proxy's address."));

        preset.setItems(FXCollections.observableArrayList(ProxyPresets.all()));
        preset.setMaxWidth(Double.MAX_VALUE);
        preset.setOnAction(e -> {
            if (!filling && preset.getValue() != null) {
                fillFromPreset(preset.getValue());
                onChange.run();
            }
        });
        sources.setPrefRowCount(7);
        sources.setPromptText("one address or range per line, such as 10.0.0.0/8");
        header.setPromptText("such as CF-Connecting-IP or X-Forwarded-For");
        fetch.setOnAction(e -> fetchRanges());
        for (javafx.beans.value.ObservableValue<?> v : List.of(header.textProperty(), sources.textProperty(),
                recursive.selectedProperty(), enabled.selectedProperty())) {
            v.addListener((obs, o, n) -> {
                if (!filling) {
                    onChange.run();
                }
            });
        }

        form.setHgap(10);
        form.setVgap(8);
        form.add(new Label("Kind of proxy"), 0, 0);
        form.add(preset, 1, 0);
        form.add(presetInfo, 1, 1);
        form.add(new Label("Header with the visitor"), 0, 2);
        form.add(header, 1, 2);
        form.add(recursive, 1, 3);
        form.add(new Label("Addresses to trust"), 0, 4);
        form.add(sources, 1, 4);
        HBox fetchRow = new HBox(10, fetch, fetched);
        fetchRow.setAlignment(Pos.CENTER_LEFT);
        form.add(fetchRow, 1, 5);
        ColumnConstraints labels = new ColumnConstraints();
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        form.getColumnConstraints().addAll(labels, fields);
        form.disableProperty().bind(enabled.selectedProperty().not());

        getChildren().addAll(intro, inheritedNote, enabled, form);
        setPadding(new Insets(12));
        load(original);
    }

    private static Label hint(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setOpacity(0.8);
        return l;
    }

    // ---------------------------------------------------------------- reading and writing the form

    private void load(RealIpSpec spec) {
        filling = true;
        try {
            enabled.setSelected(spec != null && !spec.isEmpty());
            if (spec == null || spec.isEmpty()) {
                ProxyPreset first = ProxyPresets.all().get(0);
                preset.setValue(first);
                fillFromPreset(first);
                return;
            }
            ProxyPreset match = guess(spec);
            preset.setValue(match);
            presetInfo.setText(match.description());
            header.setText(spec.header);
            recursive.setSelected(spec.recursive);
            sources.setText(String.join("\n", spec.sources));
            updateFetchControls(match);
        } finally {
            filling = false;
        }
    }

    /** The preset the existing settings look like, or "something else". */
    private static ProxyPreset guess(RealIpSpec s) {
        for (ProxyPreset p : ProxyPresets.all()) {
            if (!p.fetched() && !p.sources().isEmpty() && p.header().equalsIgnoreCase(s.header)
                    && new LinkedHashSet<>(p.sources()).equals(new LinkedHashSet<>(s.sources))) {
                return p;
            }
        }
        if (s.header.equalsIgnoreCase("CF-Connecting-IP") && s.sources.size() > 5) {
            return ProxyPresets.byId(ProxyPresets.CLOUDFLARE).orElseThrow();
        }
        return ProxyPresets.byId(ProxyPresets.CUSTOM).orElseThrow();
    }

    private void fillFromPreset(ProxyPreset p) {
        boolean was = filling;
        filling = true;
        try {
            presetInfo.setText(p.description());
            header.setText(p.header());
            recursive.setSelected(p.recursive());
            sources.setText(String.join("\n", p.sources()));
            updateFetchControls(p);
            fetched.setText(p.fetched() ? "Press the button to read the current ranges. They are not stored in this app." : "");
        } finally {
            filling = was;
        }
    }

    private void updateFetchControls(ProxyPreset p) {
        fetch.setVisible(p.fetched());
        fetch.setManaged(p.fetched());
        fetched.setVisible(p.fetched());
        fetched.setManaged(p.fetched());
    }

    /** The settings as typed, or null while the box is unticked or everything is empty. */
    RealIpSpec value() {
        if (!enabled.isSelected()) {
            return null;
        }
        List<String> list = new ArrayList<>();
        for (String line : sources.getText().split("[\\s,]+")) {
            if (!line.isBlank() && !list.contains(line.strip())) {
                list.add(line.strip());
            }
        }
        RealIpSpec spec = new RealIpSpec(list, header.getText().strip(), recursive.isSelected());
        // "off" must be written when something else would turn it on for this site: the http level, or this site's own line.
        spec.recursiveExplicit = inheritedRecursive || (original != null && original.recursiveExplicit);
        return spec.isEmpty() ? null : spec;
    }

    // ---------------------------------------------------------------- fetching ranges

    private void fetchRanges() {
        ProxyPreset p = preset.getValue();
        if (p == null || !p.fetched()) {
            return;
        }
        fetch.setDisable(true);
        fetched.setText("Reading...");
        SshExecutor.submit(() -> p.fetcher().fetch(access.commandLog())).whenComplete((ranges, failure) ->
                Platform.runLater(() -> {
                    fetch.setDisable(false);
                    if (failure != null) {
                        Throwable cause = failure.getCause() instanceof IOException ? failure.getCause() : failure;
                        fetched.setText("");
                        Dialogs.error(getScene() == null ? null : getScene().getWindow(), "The ranges could not be read",
                                String.valueOf(cause.getMessage()));
                        return;
                    }
                    sources.setText(String.join("\n", ranges));
                    fetched.setText(ranges.size() + " ranges read at " + LocalTime.now().withNano(0)
                            + ". They change now and then; read them again to update.");
                }));
    }

    // For tests.

    void tick(boolean on) {
        enabled.setSelected(on);
    }

    void choose(String presetId) {
        ProxyPresets.byId(presetId).ifPresent(p -> {
            preset.setValue(p);
        });
    }

    void type(String headerName, String addresses) {
        header.setText(headerName);
        sources.setText(addresses);
    }
}
