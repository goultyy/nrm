package mt.su.nrm.ssh;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Names typed for a new file or folder must be one plain name: never a path that could leave the folder. */
class FileNameChecksTest {

    @Test
    void ordinaryNamesAreAccepted() {
        for (String ok : new String[] {"index.html", "notes.txt", ".htaccess", "my file.conf", "a", "x".repeat(255)}) {
            assertNull(SftpService.fileNameProblem(ok), ok);
        }
    }

    @Test
    void pathsAndSpecialNamesAreRefused() {
        for (String bad : new String[] {"", "   ", ".", "..", "a/b", "/etc/passwd", "..\\x", "../x", "bad\nname",
                "x".repeat(256)}) {
            assertNotNull(SftpService.fileNameProblem(bad), "'" + bad + "' should be refused");
        }
    }

    @Test
    void theReasonForAPathSaysToOpenTheFolderFirst() {
        assertTrue(SftpService.fileNameProblem("sub/new.txt").contains("open that folder first"));
    }
}
