package mt.su.nrm.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ThemeSettingsTest {

    @TempDir
    Path dir;

    @Test
    void aMissingFileMeansFollowTheSystem() {
        assertEquals(ThemeMode.SYSTEM, ThemeSettings.load(dir.resolve("settings.properties")));
    }

    @Test
    void eachChoiceIsSavedAndReadBack() throws IOException {
        Path file = dir.resolve("settings.properties");
        for (ThemeMode mode : ThemeMode.values()) {
            ThemeSettings.save(file, mode);
            assertEquals(mode, ThemeSettings.load(file));
        }
    }

    @Test
    void anUnknownOrDamagedValueMeansFollowTheSystem() throws IOException {
        Path file = dir.resolve("settings.properties");
        Files.writeString(file, "theme=sepia\n");
        assertEquals(ThemeMode.SYSTEM, ThemeSettings.load(file));
        Files.writeString(file, "theme=\\uZZZZ\n");
        assertEquals(ThemeMode.SYSTEM, ThemeSettings.load(file));
    }

    @Test
    void savingKeepsOtherSettingsAndCreatesTheFolder() throws IOException {
        Path file = dir.resolve("sub").resolve("settings.properties");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "other=kept\n");
        ThemeSettings.save(file, ThemeMode.DARK);
        assertTrue(Files.readString(file).contains("other=kept"));
        assertEquals(ThemeMode.DARK, ThemeSettings.load(file));

        Path deeper = dir.resolve("a").resolve("b").resolve("settings.properties");
        ThemeSettings.save(deeper, ThemeMode.LIGHT);
        assertEquals(ThemeMode.LIGHT, ThemeSettings.load(deeper));
    }

    @Test
    void savingReplacesAFileThatCannotBeRead() throws IOException {
        Path file = dir.resolve("settings.properties");
        Files.writeString(file, "theme=\\uZZZZ\n");
        ThemeSettings.save(file, ThemeMode.DARK);
        assertEquals(ThemeMode.DARK, ThemeSettings.load(file));
    }

    @Test
    void modeNamesAreReadForgivingly() {
        assertEquals(ThemeMode.DARK, ThemeMode.parse(" dark "));
        assertEquals(ThemeMode.LIGHT, ThemeMode.parse("Light"));
        assertEquals(ThemeMode.SYSTEM, ThemeMode.parse(null));
        assertEquals(ThemeMode.SYSTEM, ThemeMode.parse(""));
    }

    @Test
    void theRegistryValueZeroMeansDarkAndAnythingElseLight() {
        assertEquals(Optional.of(true), SystemTheme.fromRegistryValue(0));
        assertEquals(Optional.of(false), SystemTheme.fromRegistryValue(1));
        assertEquals(Optional.empty(), SystemTheme.fromRegistryValue(null));
    }

    @Test
    void readingTheRealRegistryNeverThrows() {
        // On Windows this is the machine's setting; elsewhere it is simply unknown. Either way it must not fail.
        SystemTheme.isDark();
    }
}
