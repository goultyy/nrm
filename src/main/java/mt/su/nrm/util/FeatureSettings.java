package mt.su.nrm.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;

/**
 * Which optional features are switched on, kept in the same small settings file as the theme. A missing or damaged
 * file means none are on: add-ons are opt-in.
 */
public final class FeatureSettings {

    private static final String KEY = "features";

    private FeatureSettings() {
    }

    public static Set<String> load(Path file) {
        Properties props = read(file);
        Set<String> ids = new LinkedHashSet<>();
        String value = props.getProperty(KEY, "");
        for (String id : value.split(",")) {
            if (!id.isBlank()) {
                ids.add(id.strip());
            }
        }
        return ids;
    }

    /** Saves the set, keeping every other setting in the file. */
    public static void save(Path file, Collection<String> ids) throws IOException {
        Properties props = read(file);
        props.setProperty(KEY, String.join(",", new LinkedHashSet<>(ids)));
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream out = Files.newOutputStream(file)) {
            props.store(out, "NRM settings");
        }
    }

    private static Properties read(Path file) {
        Properties props = new Properties();
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException | IllegalArgumentException ignored) {
                // A damaged file counts as empty and is replaced on the next save.
            }
        }
        return props;
    }
}
