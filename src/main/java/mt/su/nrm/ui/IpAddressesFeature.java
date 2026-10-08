package mt.su.nrm.ui;

import mt.su.nrm.model.ServerProfile;
import javafx.scene.Node;

/** The IP addresses add-on: every address on the server, what nginx does with each, and their external addresses. */
final class IpAddressesFeature implements Feature {

    @Override
    public String id() {
        return "ip-addresses";
    }

    @Override
    public String title() {
        return "IP addresses";
    }

    @Override
    public String description() {
        return "Every IP address on the server, which ones nginx listens on now and is set up to use, and the external "
                + "address behind each private one (for cloud servers and NAT). View only.";
    }

    @Override
    public String iconKey() {
        return "IP Addresses";
    }

    @Override
    public Node page(ServerProfile profile, ServerConnection connection) {
        return new IpAddressesPage(profile, connection);
    }
}
