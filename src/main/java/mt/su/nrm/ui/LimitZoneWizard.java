package mt.su.nrm.ui;

import mt.su.nrm.nginx.LimitKey;
import mt.su.nrm.nginx.LimitZoneSettings;
import mt.su.nrm.nginx.Units;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Guides the creation or editing of a rate or connection limit: what to limit, who is counted, and
 * how strict to be. It explains each choice in plain words, suggests a zone name and memory size,
 * and ends with a review that also tells you how to use the zone in a location.
 */
final class LimitZoneWizard {

    private LimitZoneWizard() {
    }

    /**
     * @param s          the zone to create or edit; updated only if the wizard is finished
     * @param takenNames "KIND:name" of the other limit zones, so a name isn't used twice for the same kind
     * @param creating   false when editing (what is limited can't be changed then)
     * @return true if the user finished the wizard
     */
    static boolean run(Window owner, LimitZoneSettings s, Set<String> takenNames, boolean creating) {
        // ---- page 1: what to limit
        ToggleGroup kindGroup = new ToggleGroup();
        RadioButton requests = new RadioButton("How many requests a visitor can make");
        RadioButton connections = new RadioButton("How many connections a visitor can hold open at once");
        requests.setToggleGroup(kindGroup);
        connections.setToggleGroup(kindGroup);
        (s.kind == LimitZoneSettings.Kind.CONNECTION ? connections : requests).setSelected(true);
        requests.setDisable(!creating);
        connections.setDisable(!creating);
        VBox page1 = new VBox(10, requests,
                WizardParts.hint("Set a ceiling such as \"10 requests per second\". Visitors who go over it get errors "
                        + "or are slowed down. Best for stopping scrapers, brute-force logins and traffic bursts."),
                connections,
                WizardParts.hint("Set a ceiling such as \"10 connections at once\". Best for large downloads and "
                        + "slow clients that would otherwise tie up the server."));
        page1.setPadding(new Insets(6));
        if (!creating) {
            page1.getChildren().add(WizardParts.hint("What is limited can't be changed for an existing zone. "
                    + "Create a new zone if you need the other kind."));
        }

        // ---- page 2: who is counted
        ToggleGroup keyGroup = new ToggleGroup();
        LimitKey initialKey = LimitKey.of(s.key);
        VBox page2 = new VBox(8);
        page2.setPadding(new Insets(6));
        page2.getChildren().add(WizardParts.hint("Each choice keeps a separate counter. Pick what should be limited "
                + "individually."));
        TextField customKey = new TextField(initialKey == LimitKey.CUSTOM ? s.key : "");
        customKey.setPromptText("$http_authorization");
        List<RadioButton> keyButtons = new ArrayList<>();
        for (LimitKey k : LimitKey.values()) {
            RadioButton b = new RadioButton(k.toString());
            b.setUserData(k);
            b.setToggleGroup(keyGroup);
            b.setSelected(k == initialKey);
            keyButtons.add(b);
            page2.getChildren().addAll(b, WizardParts.hint("   " + k.description()));
        }
        page2.getChildren().add(customKey);
        customKey.disableProperty().bind(keyGroup.selectedToggleProperty().map(t -> t.getUserData() != LimitKey.CUSTOM)
                .orElse(true));

        // ---- page 3: the details
        Optional<Units.Rate> rate = Units.Rate.parse(s.rate);
        TextField rateAmount = new TextField(rate.map(x -> String.valueOf(x.amount())).orElse(s.rate.isBlank() ? "10" : s.rate));
        rateAmount.setPrefColumnCount(6);
        ComboBox<Units.RateUnit> rateUnit = new ComboBox<>(FXCollections.observableArrayList(Units.RateUnit.values()));
        rateUnit.setValue(rate.map(Units.Rate::unit).orElse(Units.RateUnit.SECOND));
        Button gentle = new Button("Gentle: 20 per second");
        gentle.setOnAction(e -> preset(rateAmount, rateUnit, "20", Units.RateUnit.SECOND));
        Button normal = new Button("Normal: 10 per second");
        normal.setOnAction(e -> preset(rateAmount, rateUnit, "10", Units.RateUnit.SECOND));
        Button strict = new Button("Strict: 1 per second");
        strict.setOnAction(e -> preset(rateAmount, rateUnit, "1", Units.RateUnit.SECOND));
        HBox rateRow = new HBox(8, rateAmount, rateUnit);
        HBox presets = new HBox(8, gentle, normal, strict);

        String existingName = s.zoneName();
        TextField zoneName = new TextField(existingName);
        boolean[] nameEdited = {!existingName.isEmpty()};
        zoneName.textProperty().addListener((obs, o, n) -> {
            if (zoneName.isFocused()) {
                nameEdited[0] = true;
            }
        });
        Optional<Units.Size> zoneSize = Units.Size.parse(s.zone.contains(":") ? s.zone.substring(s.zone.indexOf(':') + 1) : "");
        TextField memory = new TextField(zoneSize.map(z -> String.valueOf(z.unit() == Units.SizeUnit.MB ? z.amount()
                : Math.max(1, z.bytes() / (1024 * 1024)))).orElse("10"));
        memory.setPrefColumnCount(6);
        Label estimate = WizardParts.hint("");
        Label rateHint = WizardParts.hint("Visitors who make more requests than this are rejected with an error (HTTP 503). "
                + "You can allow short bursts, or serve them without delay, in the location's Limits tab.");
        Label connHint = WizardParts.hint("The number of connections allowed per visitor is chosen where the zone is used, in a "
                + "location's Limits tab, so you can use one zone with different limits in different places.");

        Runnable refreshSuggestions = () -> {
            LimitZoneSettings.Kind kind = requests.isSelected() ? LimitZoneSettings.Kind.REQUEST : LimitZoneSettings.Kind.CONNECTION;
            Toggle t = keyGroup.getSelectedToggle();
            LimitKey key = t == null ? LimitKey.CLIENT_IP : (LimitKey) t.getUserData();
            if (!nameEdited[0]) {
                zoneName.setText(key.suggestedZoneName(kind));
            }
            boolean req = kind == LimitZoneSettings.Kind.REQUEST;
            rateRow.setVisible(req);
            rateRow.setManaged(req);
            presets.setVisible(req);
            presets.setManaged(req);
            rateHint.setVisible(req);
            rateHint.setManaged(req);
            connHint.setVisible(!req);
            connHint.setManaged(!req);
            Long mb = WizardParts.number(memory.getText());
            estimate.setText(mb == null || mb < 1 ? "" : "Room for about " + String.format("%,d", LimitKey.keysInLimitZone(mb))
                    + " different " + (key == LimitKey.SITE ? "counters" : "visitors or keys") + " at once. 10 MB is plenty for most sites.");
        };
        requests.selectedProperty().addListener((obs, o, n) -> refreshSuggestions.run());
        keyGroup.selectedToggleProperty().addListener((obs, o, n) -> refreshSuggestions.run());
        memory.textProperty().addListener((obs, o, n) -> refreshSuggestions.run());
        refreshSuggestions.run();

        GridPane page3 = WizardParts.form();
        int r = WizardParts.row(page3, 0, "Allowed rate", rateRow);
        r = WizardParts.row(page3, r, "", presets);
        r = WizardParts.note(page3, r, rateHint);
        r = WizardParts.note(page3, r, connHint);
        r = WizardParts.row(page3, r, "Zone name", zoneName);
        r = WizardParts.row(page3, r, "Memory (MB)", memory);
        WizardParts.note(page3, r, estimate);

        java.util.function.Supplier<LimitZoneSettings> read = () -> {
            LimitZoneSettings z = new LimitZoneSettings();
            z.kind = requests.isSelected() ? LimitZoneSettings.Kind.REQUEST : LimitZoneSettings.Kind.CONNECTION;
            Toggle t = keyGroup.getSelectedToggle();
            LimitKey key = t == null ? LimitKey.CLIENT_IP : (LimitKey) t.getUserData();
            z.key = key == LimitKey.CUSTOM ? customKey.getText().strip() : key.variable();
            Long mb = WizardParts.number(memory.getText());
            z.zone = zoneName.getText().strip() + ":" + (mb == null ? "" : mb + "m");
            Long amount = WizardParts.number(rateAmount.getText());
            z.rate = z.kind == LimitZoneSettings.Kind.REQUEST && amount != null && rateUnit.getValue() != null
                    ? new Units.Rate(amount, rateUnit.getValue()).format() : "";
            return z;
        };

        List<WizardDialog.Page> pages = List.of(
                new WizardDialog.Page("What to limit", "Choose the kind of limit.", page1, List::of),
                new WizardDialog.Page("Who is counted", "Choose what the limit is counted per.", page2, () -> {
                    Toggle t = keyGroup.getSelectedToggle();
                    if (t != null && t.getUserData() == LimitKey.CUSTOM
                            && !customKey.getText().strip().matches("\\$[A-Za-z_][A-Za-z0-9_]*")) {
                        return List.of("Enter an nginx variable such as $http_authorization.");
                    }
                    return List.of();
                }, refreshSuggestions),
                new WizardDialog.Page("The limit", "Set the ceiling and name the zone.", page3, () -> {
                    List<String> problems = new ArrayList<>();
                    boolean req = requests.isSelected();
                    Long amount = WizardParts.number(rateAmount.getText());
                    if (req && (amount == null || amount < 1)) {
                        problems.add("Enter the allowed rate as a whole number of 1 or more.");
                    }
                    String n = zoneName.getText().strip();
                    LimitZoneSettings.Kind kind = req ? LimitZoneSettings.Kind.REQUEST : LimitZoneSettings.Kind.CONNECTION;
                    if (!n.matches("[A-Za-z0-9_]+")) {
                        problems.add("The zone name may use letters, digits and underscores.");
                    } else if (takenNames.contains(kind + ":" + n)) {
                        problems.add("A zone named \"" + n + "\" of this kind already exists.");
                    }
                    Long mb = WizardParts.number(memory.getText());
                    if (mb == null || mb < 1) {
                        problems.add("Enter the memory as a whole number of megabytes (at least 1).");
                    }
                    return problems;
                }, refreshSuggestions));

        boolean finished = WizardDialog.show(owner, creating ? "New rate limit" : "Edit rate limit",
                creating ? "Add limit" : "Save", pages, () -> read.get().preview(), () -> {
                    LimitZoneSettings z = read.get();
                    return requests.isSelected()
                            ? "Next: in a virtual host, open a location's Limits tab and choose the zone \"" + z.zoneName()
                            + "\" under request limits. Add a burst allowance if visitors legitimately send short spikes."
                            : "Next: in a virtual host, open a location's Limits tab and choose the zone \"" + z.zoneName()
                            + "\" under connection limits, then set how many connections each visitor may hold.";
                });
        if (finished) {
            LimitZoneSettings z = read.get();
            s.kind = z.kind;
            s.key = z.key;
            s.zone = z.zone;
            s.rate = z.rate;
        }
        return finished;
    }

    private static void preset(TextField amount, ComboBox<Units.RateUnit> unit, String value, Units.RateUnit u) {
        amount.setText(value);
        unit.setValue(u);
    }

}
