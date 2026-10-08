package mt.su.nrm.status;

import mt.su.nrm.ssh.CommandResult;
import mt.su.nrm.ssh.Shell;
import mt.su.nrm.ssh.SshSession;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Which optional modules the server's nginx was built with, read from {@code nginx -V}. A directive from a module the
 * build lacks makes {@code nginx -t} fail, so the pages check first and say so in plain words.
 */
public final class NginxBuild {

    private static final Pattern BINARY = Pattern.compile("[A-Za-z0-9_./-]+");

    private NginxBuild() {
    }

    /** The output of {@code nginx -V} (nginx prints it on stderr, so both are returned). */
    public static String read(SshSession session, String nginxBinary) throws IOException {
        if (nginxBinary == null || !BINARY.matcher(nginxBinary).matches()) {
            throw new IOException("The nginx program path has characters that can't be used.");
        }
        CommandResult r = session.exec(Shell.quote(nginxBinary) + " -V 2>&1", Duration.ofSeconds(15));
        return r.stdout() + r.stderr();
    }

    /** True or false if the output shows how nginx was built; empty if it doesn't (the command failed, say). */
    static Optional<Boolean> hasModule(String versionOutput, String flag) {
        if (versionOutput == null || !versionOutput.contains("configure arguments:")) {
            return Optional.empty();
        }
        return Optional.of(versionOutput.contains(flag));
    }

    public static Optional<Boolean> hasStubStatus(String versionOutput) {
        return hasModule(versionOutput, "--with-http_stub_status_module");
    }

    public static Optional<Boolean> hasRealIp(String versionOutput) {
        return hasModule(versionOutput, "--with-http_realip_module");
    }
}
