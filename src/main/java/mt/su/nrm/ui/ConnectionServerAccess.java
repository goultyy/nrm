package mt.su.nrm.ui;

import mt.su.nrm.model.ServerProfile;
import mt.su.nrm.nginx.Directive;
import mt.su.nrm.nginx.RemoteConfig;
import mt.su.nrm.ssh.AuthService;
import mt.su.nrm.ssh.CertificateService;
import mt.su.nrm.ssh.ImportService;
import mt.su.nrm.ssh.PhpService;
import mt.su.nrm.ssl.CertificateInfo;
import mt.su.nrm.ssh.SshExecutor;
import mt.su.nrm.ssh.SshSession;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.stage.Window;

import java.util.List;
import java.util.function.Consumer;

/** {@link ServerAccess} backed by a server's open connection; everything runs off the FX thread. */
final class ConnectionServerAccess implements ServerAccess {

    private final ServerProfile profile;
    private final ServerConnection connection;

    ConnectionServerAccess(ServerProfile profile, ServerConnection connection) {
        this.profile = profile;
        this.connection = connection;
    }

    @Override
    public boolean connected() {
        return connection.isConnected() && connection.session() != null;
    }

    @Override
    public void openInFileTransfer(String remoteDirectory) {
        connection.openFileTransfer(remoteDirectory);
    }

    @Override
    public List<String> logFormatNames() {
        List<String> names = new java.util.ArrayList<>(List.of("combined"));
        RemoteConfig config = connection.config();
        if (config != null) {
            for (mt.su.nrm.nginx.LogFormat format : config.logFormats()) {
                // The cache statistics format is plumbing for that feature, not something to pick for a site.
                if (!format.isManaged() && !format.name().isEmpty() && !names.contains(format.name())) {
                    names.add(format.name());
                }
            }
        }
        return names;
    }

    @Override
    public java.util.Optional<mt.su.nrm.nginx.VhostSettings.RealIpSpec> inheritedRealIp() {
        RemoteConfig config = connection.config();
        return config == null ? java.util.Optional.empty() : mt.su.nrm.nginx.RealIp.inherited(config);
    }

    @Override
    public mt.su.nrm.ssh.CommandLog commandLog() {
        return connection.log();
    }

    @Override
    public String confDir() {
        RemoteConfig config = connection.config();
        return config == null ? profile.getPaths().getNginxConfDir() : config.confDir();
    }

    /** The group named on the {@code user} line of nginx.conf ({@code user www-data;} or {@code user www-data web;}). */
    @Override
    public String workerUser() {
        RemoteConfig config = connection.config();
        if (config == null) {
            return "";
        }
        Directive user = config.mainFile().root().first("user");
        if (user == null || user.arg(0) == null) {
            return "";
        }
        return user.arg(1) != null ? user.arg(1) : user.arg(0);
    }

    @Override
    public void listUsers(Window owner, String path, Consumer<List<String>> onDone) {
        SshSession session = connection.session();
        if (session == null) {
            onDone.accept(List.of());
            return;
        }
        SshExecutor.submit(() -> AuthService.listUsers(session, confDir(), path))
                .whenComplete((users, failure) -> Platform.runLater(() -> onDone.accept(failure != null ? List.of() : users)));
    }

    @Override
    public void writePasswordFile(Window owner, AuthService.PasswordFileRequest request, Consumer<Boolean> onDone) {
        SshSession session = connection.session();
        if (session == null) {
            Dialogs.error(owner, "Not connected", "Connect to the server to create the password file.");
            onDone.accept(false);
            return;
        }
        ProgressDialog progress = ProgressDialog.show(owner, "Password file", "Writing the password file on the server...");
        SshExecutor.submit(() -> AuthService.write(session, confDir(), request))
                .whenComplete((result, failure) -> Platform.runLater(() -> {
                    progress.close();
                    if (failure != null) {
                        Dialogs.showOutput(owner, Alert.AlertType.ERROR, "The password file was not written",
                                "Nothing was changed.", ConnectionManager.describeFailure(profile, connection, failure));
                        onDone.accept(false);
                    } else if (!result.ok()) {
                        Dialogs.showOutput(owner, Alert.AlertType.ERROR, "The password file was not written",
                                "The server reported a problem.", result.output());
                        onDone.accept(false);
                    } else {
                        onDone.accept(true);
                    }
                }));
    }

    @Override
    public String certificateDir() {
        return profile.getPaths().getManualCertDir();
    }

    @Override
    public void listCertificates(Window owner, Consumer<List<CertificateInfo>> onDone) {
        SshSession session = connection.session();
        if (session == null) {
            onDone.accept(List.of());
            return;
        }
        ProgressDialog progress = ProgressDialog.show(owner, "Certificates", "Looking for certificates on the server...");
        SshExecutor.submit(() -> CertificateService.list(session, profile.getPaths(), List.of()))
                .whenComplete((certs, failure) -> Platform.runLater(() -> {
                    progress.close();
                    if (failure != null) {
                        Dialogs.showOutput(owner, Alert.AlertType.ERROR, "Could not list the certificates",
                                "The server reported a problem.", ConnectionManager.describeFailure(profile, connection, failure));
                        onDone.accept(List.of());
                    } else {
                        onDone.accept(certs);
                    }
                }));
    }

    @Override
    public void importCertificate(Window owner, ImportService.ImportRequest request,
                                  Consumer<ImportService.Result> onDone) {
        SshSession session = connection.session();
        if (session == null) {
            Dialogs.error(owner, "Not connected", "Connect to the server to import a certificate.");
            onDone.accept(null);
            return;
        }
        ProgressDialog progress = ProgressDialog.show(owner, "Import certificate", "Copying the certificate to the server and checking the key...");
        SshExecutor.submit(() -> ImportService.importCertificate(session, profile.getPaths(), request))
                .whenComplete((result, failure) -> Platform.runLater(() -> {
                    progress.close();
                    if (failure != null) {
                        Dialogs.showOutput(owner, Alert.AlertType.ERROR, "The certificate was not imported",
                                "Nothing was installed.", ConnectionManager.describeFailure(profile, connection, failure));
                        onDone.accept(null);
                    } else if (!result.ok()) {
                        Dialogs.showOutput(owner, Alert.AlertType.ERROR, "The certificate was not imported",
                                "Nothing was installed.", result.output());
                        onDone.accept(null);
                    } else {
                        onDone.accept(result);
                    }
                }));
    }

    @Override
    public void phpStatus(Window owner, Consumer<PhpService.Status> onDone) {
        SshSession session = connection.session();
        if (session == null) {
            onDone.accept(null);
            return;
        }
        ProgressDialog progress = ProgressDialog.show(owner, "PHP", "Checking the server for PHP-FPM...");
        SshExecutor.submit(() -> PhpService.detect(session, confDir()))
                .whenComplete((status, failure) -> Platform.runLater(() -> {
                    progress.close();
                    if (failure != null) {
                        Dialogs.showOutput(owner, Alert.AlertType.ERROR, "Could not check for PHP",
                                "The server reported a problem.", ConnectionManager.describeFailure(profile, connection, failure));
                        onDone.accept(null);
                    } else {
                        onDone.accept(status);
                    }
                }));
    }

    @Override
    public void installPhp(Window owner, String packageManager, Consumer<Boolean> onDone) {
        SshSession session = connection.session();
        if (session == null) {
            Dialogs.error(owner, "Not connected", "Connect to the server to install PHP.");
            onDone.accept(false);
            return;
        }
        ProgressDialog progress = ProgressDialog.show(owner, "Install PHP",
                "Installing PHP-FPM with the server's package manager. This can take a few minutes...");
        SshExecutor.submit(() -> PhpService.install(session, packageManager))
                .whenComplete((result, failure) -> Platform.runLater(() -> {
                    progress.close();
                    if (failure != null) {
                        Dialogs.showOutput(owner, Alert.AlertType.ERROR, "PHP was not installed",
                                "The server reported a problem.", ConnectionManager.describeFailure(profile, connection, failure));
                        onDone.accept(false);
                    } else if (!result.ok()) {
                        Dialogs.showOutput(owner, Alert.AlertType.ERROR, "PHP was not installed",
                                "The install did not finish. What the server printed:", result.output());
                        onDone.accept(false);
                    } else {
                        onDone.accept(true);
                    }
                }));
    }
}
