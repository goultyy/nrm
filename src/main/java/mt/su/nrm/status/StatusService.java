package mt.su.nrm.status;

import mt.su.nrm.ssh.CommandResult;
import mt.su.nrm.ssh.Shell;
import mt.su.nrm.ssh.SshSession;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads the status page from the server itself. It runs {@code curl} (or {@code wget}) there against the loopback
 * address, as the login user, through the session, so every reading shows in the command log like any other command.
 * Everything that goes into the command line is checked first and quoted.
 */
public final class StatusService {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final Pattern HOST = Pattern.compile("127\\.0\\.0\\.1|\\[::1\\]|localhost");
    private static final Pattern PATH = Pattern.compile("/[A-Za-z0-9_./-]*");
    private static final Pattern HOST_HEADER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.-]*");

    private StatusService() {
    }

    /** What is wrong with where the page is, worded for the user; empty if it can be asked for. */
    public static List<String> problems(String host, int port, String path, String hostHeader) {
        List<String> problems = new ArrayList<>();
        if (!HOST.matcher(host).matches()) {
            problems.add("The status page must be on this machine (127.0.0.1, ::1 or localhost), not " + host + ".");
        }
        if (port < 1 || port > 65535) {
            problems.add("The port must be between 1 and 65535.");
        }
        if (!PATH.matcher(path).matches()) {
            problems.add("The path " + path + " has characters that can't be used.");
        }
        if (hostHeader != null && !HOST_HEADER.matcher(hostHeader).matches()) {
            problems.add("The server name " + hostHeader + " has characters that can't be used.");
        }
        return problems;
    }

    /** The shell script that fetches the page; the values must have passed {@link #problems}. */
    static String script(String host, int port, String path, String hostHeader) {
        String url = Shell.quote("http://" + host + ":" + port + path);
        String curlHeader = hostHeader == null ? "" : " -H " + Shell.quote("Host: " + hostHeader);
        String wgetHeader = hostHeader == null ? "" : " --header=" + Shell.quote("Host: " + hostHeader);
        return "if command -v curl >/dev/null 2>&1; then\n"
                + "  curl -fsS --max-time 5" + curlHeader + " " + url + "\n"
                + "elif command -v wget >/dev/null 2>&1; then\n"
                + "  wget -q -O - -T 5" + wgetHeader + " " + url + "\n"
                + "else\n"
                + "  exit 127\n"
                + "fi\n";
    }

    /** Takes one reading. Throws an {@link IOException} whose message says, in plain words, why it failed. */
    public static StubStatus sample(SshSession session, String host, int port, String path, String hostHeader)
            throws IOException {
        List<String> problems = problems(host, port, path, hostHeader);
        if (!problems.isEmpty()) {
            throw new IOException(String.join(" ", problems));
        }
        CommandResult r = session.exec(script(host, port, path, hostHeader), TIMEOUT);
        if (!r.ok()) {
            throw new IOException(explain(r.exitStatus(), r.stderr(), host, port));
        }
        try {
            return StubStatus.parse(r.stdout());
        } catch (IllegalArgumentException e) {
            throw new IOException("The server answered, but not with an nginx status page. Is something else "
                    + "listening on " + host + ":" + port + "?", e);
        }
    }

    /** Turns the exit status of curl or wget into a sentence. */
    static String explain(Integer exit, String stderr, String host, int port) {
        int code = exit == null ? -1 : exit;
        return switch (code) {
            case 127 -> "Neither curl nor wget is installed on the server, so the status page can't be read.";
            case 7, 4 -> "Nothing is listening on " + host + ":" + port + " yet. If you have just enabled the status "
                    + "page, apply the pending changes first.";
            case 22, 8 -> "nginx answered with an error. The path may be wrong, or access to the page is denied.";
            case 28 -> "The status page did not answer in time.";
            default -> stderr == null || stderr.isBlank() ? "Reading the status page failed (exit status " + code + ")."
                    : "Reading the status page failed: " + stderr.strip();
        };
    }
}
