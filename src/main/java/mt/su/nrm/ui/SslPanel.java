package mt.su.nrm.ui;

import mt.su.nrm.model.ServerProfile;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;

/**
 * SSL Certificates: a Certificates tab (everything found on the server, Let's Encrypt) and a
 * Certificate Authority tab, which has its own sub-tabs: the Authority itself and the Certificates
 * it has issued. All of them show the same list, read from the server once.
 */
final class SslPanel extends BorderPane {

    private final CertificatesPanel certificates;

    SslPanel(ServerProfile profile, ServerConnection connection) {
        certificates = new CertificatesPanel(profile, connection);

        TabPane authority = new TabPane(
                tab("Authority", new AuthorityPanel(profile, connection)),
                tab("Certificates", new IssuedCertificatesPanel(profile, connection)));
        authority.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        authority.setSide(javafx.geometry.Side.TOP);
        authority.getStyleClass().add(TabPane.STYLE_CLASS_FLOATING);

        TabPane main = new TabPane(tab("Certificates", certificates), tab("Certificate Authority", authority));
        main.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        setCenter(main);
    }

    /** Re-reads the certificates from the server. */
    void reload() {
        certificates.refreshAll();
    }

    private static Tab tab(String title, javafx.scene.Node content) {
        Tab t = new Tab(title, content);
        t.setClosable(false);
        return t;
    }
}
