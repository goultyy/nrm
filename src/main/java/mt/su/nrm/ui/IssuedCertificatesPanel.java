package mt.su.nrm.ui;

import mt.su.nrm.model.ServerProfile;
import mt.su.nrm.ssh.CaService;
import mt.su.nrm.ssl.CertificateInfo;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.List;

/**
 * The Certificates tab under Certificate Authority: pick one of the server's authorities, see what
 * it has issued, and issue more from it.
 */
final class IssuedCertificatesPanel extends CertificateViewBase {

    /** Which authority is chosen, by certificate path; kept across redraws. */
    private String selectedCa;

    IssuedCertificatesPanel(ServerProfile profile, ServerConnection connection) {
        super(profile, connection);
        table.setPlaceholder(new Label("This authority has not issued any certificates yet."));
        addStandardColumns();
        installContextMenu();
        render();
    }

    @Override
    Node content(List<CertificateInfo> all) {
        List<CertificateInfo> cas = authorityTree(authorities());
        if (cas.isEmpty()) {
            return centered(new Label("This server has no certificate authority yet. Create one on the Authority tab."));
        }
        CertificateInfo ca = cas.stream().filter(c -> c.path().equals(selectedCa)).findFirst().orElse(cas.get(0));
        selectedCa = ca.path();

        ComboBox<CertificateInfo> chooser = new ComboBox<>(FXCollections.observableArrayList(cas));
        chooser.setConverter(new StringConverter<>() {
            @Override
            public String toString(CertificateInfo c) {
                return c == null ? "" : authorityLabel(c);
            }

            @Override
            public CertificateInfo fromString(String s) {
                return null;
            }
        });
        chooser.setValue(ca);
        chooser.setOnAction(e -> {
            if (chooser.getValue() != null && !chooser.getValue().path().equals(selectedCa)) {
                selectedCa = chooser.getValue().path();
                render();
            }
        });
        HBox picker = new HBox(8, new Label("Certificate authority:"), chooser);
        picker.setAlignment(Pos.CENTER_LEFT);
        picker.setPadding(new javafx.geometry.Insets(12, 12, 0, 12));

        Button issue = new Button("Issue certificate");
        issue.setOnAction(e -> issueCertificate(ca));
        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> reload());
        Button use = new Button("Use in virtual host");
        use.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        use.setOnAction(e -> useInHost());
        Button delete = new Button("Delete");
        delete.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        delete.setOnAction(e -> deleteSelectedCertificate());
        FlowPane buttons = new FlowPane(8, 8, viewButton(), downloadButton(), issue, use, delete, refresh);

        Label note = new Label("Certificates are created with their key on the server (the key stays there unless you choose to download it, after a warning). "
                + "Clients trust them once this CA's certificate is installed (Authority tab).");
        note.setWrapText(true);
        note.setOpacity(0.8);
        return new VBox(0, picker, tableWithDetails(issuedBy(ca, all), buttons, note));
    }

    private void issueCertificate(CertificateInfo ca) {
        CaDialogs.issueCertificate(window(), ca.commonName()).ifPresent(request -> {
            CaService.CaRef ref;
            try {
                ref = CaService.authority(profile.getPaths(), ca.path());
            } catch (java.io.IOException e) {
                Dialogs.error(window(), "Can't use this authority", e.getMessage());
                return;
            }
            work("Issuing", "Creating a key and a certificate signed by " + ca.commonName() + " on the server...",
                    () -> CaService.issue(connection.session(), profile.getPaths(), ref, request), result -> {
                        if (result.ok()) {
                            Dialogs.info(window(), "Certificate issued", "Certificate: " + result.certPath()
                                    + "\nKey: " + result.keyPath() + "\n\nUse \"Use in virtual host\" to put it on a site.");
                            reload();
                        } else {
                            Dialogs.showOutput(window(), Alert.AlertType.ERROR, "The certificate was not issued", "",
                                    result.output());
                        }
                    });
        });
    }
}
