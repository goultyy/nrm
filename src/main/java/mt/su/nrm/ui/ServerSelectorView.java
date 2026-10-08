package mt.su.nrm.ui;

import mt.su.nrm.config.ProfileRepository;
import mt.su.nrm.config.ProfileStoreException;
import mt.su.nrm.config.ProfileValidationException;
import mt.su.nrm.model.ServerProfile;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The server selector: the list of saved profiles with Add, Edit and Delete. It talks to the
 * {@link ProfileRepository}, and hands "connect" requests to the main window.
 */
public final class ServerSelectorView extends BorderPane {

    private final ProfileRepository repository;
    private final ObservableList<ServerProfile> items = FXCollections.observableArrayList();
    private final javafx.scene.layout.FlowPane tiles = new javafx.scene.layout.FlowPane(12, 12);
    private final javafx.scene.control.ScrollPane list = new javafx.scene.control.ScrollPane(tiles);
    private final javafx.beans.property.ObjectProperty<ServerProfile> selected = new javafx.beans.property.SimpleObjectProperty<>();
    private final javafx.beans.property.BooleanProperty testing = new javafx.beans.property.SimpleBooleanProperty(false);

    private final java.util.function.Consumer<ServerProfile> onConnect;
    private final Runnable onChanged;

    public ServerSelectorView(ProfileRepository repository) {
        this(repository, null, () -> { });
    }

    /**
     * @param onConnect called when the user asks to connect to a server; null hides the button
     * @param onChanged called after the list of profiles changed (added, edited, deleted, tested)
     */
    public ServerSelectorView(ProfileRepository repository, java.util.function.Consumer<ServerProfile> onConnect,
                              Runnable onChanged) {
        this.onConnect = onConnect;
        this.onChanged = onChanged;
        this.repository = repository;

        Label heading = new Label("Servers");
        heading.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        Label empty = new Label("No servers yet. Click Add to save your first one.");
        list.setFitToWidth(true);
        tiles.setPadding(new Insets(8));
        list.setFocusTraversable(true);
        list.setStyle("-fx-background-color: transparent;");
        items.addListener((javafx.collections.ListChangeListener<ServerProfile>) c -> rebuildTiles(empty));
        // A click on empty space clears the selection.
        tiles.setOnMouseClicked(e -> {
            if (e.getTarget() == tiles) {
                select(null);
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);

        Button add = new Button("Add");
        Button edit = new Button("Edit");
        Button delete = new Button("Delete");
        Button test = new Button("Test connection");
        Button connect = new Button("Connect");
        connect.setDefaultButton(false);
        connect.disableProperty().bind(Bindings.isNull(selected));
        connect.setOnAction(e -> connectSelected());
        edit.disableProperty().bind(Bindings.isNull(selected));
        delete.disableProperty().bind(edit.disableProperty());
        test.disableProperty().bind(edit.disableProperty().or(testing));
        add.setOnAction(e -> addProfile());
        test.setOnAction(e -> testSelected());
        edit.setOnAction(e -> editSelected());
        delete.setOnAction(e -> deleteSelected());

        list.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DELETE) {
                deleteSelected();
            } else if (e.getCode() == KeyCode.ENTER) {
                editSelected();
            }
        });

        HBox buttons = new HBox(8, add, edit, delete, test);
        if (onConnect != null) {
            buttons.getChildren().add(0, connect);
        }
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(10, heading, list, buttons);
        content.setPadding(new Insets(16));
        setCenter(content);

        // Initial fill only: the owner is still being constructed, so it must not be called back yet.
        items.setAll(repository.list());
        rebuildTiles(empty);
    }

    private void addProfile() {
        new ProfileEditorDialog(null, namesExcept(null))
                .showAndWait(window())
                .ifPresent(draft -> save(() -> repository.add(draft), "add"));
    }

    private void editSelected() {
        ServerProfile selected = this.selected.get();
        if (selected == null) {
            return;
        }
        new ProfileEditorDialog(selected, namesExcept(selected.getId()))
                .showAndWait(window())
                .ifPresent(edited -> save(() -> repository.update(edited), "save"));
    }

    private void testSelected() {
        ServerProfile selected = this.selected.get();
        if (selected == null) {
            return;
        }
        testing.set(true);
        ConnectionTestRunner.run(window(), repository, selected, () -> testing.set(false),
                saved -> reload(saved.getId()));
    }

    private void deleteSelected() {
        ServerProfile selected = this.selected.get();
        if (selected == null) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete \"" + selected.getName() + "\" (" + selected.displayAddress() + ")?\n\n"
                        + "This removes the saved profile and any stored passwords from this computer. "
                        + "Nothing on the server itself is changed.",
                ButtonType.CANCEL, new ButtonType("Delete", javafx.scene.control.ButtonBar.ButtonData.OK_DONE));
        confirm.setTitle("Delete Server");
        confirm.setHeaderText("Delete this server?");
        confirm.initOwner(window());
        confirm.getDialogPane().lookupButton(ButtonType.CANCEL).requestFocus();
        Dialogs.tighten(confirm);
        Optional<ButtonType> answer = confirm.showAndWait();
        if (answer.isPresent() && answer.get().getButtonData() == javafx.scene.control.ButtonBar.ButtonData.OK_DONE) {
            save(() -> {
                repository.delete(selected.getId());
                return null;
            }, "delete");
        }
    }

    /** Runs a repository change, then refreshes the list; store failures are shown, not swallowed. */
    private void save(StoreAction action, String verb) {
        try {
            ServerProfile saved = action.run();
            reload(saved == null ? null : saved.getId());
        } catch (ProfileValidationException e) {
            error("Could not " + verb + " the server", String.join("\n", e.errors()));
        } catch (ProfileStoreException e) {
            error("Could not " + verb + " the server", e.getMessage());
        }
    }

    /** Reloads the list from the repository, e.g. after another part of the app changed a profile. */
    public void refresh() {
        UUID keep = selected.get() == null ? null : selected.get().getId();
        items.setAll(repository.list());
        select(keep);
    }

    private void connectSelected() {
        ServerProfile selected = this.selected.get();
        if (selected != null && onConnect != null) {
            onConnect.accept(selected);
        }
    }

    private void select(UUID id) {
        ServerProfile found = id == null ? null
                : items.stream().filter(p -> id.equals(p.getId())).findFirst().orElse(null);
        selected.set(found);
        for (javafx.scene.Node n : tiles.getChildren()) {
            if (n instanceof ServerTile t) {
                t.setSelected(found != null && t.profile().getId().equals(found.getId()));
            }
        }
    }

    private void rebuildTiles(Label empty) {
        UUID keep = selected.get() == null ? null : selected.get().getId();
        tiles.getChildren().clear();
        if (items.isEmpty()) {
            tiles.getChildren().add(empty);
        }
        for (ServerProfile p : items) {
            ServerTile tile = new ServerTile(p);
            tile.setOnMouseClicked(e -> {
                if (e.getButton() != MouseButton.PRIMARY) {
                    return;
                }
                select(p.getId());
                list.requestFocus();
                if (e.getClickCount() == 2) {
                    if (onConnect != null) {
                        connectSelected();
                    } else {
                        editSelected();
                    }
                }
            });
            tiles.getChildren().add(tile);
        }
        select(keep);
    }

    private void reload(UUID select) {
        items.setAll(repository.list());
        select(select);
        onChanged.run();
    }

    private Set<String> namesExcept(UUID id) {
        return items.stream()
                .filter(p -> !p.getId().equals(id))
                .map(ServerProfile::getName)
                .collect(Collectors.toSet());
    }

    private void error(String header, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.setTitle("NRM");
        alert.setHeaderText(header);
        alert.initOwner(window());
        Dialogs.tighten(alert);
        alert.showAndWait();
    }

    private Window window() {
        return getScene() == null ? null : getScene().getWindow();
    }

    @FunctionalInterface
    private interface StoreAction {
        ServerProfile run() throws ProfileStoreException;
    }
}
