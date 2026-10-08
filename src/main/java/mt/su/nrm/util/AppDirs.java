package mt.su.nrm.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Local folders used by the app. Everything lives under %APPDATA%\NRM on Windows. */
public final class AppDirs {

    /** System property that overrides the data folder, e.g. -Dnrm.dataDir=D:\portable\nrm. */
    public static final String DATA_DIR_PROPERTY = "nrm.dataDir";

    private static final String WINDOWS_FOLDER = "NRM";
    private static final String UNIX_FOLDER = ".nrm";
    private static final String PROFILE_STORE_FILE = "profiles.nrm";

    private AppDirs() {
    }

    /** The folder used by versions before the app was renamed to NRM. */
    private static final String LEGACY_WINDOWS_FOLDER = "NginxRemoteManager";
    private static final String LEGACY_UNIX_FOLDER = ".nginx-remote-manager";

    public static Path dataDir() {
        String override = System.getProperty(DATA_DIR_PROPERTY);
        if (!Text.isBlank(override)) {
            return Path.of(override);
        }
        String appData = System.getenv("APPDATA");
        if (!Text.isBlank(appData)) {
            return migrated(Path.of(appData, WINDOWS_FOLDER), Path.of(appData, LEGACY_WINDOWS_FOLDER));
        }
        String home = System.getProperty("user.home");
        return migrated(Path.of(home, UNIX_FOLDER), Path.of(home, LEGACY_UNIX_FOLDER));
    }

    /**
     * Saved servers from before the rename live in the old folder. The first run moves it to the
     * new name; if that can't be done the old folder is used as it is, so nothing is ever lost.
     */
    static Path migrated(Path current, Path legacy) {
        if (Files.exists(current) || !Files.isDirectory(legacy)) {
            return current;
        }
        try {
            Files.move(legacy, current);
            return current;
        } catch (IOException | RuntimeException e) {
            return legacy;
        }
    }

    public static Path profileStoreFile() {
        return dataDir().resolve(PROFILE_STORE_FILE);
    }

    /** Folder for the application log. */
    public static Path logDir() {
        return dataDir().resolve("logs");
    }

    public static Path logFile() {
        return logDir().resolve("app.log");
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }
}
