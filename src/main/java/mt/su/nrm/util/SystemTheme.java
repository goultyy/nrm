package mt.su.nrm.util;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;

import java.util.Optional;

/**
 * Whether Windows is set to dark mode for apps, read from the registry
 * ({@code AppsUseLightTheme}: 0 means dark, 1 means light). Anywhere else, or if the value can't be read, the answer
 * is "unknown" and the app stays light.
 */
public final class SystemTheme {

    private static final String KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";
    private static final String VALUE = "AppsUseLightTheme";

    private SystemTheme() {
    }

    /** True if Windows is in dark mode, false if light, empty if that can't be told. */
    public static Optional<Boolean> isDark() {
        if (!AppDirs.isWindows()) {
            return Optional.empty();
        }
        try {
            return fromRegistryValue(Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, KEY, VALUE));
        } catch (RuntimeException | LinkageError e) {
            // A missing key (older Windows), no permission, or the native library not loading: stay light.
            return Optional.empty();
        }
    }

    /** The meaning of the registry value: 0 is dark, any other number is light. */
    static Optional<Boolean> fromRegistryValue(Integer value) {
        return value == null ? Optional.empty() : Optional.of(value == 0);
    }
}
