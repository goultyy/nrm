package mt.su.nrm.ui;

import mt.su.nrm.config.ProfileRepository;
import mt.su.nrm.config.ProfileStoreException;
import mt.su.nrm.model.ServerProfile;
import mt.su.nrm.model.ToolStatus;
import mt.su.nrm.ssh.CommandLog;
import mt.su.nrm.ssh.ConnectionTester;
import mt.su.nrm.ssh.Credentials;
import mt.su.nrm.ssh.HostKeyChangedException;
import mt.su.nrm.ssh.RequirementChecker;
import mt.su.nrm.ssh.SshExecutor;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * "Test connection": connects to a server, checks openssl, certbot and root access, and stores
 * what it learned (pinned host key, cached tool status) on the profile. The connection runs on a
 * background thread; the command log window shows each command live while it works.
 */
public final class ConnectionTestRunner {

    private ConnectionTestRunner() {
    }

    /**
     * @param onFinished called on the FX thread when the test has ended, however it ended
     */
    public static void run(Window owner, ProfileRepository repository, ServerProfile profile,
                           Runnable onFinished, Consumer<ServerProfile> onProfileUpdated) {
        Optional<Credentials> credentials = CredentialPrompt.ask(owner, profile, Credentials.stored(profile));
        if (credentials.isEmpty()) {
            onFinished.run();
            return;
        }

        CommandLog log = new CommandLog();
        CommandLogPanel.showInWindow(log, owner, "Command log - " + profile.getName());

        SshExecutor.submit(() -> ConnectionTester.test(profile, credentials.get(), log, Clock.systemUTC()))
                .whenComplete((report, failure) -> Platform.runLater(() -> {
                    boolean retrying = false;
                    try {
                        if (failure != null && unwrap(failure) instanceof HostKeyChangedException changed) {
                            if (HostKeyChangedDialog.confirm(owner, profile, changed)) {
                                ServerProfile fresh = HostKeyChangedDialog.pinNewKey(repository, profile, changed);
                                if (fresh != null) {
                                    onProfileUpdated.accept(fresh);
                                    retrying = true;
                                    run(owner, repository, fresh, onFinished, onProfileUpdated);
                                }
                            }
                        } else if (failure != null) {
                            showFailure(owner, unwrap(failure), profile);
                        } else {
                            ServerProfile saved = saveFindings(repository, profile, report);
                            if (saved != null) {
                                onProfileUpdated.accept(saved);
                            }
                            showReport(owner, report);
                        }
                    } finally {
                        if (!retrying) {
                            onFinished.run();
                        }
                    }
                }));
    }

    /** Applies the findings to the current stored profile, so edits made meanwhile aren't overwritten. */
    private static ServerProfile saveFindings(ProfileRepository repository, ServerProfile tested,
                                              ConnectionTester.Report report) {
        Optional<ServerProfile> current = repository.find(tested.getId());
        if (current.isEmpty()) {
            return null;
        }
        ServerProfile updated = current.get();
        if (report.newlyPinnedFingerprint() != null && updated.getHostKeyFingerprint() == null) {
            updated.setHostKeyFingerprint(report.newlyPinnedFingerprint());
        }
        if (report.newlyPinnedGatewayFingerprint() != null && updated.getGatewayHostKeyFingerprint() == null) {
            updated.setGatewayHostKeyFingerprint(report.newlyPinnedGatewayFingerprint());
        }
        updated.setOpensslStatus(report.requirements().openssl());
        updated.setCertbotStatus(report.requirements().certbot());
        try {
            return repository.update(updated);
        } catch (ProfileStoreException | RuntimeException e) {
            new Alert(Alert.AlertType.ERROR, "The test worked, but its results could not be saved:\n" + e.getMessage(),
                    ButtonType.OK).showAndWait();
            return null;
        }
    }

    private static void showReport(Window owner, ConnectionTester.Report report) {
        RequirementChecker.Requirements r = report.requirements();
        StringBuilder text = new StringBuilder();
        if (report.newlyPinnedGatewayFingerprint() != null) {
            text.append("Gateway host key pinned (first connection):\n")
                    .append(report.newlyPinnedGatewayFingerprint()).append("\n\n");
        }
        if (report.newlyPinnedFingerprint() != null) {
            text.append("Host key pinned (first connection):\n").append(report.newlyPinnedFingerprint()).append("\n\n");
        }
        text.append("openssl: ").append(describe(r.openssl())).append('\n');
        text.append("certbot: ").append(describe(r.certbot())).append('\n');
        text.append("Root access: ").append(r.privilegeOk() ? "works" : r.privilegeMessage());

        Alert alert = new Alert(r.privilegeOk() ? Alert.AlertType.INFORMATION : Alert.AlertType.WARNING,
                text.toString(), ButtonType.OK);
        alert.setTitle("Test connection");
        alert.setHeaderText(r.privilegeOk() ? "Connection works" : "Connected, but root access failed");
        alert.initOwner(owner);
        Dialogs.tighten(alert);
        alert.show();
    }

    private static String describe(ToolStatus status) {
        if (!status.available()) {
            return "not found";
        }
        return (status.version() == null ? "found" : status.version()) + "  (" + status.path() + ")";
    }

    static void showFailure(Window owner, Throwable failure, ServerProfile profile) {
        mt.su.nrm.util.AppLog.warn("Connection to " + profile.getName() + " failed", failure);
        boolean keyChanged = failure instanceof HostKeyChangedException;
        Alert alert = new Alert(keyChanged ? Alert.AlertType.WARNING : Alert.AlertType.ERROR,
                mt.su.nrm.ssh.ErrorMessages.describe(failure, profile.getHost(), profile.getPort()), ButtonType.OK);
        alert.setTitle("Test connection");
        alert.setHeaderText(keyChanged ? "Host key changed - connection refused" : "Connection failed");
        alert.initOwner(owner);
        Dialogs.tighten(alert);
        alert.show();
    }

    static Throwable unwrap(Throwable t) {
        return t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
    }
}
