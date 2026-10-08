package mt.su.nrm.ssh;

import mt.su.nrm.model.PrivilegeMode;

import java.nio.charset.StandardCharsets;

/**
 * Turns a command into the form that runs it as root. The sudo password is never part of the
 * command line: with {@code sudo -S} it travels on stdin, which is kept out of process listings
 * and shell history on the server.
 */
public final class Privilege {

    /** The command line to send, and the bytes to write to its stdin (null for none). */
    public record Invocation(String commandLine, byte[] stdin) {
    }

    private Privilege() {
    }

    public static Invocation wrap(PrivilegeMode mode, String command, String sudoPassword) {
        switch (mode) {
            case NONE:
                return new Invocation(command, null);
            case SUDO_NOPASSWD:
                return new Invocation("sudo -n sh -c " + Shell.quote(command), null);
            case SUDO_PASSWORD:
                if (sudoPassword == null) {
                    throw new IllegalStateException("A sudo password is required for this server.");
                }
                // -p '' suppresses the prompt text so it doesn't end up in the command's output.
                return new Invocation("sudo -S -p '' sh -c " + Shell.quote(command),
                        (sudoPassword + "\n").getBytes(StandardCharsets.UTF_8));
            default:
                throw new IllegalArgumentException("Unknown privilege mode " + mode);
        }
    }
}
