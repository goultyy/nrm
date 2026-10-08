package mt.su.nrm.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mt.su.nrm.model.PrivilegeMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SftpServiceTest {

    private final SshSessionTest.FakeTransport transport = new SshSessionTest.FakeTransport();
    private final SshSession session = new SshSession(transport, new CommandLog(), PrivilegeMode.NONE, null, "u@h");

    private static final SftpService.Listener QUIET = new SftpService.Listener() {
        @Override
        public void item(String description) {
        }

        @Override
        public void bytes(long delta) {
        }

        @Override
        public boolean cancelled() {
            return false;
        }
    };

    @Test
    void joinAndParent() {
        assertEquals("/a/b", SftpService.join("/a", "b"));
        assertEquals("/b", SftpService.join("/", "b"));
        assertEquals("/a", SftpService.parent("/a/b"));
        assertEquals("/", SftpService.parent("/a"));
        assertEquals("/", SftpService.parent("/"));
        assertEquals("/a", SftpService.parent("/a/b/"));
    }

    @Test
    void uploadingAFolderCreatesDirectoriesAndSendsEveryFile(@TempDir Path dir) throws IOException {
        Path site = Files.createDirectories(dir.resolve("site").resolve("css"));
        Files.writeString(dir.resolve("site").resolve("index.html"), "x");
        Files.writeString(site.resolve("a.css"), "y");

        SftpService.upload(session, dir.resolve("site"), "/var/www", QUIET);

        assertEquals(List.of("mkdir /var/www/site", "mkdir /var/www/site/css", "put /var/www/site/css/a.css",
                "put /var/www/site/index.html"), transport.sftpOps);
    }

    @Test
    void uploadingIntoAnExistingFolderMergesInsteadOfFailing(@TempDir Path dir) throws IOException {
        Files.writeString(Files.createDirectories(dir.resolve("site")).resolve("a"), "x");
        transport.failNext = true; // the mkdir fails because the folder exists
        SftpService.upload(session, dir.resolve("site"), "/var/www", QUIET);
        assertTrue(transport.sftpOps.contains("put /var/www/site/a"));
    }

    @Test
    void downloadingAFolderRecreatesItLocally(@TempDir Path dir) throws IOException {
        transport.listing.put("/srv/site", List.of(new SftpEntry("sub", true, 0, 0, "drwxr-xr-x"),
                new SftpEntry("a.txt", false, 5, 0, "-rw-r--r--")));
        transport.listing.put("/srv/site/sub", List.of(new SftpEntry("b.txt", false, 5, 0, "-rw-r--r--")));

        SftpService.download(session, "/srv/site", "site", true, dir, QUIET);

        assertTrue(Files.isRegularFile(dir.resolve("site").resolve("a.txt")));
        assertTrue(Files.isRegularFile(dir.resolve("site").resolve("sub").resolve("b.txt")));
    }

    @Test
    void namesFromTheServerCannotEscapeTheDownloadFolder(@TempDir Path dir) {
        for (String evil : List.of("..", "../x", "a/b", "a\\b", "C:evil", "", ".")) {
            assertThrows(IOException.class, () -> SftpService.safeChild(dir, evil), evil);
        }
        transport.listing.put("/srv/site", List.of(new SftpEntry("..", false, 1, 0, "-rw-r--r--")));
        assertThrows(IOException.class, () -> SftpService.download(session, "/srv/site", "site", true, dir, QUIET));
    }

    @Test
    void deletingAFolderRemovesContentsBeforeTheFolder() throws IOException {
        transport.listing.put("/srv/old", List.of(new SftpEntry("f", false, 1, 0, "-rw-r--r--"),
                new SftpEntry("d", true, 0, 0, "drwxr-xr-x")));

        SftpService.delete(session, "/srv/old", true, QUIET);

        assertEquals(List.of("ls /srv/old", "rm /srv/old/f", "ls /srv/old/d", "rmdir /srv/old/d", "rmdir /srv/old"),
                transport.sftpOps);
    }

    @Test
    void cancellingStopsBeforeTheNextFile(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("a"), "x");
        SftpService.Listener cancelled = new SftpService.Listener() {
            public void item(String d) { }
            public void bytes(long n) { }
            public boolean cancelled() { return true; }
        };
        assertThrows(SftpService.CancelledException.class,
                () -> SftpService.upload(session, dir.resolve("a"), "/x", cancelled));
        assertTrue(transport.sftpOps.isEmpty());
    }
}
