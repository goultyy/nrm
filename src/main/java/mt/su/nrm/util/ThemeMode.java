package mt.su.nrm.util;

import java.util.Locale;

/** Which colours the app uses: always light, always dark, or whatever Windows is set to. */
public enum ThemeMode {

    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark");

    private final String label;

    ThemeMode(String label) {
        this.label = label;
    }

    /** The name shown in the View menu. */
    public String label() {
        return label;
    }

    /** The mode saved under this name, or {@link #SYSTEM} for a missing or unknown one. */
    public static ThemeMode parse(String text) {
        if (text != null) {
            for (ThemeMode mode : values()) {
                if (mode.name().equals(text.strip().toUpperCase(Locale.ROOT))) {
                    return mode;
                }
            }
        }
        return SYSTEM;
    }
}
