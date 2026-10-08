package mt.su.nrm.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NetworkSettingsTest {

    @TempDir
    Path dir;

    @Test
    void withNothingSavedTheDefaultsApplyAndNothingIsConfirmed() {
        Path file = dir.resolve("settings.properties");
        assertEquals(NetworkSettings.DEFAULT_SERVICES, NetworkSettings.services(file));
        assertFalse(NetworkSettings.confirmed(file));
        assertTrue(NetworkSettings.DEFAULT_SERVICES.get(0).contains("ipify"));
    }

    @Test
    void aChosenListIsKeptInOrderAndTheDefaultListIsNotStored() throws IOException {
        Path file = dir.resolve("settings.properties");
        NetworkSettings.saveServices(file, List.of("https://ifconfig.me/ip", "https://api.ipify.org", "https://ifconfig.me/ip"));
        assertEquals(List.of("https://ifconfig.me/ip", "https://api.ipify.org"), NetworkSettings.services(file), "in order, no repeats");

        NetworkSettings.saveServices(file, NetworkSettings.DEFAULT_SERVICES);
        assertFalse(Files.readString(file).contains("echo.services"), "back to the defaults means nothing stored");
        assertEquals(NetworkSettings.DEFAULT_SERVICES, NetworkSettings.services(file));
    }

    @Test
    void onlyPlainHttpsAddressesAreAccepted() {
        for (String ok : List.of("https://api.ipify.org", "https://icanhazip.com", "https://ifconfig.me/ip",
                "https://example.org:8443/ip", "https://1.1.1.1/cdn-cgi/trace")) {
            assertTrue(NetworkSettings.validService(ok), ok);
        }
        for (String bad : List.of("http://api.ipify.org", "ftp://x.org", "https://", "https:///x", "https://x.org/a b",
                "https://x.org/;id", "https://x.org/?q=1", "https://x.org/$(id)", "https://x.org/'", "https://x.org/../a",
                "https://-x.org", "", "api.ipify.org", "https://user@x.org")) {
            assertFalse(NetworkSettings.validService(bad), "'" + bad + "'");
        }
        assertFalse(NetworkSettings.validService(null));
    }

    @Test
    void anUnusableListIsRefusedWithReasonsAndNeverSaved() {
        Path file = dir.resolve("settings.properties");
        assertThrows(IOException.class, () -> NetworkSettings.saveServices(file, List.of()));
        assertThrows(IOException.class, () -> NetworkSettings.saveServices(file, List.of("http://insecure.example")));
        assertFalse(Files.exists(file));
        assertEquals(1, NetworkSettings.problems(List.of("nonsense")).size());
        assertTrue(NetworkSettings.problems(List.of()).get(0).contains("at least one"));
        assertTrue(NetworkSettings.problems(java.util.Collections.nCopies(9, "https://a.org")).get(0).contains("or fewer"));
    }

    @Test
    void aHandEditedFileWithABadAddressFallsBackToTheDefaults() throws IOException {
        Path file = dir.resolve("settings.properties");
        Files.writeString(file, "echo.services=https://ok.example|file\\:///etc/passwd\n");
        assertEquals(NetworkSettings.DEFAULT_SERVICES, NetworkSettings.services(file));
        Files.writeString(file, "this is not = a valid \\u00zz properties file");
        assertEquals(NetworkSettings.DEFAULT_SERVICES, NetworkSettings.services(file));
    }

    @Test
    void confirmationAndTheOtherSettingsLiveSideBySide() throws IOException {
        Path file = dir.resolve("settings.properties");
        ThemeSettings.save(file, ThemeMode.DARK);
        NetworkSettings.setConfirmed(file, true);
        NetworkSettings.saveServices(file, List.of("https://ifconfig.me/ip"));
        assertTrue(NetworkSettings.confirmed(file));
        assertEquals(ThemeMode.DARK, ThemeSettings.load(file), "saving these must not lose the theme");
        ThemeSettings.save(file, ThemeMode.LIGHT);
        assertEquals(List.of("https://ifconfig.me/ip"), NetworkSettings.services(file), "nor must saving the theme lose these");
        assertTrue(NetworkSettings.confirmed(file));
    }
}
