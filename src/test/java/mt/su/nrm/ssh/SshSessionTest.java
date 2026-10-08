package mt.su.nrm.ssh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mt.su.nrm.model.PrivilegeMode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** The logging hook: every operation on a session must show up in the command log. */
class SshSessionTest {

    /** Records what reaches the wire and answers from a script. */
    static final class FakeTransport implements SshTransport {
        final List<String> commands = new ArrayList<>();
        final List<byte[]> stdins = new ArrayList<>();
        final List<String> sftpOps = new ArrayList<>();
        Function<String, Raw> responder = c -> new Raw(0, new byte[0], new byte[0]);
        final java.util.Map<String, List<SftpEntry>> listing = new java.util.HashMap<>();
        boolean failNext;
        boolean closed;

        static Raw raw(int exit, String out, String err) {
            return new Raw(exit, out.getBytes(StandardCharsets.UTF_8), err.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public Raw exec(String commandLine, byte[] stdin, Duration timeout) throws IOException {
            commands.add(commandLine);
            stdins.add(stdin);
            if (failNext) {
                failNext = false;
                throw new IOException("connection lost");
            }
            return responder.apply(commandLine);
        }

        @Override
        public void upload(byte[] data, String remotePath) throws IOException {
            sftpOps.add("put " + remotePath);
            if (failNext) {
                failNext = false;
                throw new IOException("disk full");
            }
        }

        @Override
        public byte[] download(String remotePath) {
            sftpOps.add("get " + remotePath);
            return "hello".getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void delete(String remotePath) {
            sftpOps.add("rm " + remotePath);
        }

        @Override
        public List<SftpEntry> list(String remoteDir) {
            sftpOps.add("ls " + remoteDir);
            return listing.getOrDefault(remoteDir, List.of());
        }

        @Override
        public String canonicalize(String remotePath) {
            sftpOps.add("realpath " + remotePath);
            return "/home/u";
        }

        @Override
        public void mkdir(String remoteDir) throws IOException {
            sftpOps.add("mkdir " + remoteDir);
            if (failNext) {
                failNext = false;
                throw new IOException("already exists");
            }
        }

        @Override
        public void createFile(String remotePath) throws IOException {
            sftpOps.add("create " + remotePath);
            if (failNext) {
                failNext = false;
                throw new IOException("file exists");
            }
        }

        @Override
        public void rename(String from, String to) {
            sftpOps.add("rename " + from + " " + to);
        }

        @Override
        public void rmdir(String remoteDir) {
            sftpOps.add("rmdir " + remoteDir);
        }

        @Override
        public void uploadFile(java.nio.file.Path local, String remotePath, java.util.function.LongConsumer progress) {
            sftpOps.add("put " + remotePath);
            progress.accept(5);
        }

        @Override
        public void downloadFile(String remotePath, java.nio.file.Path local, java.util.function.LongConsumer progress)
                throws IOException {
            sftpOps.add("get " + remotePath);
            java.nio.file.Files.writeString(local, "hello");
            progress.accept(5);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private final FakeTransport transport = new FakeTransport();
    private final CommandLog log = new CommandLog();

    private SshSession session(PrivilegeMode mode, String sudoPassword) {
        return new SshSession(transport, log, mode, sudoPassword, "u@h");
    }

    private List<String> texts(CommandLog.Kind kind) {
        return log.snapshot().stream().filter(l -> l.kind() == kind).map(CommandLog.Line::text).toList();
    }

    @Test
    void execIsLoggedWithOutputAndExitStatus() throws IOException {
        transport.responder = c -> FakeTransport.raw(0, "line one\nline two\n", "warn\n");
        CommandResult result = session(PrivilegeMode.NONE, null).exec("ls /etc");

        assertTrue(result.ok());
        assertEquals(List.of("$ ls /etc"), texts(CommandLog.Kind.COMMAND));
        assertEquals(List.of("line one", "line two"), texts(CommandLog.Kind.OUTPUT));
        assertEquals(List.of("warn"), texts(CommandLog.Kind.ERROR_OUTPUT));
        assertEquals(List.of("[exit 0]"), texts(CommandLog.Kind.STATUS));
    }

    @Test
    void secretOutputIsReturnedToTheCallerButNeverWrittenToTheLog() throws IOException {
        String key = "-----BEGIN PRIVATE KEY-----\nTOPSECRETKEYMATERIAL\n-----END PRIVATE KEY-----\n";
        transport.responder = c -> FakeTransport.raw(0, key, "");
        CommandResult result = session(PrivilegeMode.NONE, null).execPrivilegedSecret("sed -n p /etc/ssl/a.key",
                Duration.ofSeconds(5));

        assertEquals(key, result.stdout(), "the caller still gets the output");
        assertEquals(List.of("$ sed -n p /etc/ssl/a.key"), texts(CommandLog.Kind.COMMAND), "the command is logged");
        assertEquals(List.of("[exit 0]"), texts(CommandLog.Kind.STATUS));
        assertTrue(texts(CommandLog.Kind.INFO).stream().anyMatch(t -> t.contains("withheld")), texts(CommandLog.Kind.INFO).toString());
        assertTrue(texts(CommandLog.Kind.OUTPUT).isEmpty());
        for (CommandLog.Line line : log.snapshot()) {
            assertFalse(line.text().contains("TOPSECRET") || line.text().contains("PRIVATE KEY-----\nT"), line.text());
        }
    }

    @Test
    void secretExecStillUsesTheSudoPasswordOnStdinAndStillLogsErrors() throws IOException {
        log.addSecret("hunter2");
        transport.responder = c -> FakeTransport.raw(1, "", "openssl: not found\n");
        CommandResult result = session(PrivilegeMode.SUDO_PASSWORD, "hunter2").execPrivilegedSecret("openssl x",
                Duration.ofSeconds(5));
        assertEquals(1, result.exitStatus());
        assertEquals("sudo -S -p '' sh -c 'openssl x'", transport.commands.get(0));
        assertArrayEquals("hunter2\n".getBytes(StandardCharsets.UTF_8), transport.stdins.get(0));
        assertEquals(List.of("openssl: not found"), texts(CommandLog.Kind.ERROR_OUTPUT), "error text is not secret");
        for (CommandLog.Line line : log.snapshot()) {
            assertFalse(line.text().contains("hunter2"), line.text());
        }
    }

    @Test
    void commandIsLoggedBeforeItIsSent() {
        transport.failNext = true;
        assertThrows(IOException.class, () -> session(PrivilegeMode.NONE, null).exec("uname -a"));

        assertEquals(List.of("$ uname -a"), texts(CommandLog.Kind.COMMAND));
        assertTrue(texts(CommandLog.Kind.STATUS).get(0).contains("connection lost"));
    }

    @Test
    void nonZeroExitIsReportedNotThrown() throws IOException {
        transport.responder = c -> FakeTransport.raw(3, "", "nope\n");
        CommandResult result = session(PrivilegeMode.NONE, null).exec("false");
        assertFalse(result.ok());
        assertEquals(3, result.exitStatus());
        assertEquals(List.of("[exit 3]"), texts(CommandLog.Kind.STATUS));
    }

    @Test
    void sudoPasswordTravelsOnStdinNeverInTheCommandOrTheLog() throws IOException {
        log.addSecret("hunter2");
        transport.responder = c -> FakeTransport.raw(0, "root\n", "");
        session(PrivilegeMode.SUDO_PASSWORD, "hunter2").execPrivileged("whoami");

        assertEquals("sudo -S -p '' sh -c 'whoami'", transport.commands.get(0));
        assertArrayEquals("hunter2\n".getBytes(StandardCharsets.UTF_8), transport.stdins.get(0));
        for (CommandLog.Line line : log.snapshot()) {
            assertFalse(line.text().contains("hunter2"), line.text());
        }
        assertTrue(texts(CommandLog.Kind.INFO).stream().anyMatch(t -> t.contains("stdin")));
    }

    @Test
    void secretsEchoedBackByTheServerAreMasked() throws IOException {
        log.addSecret("hunter2");
        transport.responder = c -> FakeTransport.raw(0, "password is hunter2\n", "");
        session(PrivilegeMode.NONE, null).exec("echo");
        assertEquals(List.of("password is " + CommandLog.MASK), texts(CommandLog.Kind.OUTPUT));
    }

    @Test
    void noPasswordModesSendNothingOnStdin() throws IOException {
        session(PrivilegeMode.SUDO_NOPASSWD, null).execPrivileged("id -u");
        session(PrivilegeMode.NONE, null).execPrivileged("id -u");

        assertEquals("sudo -n sh -c 'id -u'", transport.commands.get(0));
        assertEquals("id -u", transport.commands.get(1));
        assertNull(transport.stdins.get(0));
        assertNull(transport.stdins.get(1));
    }

    @Test
    void sftpOperationsAreLoggedWithoutContent() throws IOException {
        SshSession s = session(PrivilegeMode.NONE, null);
        s.upload("secret config".getBytes(StandardCharsets.UTF_8), "/tmp/a.conf");
        s.download("/tmp/a.conf");
        s.delete("/tmp/a.conf");

        assertEquals(List.of("put /tmp/a.conf", "get /tmp/a.conf", "rm /tmp/a.conf"), transport.sftpOps);
        assertEquals(List.of("sftp put /tmp/a.conf (13 bytes)", "sftp get /tmp/a.conf", "sftp rm /tmp/a.conf"),
                texts(CommandLog.Kind.COMMAND));
        assertFalse(log.snapshot().stream().anyMatch(l -> l.text().contains("secret config")));
    }

    @Test
    void browsingAndStreamingOperationsAreLoggedToo() throws IOException {
        SshSession s = session(PrivilegeMode.NONE, null);
        java.nio.file.Path file = java.nio.file.Files.createTempFile("nrm", ".txt");
        try {
            java.nio.file.Files.writeString(file, "hello");
            s.sftpList("/var/www");
            s.sftpResolve(".");
            s.sftpMkdir("/var/www/new");
            s.sftpRename("/a", "/b");
            s.sftpRmdir("/var/www/new");
            s.sftpUploadFile(file, "/var/www/a.txt", n -> { });
            s.sftpDownloadFile("/var/www/a.txt", file, n -> { });
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }

        assertEquals(List.of("sftp ls /var/www", "sftp realpath .", "sftp mkdir /var/www/new", "sftp rename /a /b",
                "sftp rmdir /var/www/new", "sftp put /var/www/a.txt (5 bytes)", "sftp get /var/www/a.txt"),
                texts(CommandLog.Kind.COMMAND));
        assertEquals(7, texts(CommandLog.Kind.STATUS).stream().filter(t -> t.endsWith("ok]")).count());
    }

    @Test
    void creatingAFileIsLoggedAndReachesTheTransport() throws IOException {
        session(PrivilegeMode.NONE, null).sftpCreateFile("/var/www/new.txt");
        assertEquals(List.of("sftp create /var/www/new.txt"), texts(CommandLog.Kind.COMMAND));
        assertEquals(List.of("create /var/www/new.txt"), transport.sftpOps);
    }

    @Test
    void creatingAFileThatExistsFailsAndIsLogged() {
        transport.failNext = true;
        IOException e = assertThrows(IOException.class,
                () -> session(PrivilegeMode.NONE, null).sftpCreateFile("/var/www/new.txt"));
        assertTrue(e.getMessage().contains("file exists"));
        assertEquals("[sftp create failed: file exists]", texts(CommandLog.Kind.STATUS).get(0));
    }

    @Test
    void failedBrowsingOperationIsLoggedAndRethrown() {
        transport.failNext = true;
        assertThrows(IOException.class, () -> session(PrivilegeMode.NONE, null).sftpMkdir("/x"));
        assertEquals("[sftp mkdir failed: already exists]", texts(CommandLog.Kind.STATUS).get(0));
    }

    @Test
    void failedSftpIsLoggedAndRethrown() {
        transport.failNext = true;
        assertThrows(IOException.class, () -> session(PrivilegeMode.NONE, null).upload(new byte[1], "/x"));
        assertTrue(texts(CommandLog.Kind.STATUS).get(0).contains("disk full"));
    }

    @Test
    void closeClosesTheTransportAndIsLogged() {
        session(PrivilegeMode.NONE, null).close();
        assertTrue(transport.closed);
        assertTrue(texts(CommandLog.Kind.INFO).get(0).startsWith("Disconnected"));
    }

    @Test
    void sudoPasswordModeWithoutPasswordFailsBeforeSendingAnything() {
        assertThrows(IllegalStateException.class,
                () -> session(PrivilegeMode.SUDO_PASSWORD, null).execPrivileged("id -u"));
        assertTrue(transport.commands.isEmpty());
    }
}
