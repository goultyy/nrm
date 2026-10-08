package mt.su.nrm.ssh;

import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;

/**
 * Reads the end of an nginx log file from the server. Logs are usually readable only by root or the adm
 * group, so this runs with root rights, which is why the file must be one the configuration names or
 * sit under /var/log: it must never become a way to read any file on the server.
 */
public final class LogService {

    /** Most lines that can be requested at once. */
    public static final int MAX_LINES = 20_000;
    /** Cap on what is sent back, however long the lines are. */
    private static final int MAX_BYTES = 4_000_000;
    /** How far back a text search looks, so a huge log doesn't take minutes. */
    private static final int SEARCH_WINDOW = 500_000;

    private LogService() {
    }

    /**
     * @param path      the log file
     * @param known     the log paths named in the configuration
     * @param lines     how many of the last lines to return
     * @param filter    only lines containing this text (case-insensitive), or ""
     * @return what is wrong with the request, worded for the user; empty if it can be sent
     */
    public static List<String> problems(String path, Collection<String> known, int lines, String filter) {
        List<String> problems = new java.util.ArrayList<>();
        if (!ApplyPipeline.isSafe(path) || !path.startsWith("/")) {
            problems.add("That isn't a usable log file path.");
        } else if (!known.contains(path) && !path.startsWith("/var/log/")) {
            problems.add("Only logs named in the nginx configuration or under /var/log can be read.");
        }
        if (lines < 1 || lines > MAX_LINES) {
            problems.add("Show between 1 and " + MAX_LINES + " lines.");
        }
        if (filter.length() > 200 || filter.chars().anyMatch(c -> c < 0x20)) {
            problems.add("The search text is too long or has control characters.");
        }
        return problems;
    }

    /** The last lines of a log, optionally only those containing the filter text. */
    public static String tail(SshSession session, String path, Collection<String> known, int lines, String filter)
            throws IOException {
        List<String> problems = problems(path, known, lines, filter);
        if (!problems.isEmpty()) {
            throw new IOException(String.join(" ", problems));
        }
        CommandResult r = session.execPrivileged(script(path, lines, filter), Duration.ofSeconds(60));
        if (r.exitStatus() == 44) {
            throw new IOException("There is no log file at " + path + " yet (nginx creates it when something is logged).");
        }
        if (!r.ok()) {
            throw new IOException("Could not read " + path + ": " + (r.stderr().isBlank() ? "exit status " + r.exitStatus() : r.stderr().strip()));
        }
        return r.stdout();
    }

    static String script(String path, int lines, String filter) {
        String file = Shell.quote(path);
        StringBuilder s = new StringBuilder();
        s.append("[ -f ").append(file).append(" ] || exit 44\n");
        if (filter.isEmpty()) {
            s.append("tail -n ").append(lines).append(" -- ").append(file);
        } else {
            s.append("tail -n ").append(SEARCH_WINDOW).append(" -- ").append(file)
                    .append(" | grep -a -i -F -e ").append(Shell.quote(filter)).append(" | tail -n ").append(lines);
        }
        s.append(" | tail -c ").append(MAX_BYTES).append('\n');
        return s.toString();
    }
}
