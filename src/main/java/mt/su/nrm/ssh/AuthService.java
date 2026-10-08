package mt.su.nrm.ssh;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Password files for nginx basic authentication. The entries arrive already hashed (see
 * {@code Htpasswd}); this only puts them on the server: written to a temporary file next to the
 * target, then moved into place, readable by root and the nginx worker group but not by everyone.
 */
public final class AuthService {

    /**
     * @param path            the password file; must be inside the nginx config folder
     * @param replaceExisting true to start a new file, false to add to (and update users in) an existing one
     * @param entries         lines of the form {@code user:$apr1$salt$hash}
     * @param workerUser      the user nginx workers run as (from nginx.conf's {@code user}), or empty; the file is
     *                        made readable by that group if it exists
     */
    public record PasswordFileRequest(String path, boolean replaceExisting, List<String> entries, String workerUser) {
    }

    /**
     * The outcome of writing a password file.
     *
     * @param ok     true if the server confirmed the file is in place
     * @param output what the server printed, without the internal markers
     */
    public record Result(boolean ok, String output) {
    }

    private static final Pattern ENTRY = Pattern.compile(
            "([A-Za-z0-9][A-Za-z0-9._@-]{0,63}):\\$apr1\\$[./0-9A-Za-z]{1,8}\\$[./0-9A-Za-z]{22}");
    private static final Pattern GROUP = Pattern.compile("[a-z_][a-z0-9_-]{0,31}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private AuthService() {
    }

    /**
     * Checks a request without contacting the server.
     *
     * @param confDir the nginx configuration folder the file must live in
     * @param r       the request to check
     * @return what is wrong, worded for the user; empty if the request can be sent
     */
    public static List<String> problems(String confDir, PasswordFileRequest r) {
        List<String> problems = new ArrayList<>();
        String base = confDir.replaceAll("/+$", "");
        String path = r.path();
        if (!ApplyPipeline.isSafe(path) || !path.startsWith(base + "/")) {
            problems.add("The password file must be inside the nginx config folder (" + base + "), for example "
                    + base + "/htpasswd/site.");
        } else if (path.endsWith(".conf") || path.endsWith("/nginx.conf") || path.endsWith(".types") || path.endsWith("/")) {
            problems.add("That name looks like a configuration file. Use a name such as " + base + "/htpasswd/site.");
        }
        if (r.entries().isEmpty()) {
            problems.add("Add at least one user.");
        }
        for (String e : r.entries()) {
            if (!ENTRY.matcher(e).matches()) {
                problems.add("An entry is not a valid user with a hashed password.");
                break;
            }
        }
        if (!r.workerUser().isEmpty() && !GROUP.matcher(r.workerUser()).matches()) {
            problems.add("The nginx user name has unusual characters.");
        }
        return problems;
    }

    /**
     * Writes the password file with root rights, atomically (temporary file, then move). Nothing is sent if
     * the request has problems.
     *
     * @param session the logged server session
     * @param confDir the nginx configuration folder the file must live in
     * @param request what to write
     * @return whether the server confirmed the write, with its output
     * @throws IOException if the request is invalid or the command could not be run
     */
    public static Result write(SshSession session, String confDir, PasswordFileRequest request) throws IOException {
        List<String> problems = problems(confDir, request);
        if (!problems.isEmpty()) {
            throw new IOException(String.join(" ", problems));
        }
        String nonce = nonce();
        CommandResult r = session.execPrivileged(script(request, nonce));
        String all = r.stdout() + r.stderr();
        String output = all.replaceAll("(?m)^@@NRM-" + nonce + ".*\\R?", "").strip();
        return new Result(r.ok() && all.contains("@@NRM-" + nonce + " DONE"), output);
    }

    /**
     * Builds the shell script that creates or updates the file; separate from {@link #write} so it can be tested.
     *
     * @param r     a request that has passed {@link #problems}
     * @param nonce a random token that marks the script's success line in the output
     * @return the script text
     */
    static String script(PasswordFileRequest r, String nonce) {
        String m = "@@NRM-" + nonce;
        StringBuilder s = new StringBuilder();
        s.append("umask 027\n");
        s.append("F=").append(Shell.quote(r.path())).append('\n');
        s.append("D=$(dirname \"$F\")\n");
        s.append("mkdir -p \"$D\" || { echo 'Could not create the folder.'; exit 81; }\n");
        // The folder is made under umask 027 (no access for others), which stops the nginx worker reaching the
        // file inside it even though the file itself is readable. Others may pass through but not list it.
        s.append("chmod a+x \"$D\" || { echo 'Could not make the folder accessible to nginx.'; exit 86; }\n");
        s.append("T=$(mktemp \"$D/.nrm-htpasswd.XXXXXX\") || { echo 'Could not create a temporary file.'; exit 82; }\n");
        s.append("trap 'rm -f \"$T\" \"$T.2\"' EXIT\n");
        if (r.replaceExisting()) {
            s.append(": > \"$T\"\n");
        } else {
            s.append("if [ -f \"$F\" ]; then cat \"$F\" > \"$T\" || exit 83; fi\n");
        }
        for (String entry : r.entries()) {
            String user = entry.substring(0, entry.indexOf(':'));
            if (!r.replaceExisting()) {
                // Drop an existing line for the same user (exact match on the first field), then append the new one.
                s.append("awk -F: -v u=").append(Shell.quote(user)).append(" '$1 != u' \"$T\" > \"$T.2\" && mv \"$T.2\" \"$T\" || exit 84\n");
            }
            s.append("printf '%s\\n' ").append(Shell.quote(entry)).append(" >> \"$T\"\n");
        }
        String group = r.workerUser();
        if (!group.isEmpty()) {
            s.append("if getent group ").append(Shell.quote(group)).append(" >/dev/null 2>&1; then chown root:")
                    .append(Shell.quote(group)).append(" \"$T\" && chmod 640 \"$T\"; else chmod 644 \"$T\"; fi\n");
        } else {
            s.append("chmod 644 \"$T\"\n");
        }
        s.append("mv \"$T\" \"$F\" || { echo 'Could not write the password file.'; exit 85; }\n");
        s.append("echo '").append(m).append(" DONE'\n");
        return s.toString();
    }

    /**
     * The user names already in a password file, or an empty list if it doesn't exist. Only the names
     * are read (the hashes are cut off on the server).
     *
     * @param session the logged server session
     * @param confDir the nginx configuration folder the file must live in
     * @param path    the password file
     * @return the user names in file order
     * @throws IOException if the path is outside {@code confDir} or the command could not be run
     */
    public static List<String> listUsers(SshSession session, String confDir, String path) throws IOException {
        String base = confDir.replaceAll("/+$", "");
        if (!ApplyPipeline.isSafe(path) || !path.startsWith(base + "/")) {
            throw new IOException("The password file must be inside " + base + ".");
        }
        CommandResult r = session.execPrivileged("[ -f " + Shell.quote(path) + " ] && cut -d: -f1 " + Shell.quote(path) + " || true");
        List<String> users = new ArrayList<>();
        for (String line : r.stdout().split("\r?\n")) {
            String u = line.strip();
            if (!u.isEmpty() && !u.startsWith("#")) {
                users.add(u);
            }
        }
        return users;
    }

    private static String nonce() {
        byte[] b = new byte[6];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }
}
