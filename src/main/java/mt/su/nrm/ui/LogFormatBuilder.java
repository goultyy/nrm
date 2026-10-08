package mt.su.nrm.ui;

import mt.su.nrm.logformat.LogField;
import mt.su.nrm.logformat.LogFields;
import mt.su.nrm.logformat.LogFormatDesign;
import mt.su.nrm.logformat.LogFormatDesign.Element;
import mt.su.nrm.logformat.LogFormatDesign.Style;
import mt.su.nrm.nginx.LogFormatSettings;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * The interactive part of the log format wizard: pick fields from a searchable list, arrange them as rows, and watch
 * a sample line change as you go. In the text style the rows are fields and pieces of text (a space, a bracket, a
 * quote); in the JSON style each field is a key of an object.
 * <p>
 * It edits a {@link LogFormatDesign}; everything about what is allowed lives there.
 */
final class LogFormatBuilder extends VBox {

    private static final String ALL_GROUPS = "All groups";
    private static final String MONO = "-fx-font-family: 'Consolas', 'Menlo', monospace;";

    /** One row of the table. Rows are compared by identity, so two identical rows can still be changed separately. */
    private static final class Row {
        Element element;

        Row(Element element) {
            this.element = element;
        }
    }

    private LogFormatDesign design;
    private final Supplier<String> name;
    private final Runnable onChange;
    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final TableView<Row> table = new TableView<>(rows);
    private final TableColumn<Row, String> keyColumn = new TableColumn<>("JSON key");
    private final TableColumn<Row, Boolean> quotedColumn = new TableColumn<>("In quotes");
    private final FilteredList<LogField> filtered = new FilteredList<>(FXCollections.observableArrayList(LogFields.all()));
    private final ListView<LogField> palette = new ListView<>(filtered);
    private final TextField search = new TextField();
    private final ComboBox<String> group = new ComboBox<>();
    private final Label fieldInfo = new Label();
    private final CheckBox quote = new CheckBox("Put the field in quotes");
    private final HBox escapeRow = new HBox(8);
    private final ComboBox<LogFormatSettings.Escape> escape = new ComboBox<>();
    private final TextArea sample = new TextArea();
    private final TextField formatText = new TextField();
    private final Label warnings = new Label();

    /**
     * @param name     the name chosen for the format (only used to word warnings)
     * @param onChange runs after every change, so the wizard can re-check the page
     */
    LogFormatBuilder(LogFormatDesign design, Supplier<String> name, Runnable onChange) {
        super(10);
        this.design = design;
        this.name = name;
        this.onChange = onChange;
        buildPalette();
        buildTable();

        VBox left = new VBox(6, hintLabel("1. Pick a field"), group, search, palette, fieldInfo, quote, adders());
        VBox right = new VBox(6, hintLabel("2. Arrange the rows. Double-click text or a key to change it."), table,
                moveButtons());
        left.setPrefWidth(280);
        left.setMinWidth(240);
        VBox.setVgrow(palette, Priority.ALWAYS);
        VBox.setVgrow(table, Priority.ALWAYS);
        HBox top = new HBox(12, left, right);
        HBox.setHgrow(right, Priority.ALWAYS);

        escape.getItems().setAll(LogFormatSettings.Escape.values());
        escape.setConverter(new StringConverter<>() {
            @Override
            public String toString(LogFormatSettings.Escape e) {
                return e == null ? "" : switch (e) {
                    case DEFAULT -> "Standard (quotes and control characters as \\xHH)";
                    case JSON -> "For JSON";
                    case NONE -> "None (values written as they are)";
                };
            }

            @Override
            public LogFormatSettings.Escape fromString(String s) {
                return null;
            }
        });
        escape.setOnAction(e -> {
            if (escape.getValue() != null) {
                this.design.escape(escape.getValue());
                changed();
            }
        });
        escapeRow.setAlignment(Pos.CENTER_LEFT);
        escapeRow.getChildren().addAll(new Label("Escape characters in values"), escape);

        sample.setEditable(false);
        sample.setPrefRowCount(3);
        sample.setWrapText(true);
        sample.setStyle(MONO);
        formatText.setEditable(false);
        formatText.setStyle(MONO);
        warnings.setWrapText(true);
        warnings.setStyle("-fx-text-fill: #8a6d00;");

        // At the top or bottom of the field list or the rows, the wheel would otherwise carry on and scroll the whole
        // wizard page. Scrolling is handled by the inner control by now, so stop it going further up.
        for (javafx.scene.Node inner : List.of(palette, table, sample)) {
            inner.addEventHandler(javafx.scene.input.ScrollEvent.SCROLL, javafx.event.Event::consume);
        }

        getChildren().addAll(top, escapeRow, hintLabel("3. A line as it would look, with example values"), sample,
                hintLabel("The format as nginx will read it"), formatText, warnings);
        setPadding(new Insets(4));
        setDesign(design);
    }

    // ---------------------------------------------------------------- the design

    /** Shows another design (the user picked a different preset or style). */
    void setDesign(LogFormatDesign newDesign) {
        this.design = newDesign;
        rows.setAll(newDesign.elements().stream().map(Row::new).toList());
        escape.setValue(newDesign.escape());
        refresh();
    }

    LogFormatDesign design() {
        syncDesign();
        return design;
    }

    private void syncDesign() {
        design.elements().clear();
        for (Row r : rows) {
            design.elements().add(r.element);
        }
    }

    /** Redraws what depends on the rows and the style: the columns, the sample line and the warnings. */
    void refresh() {
        syncDesign();
        boolean json = design.style() == Style.JSON;
        keyColumn.setVisible(json);
        quotedColumn.setVisible(json);
        escapeRow.setVisible(!json);
        escapeRow.setManaged(!json);
        quote.setVisible(!json);
        quote.setManaged(!json);
        if (!json) {
            escape.setValue(design.escape());
        }
        sample.setText(design.preview());
        formatText.setText(design.text());
        List<String> found = design.warnings(name.get().strip());
        warnings.setText(String.join("\n", found));
        warnings.setManaged(!found.isEmpty());
        warnings.setVisible(!found.isEmpty());
        table.refresh();
    }

    private void changed() {
        refresh();
        onChange.run();
    }

    // ---------------------------------------------------------------- the palette

    private void buildPalette() {
        group.getItems().add(ALL_GROUPS);
        group.getItems().addAll(LogFields.categories());
        group.setValue(ALL_GROUPS);
        group.setMaxWidth(Double.MAX_VALUE);
        search.setPromptText("search, such as time or user");
        Runnable filter = () -> {
            String text = search.getText().strip().toLowerCase(Locale.ROOT);
            String chosen = group.getValue();
            filtered.setPredicate(f -> (ALL_GROUPS.equals(chosen) || f.category().equals(chosen))
                    && (text.isEmpty() || (f.title() + " " + f.variable() + " " + f.description()).toLowerCase(Locale.ROOT)
                    .contains(text)));
        };
        search.textProperty().addListener((obs, o, n) -> filter.run());
        group.setOnAction(e -> filter.run());
        palette.setPrefHeight(170);
        palette.getSelectionModel().selectedItemProperty().addListener((obs, o, f) -> {
            fieldInfo.setText(f == null ? "" : f.description() + "\nExample: " + f.sample()
                    + (f.note().isEmpty() ? "" : "\n" + f.note()));
        });
        palette.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && palette.getSelectionModel().getSelectedItem() != null) {
                addField(palette.getSelectionModel().getSelectedItem());
            }
        });
        fieldInfo.setWrapText(true);
        fieldInfo.setMinHeight(54);
        fieldInfo.setStyle("-fx-opacity: 0.85;");
        quote.setTooltip(new javafx.scene.control.Tooltip("Adds a quote mark before and after the field, as in \"$request\"."));
    }

    private HBox adders() {
        Button add = new Button("Add field");
        add.setOnAction(e -> {
            LogField f = palette.getSelectionModel().getSelectedItem();
            if (f != null) {
                addField(f);
            }
        });
        Button header = new Button("A request header");
        header.setOnAction(e -> ask("Request header", "Header name, such as X-Request-Id:").ifPresent(this::addHeader));
        Button variable = new Button("Another variable");
        variable.setOnAction(e -> ask("Variable", "Variable name, such as $my_variable:").ifPresent(this::addVariable));
        HBox box = new HBox(6, add, header, variable);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private HBox moveButtons() {
        Button up = new Button("Move up");
        up.setOnAction(e -> move(-1));
        Button down = new Button("Move down");
        down.setOnAction(e -> move(1));
        Button remove = new Button("Remove");
        remove.setOnAction(e -> remove());
        Button clear = new Button("Clear all");
        clear.setOnAction(e -> {
            rows.clear();
            changed();
        });
        Button text = new Button("Add text");
        text.setOnAction(e -> ask("Text", "Text to put between fields (for example a space, \" - \" or \"[\"):")
                .ifPresent(this::addText));
        Button space = new Button("Add space");
        space.setOnAction(e -> addText(" "));
        for (Button b : List.of(up, down, remove)) {
            b.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        }
        HBox box = new HBox(6, text, space, up, down, remove, clear);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private java.util.Optional<String> ask(String title, String prompt) {
        TextInputDialog dialog = new TextInputDialog();
        if (getScene() != null) {
            dialog.initOwner(getScene().getWindow());
        }
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        dialog.setContentText(prompt);
        return dialog.showAndWait().filter(s -> !s.isEmpty());
    }

    // ---------------------------------------------------------------- the rows

    private void buildTable() {
        table.setEditable(true);
        table.setPlaceholder(new Label("Nothing yet. Pick a field on the left and press Add field."));
        table.setPrefHeight(230);

        TableColumn<Row, String> kind = new TableColumn<>("Row");
        kind.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().element.isField() ? "Field" : "Text"));
        kind.setPrefWidth(55);
        kind.setSortable(false);

        TableColumn<Row, String> content = new TableColumn<>("Content");
        content.setCellValueFactory(c -> new SimpleStringProperty(display(c.getValue().element)));
        StringConverter<String> asTyped = new StringConverter<>() {
            @Override
            public String toString(String s) {
                return s;
            }

            @Override
            public String fromString(String s) {
                return s;
            }
        };
        content.setCellFactory(col -> new TextFieldTableCell<Row, String>(asTyped) {
            @Override
            public void startEdit() {
                Row row = getTableRow() == null ? null : getTableRow().getItem();
                if (row != null && !row.element.isField()) {
                    super.startEdit();
                }
            }

            @Override
            public void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setStyle(MONO);
            }
        });
        content.setOnEditCommit(ev -> {
            Row row = ev.getRowValue();
            if (!row.element.isField()) {
                row.element = row.element.withValue(stripMarks(ev.getNewValue()));
                changed();
            }
        });
        content.setPrefWidth(190);
        content.setSortable(false);

        TableColumn<Row, String> shows = new TableColumn<>("What it shows");
        shows.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().element.isField()
                ? LogFields.find(c.getValue().element.value()).map(LogField::title).orElse("(your own variable)") : ""));
        shows.setPrefWidth(160);
        shows.setSortable(false);

        keyColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().element.key()));
        keyColumn.setCellFactory(TextFieldTableCell.forTableColumn());
        keyColumn.setOnEditCommit(ev -> {
            ev.getRowValue().element = ev.getRowValue().element.withKey(ev.getNewValue().strip());
            changed();
        });
        keyColumn.setPrefWidth(110);
        keyColumn.setSortable(false);

        quotedColumn.setCellValueFactory(c -> {
            Row row = c.getValue();
            SimpleBooleanProperty property = new SimpleBooleanProperty(row.element.quoted());
            property.addListener((obs, was, now) -> {
                row.element = row.element.withQuoted(now);
                changed();
            });
            return property;
        });
        quotedColumn.setCellFactory(CheckBoxTableCell.forTableColumn(quotedColumn));
        quotedColumn.setPrefWidth(70);
        quotedColumn.setSortable(false);

        table.getColumns().addAll(List.of(kind, content, shows, keyColumn, quotedColumn));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    }

    /** How a row is shown: a field as its variable, text between marks so spaces can be seen. */
    private static String display(Element e) {
        return e.isField() ? e.value() : "‹" + e.value() + "›";
    }

    private static String stripMarks(String shown) {
        String s = shown;
        if (s.startsWith("‹")) {
            s = s.substring(1);
        }
        if (s.endsWith("›")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** Where new rows go: after the selected row, or at the end. */
    private int insertionIndex() {
        int selected = table.getSelectionModel().getSelectedIndex();
        return selected < 0 ? rows.size() : selected + 1;
    }

    /**
     * Adds a field. In the text style a space is put in first if the row before is a field, and the field goes in
     * quotes if that option is ticked; in the JSON style it becomes a key with a name that isn't already used.
     */
    void addField(LogField field) {
        addVariable(field.variable());
    }

    private void addHeader(String headerName) {
        if (!headerName.strip().matches("[A-Za-z0-9-]+")) {
            Dialogs.error(getScene() == null ? null : getScene().getWindow(), "Not a header name",
                    "A header name uses letters, digits and hyphens, such as X-Request-Id.");
            return;
        }
        addVariable(LogFields.requestHeader(headerName));
    }

    void addVariable(String variable) {
        String v = variable.strip();
        if (!v.startsWith("$")) {
            v = "$" + v;
        }
        int at = insertionIndex();
        List<Row> added = new ArrayList<>();
        Row field;
        if (design.style() == Style.JSON) {
            Element e = Element.field(v);
            field = new Row(e.withKey(uniqueKey(e.key())));
            added.add(field);
        } else {
            if (at > 0 && rows.get(at - 1).element.isField()) {
                added.add(new Row(Element.text(" ")));
            }
            if (quote.isSelected()) {
                added.add(new Row(Element.text("\"")));
            }
            field = new Row(Element.field(v));
            added.add(field);
            if (quote.isSelected()) {
                added.add(new Row(Element.text("\"")));
            }
        }
        rows.addAll(at, added);
        table.getSelectionModel().select(field);
        changed();
    }

    void addText(String literal) {
        if (design.style() == Style.JSON) {
            return;
        }
        int at = insertionIndex();
        Row row = new Row(Element.text(literal));
        rows.add(at, row);
        table.getSelectionModel().select(row);
        changed();
    }

    private String uniqueKey(String wanted) {
        List<String> taken = rows.stream().map(r -> r.element.key()).toList();
        String key = wanted;
        for (int n = 2; taken.contains(key); n++) {
            key = wanted + "_" + n;
        }
        return key;
    }

    private void move(int by) {
        int i = table.getSelectionModel().getSelectedIndex();
        int j = i + by;
        if (i < 0 || j < 0 || j >= rows.size()) {
            return;
        }
        Row row = rows.remove(i);
        rows.add(j, row);
        table.getSelectionModel().select(row);
        changed();
    }

    private void remove() {
        Row row = table.getSelectionModel().getSelectedItem();
        if (row != null) {
            rows.remove(row);
            changed();
        }
    }

    private static Label hintLabel(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setStyle("-fx-font-weight: bold;");
        return label;
    }

    // For tests.

    List<String> rowTexts() {
        return rows.stream().map(r -> display(r.element)).toList();
    }

    String sampleLine() {
        return sample.getText();
    }

    String formatString() {
        return formatText.getText();
    }

    void selectRow(int index) {
        table.getSelectionModel().select(index);
    }

    void setQuoteFields(boolean on) {
        quote.setSelected(on);
    }

    void moveSelected(int by) {
        move(by);
    }

    void removeSelected() {
        remove();
    }

    int paletteSize() {
        return filtered.size();
    }

    void search(String text, String groupName) {
        search.setText(text);
        group.setValue(groupName);
        group.getOnAction().handle(null);
    }

    static String allGroups() {
        return ALL_GROUPS;
    }
}
