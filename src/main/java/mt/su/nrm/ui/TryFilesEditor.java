package mt.su.nrm.ui;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * A readable way to set nginx's {@code try_files}: pick what should happen when a requested file is not
 * there, and a sentence underneath says in plain words what nginx will do. The raw value stays in the
 * text field given to it (which the location editor reads and saves), and "Custom" shows that field for
 * anything the presets don't cover.
 */
final class TryFilesEditor extends VBox {

    /** One choice in the list: what it says, and the try_files value it stands for (null = show the raw field). */
    record Preset(String label, String value) {
        @Override
        public String toString() {
            return label;
        }
    }

    static final List<Preset> STATIC_PRESETS = List.of(
            new Preset("Not set (nginx's default)", ""),
            new Preset("Serve the file or folder, otherwise show 404 Not Found", "$uri $uri/ =404"),
            new Preset("Serve the file only, otherwise show 404 Not Found", "$uri =404"),
            new Preset("Single-page app: unknown addresses open /index.html", "$uri $uri/ /index.html"),
            new Preset("PHP site: unknown addresses go to /index.php", "$uri $uri/ /index.php?$args"),
            new Preset("Custom...", null));

    static final List<Preset> PHP_PRESETS = List.of(
            new Preset("Not set (nginx's default)", ""),
            new Preset("Only run scripts that exist, otherwise show 404 Not Found", "$uri =404"),
            new Preset("Custom...", null));

    private final TextField field;
    private final ComboBox<Preset> choice;
    private final Label explanation = new Label();
    private boolean syncing;

    /** @param field the text field that holds the raw value; it is shown only for a custom setting */
    TryFilesEditor(TextField field, List<Preset> presets) {
        super(4);
        this.field = field;
        this.choice = new ComboBox<>(FXCollections.observableArrayList(presets));
        choice.setMaxWidth(Double.MAX_VALUE);
        field.setPromptText("e.g. $uri $uri/ /index.html");
        explanation.setWrapText(true);
        explanation.setOpacity(0.8);
        explanation.setPadding(new Insets(0, 0, 0, 2));
        getChildren().addAll(choice, field, explanation);

        choice.valueProperty().addListener((obs, o, n) -> {
            if (syncing || n == null) {
                return;
            }
            if (n.value() != null) {
                syncing = true;
                field.setText(n.value());
                syncing = false;
            }
            refresh();
        });
        field.textProperty().addListener((obs, o, n) -> {
            if (!syncing) {
                refresh();
            }
        });
        refresh();
    }

    /** Re-reads the field: picks the matching preset (or Custom) and rewrites the explanation. */
    void refresh() {
        String value = field.getText() == null ? "" : field.getText().strip();
        Preset match = null;
        Preset custom = null;
        for (Preset p : choice.getItems()) {
            if (p.value() == null) {
                custom = p;
            } else if (normalise(p.value()).equals(normalise(value))) {
                match = p;
            }
        }
        Preset shown = match != null ? match : custom;
        // A custom setting keeps its Custom entry selected even while the user is typing into the field.
        if (choice.getValue() != null && choice.getValue().value() == null && match == null) {
            shown = choice.getValue();
        }
        syncing = true;
        choice.setValue(shown);
        syncing = false;
        boolean isCustom = shown != null && shown.value() == null;
        field.setVisible(isCustom);
        field.setManaged(isCustom);
        explanation.setText(describe(value));
    }

    private static String normalise(String v) {
        return String.join(" ", v.strip().split("\\s+"));
    }

    /** Says in plain words what a try_files value makes nginx do. */
    static String describe(String value) {
        String v = normalise(value == null ? "" : value);
        if (v.isEmpty()) {
            return "nothing is checked first: nginx looks for the requested address in the folder given above.";
        }
        String[] parts = v.split(" ");
        if (parts.length < 2) {
            return "needs at least one thing to try and a last resort, e.g. $uri =404.";
        }
        List<String> tries = new ArrayList<>();
        for (int i = 0; i < parts.length - 1; i++) {
            tries.add(describeCandidate(parts[i]));
        }
        StringBuilder sb = new StringBuilder("nginx looks for ");
        for (int i = 0; i < tries.size(); i++) {
            sb.append(i == 0 ? "" : i == tries.size() - 1 ? ", then " : ", then ").append(tries.get(i));
        }
        sb.append(". If none exists, ").append(describeFallback(parts[parts.length - 1])).append('.');
        return sb.toString();
    }

    private static String describeCandidate(String token) {
        if (token.equals("$uri")) {
            return "the file itself";
        }
        if (token.equals("$uri/")) {
            return "a folder of that name (and opens its index file)";
        }
        if (token.startsWith("$uri") && token.length() > 4) {
            return "the file with \"" + token.substring(4) + "\" added";
        }
        return "\"" + token + "\"";
    }

    private static String describeFallback(String token) {
        if (token.startsWith("=") && token.substring(1).matches("\\d{3}")) {
            String code = token.substring(1);
            switch (code) {
                case "404":
                    return "it answers with error 404 (Not Found)";
                case "403":
                    return "it answers with error 403 (Forbidden)";
                default:
                    return "it answers with error " + code;
            }
        }
        if (token.startsWith("@")) {
            return "it hands the request to the named location " + token;
        }
        return "it serves " + token + " instead (the address in the browser doesn't change)";
    }
}
