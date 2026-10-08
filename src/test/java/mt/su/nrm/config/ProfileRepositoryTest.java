package mt.su.nrm.config;

import mt.su.nrm.model.ConfigLayout;
import mt.su.nrm.model.ServerProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileRepositoryTest {

    private static final Instant START = Instant.parse("2026-09-26T09:00:00Z");

    private Path dir;
    private Path file;
    private TestSupport.MutableClock clock;

    @BeforeEach
    void setUp() throws IOException {
        dir = Files.createTempDirectory("nrm-repo-test");
        file = dir.resolve("profiles.nrm");
        clock = new TestSupport.MutableClock(START);
    }

    @AfterEach
    void tearDown() throws IOException {
        TestSupport.deleteRecursively(dir);
    }

    private EncryptedProfileStore store() {
        return new EncryptedProfileStore(file, TestSupport.passphrase("pw"));
    }

    private ProfileRepository open() throws ProfileStoreException {
        return ProfileRepository.open(store(), clock);
    }

    @Test
    void addAssignsIdAndTimestamps() throws Exception {
        ServerProfile saved = open().add(TestSupport.minimalProfile("Web"));
        assertNotNull(saved.getId());
        assertEquals(START, saved.getCreatedAt());
        assertEquals(START, saved.getUpdatedAt());
    }

    @Test
    void addTrimsNameHostAndUser() throws Exception {
        ServerProfile draft = TestSupport.minimalProfile("Web");
        draft.setName("  Web  ");
        draft.setHost(" web.example.com ");
        draft.setUsername(" admin ");
        ServerProfile saved = open().add(draft);
        assertEquals("Web", saved.getName());
        assertEquals("web.example.com", saved.getHost());
        assertEquals("admin", saved.getUsername());
    }

    @Test
    void invalidProfileIsRejectedWithAllErrors() throws Exception {
        ProfileRepository repo = open();
        ProfileValidationException e = assertThrows(ProfileValidationException.class,
                () -> repo.add(new ServerProfile()));
        assertTrue(e.errors().contains("Name is required."));
        assertTrue(e.errors().contains("Host is required."));
        assertTrue(repo.list().isEmpty());
        assertFalse(Files.exists(file));
    }

    @Test
    void namesMustBeUniqueIgnoringCase() throws Exception {
        ProfileRepository repo = open();
        repo.add(TestSupport.minimalProfile("Web"));
        ProfileValidationException e = assertThrows(ProfileValidationException.class,
                () -> repo.add(TestSupport.minimalProfile("WEB")));
        assertTrue(e.errors().contains("A server named \"WEB\" already exists."));
    }

    @Test
    void updateKeepsCreatedAtAndBumpsUpdatedAt() throws Exception {
        ProfileRepository repo = open();
        ServerProfile saved = repo.add(TestSupport.minimalProfile("Web"));
        clock.advanceSeconds(60);

        saved.setPort(2222);
        saved.setDetectedLayout(ConfigLayout.SITES_AVAILABLE);
        ServerProfile updated = repo.update(saved);

        assertEquals(START, updated.getCreatedAt());
        assertEquals(START.plusSeconds(60), updated.getUpdatedAt());
        assertEquals(2222, repo.find(saved.getId()).orElseThrow().getPort());
    }

    @Test
    void renamingToOwnNameInDifferentCaseIsAllowed() throws Exception {
        ProfileRepository repo = open();
        ServerProfile saved = repo.add(TestSupport.minimalProfile("Web"));
        saved.setName("WEB");
        assertEquals("WEB", repo.update(saved).getName());
    }

    @Test
    void updatingUnknownProfileFails() throws Exception {
        ServerProfile stranger = TestSupport.minimalProfile("Ghost");
        stranger.setId(UUID.randomUUID());
        ProfileRepository repo = open();
        assertThrows(NoSuchElementException.class, () -> repo.update(stranger));
    }

    @Test
    void deleteRemovesProfile() throws Exception {
        ProfileRepository repo = open();
        ServerProfile saved = repo.add(TestSupport.minimalProfile("Web"));
        assertTrue(repo.delete(saved.getId()));
        assertFalse(repo.delete(saved.getId()));
        assertTrue(repo.list().isEmpty());
    }

    @Test
    void changesSurviveReopening() throws Exception {
        ProfileRepository repo = open();
        repo.add(TestSupport.minimalProfile("Web"));
        ServerProfile db = repo.add(TestSupport.minimalProfile("Db"));
        repo.delete(db.getId());
        repo.add(TestSupport.minimalProfile("Cache"));

        List<String> names = open().list().stream().map(ServerProfile::getName).toList();
        assertEquals(List.of("Cache", "Web"), names);
    }

    @Test
    void listIsSortedByNameIgnoringCase() throws Exception {
        ProfileRepository repo = open();
        repo.add(TestSupport.minimalProfile("beta"));
        repo.add(TestSupport.minimalProfile("Alpha"));
        repo.add(TestSupport.minimalProfile("Gamma"));
        assertEquals(List.of("Alpha", "beta", "Gamma"),
                repo.list().stream().map(ServerProfile::getName).toList());
    }

    @Test
    void returnedProfilesAreCopies() throws Exception {
        ProfileRepository repo = open();
        ServerProfile saved = repo.add(TestSupport.minimalProfile("Web"));
        saved.setHost("changed.example.com");
        repo.list().get(0).getPaths().setNginxConfDir("/changed");

        ServerProfile stored = repo.find(saved.getId()).orElseThrow();
        assertEquals("web.example.com", stored.getHost());
        assertEquals("/etc/nginx", stored.getPaths().getNginxConfDir());
    }

    @Test
    void failedSaveLeavesEverythingUnchanged() throws Exception {
        ProfileRepository repo = ProfileRepository.open(
                new EncryptedProfileStore(file, TestSupport.failing()), clock);
        assertThrows(ProfileStoreException.class, () -> repo.add(TestSupport.minimalProfile("Web")));
        assertTrue(repo.list().isEmpty());
        assertFalse(Files.exists(file));
    }

    @Test
    void recoverFromBackupRestoresPreviousVersion() throws Exception {
        ProfileRepository repo = open();
        repo.add(TestSupport.minimalProfile("Web"));
        repo.add(TestSupport.minimalProfile("Db"));
        Files.write(file, new byte[] {1, 2, 3}); // damage the current file

        ProfileStoreException e = assertThrows(ProfileStoreException.class, this::open);
        assertEquals(ProfileStoreException.Kind.DAMAGED, e.kind());

        ProfileRepository recovered = ProfileRepository.recoverFromBackup(store(), clock);
        assertEquals(List.of("Web"), recovered.list().stream().map(ServerProfile::getName).toList());
        assertEquals(1, open().list().size());
        try (var files = Files.list(dir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("profiles.nrm.damaged-")));
        }
    }

    @Test
    void startFreshKeepsTheUnreadableFile() throws Exception {
        open().add(TestSupport.minimalProfile("Web"));
        ProfileRepository fresh = ProfileRepository.startFresh(
                new EncryptedProfileStore(file, TestSupport.passphrase("forgotten")), clock);
        assertTrue(fresh.list().isEmpty());
        assertFalse(Files.exists(file));
        try (var files = Files.list(dir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("profiles.nrm.damaged-")));
        }
    }
}
