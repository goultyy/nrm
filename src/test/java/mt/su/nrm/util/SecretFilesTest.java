package mt.su.nrm.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretFilesTest {

    @TempDir
    Path dir;

    @Test
    void theContentIsWrittenAndAccessIsLimitedToTheOwner() throws IOException {
        Path file = dir.resolve("site.key");
        boolean restricted = SecretFiles.write(file, "KEY\n");
        assertEquals("KEY\n", Files.readString(file));
        assertTrue(restricted, "this file system supports owner-only access");

        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (acl != null) {
            List<AclEntry> entries = acl.getAcl();
            assertEquals(1, entries.size(), "one entry only: " + entries);
            assertEquals(AclEntryType.ALLOW, entries.get(0).type());
            assertEquals(Files.getOwner(file), entries.get(0).principal());
        } else {
            PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
            assertEquals("rw-------", PosixFilePermissions.toString(posix.readAttributes().permissions()));
        }
    }

    @Test
    void anExistingFileIsReplacedNotAppendedTo() throws IOException {
        Path file = dir.resolve("site.key");
        Files.writeString(file, "OLD CONTENT THAT IS LONGER THAN THE NEW ONE");
        SecretFiles.write(file, "NEW");
        assertEquals("NEW", Files.readString(file));
    }

    @Test
    void theOwnerCanStillReadAndRemoveTheirFile() throws IOException {
        Path file = dir.resolve("site.key");
        SecretFiles.write(file, "KEY");
        assertTrue(Files.isReadable(file));
        Files.delete(file);
        assertTrue(Files.notExists(file));
    }
}
