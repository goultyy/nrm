package mt.su.nrm.ui;

import mt.su.nrm.logformat.LogFormatDesign;
import mt.su.nrm.logformat.LogFormatDesign.Style;
import mt.su.nrm.logformat.LogFormatPresets;
import mt.su.nrm.nginx.Directive;
import mt.su.nrm.nginx.LogFormatSettings;
import javafx.collections.FXCollections;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Creates or edits a log format: choose a name and a starting point, build the line field by field with a live example,
 * and review the exact {@code log_format} that will be added. Nothing is written to the server; the result becomes a
 * pending change.
 */
final class LogFormatWizard {

    private LogFormatWizard() {
    }

    /**
     * @param s          the format to create or edit; updated only if the wizard is finished
     * @param takenNames the names of the other log formats
     * @param uses       where the format is used now (when editing), to say what changing it affects
     * @return true if the user finished the wizard
     */
    static boolean run(Window owner, LogFormatSettings s, Set<String> takenNames, boolean creating, List<String> uses) {
        LogFormatDesign[] design = {creating ? LogFormatPresets.all().get(0).design().get() : LogFormatDesign.from(s)};

        // ---- page 1: name, starting point, style
        TextField name = new TextField(s.name);
        name.setPromptText("e.g. main or json_timing");
        name.setDisable(!creating);
        ComboBox<LogFormatPresets.Preset> preset = new ComboBox<>(FXCollections.observableArrayList(LogFormatPresets.all()));
        preset.setValue(LogFormatPresets.all().get(0));
        preset.setMaxWidth(Double.MAX_VALUE);
        Label presetInfo = WizardParts.hint(preset.getValue().description());
        ToggleGroup styleGroup = new ToggleGroup();
        RadioButton text = new RadioButton("Plain text: fields with your own punctuation between them");
        RadioButton json = new RadioButton("JSON: each line is an object, with a key for each field");
        text.setToggleGroup(styleGroup);
        json.setToggleGroup(styleGroup);
        (design[0].style() == Style.JSON ? json : text).setSelected(true);

        LogFormatBuilder[] builder = new LogFormatBuilder[1];
        boolean[] settingUp = {false};
        preset.setOnAction(e -> {
            if (preset.getValue() == null || settingUp[0]) {
                return;
            }
            presetInfo.setText(preset.getValue().description());
            design[0] = preset.getValue().design().get();
            settingUp[0] = true;
            (design[0].style() == Style.JSON ? json : text).setSelected(true);
            settingUp[0] = false;
            builder[0].setDesign(design[0]);
        });
        styleGroup.selectedToggleProperty().addListener((obs, o, n) -> {
            if (settingUp[0] || n == null) {
                return;
            }
            LogFormatDesign current = builder[0].design();
            current.style(n == json ? Style.JSON : Style.TEXT);
            design[0] = current;
            builder[0].setDesign(current);
        });

        GridPane page1 = WizardParts.form();
        int r = WizardParts.note(page1, 0, WizardParts.hint("A log format says what each line of an access log contains. "
                + "Sites choose a format by its name in their Logging tab."));
        r = WizardParts.row(page1, r, "Name", name);
        if (creating) {
            r = WizardParts.row(page1, r, "Start from", preset);
            r = WizardParts.note(page1, r, presetInfo);
        } else {
            r = WizardParts.note(page1, r, WizardParts.hint("The name can't be changed here: sites refer to it. "
                    + (uses.isEmpty() ? "Nothing uses this format yet."
                    : "It is used by " + uses.size() + " access log line" + (uses.size() == 1 ? "" : "s")
                    + ", which will log differently once this is applied.")));
        }
        r = WizardParts.row(page1, r, "Layout", new VBox(6, text, json));
        WizardParts.note(page1, r, WizardParts.hint("You can change the layout later; the fields are kept."));

        // ---- page 2: the builder
        builder[0] = new LogFormatBuilder(design[0], () -> name.getText(), () -> { });
        VBox page2 = new VBox(builder[0]);

        List<WizardDialog.Page> pages = List.of(
                new WizardDialog.Page("Name and layout", "Choose what to call the format and what to start from.", page1,
                        () -> LogFormatDesign.nameProblems(name.getText().strip(), takenNames)),
                new WizardDialog.Page("Build the line", "Add the fields you want in each log line, in the order you "
                        + "want them.", page2, () -> {
                            List<String> problems = new ArrayList<>(builder[0].design().contentProblems());
                            return problems;
                        }, () -> builder[0].refresh()));

        boolean finished = WizardDialog.show(owner, creating ? "New log format" : "Edit log format",
                creating ? "Add format" : "Save", pages, () -> review(builder[0].design(), name.getText().strip()),
                () -> afterText(name.getText().strip(), creating, uses), 860, 560);
        if (finished) {
            LogFormatSettings out = builder[0].design().toSettings(name.getText().strip());
            s.name = out.name;
            s.escape = out.escape;
            s.text = out.text;
        }
        return finished;
    }

    /** The exact directive and an example line, for the last page. */
    static String review(LogFormatDesign design, String name) {
        LogFormatSettings out = design.toSettings(name);
        List<String> args = new ArrayList<>();
        args.add(out.name);
        if (out.escape != LogFormatSettings.Escape.DEFAULT) {
            args.add("escape=" + out.escape.keyword());
        }
        args.add(out.text);
        return Directive.create("log_format", args) + ";\n\nA line would look like:\n" + design.preview();
    }

    private static String afterText(String name, boolean creating, List<String> uses) {
        if (!creating && !uses.isEmpty()) {
            return "It is used by: " + String.join("; ", uses) + ".";
        }
        return "Next: in a virtual host's Logging tab, add an access log and choose \"" + name + "\" as its format.";
    }
}
