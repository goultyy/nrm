package mt.su.nrm.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppDirsTest {

    @Test
    void savedServersFromTheOldFolderAreMovedToTheNewOne(@TempDir Path dir) throws Exception {
        Path legacy = dir.resolve("NginxRemoteManager");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("profiles.nrm"), "data");

        Path result = AppDirs.migrated(dir.resolve("NRM"), legacy);

        assertEquals(dir.resolve("NRM"), result);
        assertEquals("data", Files.readString(result.resolve("profiles.nrm")));
        assertFalse(Files.exists(legacy));
    }

    @Test
    void anExistingNewFolderIsNeverOverwritten(@TempDir Path dir) throws Exception {
        Path legacy = dir.resolve("old");
        Path current = dir.resolve("NRM");
        Files.createDirectories(legacy);
        Files.createDirectories(current);
        Files.writeString(legacy.resolve("profiles.nrm"), "old");
        assertEquals(current, AppDirs.migrated(current, legacy));
        assertTrue(Files.exists(legacy.resolve("profiles.nrm")), "the old folder is left alone");
    }

    @Test
    void nothingHappensWhenThereIsNoOldFolder(@TempDir Path dir) {
        assertEquals(dir.resolve("NRM"), AppDirs.migrated(dir.resolve("NRM"), dir.resolve("missing")));
        assertFalse(Files.exists(dir.resolve("NRM")), "the new folder is created lazily by whoever writes to it");
    }
}
