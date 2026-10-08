package mt.su.nrm.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * The chosen theme, kept in a small plain file beside the profile store. It isn't secret, so it stays out of the
 * encrypted store, and a damaged or missing file simply means "follow the system".
 */
public final class ThemeSettings {

    private static final String KEY = "theme";

    private ThemeSettings() {
    }

    public static Path file() {
        return AppDirs.dataDir().resolve("settings.properties");
    }

    public static ThemeMode load(Path file) {
        if (!Files.isRegularFile(file)) {
            return ThemeMode.SYSTEM;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException | IllegalArgumentException e) {
            return ThemeMode.SYSTEM;
        }
        return ThemeMode.parse(props.getProperty(KEY));
    }

    /** Saves the mode, keeping any other settings that are already in the file. */
    public static void save(Path file, ThemeMode mode) throws IOException {
        Properties props = new Properties();
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException | IllegalArgumentException ignored) {
                // A damaged file is replaced rather than blocking the change.
            }
        }
        props.setProperty(KEY, mode.name());
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream out = Files.newOutputStream(file)) {
            props.store(out, "NRM settings");
        }
    }
}
