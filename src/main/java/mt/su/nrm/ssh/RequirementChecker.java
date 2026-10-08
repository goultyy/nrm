package mt.su.nrm.ssh;

import mt.su.nrm.model.ServerPaths;
import mt.su.nrm.model.ToolStatus;

import java.io.IOException;
import java.time.Clock;

/** Checks what the server has that the app depends on: openssl, certbot, and working root access. */
public final class RequirementChecker {

    /**
     * @param openssl          never null
     * @param certbot          never null
     * @param privilegeOk      whether commands can run as root in this server's privilege mode
     * @param privilegeMessage explanation when {@code privilegeOk} is false, otherwise empty
     */
    public record Requirements(ToolStatus openssl, ToolStatus certbot, boolean privilegeOk, String privilegeMessage) {
    }

    private RequirementChecker() {
    }

    public static Requirements check(SshSession session, ServerPaths paths, Clock clock) throws IOException {
        ToolStatus openssl = parseTool(
                session.exec("p=$(command -v openssl); [ -n \"$p\" ] && echo \"$p\" && \"$p\" version"), clock);
        ToolStatus certbot = parseTool(session.exec(
                "p=" + Shell.quote(paths.getCertbotBinary()) + "; [ -x \"$p\" ] || p=$(command -v certbot); "
                        + "[ -n \"$p\" ] && echo \"$p\" && \"$p\" --version 2>&1"), clock);

        CommandResult id = session.execPrivileged("id -u");
        boolean root = id.ok() && id.stdout().strip().equals("0");
        String message = root ? "" : privilegeProblem(id);
        return new Requirements(openssl, certbot, root, message);
    }

    /**
     * Reads the output of a "print the path, then the version" probe: the first line is the path,
     * the second the version. Anything else, or a failing exit status, means the tool is missing.
     */
    static ToolStatus parseTool(CommandResult result, Clock clock) {
        String[] lines = result.stdout().strip().split("\r?\n");
        if (!result.ok() || lines.length == 0 || lines[0].isBlank()) {
            return ToolStatus.missing(clock.instant());
        }
        String version = lines.length > 1 ? lines[1].strip() : null;
        return ToolStatus.found(version, lines[0].strip(), clock.instant());
    }

    private static String privilegeProblem(CommandResult id) {
        String detail = id.stderr().strip();
        if (detail.isEmpty()) {
            detail = "the command ran as user id \"" + id.stdout().strip() + "\" instead of root";
        }
        return "Root access does not work with this server's settings: " + detail;
    }
}
