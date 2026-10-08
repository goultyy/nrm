package mt.su.nrm.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mt.su.nrm.util.AppDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The add-on registry: all off by default, remembered, open to outside providers but not to impostors. */
class FeaturesTest {

    @TempDir
    Path dataDir;

    private String savedDataDir;

    @BeforeEach
    void useATemporaryDataFolder() {
        savedDataDir = System.getProperty(AppDirs.DATA_DIR_PROPERTY);
        System.setProperty(AppDirs.DATA_DIR_PROPERTY, dataDir.toString());
        Features.reload();
    }

    @AfterEach
    void restore() {
        if (savedDataDir == null) {
            System.clearProperty(AppDirs.DATA_DIR_PROPERTY);
        } else {
            System.setProperty(AppDirs.DATA_DIR_PROPERTY, savedDataDir);
        }
        Features.reload();
    }

    @Test
    void theBuiltInFeaturesAreThereAndNothingIsOnByDefault() {
        List<String> ids = Features.all().stream().map(Feature::id).toList();
        assertTrue(ids.contains("status-page"));
        assertTrue(ids.contains("ip-addresses"));
        assertEquals(ids.size(), ids.stream().distinct().count(), "ids must be unique");
        assertTrue(Features.enabled().isEmpty());
    }

    @Test
    void aProvidedFeatureIsFoundButCannotReplaceABuiltInOne() {
        assertTrue(Features.byId("test-addon").isPresent(), "a feature registered with the service loader is offered");
        Feature statusPage = Features.byId("status-page").orElseThrow();
        assertEquals("Status page", statusPage.title(), "the built-in one must win over an impostor with its id");
        assertEquals(1, Features.all().stream().filter(f -> f.id().equals("status-page")).count());
    }

    @Test
    void turningFeaturesOnIsRememberedAndTellsTheListeners() throws IOException {
        List<String> told = new ArrayList<>();
        Features.addListener(() -> told.add("changed"));

        Features.setEnabled(List.of("status-page"));

        assertEquals(List.of("changed"), told);
        assertTrue(Features.isEnabled(Features.byId("status-page").orElseThrow()));
        assertFalse(Features.isEnabled(Features.byId("test-addon").orElseThrow()));
        assertTrue(Files.readString(dataDir.resolve("settings.properties")).contains("features=status-page"));

        Features.reload(); // as after a restart
        assertEquals(List.of("status-page"), Features.enabled().stream().map(Feature::id).toList());
    }

    @Test
    void choosingTheSameFeaturesAgainChangesNothing() {
        List<String> told = new ArrayList<>();
        Features.setEnabled(List.of("test-addon"));
        Features.addListener(() -> told.add("changed"));
        Features.setEnabled(List.of("test-addon"));
        assertTrue(told.isEmpty());
    }

    @Test
    void anUnknownIdInTheSettingsIsIgnoredNotShown() {
        Features.setEnabled(List.of("gone-feature", "test-addon"));
        assertEquals(List.of("test-addon"), Features.enabled().stream().map(Feature::id).toList());
    }
}

