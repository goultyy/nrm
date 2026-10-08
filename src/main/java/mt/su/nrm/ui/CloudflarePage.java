package mt.su.nrm.ui;

import mt.su.nrm.model.ServerProfile;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.function.Function;

/**
 * Base of the Cloudflare pages (the Cloudflare page, the lists of DNS zones and tunnels, one zone, one tunnel).
 * A page starts when it is first shown, draws from the server's {@link mt.su.nrm.cloudflare.CloudflareSession}, and
 * only ever stages changes: sending them is the shared review-and-apply in {@link CloudflareHub}.
 */
abstract class CloudflarePage extends javafx.scene.layout.BorderPane {

    final ServerProfile profile;
    final ServerConnection connection;
    final CloudflareHub hub;
    private boolean started;

    CloudflarePage(ServerProfile profile, ServerConnection connection) {
        this.profile = profile;
        this.connection = connection;
        this.hub = connection.cloudflareHub();
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null && !started) {
                started = true;
                start();
            }
        });
    }

    /** Called once, when the page is first shown. */
    abstract void start();

    /** Draws the page from what is loaded, reading from Cloudflare only what isn't (after an apply or a discard). */
    abstract void showCurrent();

    /** Reads this page's data from Cloudflare again (asks first if that would drop staged changes). */
    abstract void reload();

    /** "Add" for the page: a DNS record, or a published application. Pages without one do nothing. */
    void add() {
    }

    void editSelected() {
    }

    void deleteSelected() {
    }

    /** Deletes the zone or tunnel this page shows, after the typed confirmation. Other pages show neither. */
    void removeThis() {
    }

    /** A menu entry; {@code disabled} greys it out (for example Edit when no row is selected). */
    static javafx.scene.control.MenuItem menuItem(String text, Runnable run, boolean disabled) {
        javafx.scene.control.MenuItem item = new javafx.scene.control.MenuItem(text);
        item.setOnAction(e -> run.run());
        item.setDisable(disabled);
        return item;
    }

    static javafx.scene.control.MenuItem menuSeparator() {
        return new javafx.scene.control.SeparatorMenuItem();
    }

    /** Opens a right-click menu where the user clicked. */
    static void showMenu(Node anchor, javafx.scene.input.ContextMenuEvent event, javafx.scene.control.MenuItem... items) {
        new javafx.scene.control.ContextMenu(items).show(anchor, event.getScreenX(), event.getScreenY());
        event.consume();
    }

    /**
     * Forgets the open session so the zones and tunnels are read again. Refused (with a message) while changes are
     * staged, because they belong to the session.
     */
    final boolean dropSession() {
        mt.su.nrm.cloudflare.CloudflareSession open = connection.cloudflare();
        if (open != null && open.workspace().hasChanges()) {
            Dialogs.info(window(), "There are staged changes",
                    "Apply or discard the staged Cloudflare changes before reading the account again.");
            return false;
        }
        connection.setCloudflare(null);
        return true;
    }

    final void reviewAndApply() {
        hub.reviewAndApply(window(), this::showCurrent);
    }

    final void discardAll() {
        hub.discardAll(window(), this::showCurrent);
    }

    final BooleanBinding nothingToApply() {
        return hub.nothingToApply();
    }

    // ---------------------------------------------------------------- pieces for the subclasses

    /** The staged-changes line with Review and apply / Discard all, shown at the bottom of the detail pages. */
    final Node stagedBar() {
        Label staged = new Label();
        staged.textProperty().bind(connection.cloudflarePendingProperty().asString().map(n -> n.equals("0")
                ? "No staged changes."
                : n + " staged change" + (n.equals("1") ? "" : "s") + ". Nothing is sent until you apply."));
        Button apply = new Button("Review and apply");
        apply.setOnAction(e -> reviewAndApply());
        apply.disableProperty().bind(nothingToApply());
        Button discard = new Button("Discard all");
        discard.setOnAction(e -> discardAll());
        discard.disableProperty().bind(nothingToApply());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8, staged, spacer, apply, discard);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    /** A spinner and what is being waited for; invisible when nothing is running. */
    final Node busyIndicator() {
        ProgressIndicator spinner = ProgressDialog.spinner(18);
        Label text = new Label();
        text.textProperty().bind(hub.busyTextProperty());
        HBox box = new HBox(8, spinner, text);
        box.setAlignment(Pos.CENTER_LEFT);
        box.visibleProperty().bind(hub.busyProperty());
        box.managedProperty().bind(hub.busyProperty());
        return box;
    }

    static <T> void column(TableView<T> table, String title, double width, Function<T, String> value) {
        TableColumn<T, String> c = new TableColumn<>(title);
        c.setPrefWidth(width);
        c.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        table.getColumns().add(c);
    }

    static Label heading(String text) {
        Label heading = new Label(text);
        heading.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        return heading;
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

    final void error(String header, String message) {
        // A page that was navigated away from has nobody to tell.
        if (getScene() != null) {
            Dialogs.error(window(), header, message);
        }
    }

    final Window window() {
        return getScene() == null ? null : getScene().getWindow();
    }
}
