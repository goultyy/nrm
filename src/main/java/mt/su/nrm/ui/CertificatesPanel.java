package mt.su.nrm.ui;

import mt.su.nrm.model.ServerProfile;
import mt.su.nrm.model.ToolStatus;
import mt.su.nrm.ssh.CertbotService;
import mt.su.nrm.ssh.PackageInstaller;
import mt.su.nrm.ssh.RequirementChecker;
import mt.su.nrm.ssl.AltNames;
import mt.su.nrm.ssl.CertificateInfo;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * The Certificates tab: every certificate found on the server (Let's Encrypt, the manual folder,
 * the ones nginx is configured to use, and the CA), with expiry, details, Let's Encrypt issue and
 * renew through certbot, and "use in a virtual host". Keys are never read or downloaded.
 */
final class CertificatesPanel extends CertificateViewBase {

    CertificatesPanel(ServerProfile profile, ServerConnection connection) {
        super(profile, connection);
        table.setPlaceholder(new Label("No certificates found in the usual places."));
        addStandardColumns();
        installContextMenu();
        render();
    }

    @Override
    Node content(List<CertificateInfo> all) {
        long expired = all.stream().filter(c -> c.status(CLOCK) == CertificateInfo.Status.EXPIRED).count();
        long expiring = all.stream().filter(c -> c.status(CLOCK) == CertificateInfo.Status.EXPIRING).count();
        Label banner = new Label();
        banner.setWrapText(true);
        if (expired + expiring > 0) {
            banner.setText((expired > 0 ? expired + " certificate(s) have expired. " : "")
                    + (expiring > 0 ? expiring + " expire within " + CertificateInfo.WARN_DAYS + " days." : ""));
            banner.setStyle("-fx-text-fill: " + (expired > 0 ? "#b00020" : "#b26a00") + "; -fx-font-weight: bold;");
        }

        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> reload());
        Button issue = new Button("Let's Encrypt");
        issue.setOnAction(e -> issueLetsEncrypt());
        Button importCert = new Button("Import");
        importCert.setOnAction(e -> CertificateImportDialog.run(window(), new ConnectionServerAccess(profile, connection), result -> {
            Dialogs.info(window(), "Certificate imported", "Certificate: " + result.certPath() + "\nKey: " + result.keyPath()
                    + "\n\nUse \"Use in virtual host\" to put it on a site.");
            reload();
        }));
        Button renew = new Button("Renew");
        renew.setOnAction(e -> renewLetsEncrypt());
        boolean certbotMissing = profile.getCertbotStatus() != null && !profile.getCertbotStatus().available();
        issue.setDisable(certbotMissing);
        renew.setDisable(certbotMissing);
        Button use = new Button("Use in virtual host");
        use.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        use.setOnAction(e -> useInHost());
        Button delete = new Button("Delete");
        delete.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        delete.setOnAction(e -> deleteSelectedCertificate());
        FlowPane buttons = new FlowPane(8, 8, viewButton(), downloadButton(), refresh, issue, importCert, renew, use, delete);
        if (certbotMissing) {
            Button install = new Button("Install certbot");
            install.setOnAction(e -> installCertbot());
            buttons.getChildren().add(install);
        }

        Label note = new Label(certbotMissing
                ? "certbot was not found on this server, so Let's Encrypt is unavailable. Use \"Install certbot...\" to add it with the server's package manager."
                : "Private keys stay on the server. Let's Encrypt uses the webroot challenge by default (the nginx plugin is an option). "
                + "Private certificates are managed under Certificate Authority.");
        note.setWrapText(true);
        note.setOpacity(0.8);
        VBox top = new VBox(8, banner);
        return new VBox(0, top, tableWithDetails(all, buttons, note));
    }

    // ---------------------------------------------------------------- Let's Encrypt

    /** What an install attempt found: the installer's output, and certbot's status afterwards. */
    private record InstallOutcome(PackageInstaller.Result result, ToolStatus certbot) {
    }

    private void installCertbot() {
        if (!Dialogs.confirm(window(), "Install certbot?",
                "NRM will install certbot on the server with its package manager (apt, dnf or yum), as root. "
                        + "Every command it runs appears in the command log.", "Install")) {
            return;
        }
        work("Install certbot", "Installing certbot with the server's package manager. This can take a few minutes...",
                () -> {
                    PackageInstaller.Result result = PackageInstaller.install(connection.session(), PackageInstaller.CERTBOT);
                    ToolStatus certbot = result.ok()
                            ? RequirementChecker.check(connection.session(), profile.getPaths(), CLOCK).certbot() : null;
                    return new InstallOutcome(result, certbot);
                }, outcome -> {
                    if (outcome.result().ok() && outcome.certbot() != null && outcome.certbot().available()) {
                        // Kept in memory now; the next connection stores it with the profile.
                        profile.setCertbotStatus(outcome.certbot());
                        render();
                        Dialogs.showOutput(window(), Alert.AlertType.INFORMATION, "certbot installed",
                                "Let's Encrypt is now available.", outcome.result().output());
                    } else {
                        Dialogs.showOutput(window(), Alert.AlertType.ERROR, "certbot was not installed",
                                "What the server printed:", outcome.result().output());
                    }
                });
    }

    private void issueLetsEncrypt() {
        TextField domains = new TextField();
        domains.setPromptText("example.com www.example.com");
        TextField email = new TextField();
        email.setPromptText("for expiry notices (recommended)");
        ComboBox<String> method = new ComboBox<>(FXCollections.observableArrayList("Webroot (recommended)", "nginx plugin"));
        method.setValue(method.getItems().get(0));
        TextField webroot = new TextField("/var/www/html");
        TextField name = new TextField();
        name.setPromptText("empty = first domain");
        CheckBox staging = new CheckBox("Use the staging server (for testing; the certificate is not trusted)");

        java.util.function.Supplier<CertbotService.IssueRequest> read = () -> new CertbotService.IssueRequest(
                AltNames.parse(domains.getText(), false).names().stream().map(AltNames.Name::value).toList(),
                email.getText().strip(), method.getValue() != null && method.getValue().startsWith("nginx")
                ? CertbotService.Method.NGINX : CertbotService.Method.WEBROOT,
                webroot.getText().strip(), staging.isSelected(), name.getText().strip());

        if (!FormDialog.show(window(), "Let's Encrypt certificate", "Request", () -> {
            List<String> problems = new ArrayList<>(AltNames.parse(domains.getText(), false).problems());
            if (problems.isEmpty()) {
                problems.addAll(CertbotService.problems(read.get()));
            }
            return problems;
        }, "Domains", domains, "E-mail", email, "Method", method, "Webroot folder", webroot, "Certificate name", name,
                "", staging)) {
            return;
        }
        CertbotService.IssueRequest request = read.get();
        work("Let's Encrypt", "Asking Let's Encrypt for a certificate. The domains must already point at this server,\n"
                        + "and port 80 must reach nginx. This can take a minute or two.",
                () -> CertbotService.issue(connection.session(), profile.getPaths(), request), result -> {
                    if (result.ok()) {
                        Dialogs.showOutput(window(), Alert.AlertType.INFORMATION, "Certificate issued",
                                "Certificate: " + result.certPath() + "\nKey: " + result.keyPath()
                                        + "\n\nUse \"Use in virtual host\" to put it on a site.", result.output());
                        reload();
                    } else {
                        Dialogs.showOutput(window(), Alert.AlertType.ERROR, "Let's Encrypt did not issue a certificate",
                                "Nothing was changed. Check that the domains resolve to this server and that "
                                        + (request.method() == CertbotService.Method.WEBROOT
                                        ? "nginx serves /.well-known/acme-challenge/ from the webroot." : "the nginx plugin is installed."),
                                result.output());
                    }
                });
    }

    private void renewLetsEncrypt() {
        Row selected = table.getSelectionModel().getSelectedItem();
        String guess = selected == null ? "" : lineageOf(selected.info().path());
        TextField name = new TextField(guess);
        name.setPromptText("empty = every certificate that is due");
        CheckBox force = new CheckBox("Renew even if it is not due yet");
        if (!FormDialog.show(window(), "Renew certificate", "Renew", () -> {
            String n = name.getText().strip();
            return n.isEmpty() || n.matches("[A-Za-z0-9._-]+") ? List.of()
                    : List.of("The certificate name may only use letters, digits, dots, dashes and underscores.");
        }, "Certificate name", name, "", force)) {
            return;
        }
        String lineage = name.getText().strip();
        boolean forced = force.isSelected();
        work("Renewing", "Renewing with certbot and reloading nginx...",
                () -> CertbotService.renew(connection.session(), profile.getPaths(), lineage, forced), result -> {
                    Dialogs.showOutput(window(), result.ok() ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR,
                            result.ok() ? "Renewal finished" : "Renewal failed",
                            result.ok() ? "nginx was reloaded so it uses the renewed certificate." : "Nothing was reloaded.",
                            result.output());
                    reload();
                });
    }

    /** The certbot lineage name for a path like /etc/letsencrypt/live/NAME/fullchain.pem, else "". */
    static String lineageOf(String path) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/live/([^/]+)/").matcher(path);
        return m.find() ? m.group(1) : "";
    }

    /** Refreshes the shared list (used by the window's Actions pane). */
    void refreshAll() {
        reload();
    }
}
