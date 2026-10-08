package mt.su.nrm.ui;

import mt.su.nrm.model.ServerProfile;
import mt.su.nrm.nginx.RemoteConfig;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Base of the pages of optional features. While the page is on screen it redraws whenever the server's configuration
 * is (re)loaded or its pending changes change, so a page always reflects what would be applied. Until the
 * configuration is available it shows why instead.
 */
abstract class FeaturePage extends BorderPane {

    final ServerProfile profile;
    final ServerConnection connection;

    private final ChangeListener<Object> redraw = (obs, o, n) -> refresh();

    FeaturePage(ServerProfile profile, ServerConnection connection) {
        this.profile = profile;
        this.connection = connection;
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) {
                connection.configStateProperty().removeListener(redraw);
                connection.pendingCountProperty().removeListener(redraw);
                hidden();
            } else {
                connection.configStateProperty().addListener(redraw);
                connection.pendingCountProperty().addListener(redraw);
                refresh();
            }
        });
    }

    /** Draws the page for the current state. Called on every change, so it must be safe to call again. */
    abstract void refresh();

    /** Reads again from the server what the page shows. */
    void reload() {
        refresh();
    }

    /** The page left the screen: stop anything running in the background. */
    void hidden() {
    }

    final RemoteConfig config() {
        return connection.config();
    }

    final boolean ready() {
        return connection.configState() == ServerConnection.ConfigState.LOADED && connection.config() != null;
    }

    /** Why the page can't be drawn yet: still loading, failed, or not connected. */
    final Node notReadyView() {
        switch (connection.configState()) {
            case LOADING:
                return centered(ProgressDialog.spinner(48), new Label("Reading the nginx configuration..."));
            case FAILED:
                return message("The configuration could not be read:\n" + connection.configError());
            default:
                return message("The configuration has not been loaded. Connect to the server first.");
        }
    }

    static Node message(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setOpacity(0.85);
        VBox box = new VBox(label);
        box.setPadding(new Insets(16));
        return box;
    }

    static Node centered(Node... nodes) {
        VBox box = new VBox(14, nodes);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(30));
        return box;
    }

    static Label heading(String text) {
        Label heading = new Label(text);
        heading.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        return heading;
    }

    static Label hint(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setOpacity(0.75);
        return label;
    }

    final Window window() {
        return getScene() == null ? null : getScene().getWindow();
    }
}
