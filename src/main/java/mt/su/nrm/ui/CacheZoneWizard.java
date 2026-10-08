package mt.su.nrm.ui;

import mt.su.nrm.nginx.CacheZoneSettings;
import mt.su.nrm.nginx.LimitKey;
import mt.su.nrm.nginx.Units;
import javafx.collections.FXCollections;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Guides the creation or editing of a cache zone: what it is called and where its files go, how
 * much disk and memory it may use, and how long unused items are kept. Sizes and times are entered
 * as numbers with units, and the review shows the exact directive that will be added.
 */
final class CacheZoneWizard {

    private static final String[] MEMORY = {
            "Small: 10 MB (about 80,000 cached items)", "Medium: 50 MB (about 400,000 cached items)",
            "Large: 200 MB (about 1,600,000 cached items)", "Custom size..."};
    private static final long[] MEMORY_MB = {10, 50, 200};
    private static final String[] KEEP = {"1 hour", "1 day", "7 days", "30 days", "Custom..."};
    private static final long[] KEEP_SECONDS = {3600, 86_400, 604_800, 2_592_000};
    private static final String[] KEEP_CODES = {"1h", "1d", "7d", "30d"};

    private CacheZoneWizard() {
    }

    /**
     * @param s          the zone to create or edit; updated only if the wizard is finished
     * @param takenNames zone names already in use by other cache zones
     * @return true if the user finished the wizard
     */
    static boolean run(Window owner, CacheZoneSettings s, Set<String> takenNames, boolean creating) {
        // ---- page 1: name and folder
        String existingName = s.zoneName();
        TextField name = new TextField(existingName);
        name.setPromptText("e.g. site_cache");
        TextField folder = new TextField(s.path);
        folder.setPromptText("/var/cache/nginx/site_cache");
        boolean[] folderEdited = {!creating || !s.path.isBlank() && !s.path.endsWith("/site_cache")};
        name.textProperty().addListener((obs, o, n) -> {
            if (!folderEdited[0]) {
                folder.setText("/var/cache/nginx/" + n.strip());
            }
        });
        folder.textProperty().addListener((obs, o, n) -> {
            if (folder.isFocused()) {
                folderEdited[0] = true;
            }
        });
        if (creating && s.path.isBlank()) {
            folder.setText("/var/cache/nginx/");
        }
        GridPane page1 = WizardParts.form();
        int r = WizardParts.note(page1, 0, WizardParts.hint("A cache zone stores copies of responses from your backend "
                + "so nginx can answer repeat requests without asking the backend again. Locations refer to the zone by name."));
        r = WizardParts.row(page1, r, "Zone name", name);
        r = WizardParts.row(page1, r, "Folder for cached files", folder);
        WizardParts.note(page1, r, WizardParts.hint("nginx creates the last folder itself (and this app creates the one "
                + "above it if needed). Put the cache on a disk with enough free space."));

        // ---- page 2: size
        Optional<Units.Size> diskSize = Units.Size.parse(s.maxSize);
        CheckBox unlimited = new CheckBox("No limit (grow until the disk is full)");
        unlimited.setSelected(!creating && s.maxSize.isBlank());
        TextField diskAmount = new TextField(diskSize.map(z -> String.valueOf(z.amount())).orElse(
                s.maxSize.isBlank() ? "1" : s.maxSize));
        diskAmount.setPrefColumnCount(7);
        ComboBox<Units.SizeUnit> diskUnit = new ComboBox<>(FXCollections.observableArrayList(Units.SizeUnit.values()));
        diskUnit.setValue(diskSize.map(Units.Size::unit).orElse(Units.SizeUnit.GB));
        diskAmount.disableProperty().bind(unlimited.selectedProperty());
        diskUnit.disableProperty().bind(unlimited.selectedProperty());
        HBox disk = new HBox(8, diskAmount, diskUnit);

        Optional<Units.Size> memorySize = Units.Size.parse(s.keysZone.contains(":") ? s.keysZone.substring(s.keysZone.indexOf(':') + 1) : "");
        ComboBox<String> memory = new ComboBox<>(FXCollections.observableArrayList(MEMORY));
        TextField memoryCustom = new TextField();
        memoryCustom.setPromptText("size in MB");
        memoryCustom.setPrefColumnCount(7);
        long presetMb = memorySize.filter(z -> z.unit() == Units.SizeUnit.MB).map(Units.Size::amount).orElse(-1L);
        int presetIndex = -1;
        for (int i = 0; i < MEMORY_MB.length; i++) {
            if (MEMORY_MB[i] == presetMb) {
                presetIndex = i;
            }
        }
        if (memorySize.isEmpty()) {
            memory.setValue(MEMORY[0]);
        } else if (presetIndex >= 0) {
            memory.setValue(MEMORY[presetIndex]);
        } else {
            memory.setValue(MEMORY[3]);
            memoryCustom.setText(memorySize.get().unit() == Units.SizeUnit.MB ? String.valueOf(memorySize.get().amount())
                    : String.valueOf(Math.max(1, memorySize.get().bytes() / (1024 * 1024))));
        }
        memoryCustom.visibleProperty().bind(memory.valueProperty().isEqualTo(MEMORY[3]));
        memoryCustom.managedProperty().bind(memoryCustom.visibleProperty());
        Label estimate = WizardParts.hint("");
        Runnable updateEstimate = () -> {
            Long mb = memoryMb(memory, memoryCustom);
            estimate.setText(mb == null ? "" : "About " + String.format("%,d", LimitKey.itemsInCacheIndex(mb))
                    + " cached items can be tracked. This is memory for the index only; the cached files themselves live on disk.");
        };
        memory.valueProperty().addListener((obs, o, n) -> updateEstimate.run());
        memoryCustom.textProperty().addListener((obs, o, n) -> updateEstimate.run());
        updateEstimate.run();

        GridPane page2 = WizardParts.form();
        r = WizardParts.note(page2, 0, WizardParts.hint("How much disk space may the cached files use? Once the limit is "
                + "reached, nginx removes the least recently used items."));
        r = WizardParts.row(page2, r, "Maximum disk size", disk);
        r = WizardParts.row(page2, r, "", unlimited);
        r = WizardParts.note(page2, r, WizardParts.hint(" "));
        r = WizardParts.row(page2, r, "Memory for the index", memory);
        r = WizardParts.row(page2, r, "", memoryCustom);
        WizardParts.note(page2, r, estimate);

        // ---- page 3: lifetime and layout
        Optional<Units.Duration> kept = Units.Duration.parse(s.inactive);
        ComboBox<String> keep = new ComboBox<>(FXCollections.observableArrayList(KEEP));
        TextField keepAmount = new TextField();
        keepAmount.setPrefColumnCount(6);
        ComboBox<Units.TimeUnit> keepUnit = new ComboBox<>(FXCollections.observableArrayList(Units.TimeUnit.values()));
        keepUnit.setValue(Units.TimeUnit.HOURS);
        int keepIndex = -1;
        for (int i = 0; i < KEEP_SECONDS.length && kept.isPresent(); i++) {
            if (kept.get().seconds() == KEEP_SECONDS[i]) {
                keepIndex = i;
            }
        }
        if (s.inactive.isBlank()) {
            keep.setValue(KEEP[0]);
        } else if (keepIndex >= 0) {
            keep.setValue(KEEP[keepIndex]);
        } else {
            keep.setValue(KEEP[4]);
            keepAmount.setText(kept.map(d -> String.valueOf(d.amount())).orElse(s.inactive));
            kept.ifPresent(d -> keepUnit.setValue(d.unit()));
        }
        HBox keepCustom = new HBox(8, keepAmount, keepUnit);
        keepCustom.visibleProperty().bind(keep.valueProperty().isEqualTo(KEEP[4]));
        keepCustom.managedProperty().bind(keepCustom.visibleProperty());
        CheckBox subfolders = new CheckBox("Spread cached files over subfolders (recommended for big caches)");
        subfolders.setSelected(creating || !s.levels.isBlank());
        CheckBox direct = new CheckBox("Write files straight into the cache folder (recommended)");
        direct.setSelected(creating || s.useTempPath.equals("off"));
        GridPane page3 = WizardParts.form();
        r = WizardParts.note(page3, 0, WizardParts.hint("Items that nobody asks for are removed after this long. "
                + "How long a response is considered fresh is set per location, in its Caching tab."));
        r = WizardParts.row(page3, r, "Remove unused items after", keep);
        r = WizardParts.row(page3, r, "", keepCustom);
        r = WizardParts.note(page3, r, WizardParts.hint(" "));
        r = WizardParts.row(page3, r, "", subfolders);
        r = WizardParts.row(page3, r, "", direct);
        WizardParts.note(page3, r, WizardParts.hint("Subfolders keep any one folder from holding too many files. "
                + "Writing straight into the cache avoids copying each file from a temporary folder."));

        java.util.function.Supplier<CacheZoneSettings> read = () -> {
            CacheZoneSettings z = new CacheZoneSettings();
            z.path = folder.getText().strip();
            Long mb = memoryMb(memory, memoryCustom);
            z.keysZone = name.getText().strip() + ":" + (mb == null ? "" : mb + "m");
            z.maxSize = unlimited.isSelected() ? "" : (diskAmount.getText().strip()
                    + (diskUnit.getValue() == null ? "" : new Units.Size(1, diskUnit.getValue()).format().substring(1)));
            z.inactive = keepText(keep, keepAmount, keepUnit);
            z.levels = subfolders.isSelected() ? "1:2" : "";
            z.useTempPath = direct.isSelected() ? "off" : "";
            return z;
        };

        List<WizardDialog.Page> pages = List.of(
                new WizardDialog.Page("Name and location", "Choose a name for the zone and where its files are stored.", page1, () -> {
                    List<String> problems = new ArrayList<>();
                    String n = name.getText().strip();
                    if (!n.matches("[A-Za-z0-9_]+")) {
                        problems.add("The name may use letters, digits and underscores.");
                    } else if (takenNames.contains(n)) {
                        problems.add("A cache zone named \"" + n + "\" already exists.");
                    }
                    String f = folder.getText().strip();
                    if (!f.startsWith("/") || f.chars().anyMatch(c -> c < 0x21) || f.length() < 2 || f.endsWith("/")) {
                        problems.add("The folder must be an absolute path without spaces, such as /var/cache/nginx/site_cache.");
                    }
                    return problems;
                }),
                new WizardDialog.Page("Size", "Decide how much disk and memory the cache may use.", page2, () -> {
                    List<String> problems = new ArrayList<>();
                    if (!unlimited.isSelected()) {
                        Long d = WizardParts.number(diskAmount.getText());
                        if (d == null || d < 1) {
                            problems.add("Enter the maximum disk size as a whole number, or choose no limit.");
                        }
                    }
                    if (memoryMb(memory, memoryCustom) == null) {
                        problems.add("Enter the index memory as a whole number of megabytes (at least 1).");
                    }
                    return problems;
                }),
                new WizardDialog.Page("Lifetime and layout", "How long unused items stay, and how files are organised.", page3, () -> {
                    List<String> problems = new ArrayList<>();
                    if (KEEP[4].equals(keep.getValue())) {
                        Long a = WizardParts.number(keepAmount.getText());
                        if (a == null || a < 1) {
                            problems.add("Enter how long to keep unused items, as a whole number.");
                        }
                    }
                    return problems;
                }));

        boolean finished = WizardDialog.show(owner, creating ? "New cache zone" : "Edit cache zone",
                creating ? "Add zone" : "Save", pages, () -> read.get().preview(),
                () -> "Next: in a virtual host, open a Reverse proxy location, go to its Caching tab and choose the zone \""
                        + name.getText().strip() + "\".");
        if (finished) {
            CacheZoneSettings z = read.get();
            s.path = z.path;
            s.keysZone = z.keysZone;
            s.maxSize = z.maxSize;
            s.inactive = z.inactive;
            s.levels = z.levels;
            s.useTempPath = z.useTempPath;
        }
        return finished;
    }

    /** The index memory in whole megabytes, or null if not valid. */
    private static Long memoryMb(ComboBox<String> memory, TextField custom) {
        String choice = memory.getValue();
        for (int i = 0; i < MEMORY_MB.length; i++) {
            if (MEMORY[i].equals(choice)) {
                return MEMORY_MB[i];
            }
        }
        Long mb = WizardParts.number(custom.getText());
        return mb == null || mb < 1 ? null : mb;
    }

    private static String keepText(ComboBox<String> keep, TextField amount, ComboBox<Units.TimeUnit> unit) {
        for (int i = 0; i < KEEP_CODES.length; i++) {
            if (KEEP[i].equals(keep.getValue())) {
                return KEEP_CODES[i];
            }
        }
        Long a = WizardParts.number(amount.getText());
        return a == null || unit.getValue() == null ? "" : new Units.Duration(a, unit.getValue()).format();
    }
}
