package mt.su.nrm.nginx;

/** The configuration text is not valid nginx syntax. The app refuses to edit such a file. */
public final class NginxParseException extends Exception {

    private static final long serialVersionUID = 1L;

    private final int line;

    public NginxParseException(String message, int line) {
        super(message + " (line " + line + ")");
        this.line = line;
    }

    /** 1-based line where the problem was found. */
    public int line() {
        return line;
    }
}
