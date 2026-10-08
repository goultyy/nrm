package mt.su.nrm.ui;

import mt.su.nrm.config.ProfileRepository;
import mt.su.nrm.config.ProfileStoreException;
import mt.su.nrm.model.ServerProfile;
import mt.su.nrm.nginx.RemoteConfig;
import mt.su.nrm.ssh.CommandLog;
import mt.su.nrm.ssh.Credentials;
import mt.su.nrm.ssh.RemoteConfigService;
import mt.su.nrm.ssh.RequirementChecker;
import mt.su.nrm.ssh.SshExecutor;
import mt.su.nrm.ssh.SshSession;
import javafx.application.Platform;
import javafx.stage.Window;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Opens and closes connections for the main window. All SSH work runs on background threads; the
 * results are applied back on the FX thread.
 */
public final class ConnectionManager {

    private record Opened(SshSession session, String newPin, String newGatewayPin,
                          RequirementChecker.Requirements requirements) {
    }

    private final ProfileRepository repository;
    private final Map<UUID, ServerConnection> connections = new HashMap<>();

    public ConnectionManager(ProfileRepository repository) {
        this.repository = repository;
    }

    /** The connection state holder for a profile, created on first use. */
    public ServerConnection of(UUID profileId) {
        return connections.computeIfAbsent(profileId, ServerConnection::new);
    }

    /**
     * Connects (asking for any secrets the profile doesn't store), pins the host key on first
     * use, and caches the openssl/certbot check the first time.
     *
     * @param onDone called on the FX thread when finished; the flag says whether it connected
     */
    public void connect(Window owner, ServerProfile profile, Consumer<Boolean> onDone) {
        ServerConnection connection = of(profile.getId());
        if (connection.state() != ServerConnection.State.DISCONNECTED) {
            onDone.accept(connection.isConnected());
            return;
        }
        Optional<Credentials> credentials = CredentialPrompt.ask(owner, profile, Credentials.stored(profile));
        if (credentials.isEmpty()) {
            onDone.accept(false);
            return;
        }
        open(owner, profile, credentials.get(), onDone);
    }

    /** Connects with credentials already in hand (also used again after the user trusts a changed host key). */
    private void open(Window owner, ServerProfile profile, Credentials credentials, Consumer<Boolean> onDone) {
        ServerConnection connection = of(profile.getId());
        connection.connecting();
        String where = profile.getName().isBlank() ? profile.displayAddress() : profile.getName();
        ProgressDialog progress = ProgressDialog.show(owner, "Manage", "Attempting connection to " + where);
        // Show what the SSH layer reports (connecting, host key, each check) under the headline.
        java.util.function.Consumer<CommandLog.Line> follow = line -> Platform.runLater(() -> progress.setDetail(line.text()));
        connection.log().addListener(follow);

        boolean needsToolCheck = profile.getOpensslStatus() == null || profile.getCertbotStatus() == null;
        SshExecutor.submit(() -> {
            AtomicReference<String> pin = new AtomicReference<>();
            AtomicReference<String> gatewayPin = new AtomicReference<>();
            Platform.runLater(() -> progress.setStatus("Connecting and logging in to " + where));
            SshSession session = SshSession.connect(profile, credentials, connection.log(), pin::set, gatewayPin::set);
            try {
                Platform.runLater(() -> progress.setStatus("Connected, validating tool box"));
                RequirementChecker.Requirements requirements = needsToolCheck
                        ? RequirementChecker.check(session, profile.getPaths(), Clock.systemUTC()) : null;
                return new Opened(session, pin.get(), gatewayPin.get(), requirements);
            } catch (RuntimeException | java.io.IOException e) {
                session.close();
                throw e;
            }
        }).whenComplete((opened, failure) -> Platform.runLater(() -> {
            connection.log().removeListener(follow);
            if (failure != null) {
                progress.close();
                connection.disconnected();
                Throwable cause = ConnectionTestRunner.unwrap(failure);
                if (cause instanceof mt.su.nrm.ssh.HostKeyChangedException changed) {
                    // Never reconnect silently: the user sees what changed and must choose to trust the new key.
                    if (HostKeyChangedDialog.confirm(owner, profile, changed)) {
                        ServerProfile fresh = HostKeyChangedDialog.pinNewKey(repository, profile, changed);
                        if (fresh != null) {
                            open(owner, fresh, credentials, onDone);
                            return;
                        }
                    }
                } else {
                    ConnectionTestRunner.showFailure(owner, cause, profile);
                }
                onDone.accept(false);
                return;
            }
            connection.connected(opened.session());
            saveFindings(profile.getId(), opened);
            progress.setStatus("Reading the server configuration. This may take a few moments.");
            progress.setDetail("");
            loadConfig(profile, () -> {
                progress.close();
                onDone.accept(true);
            });
        }));
    }

    /**
     * Reads the nginx configuration from the connected server in the background and stores it on
     * the connection; also caches the detected layout on the profile.
     *
     * @param onDone called on the FX thread when finished, successfully or not
     */
    public void loadConfig(ServerProfile profile, Runnable onDone) {
        ServerConnection connection = of(profile.getId());
        SshSession session = connection.session();
        if (session == null) {
            onDone.run();
            return;
        }
        connection.configLoading();
        SshExecutor.submit(() -> RemoteConfigService.load(session, profile.getPaths()))
                .whenComplete((config, failure) -> Platform.runLater(() -> {
                    if (connection.session() != session) {
                        onDone.run(); // disconnected while loading
                        return;
                    }
                    if (failure != null) {
                        connection.configFailed(describeFailure(profile, connection, failure));
                    } else {
                        connection.configLoaded(config);
                        saveLayout(profile.getId(), config);
                    }
                    onDone.run();
                }));
    }

    private void saveLayout(UUID profileId, RemoteConfig config) {
        Optional<ServerProfile> current = repository.find(profileId);
        if (current.isEmpty() || current.get().getDetectedLayout() == config.layout()) {
            return;
        }
        ServerProfile updated = current.get();
        updated.setDetectedLayout(config.layout());
        try {
            repository.update(updated);
        } catch (ProfileStoreException | RuntimeException e) {
            connectionsWarning(profileId, e);
        }
    }

    /**
     * Words a failed server operation for the user, records it in the application log, and if the
     * session turns out to have dropped, marks the server as disconnected so the window shows it.
     */
    static String describeFailure(ServerProfile profile, ServerConnection connection, Throwable failure) {
        Throwable cause = ConnectionTestRunner.unwrap(failure);
        String text = mt.su.nrm.ssh.ErrorMessages.describe(cause, profile.getHost(), profile.getPort());
        mt.su.nrm.util.AppLog.warn("Operation on " + profile.getName() + " failed: " + text, cause);
        SshSession session = connection.session();
        if (session != null && !session.isOpen()) {
            SshSession lost = connection.disconnected();
            if (lost != null) {
                lost.close();
            }
            return "The connection to the server was lost. Connect again, then refresh.\n(" + text + ")";
        }
        return text;
    }

    public void disconnect(UUID profileId, Runnable onDone) {
        SshSession old = of(profileId).disconnected();
        if (old == null) {
            onDone.run();
            return;
        }
        SshExecutor.submit(() -> {
            old.close();
            return null;
        }).whenComplete((v, e) -> Platform.runLater(onDone));
    }

    /** Closes every open session; used when the app exits. */
    public void disconnectAll() {
        for (ServerConnection connection : connections.values()) {
            SshSession old = connection.disconnected();
            if (old != null) {
                old.close();
            }
        }
    }

    private void saveFindings(UUID profileId, Opened opened) {
        if (opened.newPin() == null && opened.newGatewayPin() == null && opened.requirements() == null) {
            return;
        }
        Optional<ServerProfile> current = repository.find(profileId);
        if (current.isEmpty()) {
            return;
        }
        ServerProfile updated = current.get();
        if (opened.newPin() != null && updated.getHostKeyFingerprint() == null) {
            updated.setHostKeyFingerprint(opened.newPin());
        }
        if (opened.newGatewayPin() != null && updated.getGatewayHostKeyFingerprint() == null) {
            updated.setGatewayHostKeyFingerprint(opened.newGatewayPin());
        }
        if (opened.requirements() != null) {
            updated.setOpensslStatus(opened.requirements().openssl());
            updated.setCertbotStatus(opened.requirements().certbot());
        }
        try {
            repository.update(updated);
        } catch (ProfileStoreException | RuntimeException e) {
            // The connection itself worked; losing the cached facts only means asking again next time.
            connectionsWarning(profileId, e);
        }
    }

    private void connectionsWarning(UUID profileId, Exception e) {
        of(profileId).log().log(mt.su.nrm.ssh.CommandLog.Kind.INFO,
                "Could not save what was learned about this server: " + e.getMessage());
    }
}
