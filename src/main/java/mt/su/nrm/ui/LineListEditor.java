package mt.su.nrm.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * A list of one-line settings (rewrite rules, limits, logs, error pages) shown in plain words with Add,
 * Edit, Remove and reorder buttons. It edits the text of an existing {@link TextArea}, one entry per line,
 * so the editor that owns the text area keeps working unchanged; the text area itself stays available
 * under "Edit as text" for anything the friendly dialog doesn't cover.
 */
final class LineListEditor extends VBox {

    private final TextArea text;
    private final ListView<String> list = new ListView<>();
    private boolean updating;

    /**
     * @param text     the text that holds the entries, one per line
     * @param intro    one or two sentences saying what this list is for
     * @param describe turns an entry into a plain-words summary
     * @param edit     opens the entry dialog: given the owner and the current line ("" for a new entry) it
     *                 returns the new line, or empty if cancelled
     */
    LineListEditor(TextArea text, String intro, Function<String, String> describe,
                   BiFunction<Window, String, Optional<String>> edit) {
        super(8);
        this.text = text;
        setPadding(new Insets(12));

        Label help = new Label(intro);
        help.setWrapText(true);
        help.setOpacity(0.8);

        list.setPlaceholder(new Label("Nothing here yet. Click Add."));
        list.setPrefHeight(170);
        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(String line, boolean empty) {
                super.updateItem(line, empty);
                if (empty || line == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label summary = new Label(describe.apply(line));
                summary.setWrapText(true);
                Label raw = new Label(line);
                raw.setStyle("-fx-font-family: 'Consolas', 'Menlo', monospace; -fx-font-size: 11px; -fx-text-fill: #777777;");
                setGraphic(new VBox(1, summary, raw));
                setText(null);
            }
        });

        Button add = new Button("Add");
        Button change = new Button("Edit");
        Button remove = new Button("Remove");
        Button up = new Button("Move up");
        Button down = new Button("Move down");
        for (Button b : List.of(change, remove, up, down)) {
            b.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        }
        add.setOnAction(e -> edit.apply(window(), "").ifPresent(line -> {
            List<String> lines = lines();
            lines.add(line);
            store(lines, lines.size() - 1);
        }));
        Runnable editSelected = () -> {
            int i = list.getSelectionModel().getSelectedIndex();
            if (i >= 0) {
                edit.apply(window(), lines().get(i)).ifPresent(line -> {
                    List<String> lines = lines();
                    lines.set(i, line);
                    store(lines, i);
                });
            }
        };
        change.setOnAction(e -> editSelected.run());
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                editSelected.run();
            }
        });
        remove.setOnAction(e -> {
            int i = list.getSelectionModel().getSelectedIndex();
            List<String> lines = lines();
            lines.remove(i);
            store(lines, Math.min(i, lines.size() - 1));
        });
        up.setOnAction(e -> move(-1));
        down.setOnAction(e -> move(1));
        HBox buttons = new HBox(8, add, change, remove, up, down);

        TextArea raw = text;
        raw.setPrefRowCount(4);
        TitledPane advanced = new TitledPane("Edit as text (advanced)", raw);
        advanced.setExpanded(false);

        text.textProperty().addListener((obs, o, n) -> refresh());
        refresh();
        VBox.setVgrow(list, Priority.ALWAYS);
        getChildren().addAll(help, list, buttons, advanced);
    }

    /** A shorter list, for when two of these share one tab. */
    void compact() {
        list.setPrefHeight(100);
    }

    private Window window() {
        return getScene() == null ? null : getScene().getWindow();
    }

    private List<String> lines() {
        return new ArrayList<>(VhostForm.entries(text.getText()));
    }

    private void store(List<String> lines, int select) {
        text.setText(String.join("\n", lines));
        if (select >= 0 && select < list.getItems().size()) {
            list.getSelectionModel().select(select);
        }
    }

    private void move(int by) {
        int i = list.getSelectionModel().getSelectedIndex();
        List<String> lines = lines();
        int j = i + by;
        if (i < 0 || j < 0 || j >= lines.size()) {
            return;
        }
        String moved = lines.remove(i);
        lines.add(j, moved);
        store(lines, j);
    }

    private void refresh() {
        if (updating) {
            return;
        }
        updating = true;
        try {
            int selected = list.getSelectionModel().getSelectedIndex();
            list.getItems().setAll(VhostForm.entries(text.getText()));
            if (selected >= 0 && selected < list.getItems().size()) {
                list.getSelectionModel().select(selected);
            }
        } finally {
            updating = false;
        }
    }
}
