package mt.su.nrm.ssh;

import mt.su.nrm.model.PrivilegeMode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;

/**
 * For tests in other packages: a real {@link SshSession} over a scripted fake connection, so code that runs commands
 * can be tested end to end, including that each command shows up in the {@link CommandLog}.
 */
public final class FakeSessions {

    /** What a scripted command returns. */
    public record Reply(int exit, String out, String err) {
        public static Reply ok(String out) {
            return new Reply(0, out, "");
        }

        public static Reply fail(int exit, String err) {
            return new Reply(exit, "", err);
        }
    }

    private FakeSessions() {
    }

    /** A session whose commands are answered by {@code script}; every command line it was asked to run goes into {@code ran}. */
    public static SshSession session(Function<String, Reply> script, List<String> ran, CommandLog log) {
        SshTransport transport = new SshTransport() {
            @Override
            public Raw exec(String commandLine, byte[] stdin, Duration timeout) {
                ran.add(commandLine);
                Reply r = script.apply(commandLine);
                return new Raw(r.exit(), r.out().getBytes(StandardCharsets.UTF_8), r.err().getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public void upload(byte[] data, String remotePath) {
            }

            @Override
            public byte[] download(String remotePath) {
                return new byte[0];
            }

            @Override
            public void delete(String remotePath) {
            }

            @Override
            public List<SftpEntry> list(String remoteDir) {
                return List.of();
            }

            @Override
            public String canonicalize(String remotePath) {
                return remotePath;
            }

            @Override
            public void mkdir(String remoteDir) {
            }

            @Override
            public void createFile(String remotePath) throws IOException {
            }

            @Override
            public void rename(String from, String to) {
            }

            @Override
            public void rmdir(String remoteDir) {
            }

            @Override
            public void uploadFile(java.nio.file.Path local, String remotePath, java.util.function.LongConsumer progress) {
            }

            @Override
            public void downloadFile(String remotePath, java.nio.file.Path local,
                                     java.util.function.LongConsumer progress) {
            }

            @Override
            public void close() {
            }
        };
        return new SshSession(transport, log, PrivilegeMode.NONE, null, "u@h");
    }
}
