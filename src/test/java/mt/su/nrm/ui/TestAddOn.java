package mt.su.nrm.ui;

import mt.su.nrm.model.ServerProfile;
import javafx.scene.Node;
import javafx.scene.control.Label;

/** A feature supplied from outside the app, found through the service loader (see META-INF/services in the test resources). */
public final class TestAddOn implements Feature {

    @Override
    public String id() {
        return "test-addon";
    }

    @Override
    public String title() {
        return "Test add-on";
    }

    @Override
    public String description() {
        return "Only exists in tests.";
    }

    @Override
    public String iconKey() {
        return "Status";
    }

    @Override
    public Node page(ServerProfile profile, ServerConnection connection) {
        return new Label("hello");
    }
}
