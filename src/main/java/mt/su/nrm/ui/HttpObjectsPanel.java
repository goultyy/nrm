package mt.su.nrm.ui;

import mt.su.nrm.model.ServerProfile;
import mt.su.nrm.nginx.RemoteConfig;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Base for the screens that list objects living at http level (upstreams, cache zones, limit
 * zones): a table with Add, Edit and Delete over the in-memory configuration. Subclasses supply
 * the rows and the dialogs. Changes only become pending changes; nothing reaches the server here.
 *
 * @param <T> the object shown in a row
 */
abstract class HttpObjectsPanel<T> extends BorderPane {

    private final ServerProfile profile;
    private final ServerConnection connection;
    private final ConnectionManager manager;
    private final TableView<T> table = new TableView<>();
    private final javafx.beans.value.ChangeListener<ServerConnection.ConfigState> stateListener =
            (obs, o, n) -> refresh();

    HttpObjectsPanel(ServerProfile profile, ServerConnection connection, ConnectionManager manager,
                     String emptyText) {
        this.profile = profile;
        this.connection = connection;
        this.manager = manager;
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(emptyText));
        table.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                editSelected();
            }
        });
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) {
                connection.configStateProperty().removeListener(stateListener);
            } else {
                connection.configStateProperty().addListener(stateListener);
                refresh();
            }
        });
    }

    // ---------------------------------------------------------------- for subclasses

    /** The objects to show, from the loaded configuration. */
    abstract List<T> items(RemoteConfig config);

    /** Opens the dialog for a new object and adds it to the configuration; returns true if it did. */
    abstract boolean add(RemoteConfig config, Window owner);

    /** Opens the dialog for an existing object; returns true if it changed something. */
    abstract boolean edit(RemoteConfig config, T item, Window owner);

    abstract void delete(RemoteConfig config, T item);

    abstract String describe(T item);

    /** Why the item may not be changed, or null. */
    abstract String readOnlyReason(RemoteConfig config, T item);

    /** Why the item can't be deleted although it can be edited (something still uses it), or null. */
    String deleteBlockedReason(RemoteConfig config, T item) {
        return null;
    }

    /** Call from the subclass constructor to define the columns. */
    void addColumn(String title, double width, Function<T, String> value) {
        TableColumn<T, String> c = new TableColumn<>(title);
        c.setPrefWidth(width);
        c.setCellValueFactory(cell -> new javafx.beans.property.SimpleStringProperty(value.apply(cell.getValue())));
        table.getColumns().add(c);
    }

    /** Call after the columns are defined. */
    void start() {
        refresh();
    }

    // ---------------------------------------------------------------- behaviour

    void addItem() {
        RemoteConfig config = connection.config();
        if (config == null || config.global() == null) {
            Dialogs.error(window(), "Can't add here", "The main nginx.conf has no http block to add this to.");
            return;
        }
        String reason = config.mainReadOnlyReason();
        if (reason != null) {
            Dialogs.info(window(), "Can't add here", reason);
            return;
        }
        if (add(config, window())) {
            changed();
        }
    }

    void editSelected() {
        RemoteConfig config = connection.config();
        T item = table.getSelectionModel().getSelectedItem();
        if (config == null || item == null) {
            return;
        }
        String reason = readOnlyReason(config, item);
        if (reason != null) {
            Dialogs.info(window(), "This can't be edited here", reason);
            return;
        }
        if (edit(config, item, window())) {
            changed();
        }
    }

    void deleteSelected() {
        RemoteConfig config = connection.config();
        T item = table.getSelectionModel().getSelectedItem();
        if (config == null || item == null) {
            return;
        }
        String reason = readOnlyReason(config, item);
        if (reason != null) {
            Dialogs.info(window(), "This can't be deleted here", reason);
            return;
        }
        String inUse = deleteBlockedReason(config, item);
        if (inUse != null) {
            Dialogs.info(window(), "This can't be deleted yet", inUse);
            return;
        }
        if (Dialogs.confirm(window(), "Delete " + describe(item) + "?",
                "It is removed from the pending changes. Nothing on the server changes until you apply.", "Delete")) {
            delete(config, item);
            changed();
        }
    }

    private void refresh() {
        switch (connection.configState()) {
            case LOADING:
                setCenter(centered(ProgressDialog.spinner(48), new Label("Reading the nginx configuration...")));
                break;
            case LOADED:
                setCenter(loadedView());
                break;
            case FAILED:
                Button retry = new Button("Try again");
                retry.setOnAction(e -> manager.loadConfig(profile, () -> { }));
                Label error = new Label("The configuration could not be read:\n" + connection.configError());
                error.setWrapText(true);
                setCenter(centered(error, retry));
                break;
            default:
                setCenter(centered(new Label("The configuration has not been loaded.")));
        }
    }

    private Node loadedView() {
        RemoteConfig config = connection.config();
        table.setItems(FXCollections.observableArrayList(new ArrayList<>(items(config))));
        Button add = new Button("Add");
        add.setOnAction(e -> addItem());
        Button edit = new Button("Edit");
        edit.setOnAction(e -> editSelected());
        Button delete = new Button("Delete");
        delete.setOnAction(e -> deleteSelected());
        edit.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        delete.disableProperty().bind(edit.disableProperty());
        HBox buttons = new HBox(8, add, edit, delete);
        buttons.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(10, table, buttons);
        box.setPadding(new Insets(12));
        VBox.setVgrow(table, Priority.ALWAYS);
        Node extra = extraView(config);
        if (extra != null) {
            box.getChildren().add(extra);
            if (extra instanceof VBox) {
                VBox.setVgrow(extra, Priority.ALWAYS);
            }
        }
        return box;
    }

    /** Something to show under the table and buttons (cache statistics), or null for nothing. */
    Node extraView(RemoteConfig config) {
        return null;
    }

    /** For subclasses' extra views: the configuration was edited, so pending changes and the table update. */
    void changed() {
        connection.configChanged();
        refresh();
    }

    private static Node centered(Node... nodes) {
        VBox box = new VBox(14, nodes);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(30));
        return box;
    }

    Window window() {
        return getScene() == null ? null : getScene().getWindow();
    }
}
