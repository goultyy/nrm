package mt.su.nrm.ui;

import javafx.geometry.Insets;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;

/** Small builders shared by the wizard pages. */
final class WizardParts {

    private WizardParts() {
    }

    /** A two-column form: labels on the left, fields filling the rest. */
    static GridPane form() {
        GridPane g = new GridPane();
        g.setHgap(12);
        g.setVgap(10);
        g.setPadding(new Insets(6));
        ColumnConstraints label = new ColumnConstraints();
        label.setMinWidth(150);
        ColumnConstraints field = new ColumnConstraints();
        field.setHgrow(Priority.ALWAYS);
        g.getColumnConstraints().addAll(label, field);
        return g;
    }

    /** Adds a labelled row; returns the next free row index. */
    static int row(GridPane grid, int row, String label, Node field) {
        Label l = new Label(label);
        GridPane.setValignment(l, VPos.TOP);
        grid.addRow(row, l, field);
        return row + 1;
    }

    /** Adds explanatory text spanning both columns; returns the next free row index. */
    static int note(GridPane grid, int row, Label text) {
        grid.add(text, 0, row, 2, 1);
        return row + 1;
    }

    /** Muted, wrapped explanatory text. */
    static Label hint(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setOpacity(0.75);
        return l;
    }

    /** The whole number in a text field, or null if it isn't one. */
    static Long number(String text) {
        try {
            return Long.parseLong(text.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
