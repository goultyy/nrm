package mt.su.nrm.ui;

import mt.su.nrm.model.ServerProfile;
import mt.su.nrm.nginx.NginxParseException;
import mt.su.nrm.nginx.PendingChange;
import mt.su.nrm.nginx.RemoteConfig;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;

/**
 * The Pending Changes tab: every file that differs from what is on the server, with a diff, and
 * the buttons to discard changes or validate and apply them.
 */
final class PendingChangesPanel extends BorderPane {

    private final ServerProfile profile;
    private final ServerConnection connection;
    private final ConnectionManager manager;
    /** Filled in place, never replaced: {@link #nothingToApply} is bound to this very list. */
    private final javafx.collections.ObservableList<PendingChange> items = FXCollections.observableArrayList();
    private final ListView<PendingChange> list = new ListView<>(items);
    private final TextArea diff = new TextArea();
    private final BooleanProperty busy = new SimpleBooleanProperty(false);
    private final ChangeListener<Number> countListener = (obs, o, n) -> refresh();

    /** True while there is nothing to apply (or a change is being applied); the Actions pane binds to it too. */
    private final javafx.beans.binding.BooleanBinding nothingToApply;

    javafx.beans.value.ObservableBooleanValue nothingToApply() {
        return nothingToApply;
    }

    PendingChangesPanel(ServerProfile profile, ServerConnection connection, ConnectionManager manager) {
        this.profile = profile;
        this.connection = connection;
        this.manager = manager;

        nothingToApply = javafx.beans.binding.Bindings.isEmpty(items).or(busy);
        list.setPlaceholder(new Label("No pending changes."));
        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(PendingChange c, boolean empty) {
                super.updateItem(c, empty);
                setText(empty || c == null ? null : c.describe());
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> showDiff(n));
        diff.setEditable(false);
        diff.setStyle("-fx-font-family: 'Consolas', 'Menlo', monospace;");

        Button discard = new Button("Discard selected");
        discard.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull().or(busy));
        discard.setOnAction(e -> discard(false));
        Button discardAll = new Button("Discard all");
        discardAll.disableProperty().bind(nothingToApply);
        discardAll.setOnAction(e -> discard(true));
        Button apply = new Button("Validate and apply");
        apply.setDefaultButton(false);
        apply.disableProperty().bind(nothingToApply);
        apply.setOnAction(e -> apply());

        HBox buttons = new HBox(8, apply, discard, discardAll);
        buttons.setAlignment(Pos.CENTER_LEFT);

        SplitPane split = new SplitPane(list, diff);
        split.setDividerPositions(0.32);
        VBox box = new VBox(10, split, buttons);
        box.setPadding(new Insets(12));
        VBox.setVgrow(split, Priority.ALWAYS);
        setCenter(box);

        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) {
                connection.pendingCountProperty().removeListener(countListener);
            } else {
                connection.pendingCountProperty().addListener(countListener);
                refresh();
            }
        });
        refresh();
    }

    /** Starts the validate/apply pipeline (also used by the Actions pane). */
    void apply() {
        if (busy.get()) {
            return;
        }
        busy.set(true);
        ApplyFlow.run(window(), profile, connection, manager, () -> {
            busy.set(false);
            refresh();
        });
    }

    void discardAll() {
        discard(true);
    }

    private void discard(boolean all) {
        RemoteConfig config = connection.config();
        if (config == null) {
            return;
        }
        PendingChange selected = list.getSelectionModel().getSelectedItem();
        String what = all ? "all pending changes" : "the changes to " + (selected == null ? "" : selected.path());
        if (!all && selected == null) {
            return;
        }
        if (!Dialogs.confirm(window(), "Discard " + (all ? "everything" : "this change") + "?",
                "This throws away " + what + ". The server is not affected.", "Discard")) {
            return;
        }
        try {
            if (all) {
                config.discardAll();
            } else {
                config.discard(selected);
            }
        } catch (NginxParseException e) {
            Dialogs.error(window(), "Could not restore the file", e.getMessage());
        }
        connection.configChanged();
        refresh();
    }

    private void refresh() {
        RemoteConfig config = connection.config();
        List<PendingChange> changes = config == null ? List.of() : config.pendingChanges();
        items.setAll(changes);
        if (!changes.isEmpty()) {
            list.getSelectionModel().select(0);
        } else {
            showDiff(null);
        }
    }

    private void showDiff(PendingChange change) {
        diff.setText(change == null ? "" : change.diff());
    }

    private Window window() {
        return getScene() == null ? null : getScene().getWindow();
    }
}
