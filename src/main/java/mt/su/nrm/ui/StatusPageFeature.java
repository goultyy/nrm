package mt.su.nrm.ui;

import mt.su.nrm.model.ServerProfile;
import javafx.scene.Node;

/** The status page add-on: live connection and request figures from nginx's stub_status. */
final class StatusPageFeature implements Feature {

    @Override
    public String id() {
        return "status-page";
    }

    @Override
    public String title() {
        return "Status page";
    }

    @Override
    public String description() {
        return "Live figures from nginx: connections now, requests per second and a chart of the last minutes. "
                + "Adds a small server that answers on the server itself only.";
    }

    @Override
    public String iconKey() {
        return "Status";
    }

    @Override
    public Node page(ServerProfile profile, ServerConnection connection) {
        return new StatusPage(profile, connection);
    }
}
