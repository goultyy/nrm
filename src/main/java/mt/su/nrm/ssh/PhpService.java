package mt.su.nrm.ssh;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * PHP-FPM on the server: finding out whether it is there and where it listens, and installing it
 * with the server's package manager. Detection only reads; installation runs as root. Both go
 * through the logging hook like everything else, so the exact commands are visible in the log.
 */
public final class PhpService {

    /**
     * What was found.
     *
     * @param installed      whether a PHP-FPM program is present
     * @param version        the PHP version reported, or empty
     * @param endpoints      ready-to-use {@code fastcgi_pass} values found on the server, best first
     *                       ({@code unix:/run/php/php8.2-fpm.sock}, {@code 127.0.0.1:9000})
     * @param running        whether a PHP-FPM service is running
     * @param snippet        {@code snippets/fastcgi-php.conf} if nginx's config folder has it, else empty
     * @param hasFastcgiParams whether {@code fastcgi_params} exists in nginx's config folder
     * @param packageManager {@code apt}, {@code dnf} or {@code yum}, or empty if none was recognised
     */
    public record Status(boolean installed, String version, List<String> endpoints, boolean running, String snippet,
                         boolean hasFastcgiParams, String packageManager) {

        /** The endpoint to suggest for a new PHP location, or empty if there is none. */
        public String preferredEndpoint() {
            return endpoints.isEmpty() ? "" : endpoints.get(0);
        }

        /** True if a new PHP location can be created without installing or starting anything. */
        public boolean ready() {
            return installed && running && !endpoints.isEmpty();
        }
    }

    /** @param output what the package manager and service commands printed, without the app's own markers */
    public record Result(boolean ok, String output) {
    }

    private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(10);
    private static final SecureRandom RANDOM = new SecureRandom();

    private PhpService() {
    }

    public static Status detect(SshSession session, String confDir) throws IOException {
        String dir = confDir.replaceAll("/+$", "");
        if (!ApplyPipeline.isSafe(dir + "/x")) {
            throw new IOException("The nginx configuration folder in the profile's Paths has unusual characters.");
        }
        CommandResult r = session.exec(detectScript(dir));
        if (!r.ok()) {
            throw new IOException("Could not check for PHP-FPM: " + r.stderr().strip());
        }
        return parse(r.stdout());
    }

    static String detectScript(String confDir) {
        return "for f in /usr/sbin/php-fpm* /usr/sbin/php*-fpm /usr/local/sbin/php-fpm*; do "
                + "[ -x \"$f\" ] && { echo \"NRM-BIN $f\"; \"$f\" -v 2>/dev/null | head -n 1 | sed 's/^/NRM-VERSION /'; break; }; done\n"
                + "for s in /run/php/*.sock /var/run/php/*.sock /run/php-fpm/*.sock /var/run/php-fpm/*.sock; do "
                + "[ -S \"$s\" ] && echo \"NRM-SOCK $s\"; done\n"
                + "(ss -ltn 2>/dev/null || netstat -ltn 2>/dev/null) | grep -o '127\\.0\\.0\\.1:9000' | head -n 1 | sed 's/^/NRM-TCP /'\n"
                + "for u in $(systemctl list-units --state=active --no-legend --plain 'php*fpm*' 2>/dev/null | awk '{print $1}'); do "
                + "echo \"NRM-RUNNING $u\"; done\n"
                + "[ -f " + Shell.quote(confDir + "/snippets/fastcgi-php.conf") + " ] && echo 'NRM-SNIPPET snippets/fastcgi-php.conf'\n"
                + "[ -f " + Shell.quote(confDir + "/fastcgi_params") + " ] && echo 'NRM-PARAMS yes'\n"
                + "if command -v apt-get >/dev/null 2>&1; then echo 'NRM-PM apt'; "
                + "elif command -v dnf >/dev/null 2>&1; then echo 'NRM-PM dnf'; "
                + "elif command -v yum >/dev/null 2>&1; then echo 'NRM-PM yum'; fi\n"
                + "exit 0\n";
    }

    /** Reads the {@code NRM-KEY value} lines the detect script prints. */
    static Status parse(String output) {
        boolean installed = false;
        boolean running = false;
        boolean params = false;
        String version = "";
        String snippet = "";
        String pm = "";
        List<String> sockets = new ArrayList<>();
        List<String> tcp = new ArrayList<>();
        for (String line : output.split("\r?\n")) {
            int space = line.indexOf(' ');
            if (!line.startsWith("NRM-") || space < 0) {
                continue;
            }
            String key = line.substring(0, space);
            String value = line.substring(space + 1).strip();
            switch (key) {
                case "NRM-BIN":
                    installed = true;
                    break;
                case "NRM-VERSION":
                    version = value;
                    break;
                case "NRM-SOCK":
                    sockets.add("unix:" + value);
                    break;
                case "NRM-TCP":
                    tcp.add(value);
                    break;
                case "NRM-RUNNING":
                    running = true;
                    break;
                case "NRM-SNIPPET":
                    snippet = value;
                    break;
                case "NRM-PARAMS":
                    params = true;
                    break;
                case "NRM-PM":
                    pm = value;
                    break;
                default:
                    break;
            }
        }
        List<String> endpoints = new ArrayList<>(sockets);
        endpoints.addAll(tcp);
        return new Status(installed, version, endpoints, running, snippet, params, pm);
    }

    /** Installs PHP-FPM with the server's package manager and starts it. Nothing else is installed. */
    public static Result install(SshSession session, String packageManager) throws IOException {
        String nonce = nonce();
        String script = installScript(packageManager, nonce);
        CommandResult r = session.execPrivileged(script, INSTALL_TIMEOUT);
        String all = r.stdout() + r.stderr();
        String output = all.replaceAll("(?m)^@@NRM-" + nonce + ".*\\R?", "").strip();
        return new Result(r.ok() && all.contains("@@NRM-" + nonce + " DONE"), output);
    }

    static String installScript(String packageManager, String nonce) throws IOException {
        String install;
        switch (packageManager) {
            case "apt":
                install = "export DEBIAN_FRONTEND=noninteractive\n"
                        + "apt-get update && apt-get install -y php-fpm || exit 71\n";
                break;
            case "dnf":
                install = "dnf install -y php-fpm || exit 71\n";
                break;
            case "yum":
                install = "yum install -y php-fpm || exit 71\n";
                break;
            default:
                throw new IOException("This server's package manager was not recognised (apt, dnf and yum are supported). "
                        + "Install PHP-FPM on the server yourself, then try again.");
        }
        return install
                + "U=$(systemctl list-unit-files --no-legend --plain 'php*fpm*.service' 2>/dev/null | awk '{print $1}' | head -n 1)\n"
                + "if [ -z \"$U\" ]; then echo 'PHP-FPM was installed but no service for it was found.'; exit 72; fi\n"
                + "systemctl enable --now \"$U\" || { echo \"Could not start $U.\"; exit 73; }\n"
                + "echo '@@NRM-" + nonce + " DONE'\n";
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
