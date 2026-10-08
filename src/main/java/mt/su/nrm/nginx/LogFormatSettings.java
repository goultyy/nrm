package mt.su.nrm.nginx;

import java.util.Locale;

/** What a {@code log_format} says: its name, how values are escaped, and the format string. */
public final class LogFormatSettings {

    /** How nginx escapes characters in the logged values. */
    public enum Escape {
        /** The default: quotes, backslashes and control characters are written as \xHH. */
        DEFAULT,
        /** For a JSON object: values are escaped for a JSON string. */
        JSON,
        /** Values are written as they are. */
        NONE;

        /** The word nginx uses after {@code escape=}. */
        public String keyword() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Escape of(String keyword) {
            for (Escape e : values()) {
                if (e.keyword().equals(keyword)) {
                    return e;
                }
            }
            return DEFAULT;
        }
    }

    public String name = "";
    public Escape escape = Escape.DEFAULT;
    public String text = "";

    public LogFormatSettings copy() {
        LogFormatSettings c = new LogFormatSettings();
        c.name = name;
        c.escape = escape;
        c.text = text;
        return c;
    }
}
