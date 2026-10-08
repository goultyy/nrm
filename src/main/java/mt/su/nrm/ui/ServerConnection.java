package mt.su.nrm.ui;

import mt.su.nrm.nginx.RemoteConfig;
import mt.su.nrm.ssh.CommandLog;
import mt.su.nrm.ssh.SshSession;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.util.UUID;

/**
 * One server's connection state as the main window sees it: the open session (if any) and the
 * command log, which outlives the session so what happened stays visible after disconnecting.
 * Only touched from the JavaFX thread.
 */
public final class ServerConnection {

    public enum State { DISCONNECTED, CONNECTING, CONNECTED }

    /** Where the nginx configuration stands: it is loaded once after connecting and on refresh. */
    public enum ConfigState { NOT_LOADED, LOADING, LOADED, FAILED }

    private final UUID profileId;
    private final CommandLog log = new CommandLog();
    private final ObjectProperty<State> state = new SimpleObjectProperty<>(State.DISCONNECTED);
    private SshSession session;

    private final ObjectProperty<ConfigState> configState = new SimpleObjectProperty<>(ConfigState.NOT_LOADED);
    private final IntegerProperty pendingCount = new SimpleIntegerProperty();
    private RemoteConfig config;
    private String configError = "";
    private java.util.List<mt.su.nrm.ssl.CertificateInfo> certificates;
    private boolean certificatesLoading;
    private final javafx.beans.property.IntegerProperty certificatesVersion = new javafx.beans.property.SimpleIntegerProperty();

    /**
     * The Cloudflare side of this server: independent of the SSH session, so it is kept across
     * disconnects and its staged changes aren't lost by clicking elsewhere. Null until opened.
     */
    private final ObjectProperty<mt.su.nrm.cloudflare.CloudflareSession> cloudflare = new SimpleObjectProperty<>();
    private final IntegerProperty cloudflarePending = new SimpleIntegerProperty();
    private CloudflareHub cloudflareHub;

    public mt.su.nrm.cloudflare.CloudflareSession cloudflare() {
        return cloudflare.get();
    }

    /** Changes when a session is opened, so the tree can list the zones and tunnels it found. */
    public ObjectProperty<mt.su.nrm.cloudflare.CloudflareSession> cloudflareProperty() {
        return cloudflare;
    }

    void setCloudflare(mt.su.nrm.cloudflare.CloudflareSession session) {
        cloudflarePending.set(session == null ? 0 : session.workspace().changeCount());
        cloudflare.set(session);
    }

    /** The one place the Cloudflare pages share their busy state, staging and apply flow. */
    CloudflareHub cloudflareHub() {
        if (cloudflareHub == null) {
            cloudflareHub = new CloudflareHub(this);
        }
        return cloudflareHub;
    }

    /** Number of staged Cloudflare changes not yet applied. */
    public IntegerProperty cloudflarePendingProperty() {
        return cloudflarePending;
    }

    /** Set by the main window: shows a folder of this server in a File Explorer window. */
    private java.util.function.Consumer<String> fileTransferOpener = path -> { };

    void onOpenFileTransfer(java.util.function.Consumer<String> opener) {
        this.fileTransferOpener = opener;
    }

    void openFileTransfer(String remoteDirectory) {
        fileTransferOpener.accept(remoteDirectory);
    }

    public ServerConnection(UUID profileId) {
        this.profileId = profileId;
    }

    public UUID profileId() {
        return profileId;
    }

    public CommandLog log() {
        return log;
    }

    public ObjectProperty<State> stateProperty() {
        return state;
    }

    public State state() {
        return state.get();
    }

    public boolean isConnected() {
        return state.get() == State.CONNECTED;
    }

    /** The open session, or null unless connected. */
    public SshSession session() {
        return session;
    }

    void connecting() {
        state.set(State.CONNECTING);
    }

    void connected(SshSession opened) {
        session = opened;
        state.set(State.CONNECTED);
    }

    /** Returns the session that was open, so the caller can close it off the FX thread. */
    SshSession disconnected() {
        SshSession old = session;
        session = null;
        config = null;
        configError = "";
        pendingCount.set(0);
        configState.set(ConfigState.NOT_LOADED);
        certificates = null;
        certificatesLoading = false;
        state.set(State.DISCONNECTED);
        return old;
    }

    // ---------------------------------------------------------------- nginx configuration

    public ObjectProperty<ConfigState> configStateProperty() {
        return configState;
    }

    public ConfigState configState() {
        return configState.get();
    }

    /** Number of files with changes that have not been applied yet. */
    public IntegerProperty pendingCountProperty() {
        return pendingCount;
    }

    /** The loaded configuration, or null unless {@link #configState()} is LOADED. */
    public RemoteConfig config() {
        return config;
    }

    public String configError() {
        return configError;
    }

    void configLoading() {
        configState.set(ConfigState.LOADING);
    }

    void configLoaded(RemoteConfig loaded) {
        config = loaded;
        configError = "";
        pendingCount.set(0);
        configState.set(ConfigState.LOADED);
    }

    void configFailed(String message) {
        config = null;
        configError = message;
        pendingCount.set(0);
        configState.set(ConfigState.FAILED);
    }

    /** Call after changing the configuration in memory so the pending-changes count is refreshed. */
    public void configChanged() {
        pendingCount.set(config == null ? 0 : config.pendingChanges().size());
    }

    // ---------------------------------------------------------------- certificates

    /** The last certificate listing read from the server, or null if none has been read yet. */
    public java.util.List<mt.su.nrm.ssl.CertificateInfo> certificates() {
        return certificates;
    }

    void setCertificates(java.util.List<mt.su.nrm.ssl.CertificateInfo> list) {
        certificates = list == null ? null : java.util.List.copyOf(list);
        certificatesVersion.set(certificatesVersion.get() + 1);
    }

    /** Changes whenever the certificate list is replaced, so every view of it can redraw. */
    public javafx.beans.property.IntegerProperty certificatesVersionProperty() {
        return certificatesVersion;
    }

    /** True while the certificate list is being read, so several views don't each start a read. */
    boolean certificatesLoading() {
        return certificatesLoading;
    }

    void setCertificatesLoading(boolean loading) {
        certificatesLoading = loading;
    }
}
