package mt.su.nrm.config;

import mt.su.nrm.model.ServerProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EncryptedProfileStoreTest {

    private Path dir;
    private Path file;

    @BeforeEach
    void setUp() throws IOException {
        dir = Files.createTempDirectory("nrm-store-test");
        file = dir.resolve("sub").resolve("profiles.nrm");
    }

    @AfterEach
    void tearDown() throws IOException {
        TestSupport.deleteRecursively(dir);
    }

    private EncryptedProfileStore store(String passphrase) {
        return new EncryptedProfileStore(file, TestSupport.passphrase(passphrase));
    }

    @Test
    void missingFileLoadsAsEmpty() throws Exception {
        assertEquals(List.of(), store("pw").load());
    }

    @Test
    void savedProfilesLoadBackIdentically() throws Exception {
        List<ServerProfile> profiles = List.of(TestSupport.fullProfile("Web 1"), TestSupport.minimalProfile("Db"));
        store("pw").save(profiles);
        assertEquals(profiles, store("pw").load());
    }

    @Test
    void createsMissingParentFolders() throws Exception {
        store("pw").save(List.of());
        assertTrue(Files.isRegularFile(file));
    }

    @Test
    void secretsAndHostnamesAreNotStoredInPlaintext() throws Exception {
        store("pw").save(List.of(TestSupport.fullProfile("Web 1")));
        byte[] raw = Files.readAllBytes(file);
        for (String secret : List.of("hunter2-s3cret", "sudo-s3cret", "key-passphrase-s3cret",
                "web1.example.internal", "Web 1")) {
            assertFalse(TestSupport.contains(raw, secret.getBytes(StandardCharsets.UTF_8)),
                    "found in plaintext: " + secret);
        }
    }

    @Test
    void everySaveUsesAFreshKeyAndNonce() throws Exception {
        List<ServerProfile> profiles = List.of(TestSupport.fullProfile("Web 1"));
        EncryptedProfileStore store = store("pw");
        store.save(profiles);
        byte[] first = Files.readAllBytes(file);
        store.save(profiles);
        byte[] second = Files.readAllBytes(file);
        assertFalse(Arrays.equals(first, second));
    }

    @Test
    void wrongPassphraseCannotUnlock() throws Exception {
        store("right").save(List.of(TestSupport.minimalProfile("Web")));
        ProfileStoreException e = assertThrows(ProfileStoreException.class, () -> store("wrong").load());
        assertEquals(ProfileStoreException.Kind.CANNOT_UNLOCK, e.kind());
    }

    @Test
    void modifiedCiphertextIsDetected() throws Exception {
        store("pw").save(List.of(TestSupport.minimalProfile("Web")));
        byte[] raw = Files.readAllBytes(file);
        raw[raw.length - 1] ^= 0x01;
        Files.write(file, raw);
        ProfileStoreException e = assertThrows(ProfileStoreException.class, () -> store("pw").load());
        assertEquals(ProfileStoreException.Kind.DAMAGED, e.kind());
    }

    @Test
    void modifiedHeaderIsDetected() throws Exception {
        store("pw").save(List.of(TestSupport.minimalProfile("Web")));
        byte[] raw = Files.readAllBytes(file);
        // Header: magic(4) | format(4) | id length(4) | id | wrapped key length(4) | wrapped key | nonce(12)
        int idLength = ByteBuffer.wrap(raw, 8, 4).getInt();
        int wrappedLengthOffset = 12 + idLength;
        int wrappedLength = ByteBuffer.wrap(raw, wrappedLengthOffset, 4).getInt();
        int nonceOffset = wrappedLengthOffset + 4 + wrappedLength;
        raw[nonceOffset] ^= 0x01;
        Files.write(file, raw);
        ProfileStoreException e = assertThrows(ProfileStoreException.class, () -> store("pw").load());
        assertEquals(ProfileStoreException.Kind.DAMAGED, e.kind());
    }

    @Test
    void truncatedFileIsReportedAsDamaged() throws Exception {
        store("pw").save(List.of(TestSupport.minimalProfile("Web")));
        byte[] raw = Files.readAllBytes(file);
        Files.write(file, Arrays.copyOf(raw, raw.length / 2));
        ProfileStoreException e = assertThrows(ProfileStoreException.class, () -> store("pw").load());
        assertEquals(ProfileStoreException.Kind.DAMAGED, e.kind());
    }

    @Test
    void unrelatedFileIsReportedAsDamaged() throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "this is not a profile store");
        ProfileStoreException e = assertThrows(ProfileStoreException.class, () -> store("pw").load());
        assertEquals(ProfileStoreException.Kind.DAMAGED, e.kind());
    }

    @Test
    void newerFileFormatIsReported() throws Exception {
        store("pw").save(List.of());
        byte[] raw = Files.readAllBytes(file);
        raw[7] = (byte) (EncryptedProfileStore.FILE_FORMAT + 1); // low byte of the format int
        Files.write(file, raw);
        ProfileStoreException e = assertThrows(ProfileStoreException.class, () -> store("pw").load());
        assertEquals(ProfileStoreException.Kind.UNSUPPORTED_VERSION, e.kind());
    }

    @Test
    void differentProtectorIsReported() throws Exception {
        store("pw").save(List.of());
        KeyProtector other = new KeyProtector() {
            @Override
            public String id() {
                return "dpapi";
            }

            @Override
            public byte[] protect(byte[] key) throws GeneralSecurityException {
                throw new GeneralSecurityException("unused");
            }

            @Override
            public byte[] unprotect(byte[] wrappedKey) throws GeneralSecurityException {
                throw new GeneralSecurityException("unused");
            }
        };
        ProfileStoreException e = assertThrows(ProfileStoreException.class,
                () -> new EncryptedProfileStore(file, other).load());
        assertEquals(ProfileStoreException.Kind.PROTECTOR_MISMATCH, e.kind());
    }

    @Test
    void previousVersionIsKeptAsBackup() throws Exception {
        EncryptedProfileStore store = store("pw");
        List<ServerProfile> first = List.of(TestSupport.minimalProfile("First"));
        List<ServerProfile> second = List.of(TestSupport.minimalProfile("Second"));
        store.save(first);
        assertFalse(store.hasBackup());
        store.save(second);
        assertTrue(store.hasBackup());
        assertEquals(second, store.load());
        assertEquals(first, store.loadBackup());
    }

    @Test
    void noTempFileIsLeftBehind() throws Exception {
        store("pw").save(List.of(TestSupport.minimalProfile("Web")));
        assertFalse(Files.exists(file.resolveSibling("profiles.nrm.tmp")));
    }

    @Test
    void quarantineMovesTheFileAsideWithoutDeletingIt() throws Exception {
        EncryptedProfileStore store = store("pw");
        store.save(List.of(TestSupport.minimalProfile("Web")));
        byte[] before = Files.readAllBytes(file);

        Path moved = store.quarantineDamagedFile();
        assertNotNull(moved);
        assertFalse(Files.exists(file));
        assertTrue(moved.getFileName().toString().startsWith("profiles.nrm.damaged-"));
        assertTrue(Arrays.equals(before, Files.readAllBytes(moved)));
    }
}
