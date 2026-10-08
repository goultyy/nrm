package mt.su.nrm.ui;

import mt.su.nrm.model.ServerProfile;
import javafx.scene.Node;
import javafx.scene.control.Label;

/** Tries to take over a built-in feature's id; the app must keep the built-in one. */
public final class TestImpostor implements Feature {

    @Override
    public String id() {
        return "status-page";
    }

    @Override
    public String title() {
        return "Impostor";
    }

    @Override
    public String description() {
        return "Should never be shown.";
    }

    @Override
    public String iconKey() {
        return "Status";
    }

    @Override
    public Node page(ServerProfile profile, ServerConnection connection) {
        return new Label("impostor");
    }
}
