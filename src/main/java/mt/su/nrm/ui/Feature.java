package mt.su.nrm.ui;

import mt.su.nrm.model.ServerProfile;
import javafx.scene.Node;

/**
 * An optional add-on: a page of its own for each server, off until the user turns it on under View > Features. The
 * built-in ones are listed in {@link Features}; more can be provided by registering an implementation in
 * {@code META-INF/services/mt.su.nrm.ui.Feature} (it is found with {@link java.util.ServiceLoader}).
 * <p>
 * A feature changes server configuration only through the pending changes, like everything else in the app, and runs
 * anything it needs on the server through the connection, so it shows in the command log.
 */
public interface Feature {

    /** A short stable name used to remember that the feature is on, such as {@code status-page}. */
    String id();

    /** The name in the tree and on the overview. */
    String title();

    /** What it does, in a sentence or two, for the Features list. */
    String description();

    /** Which picture to draw for it (see {@code RetroIcon}). */
    String iconKey();

    /** The page for one server. It is created again each time the entry is selected. */
    Node page(ServerProfile profile, ServerConnection connection);
}
