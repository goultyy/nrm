package mt.su.nrm.ssh;

/**
 * The outcome of one remote command. Output can contain anything the server printed, so
 * {@link #toString()} reports sizes only.
 *
 * @param exitStatus the command's exit code, or -1 if the server did not report one
 */
public record CommandResult(int exitStatus, String stdout, String stderr) {

    public boolean ok() {
        return exitStatus == 0;
    }

    @Override
    public String toString() {
        return "CommandResult[exit=" + exitStatus + ", stdout=" + stdout.length() + " chars, stderr="
                + stderr.length() + " chars]";
    }
}
