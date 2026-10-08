package mt.su.nrm.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FeatureSettingsTest {

    @TempDir
    Path dir;

    @Test
    void nothingIsOnByDefault() {
        assertEquals(Set.of(), FeatureSettings.load(dir.resolve("settings.properties")));
    }

    @Test
    void theChosenFeaturesAreSavedAndReadBackInOrder() throws IOException {
        Path file = dir.resolve("settings.properties");
        FeatureSettings.save(file, List.of("status-page", "client-ip"));
        assertEquals(List.of("status-page", "client-ip"), List.copyOf(FeatureSettings.load(file)));
        FeatureSettings.save(file, List.of());
        assertEquals(Set.of(), FeatureSettings.load(file));
    }

    @Test
    void savingFeaturesKeepsTheThemeAndSavingTheThemeKeepsTheFeatures() throws IOException {
        Path file = dir.resolve("settings.properties");
        ThemeSettings.save(file, ThemeMode.DARK);
        FeatureSettings.save(file, List.of("status-page"));
        assertEquals(ThemeMode.DARK, ThemeSettings.load(file));
        ThemeSettings.save(file, ThemeMode.LIGHT);
        assertEquals(Set.of("status-page"), FeatureSettings.load(file));
        assertTrue(Files.readString(file).contains("theme=LIGHT"));
    }

    @Test
    void blanksAndDuplicatesAndADamagedFileAreHandled() throws IOException {
        Path file = dir.resolve("settings.properties");
        Files.writeString(file, "features= a , ,b,a\n");
        assertEquals(List.of("a", "b"), List.copyOf(FeatureSettings.load(file)));
        Files.writeString(file, "features=\\uZZZZ\n");
        assertEquals(Set.of(), FeatureSettings.load(file));
        FeatureSettings.save(file, List.of("x"));
        assertEquals(Set.of("x"), FeatureSettings.load(file));
    }
}
