package mt.su.nrm.ssh;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Installs software on a server with whatever package manager it has (apt, dnf or yum). What to
 * install is described by a {@link Package}; adding a new one is a new constant, not new code.
 * Installation runs as root through {@link SshSession#execPrivileged}, so the exact script is
 * visible in the command log like every other call.
 */
public final class PackageInstaller {

    /** The package managers that are recognised, in the order they are looked for. */
    public enum Manager {
        APT("apt", "apt-get"),
        DNF("dnf", "dnf"),
        YUM("yum", "yum");

        private final String id;
        private final String program;

        Manager(String id, String program) {
            this.id = id;
            this.program = program;
        }

        public String id() {
            return id;
        }

        /** The program that has to exist on the server for this manager to be in use. */
        String program() {
            return program;
        }
    }

    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.+_-]*");

    /**
     * Something that can be installed.
     *
     * @param label    how it is described to the user ("certbot")
     * @param binary   the program that must be on the server's PATH afterwards, used to confirm the install
     * @param packages the package names to install, for each package manager that has them
     * @param helpers  packages to try first and to carry on without if they can't be installed (such as
     *                 {@code epel-release}, which some distributions need and others don't have)
     */
    public record Package(String label, String binary, Map<Manager, List<String>> packages,
                          Map<Manager, List<String>> helpers) {

        public Package {
            checkName(binary);
            packages = Map.copyOf(packages);
            helpers = Map.copyOf(helpers);
            packages.values().forEach(names -> names.forEach(PackageInstaller::checkName));
            helpers.values().forEach(names -> names.forEach(PackageInstaller::checkName));
        }

        public Package(String label, String binary, Map<Manager, List<String>> packages) {
            this(label, binary, packages, Map.of());
        }
    }

    /** certbot, for Let's Encrypt. Red Hat family systems get it from EPEL, which is enabled first if available. */
    public static final Package CERTBOT = new Package("certbot", "certbot",
            Map.of(Manager.APT, List.of("certbot"), Manager.DNF, List.of("certbot"), Manager.YUM, List.of("certbot")),
            Map.of(Manager.DNF, List.of("epel-release"), Manager.YUM, List.of("epel-release")));

    /** @param output what the package manager printed, without the app's own markers */
    public record Result(boolean ok, String output) {
    }

    private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(10);
    private static final SecureRandom RANDOM = new SecureRandom();

    private PackageInstaller() {
    }

    /** The package manager the server uses, or empty if none of the supported ones is there. */
    public static Optional<Manager> detect(SshSession session) throws IOException {
        CommandResult r = session.exec(detectScript());
        String found = r.stdout().strip();
        for (Manager m : Manager.values()) {
            if (m.id().equals(found)) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }

    static String detectScript() {
        StringBuilder script = new StringBuilder();
        for (Manager m : Manager.values()) {
            script.append(script.length() == 0 ? "if" : "elif")
                    .append(" command -v ").append(m.program()).append(" >/dev/null 2>&1; then echo ")
                    .append(m.id()).append("; ");
        }
        return script.append("fi; exit 0").toString();
    }

    /** Detects the package manager and installs {@code pkg} with it. */
    public static Result install(SshSession session, Package pkg) throws IOException {
        Optional<Manager> manager = detect(session);
        if (manager.isEmpty()) {
            return new Result(false, "This server's package manager was not recognised (apt, dnf and yum are "
                    + "supported). Install " + pkg.label() + " on the server yourself, then try again.");
        }
        String nonce = nonce();
        String script = installScript(manager.get(), pkg, nonce);
        CommandResult r = session.execPrivileged(script, INSTALL_TIMEOUT);
        String all = r.stdout() + r.stderr();
        String output = all.replaceAll("(?m)^@@NRM-" + nonce + ".*\\R?", "").strip();
        return new Result(r.ok() && all.contains("@@NRM-" + nonce + " DONE"), output);
    }

    static String installScript(Manager manager, Package pkg, String nonce) throws IOException {
        List<String> names = pkg.packages().get(manager);
        if (names == null || names.isEmpty()) {
            throw new IOException(pkg.label() + " is not available through " + manager.id() + " in NRM. "
                    + "Install it on the server yourself, then try again.");
        }
        StringBuilder script = new StringBuilder();
        String install;
        switch (manager) {
            case APT:
                script.append("export DEBIAN_FRONTEND=noninteractive\n")
                        .append("apt-get update || echo 'apt-get update reported problems; trying the install anyway.'\n");
                install = "apt-get install -y ";
                break;
            default:
                install = manager.id() + " install -y ";
                break;
        }
        List<String> helpers = pkg.helpers().getOrDefault(manager, List.of());
        if (!helpers.isEmpty()) {
            script.append(install).append(String.join(" ", helpers))
                    .append(" || echo 'Could not install ").append(String.join(" ", helpers))
                    .append("; carrying on without it.'\n");
        }
        script.append(install).append(String.join(" ", names)).append(" || exit 71\n")
                .append("command -v ").append(pkg.binary()).append(" >/dev/null 2>&1 || { echo '")
                .append(pkg.label()).append(" was installed but \"").append(pkg.binary())
                .append("\" was not found on the PATH.'; exit 72; }\n")
                .append("echo '@@NRM-").append(nonce).append(" DONE'\n");
        return script.toString();
    }

    private static void checkName(String name) {
        if (!SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Not a plain package or program name: " + name);
        }
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
