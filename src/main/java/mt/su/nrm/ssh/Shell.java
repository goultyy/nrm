package mt.su.nrm.ssh;

/** Quoting for values placed into remote shell commands. */
public final class Shell {

    private Shell() {
    }

    /** Single-quotes a value so the remote shell treats it as one literal word. */
    public static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
