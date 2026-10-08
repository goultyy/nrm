package mt.su.nrm.ssh;

import mt.su.nrm.model.ServerPaths;
import mt.su.nrm.ssl.AltNames;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Let's Encrypt through certbot, run on the server. The default is the webroot challenge; the nginx
 * plugin is an option. There is no DNS challenge in this version, so wildcard names are refused.
 * Everything runs through the logging hook, so the exact certbot command is visible in the log.
 */
public final class CertbotService {

    public enum Method { WEBROOT, NGINX }

    /**
     * @param domains  names to put on the certificate (no wildcards)
     * @param email    contact address for expiry notices; empty registers without one
     * @param webroot  folder that nginx serves {@code /.well-known/acme-challenge/} from (webroot method)
     * @param certName name of the certificate lineage; empty means the first domain
     */
    public record IssueRequest(List<String> domains, String email, Method method, String webroot, boolean staging,
                               String certName) {
        public String effectiveName() {
            return certName == null || certName.isBlank() ? domains.get(0) : certName.strip();
        }
    }

    /** @param certPath fullchain.pem for the issued certificate (null if not issued); keyPath is its privkey.pem */
    public record Result(boolean ok, String output, String certPath, String keyPath) {
    }

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Duration TIMEOUT = Duration.ofMinutes(6);
    private static final SecureRandom RANDOM = new SecureRandom();

    private CertbotService() {
    }

    /** What is wrong with a request, worded for the user; empty if it can be sent. */
    public static List<String> problems(IssueRequest r) {
        List<String> problems = new ArrayList<>();
        if (r.domains().isEmpty()) {
            problems.add("Enter at least one domain name.");
        }
        for (String d : r.domains()) {
            if (d.startsWith("*.")) {
                problems.add("Wildcard names need the DNS challenge, which this version doesn't support: " + d);
            } else if (!AltNames.isDnsName(d, false)) {
                problems.add("\"" + d + "\" is not a valid domain name.");
            }
        }
        if (!r.email().isBlank() && !AltNames.isEmail(r.email().strip())) {
            problems.add("The e-mail address doesn't look right.");
        }
        if (r.method() == Method.WEBROOT && !ApplyPipeline.isSafe(r.webroot() == null ? "" : r.webroot().strip())) {
            problems.add("The webroot must be an absolute path such as /var/www/html.");
        }
        if (r.certName() != null && !r.certName().isBlank() && !NAME.matcher(r.certName().strip()).matches()) {
            problems.add("The certificate name may only use letters, digits, dots, dashes and underscores.");
        }
        return problems;
    }

    public static Result issue(SshSession session, ServerPaths paths, IssueRequest request) throws IOException {
        List<String> problems = problems(request);
        if (!problems.isEmpty()) {
            throw new IOException(String.join(" ", problems));
        }
        String nonce = nonce();
        CommandResult r = session.execPrivileged(issueScript(paths, request, nonce), TIMEOUT);
        String output = clean(r.stdout() + r.stderr(), nonce);
        String live = paths.getLetsEncryptDir().replaceAll("/+$", "") + "/live/" + request.effectiveName();
        boolean ok = r.ok() && (r.stdout() + r.stderr()).contains("@@NRM-" + nonce + " DONE");
        return new Result(ok, output, ok ? live + "/fullchain.pem" : null, ok ? live + "/privkey.pem" : null);
    }

    static String issueScript(ServerPaths paths, IssueRequest r, String nonce) {
        StringBuilder cmd = new StringBuilder("\"$C\" certonly --non-interactive --agree-tos");
        cmd.append(r.email().isBlank() ? " --register-unsafely-without-email" : " -m " + Shell.quote(r.email().strip()));
        if (r.staging()) {
            cmd.append(" --staging");
        }
        cmd.append(" --cert-name ").append(Shell.quote(r.effectiveName()));
        if (r.method() == Method.NGINX) {
            cmd.append(" --nginx");
        } else {
            cmd.append(" --webroot -w ").append(Shell.quote(r.webroot().strip()));
        }
        for (String d : r.domains()) {
            cmd.append(" -d ").append(Shell.quote(d));
        }
        return prelude(paths, nonce) + cmd + " 2>&1\nrc=$?\n[ $rc -eq 0 ] && echo '@@NRM-" + nonce + " DONE'\nexit $rc\n";
    }

    /** Deletes a Let's Encrypt certificate (its files and renewal settings) with {@code certbot delete}. */
    public static Result delete(SshSession session, ServerPaths paths, String certName) throws IOException {
        if (certName == null || !NAME.matcher(certName.strip()).matches()) {
            throw new IOException("The certificate name has characters this app will not put in a command.");
        }
        String nonce = nonce();
        CommandResult r = session.execPrivileged(deleteScript(paths, certName.strip(), nonce), TIMEOUT);
        String all = r.stdout() + r.stderr();
        return new Result(r.ok() && all.contains("@@NRM-" + nonce + " DONE"), clean(all, nonce), null, null);
    }

    static String deleteScript(ServerPaths paths, String certName, String nonce) {
        return prelude(paths, nonce) + "\"$C\" delete --non-interactive --cert-name " + Shell.quote(certName)
                + " 2>&1\nrc=$?\n[ $rc -eq 0 ] && echo '@@NRM-" + nonce + " DONE'\nexit $rc\n";
    }

    /**
     * Renews one certificate (or all that are due) and reloads nginx so it picks up the new files.
     *
     * @param certName the lineage to renew, or null/blank for every certificate that is due
     * @param force    renew even if not yet due
     */
    public static Result renew(SshSession session, ServerPaths paths, String certName, boolean force) throws IOException {
        if (certName != null && !certName.isBlank() && !NAME.matcher(certName.strip()).matches()) {
            throw new IOException("The certificate name has characters this app will not put in a command.");
        }
        String nonce = nonce();
        CommandResult r = session.execPrivileged(renewScript(paths, certName, force, nonce), TIMEOUT);
        String all = r.stdout() + r.stderr();
        return new Result(r.ok() && all.contains("@@NRM-" + nonce + " DONE"), clean(all, nonce), null, null);
    }

    static String renewScript(ServerPaths paths, String certName, boolean force, String nonce) {
        StringBuilder cmd = new StringBuilder("\"$C\" renew --non-interactive");
        if (certName != null && !certName.isBlank()) {
            cmd.append(" --cert-name ").append(Shell.quote(certName.strip()));
        }
        if (force) {
            cmd.append(" --force-renewal");
        }
        String nginx = Shell.quote(paths.getNginxBinary());
        String conf = Shell.quote(paths.mainConfigFile());
        return prelude(paths, nonce) + cmd + " 2>&1\nrc=$?\n[ $rc -eq 0 ] || exit $rc\n"
                + "out=$(" + nginx + " -t -c " + conf + " 2>&1 && " + nginx + " -c " + conf + " -s reload 2>&1); rc=$?\n"
                + "printf '%s\\n' \"$out\"\n[ $rc -eq 0 ] && echo '@@NRM-" + nonce + " DONE'\nexit $rc\n";
    }

    /** Finds certbot: the configured path, else whatever is on the PATH. */
    private static String prelude(ServerPaths paths, String nonce) {
        return "C=" + Shell.quote(paths.getCertbotBinary()) + "\n"
                + "[ -x \"$C\" ] || C=$(command -v certbot)\n"
                + "[ -n \"$C\" ] || { echo 'certbot is not installed on this server.'; echo '@@NRM-" + nonce + " NO-CERTBOT'; exit 127; }\n";
    }

    private static String clean(String output, String nonce) {
        StringBuilder sb = new StringBuilder();
        for (String line : output.split("\r?\n")) {
            if (!line.startsWith("@@NRM-" + nonce)) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString().strip();
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
