package mt.su.nrm.logformat;

/**
 * One nginx variable that can go into a log format, with what the builder shows about it.
 *
 * @param variable    the variable with its dollar sign, such as {@code $remote_addr}
 * @param title       a plain name
 * @param description what it holds, in a sentence
 * @param sample      a realistic value, used for the live preview
 * @param numeric     true if the value is always a number (so a JSON log can leave it unquoted); a variable that can be
 *                    empty or "-" is not
 * @param category    the group it is listed under
 * @param note        something to know before using it (a module it needs, a version), or empty
 */
public record LogField(String variable, String title, String description, String sample, boolean numeric,
                       String category, String note) {

    /** The variable without its dollar sign. */
    public String name() {
        return variable.substring(1);
    }

    /** A JSON key that suits this variable, such as {@code remote_addr}. */
    public String defaultKey() {
        return name().replaceFirst("^(http_|sent_http_|upstream_http_)", "");
    }

    @Override
    public String toString() {
        return title + "  (" + variable + ")";
    }
}
