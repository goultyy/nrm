package mt.su.nrm.ssh;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.Buffer;
import net.schmizz.sshj.common.IOUtils;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.sftp.FileAttributes;
import net.schmizz.sshj.sftp.FileMode;
import net.schmizz.sshj.sftp.OpenMode;
import net.schmizz.sshj.sftp.RemoteFile;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.HostKeyVerifier;
import net.schmizz.sshj.userauth.UserAuthException;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import net.schmizz.sshj.xfer.InMemoryDestFile;
import net.schmizz.sshj.xfer.InMemorySourceFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.function.LongConsumer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The only class that talks to sshj. Everything above it goes through {@link SshSession}, which
 * logs each call; a test enforces that no other class imports the library.
 */
final class SshjTransport implements SshTransport {

    private static final int CONNECT_TIMEOUT_MS = 45_000;

    private final SSHClient client;
    /** The gateway's connection when the server is reached through one, else null. */
    private final SSHClient gateway;
    private SFTPClient sftp;

    private SshjTransport(SSHClient client, SSHClient gateway) {
        this.client = client;
        this.gateway = gateway;
    }

    /**
     * Connection settings, with the host key pinned on the profile (null on first connect).
     * {@code gateway}, if set, is an SSH server to hop through; it carries its own credentials and pin.
     */
    record Target(String host, int port, String username, String pinnedFingerprint,
                  String privateKeyPath, String password, String keyPassphrase, Target gateway) {
        Target(String host, int port, String username, String pinnedFingerprint,
               String privateKeyPath, String password, String keyPassphrase) {
            this(host, port, username, pinnedFingerprint, privateKeyPath, password, keyPassphrase, null);
        }

        @Override
        public String toString() {
            return "Target[" + username + "@" + host + ":" + port + (gateway == null ? "" : " via " + gateway) + "]";
        }
    }

    /**
     * Connects and authenticates. The host key is checked before authentication, so a changed key
     * is refused before any secret is sent.
     *
     * @param onFirstUse called with the fingerprint when no key was pinned and this one was accepted
     * @throws HostKeyChangedException if the presented key differs from the pinned one
     */
    static SshjTransport connect(Target target, Consumer<String> onFirstUse) throws IOException {
        return connect(target, onFirstUse, fingerprint -> { });
    }

    /**
     * As above, going through {@code target.gateway()} when there is one. The gateway is logged in
     * first and the server is reached over a direct-tcpip channel opened on it, so the server's own
     * host key and credentials are checked exactly as for a direct connection.
     *
     * @param onGatewayFirstUse called with the gateway's fingerprint when none was pinned for it
     */
    static SshjTransport connect(Target target, Consumer<String> onFirstUse, Consumer<String> onGatewayFirstUse)
            throws IOException {
        if (target.gateway() == null) {
            return new SshjTransport(open(target, onFirstUse, false, null), null);
        }
        SSHClient jump = open(target.gateway(), onGatewayFirstUse, true, null);
        try {
            return new SshjTransport(open(target, onFirstUse, false, jump), jump);
        } catch (IOException | RuntimeException e) {
            closeQuietly(jump);
            throw e;
        }
    }

    /** Connects and authenticates one SSH endpoint, directly or through {@code via}. */
    private static SSHClient open(Target target, Consumer<String> onFirstUse, boolean isGateway, SSHClient via)
            throws IOException {
        SSHClient ssh = new SSHClient();
        PinningVerifier verifier = new PinningVerifier(target, onFirstUse, isGateway);
        ssh.addHostKeyVerifier(verifier);
        ssh.setConnectTimeout(CONNECT_TIMEOUT_MS);
        ssh.setTimeout(120_000);
        try {
            if (via == null) {
                ssh.connect(target.host(), target.port());
            } else {
                ssh.connectVia(via.newDirectConnection(target.host(), target.port()));
            }
            if (target.privateKeyPath() != null) {
                KeyProvider keys = target.keyPassphrase() == null || target.keyPassphrase().isEmpty()
                        ? ssh.loadKeys(target.privateKeyPath())
                        : ssh.loadKeys(target.privateKeyPath(), target.keyPassphrase());
                ssh.authPublickey(target.username(), keys);
            } else {
                ssh.authPassword(target.username(), target.password() == null ? "" : target.password());
            }
            ssh.getConnection().getKeepAlive().setKeepAliveInterval(30);
            return ssh;
        } catch (IOException | RuntimeException e) {
            closeQuietly(ssh);
            if (verifier.mismatch != null) {
                throw verifier.mismatch;
            }
            if (e instanceof UserAuthException) {
                throw new AuthenticationFailedException(
                        isGateway ? "Authentication to the gateway failed" : "Authentication failed", e);
            }
            throw e;
        }
    }

    @Override
    public Raw exec(String commandLine, byte[] stdin, Duration timeout) throws IOException {
        try (Session session = client.startSession()) {
            Session.Command command = session.exec(commandLine);
            CompletableFuture<byte[]> stderr = CompletableFuture.supplyAsync(() -> read(command.getErrorStream()));
            if (stdin != null) {
                OutputStream out = command.getOutputStream();
                out.write(stdin);
                out.flush();
            }
            command.getOutputStream().close();
            byte[] stdout = read(command.getInputStream());
            command.join(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return new Raw(command.getExitStatus(), stdout, stderr.join());
        }
    }

    @Override
    public void upload(byte[] data, String remotePath) throws IOException {
        try (SFTPClient sftp = client.newSFTPClient()) {
            sftp.getFileTransfer().upload(new InMemorySourceFile() {
                @Override
                public String getName() {
                    return "upload";
                }

                @Override
                public long getLength() {
                    return data.length;
                }

                @Override
                public InputStream getInputStream() {
                    return new ByteArrayInputStream(data);
                }
            }, remotePath);
        }
    }

    @Override
    public byte[] download(String remotePath) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (SFTPClient sftp = client.newSFTPClient()) {
            sftp.getFileTransfer().download(remotePath, new InMemoryDestFile() {
                @Override
                public OutputStream getOutputStream() {
                    return buffer;
                }

                @Override
                public long getLength() {
                    return buffer.size();
                }

                @Override
                public OutputStream getOutputStream(boolean append) {
                    return buffer;
                }
            });
        }
        return buffer.toByteArray();
    }

    @Override
    public void delete(String remotePath) throws IOException {
        try (SFTPClient sftp = client.newSFTPClient()) {
            sftp.rm(remotePath);
        }
    }

    @Override
    public List<SftpEntry> list(String remoteDir) throws IOException {
        SFTPClient sftp = sftp();
        List<SftpEntry> entries = new java.util.ArrayList<>();
        for (RemoteResourceInfo info : sftp.ls(remoteDir)) {
            String name = info.getName();
            if (name.equals(".") || name.equals("..")) {
                continue;
            }
            FileAttributes attrs = info.getAttributes();
            boolean directory = attrs.getType() == FileMode.Type.DIRECTORY;
            if (attrs.getType() == FileMode.Type.SYMLINK) {
                try { // a link counts as a folder if what it points to is one
                    directory = sftp.stat(info.getPath()).getType() == FileMode.Type.DIRECTORY;
                } catch (IOException ignored) {
                    // a dangling link is shown as a file
                }
            }
            entries.add(new SftpEntry(name, directory, attrs.getSize(), attrs.getMtime(),
                    permissionString(attrs.getMode().getPermissionsMask(), directory)));
        }
        return entries;
    }

    @Override
    public String canonicalize(String remotePath) throws IOException {
        return sftp().canonicalize(remotePath);
    }

    @Override
    public void mkdir(String remoteDir) throws IOException {
        sftp().mkdir(remoteDir);
    }

    @Override
    public void createFile(String remotePath) throws IOException {
        // CREAT together with EXCL makes the server itself refuse if something is already there, so there is no
        // moment between "does it exist?" and "create it" in which another change could be overwritten.
        // Nothing is written: opening it is what creates the empty file, so it is closed straight away.
        sftp().open(remotePath, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL)).close();
    }

    @Override
    public void rename(String from, String to) throws IOException {
        sftp().rename(from, to);
    }

    @Override
    public void rmdir(String remoteDir) throws IOException {
        sftp().rmdir(remoteDir);
    }

    @Override
    public void uploadFile(Path local, String remotePath, LongConsumer progress) throws IOException {
        SFTPClient sftp = sftp();
        try (RemoteFile file = sftp.open(remotePath, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC));
             InputStream in = Files.newInputStream(local);
             OutputStream out = file.new RemoteFileOutputStream(0, 16)) {
            copy(in, out, progress);
        }
    }

    @Override
    public void downloadFile(String remotePath, Path local, LongConsumer progress) throws IOException {
        SFTPClient sftp = sftp();
        try (RemoteFile file = sftp.open(remotePath);
             InputStream in = file.new RemoteFileInputStream(0);
             OutputStream out = Files.newOutputStream(local)) {
            copy(in, out, progress);
        }
    }

    private static void copy(InputStream in, OutputStream out, LongConsumer progress) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
            total += read;
            progress.accept(total);
        }
        out.flush();
    }

    /** One SFTP channel is opened on first use and shared by the browsing and transfer calls. */
    private synchronized SFTPClient sftp() throws IOException {
        if (sftp == null) {
            sftp = client.newSFTPClient();
        }
        return sftp;
    }

    private static String permissionString(int mask, boolean directory) {
        char[] out = "---------".toCharArray();
        String letters = "rwxrwxrwx";
        for (int i = 0; i < 9; i++) {
            if ((mask & (1 << (8 - i))) != 0) {
                out[i] = letters.charAt(i);
            }
        }
        return (directory ? "d" : "-") + new String(out);
    }

    @Override
    public boolean isOpen() {
        return client.isConnected() && client.isAuthenticated()
                && (gateway == null || gateway.isConnected());
    }

    @Override
    public void close() {
        synchronized (this) {
            if (sftp != null) {
                try {
                    sftp.close();
                } catch (IOException | RuntimeException ignored) {
                    // the connection is being torn down anyway
                }
            }
        }
        closeQuietly(client);
        if (gateway != null) {
            closeQuietly(gateway);
        }
    }

    private static byte[] read(InputStream in) {
        try {
            return IOUtils.readFully(in).toByteArray();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void closeQuietly(SSHClient ssh) {
        try {
            ssh.close();
        } catch (IOException | RuntimeException ignored) {
            // Nothing useful to do if closing fails.
        }
    }

    /** Pins on first use and refuses any later change. */
    private static final class PinningVerifier implements HostKeyVerifier {
        private final Target target;
        private final Consumer<String> onFirstUse;
        private final boolean gateway;
        HostKeyChangedException mismatch;

        PinningVerifier(Target target, Consumer<String> onFirstUse, boolean gateway) {
            this.target = target;
            this.onFirstUse = onFirstUse;
            this.gateway = gateway;
        }

        @Override
        public boolean verify(String hostname, int port, PublicKey key) {
            String actual = HostKeyPinning.fingerprint(new Buffer.PlainBuffer().putPublicKey(key).getCompactData());
            switch (HostKeyPinning.evaluate(target.pinnedFingerprint(), actual)) {
                case MATCH:
                    return true;
                case FIRST_USE:
                    onFirstUse.accept(actual);
                    return true;
                default:
                    mismatch = new HostKeyChangedException(target.host(), target.pinnedFingerprint(), actual,
                            net.schmizz.sshj.common.KeyType.fromKey(key).toString(), gateway);
                    return false;
            }
        }

        @Override
        public List<String> findExistingAlgorithms(String hostname, int port) {
            return List.of();
        }
    }
}
