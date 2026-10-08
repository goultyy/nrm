package mt.su.nrm.ssh;

import mt.su.nrm.model.AuthMethod;
import mt.su.nrm.model.PrivilegeMode;
import mt.su.nrm.model.ServerProfile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * An open connection to one server. This is the single logging hook: every command and SFTP
 * operation the app performs goes through a method here, and each one is written to the
 * {@link CommandLog} before it is sent and again when it finishes. There is deliberately no way
 * to reach the underlying transport from outside this package.
 * <p>
 * Methods block, so call them off the JavaFX thread (see {@link SshExecutor}).
 */
public final class SshSession implements AutoCloseable {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private final SshTransport transport;
    private final CommandLog log;
    private final PrivilegeMode privilegeMode;
    private final String sudoPassword;
    private final String label;

    SshSession(SshTransport transport, CommandLog log, PrivilegeMode privilegeMode, String sudoPassword,
               String label) {
        this.transport = transport;
        this.log = log;
        this.privilegeMode = privilegeMode;
        this.sudoPassword = sudoPassword;
        this.label = label;
    }

    /**
     * Connects and logs in. Secrets in {@code credentials} are registered with the log so they are
     * masked wherever they appear.
     *
     * @param onFirstUse called with the SHA-256 fingerprint when the profile had no pinned host key
     *                   and the server's key was accepted; the caller should save it on the profile
     * @throws HostKeyChangedException if the server's key differs from the pinned one
     */
    public static SshSession connect(ServerProfile profile, Credentials credentials, CommandLog log,
                                     Consumer<String> onFirstUse) throws IOException {
        return connect(profile, credentials, log, onFirstUse, fingerprint -> { });
    }

    /**
     * As above; when the profile uses a gateway, {@code onGatewayFirstUse} receives the gateway's
     * fingerprint if none was pinned for it yet.
     */
    public static SshSession connect(ServerProfile profile, Credentials credentials, CommandLog log,
                                     Consumer<String> onFirstUse, Consumer<String> onGatewayFirstUse)
            throws IOException {
        credentials.registerWith(log);
        String label = profile.displayAddress();
        log.log(CommandLog.Kind.INFO, "Connecting to " + label + " ("
                + (profile.getAuthMethod() == AuthMethod.PRIVATE_KEY ? "private key" : "password") + ")");
        boolean useKey = profile.getAuthMethod() == AuthMethod.PRIVATE_KEY;
        SshjTransport.Target gateway = null;
        if (profile.isGatewayEnabled()) {
            boolean gatewayKey = profile.getGatewayAuthMethod() == AuthMethod.PRIVATE_KEY;
            log.log(CommandLog.Kind.INFO, "Through gateway " + profile.gatewayDisplayAddress() + " ("
                    + (gatewayKey ? "private key" : "password") + ")");
            gateway = new SshjTransport.Target(profile.getGatewayHost(), profile.getGatewayPort(),
                    profile.getGatewayUsername(), profile.getGatewayHostKeyFingerprint(),
                    gatewayKey ? profile.getGatewayPrivateKeyPath() : null,
                    credentials.gatewayPassword(), credentials.gatewayKeyPassphrase());
        }
        SshjTransport.Target target = new SshjTransport.Target(profile.getHost(), profile.getPort(),
                profile.getUsername(), profile.getHostKeyFingerprint(),
                useKey ? profile.getPrivateKeyPath() : null, credentials.password(), credentials.keyPassphrase(),
                gateway);
        try {
            SshjTransport transport = SshjTransport.connect(target, fingerprint -> {
                log.log(CommandLog.Kind.INFO, "First connection: trusting host key " + fingerprint
                        + " and pinning it for this server.");
                onFirstUse.accept(fingerprint);
            }, fingerprint -> {
                log.log(CommandLog.Kind.INFO, "First connection to the gateway: trusting host key " + fingerprint
                        + " and pinning it for the gateway.");
                onGatewayFirstUse.accept(fingerprint);
            });
            log.log(CommandLog.Kind.STATUS, "Connected to " + label);
            return new SshSession(transport, log, profile.getPrivilegeMode(), credentials.sudoPassword(), label);
        } catch (IOException | RuntimeException e) {
            log.log(CommandLog.Kind.STATUS, "Connection to " + label + " failed: " + e.getMessage());
            throw e;
        }
    }

    /** Runs a command as the login user. */
    public CommandResult exec(String command) throws IOException {
        return exec(command, DEFAULT_TIMEOUT);
    }

    public CommandResult exec(String command, Duration timeout) throws IOException {
        return run(command, null, false, timeout);
    }

    /** Runs a command as root, using this server's privilege mode. */
    public CommandResult execPrivileged(String command) throws IOException {
        return execPrivileged(command, DEFAULT_TIMEOUT);
    }

    public CommandResult execPrivileged(String command, Duration timeout) throws IOException {
        Privilege.Invocation invocation = Privilege.wrap(privilegeMode, command, sudoPassword);
        return run(invocation.commandLine(), invocation.stdin(), invocation.stdin() != null, timeout);
    }

    /** Writes a file over SFTP as the login user. The content is not logged, only its size. */
    public void upload(byte[] data, String remotePath) throws IOException {
        log.log(CommandLog.Kind.COMMAND, "sftp put " + remotePath + " (" + data.length + " bytes)");
        try {
            transport.upload(data, remotePath);
            log.log(CommandLog.Kind.STATUS, "[sftp put ok]");
        } catch (IOException | RuntimeException e) {
            log.log(CommandLog.Kind.STATUS, "[sftp put failed: " + e.getMessage() + "]");
            throw e;
        }
    }

    /** Reads a file over SFTP as the login user. The content is not logged, only its size. */
    public byte[] download(String remotePath) throws IOException {
        log.log(CommandLog.Kind.COMMAND, "sftp get " + remotePath);
        try {
            byte[] data = transport.download(remotePath);
            log.log(CommandLog.Kind.STATUS, "[sftp get ok, " + data.length + " bytes]");
            return data;
        } catch (IOException | RuntimeException e) {
            log.log(CommandLog.Kind.STATUS, "[sftp get failed: " + e.getMessage() + "]");
            throw e;
        }
    }

    /** Deletes a file over SFTP as the login user. */
    public void delete(String remotePath) throws IOException {
        log.log(CommandLog.Kind.COMMAND, "sftp rm " + remotePath);
        try {
            transport.delete(remotePath);
            log.log(CommandLog.Kind.STATUS, "[sftp rm ok]");
        } catch (IOException | RuntimeException e) {
            log.log(CommandLog.Kind.STATUS, "[sftp rm failed: " + e.getMessage() + "]");
            throw e;
        }
    }

    // ---------------------------------------------------------------- file browsing and transfer

    /** Lists a remote directory over SFTP as the login user. */
    public List<SftpEntry> sftpList(String remoteDir) throws IOException {
        return sftp("ls " + remoteDir, () -> transport.list(remoteDir));
    }

    /** Resolves a remote path (such as "." for the login directory) to an absolute one. */
    public String sftpResolve(String remotePath) throws IOException {
        return sftp("realpath " + remotePath, () -> transport.canonicalize(remotePath));
    }

    public void sftpMkdir(String remoteDir) throws IOException {
        sftp("mkdir " + remoteDir, () -> {
            transport.mkdir(remoteDir);
            return null;
        });
    }

    /** Creates an empty file as the login user; fails if something is already at the path. */
    public void sftpCreateFile(String remotePath) throws IOException {
        sftp("create " + remotePath, () -> {
            transport.createFile(remotePath);
            return null;
        });
    }

    public void sftpRename(String from, String to) throws IOException {
        sftp("rename " + from + " " + to, () -> {
            transport.rename(from, to);
            return null;
        });
    }

    /** Removes an empty remote directory. */
    public void sftpRmdir(String remoteDir) throws IOException {
        sftp("rmdir " + remoteDir, () -> {
            transport.rmdir(remoteDir);
            return null;
        });
    }

    /** Streams a local file to the server. The content is not logged, only the size. */
    public void sftpUploadFile(Path local, String remotePath, LongConsumer progress) throws IOException {
        sftp("put " + remotePath + " (" + Files.size(local) + " bytes)", () -> {
            transport.uploadFile(local, remotePath, progress);
            return null;
        });
    }

    /** Streams a remote file to a local one. The content is not logged. */
    public void sftpDownloadFile(String remotePath, Path local, LongConsumer progress) throws IOException {
        sftp("get " + remotePath, () -> {
            transport.downloadFile(remotePath, local, progress);
            return null;
        });
    }

    private interface SftpCall<T> {
        T run() throws IOException;
    }

    /** Logs the operation before it is sent and its outcome afterwards, like the other SFTP calls. */
    private <T> T sftp(String description, SftpCall<T> call) throws IOException {
        String verb = description.substring(0, description.indexOf(' '));
        log.log(CommandLog.Kind.COMMAND, "sftp " + description);
        try {
            T result = call.run();
            log.log(CommandLog.Kind.STATUS, "[sftp " + verb + " ok]");
            return result;
        } catch (IOException | RuntimeException e) {
            log.log(CommandLog.Kind.STATUS, "[sftp " + verb + " failed: " + e.getMessage() + "]");
            throw e;
        }
    }

    /** False once the connection to the server has dropped. */
    public boolean isOpen() {
        return transport.isOpen();
    }

    public CommandLog log() {
        return log;
    }

    @Override
    public void close() {
        transport.close();
        log.log(CommandLog.Kind.INFO, "Disconnected from " + label);
    }

    private CommandResult run(String commandLine, byte[] stdin, boolean passwordOnStdin, Duration timeout)
            throws IOException {
        log.log(CommandLog.Kind.COMMAND, "$ " + commandLine);
        if (passwordOnStdin) {
            log.log(CommandLog.Kind.INFO, "(sudo password sent on stdin, not on the command line)");
        }
        SshTransport.Raw raw;
        try {
            raw = transport.exec(commandLine, stdin, timeout);
        } catch (IOException | RuntimeException e) {
            log.log(CommandLog.Kind.STATUS, "[failed: " + e.getMessage() + "]");
            throw e;
        }
        String stdout = new String(raw.stdout(), StandardCharsets.UTF_8);
        String stderr = new String(raw.stderr(), StandardCharsets.UTF_8);
        logOutput(CommandLog.Kind.OUTPUT, stdout);
        logOutput(CommandLog.Kind.ERROR_OUTPUT, stderr);
        int exit = raw.exitStatus() == null ? -1 : raw.exitStatus();
        log.log(CommandLog.Kind.STATUS, exit < 0 ? "[no exit status reported]" : "[exit " + exit + "]");
        return new CommandResult(exit, stdout, stderr);
    }

    private void logOutput(CommandLog.Kind kind, String text) {
        if (text.isEmpty()) {
            return;
        }
        log.log(kind, text.endsWith("\n") ? text.substring(0, text.length() - 1) : text);
    }
}
