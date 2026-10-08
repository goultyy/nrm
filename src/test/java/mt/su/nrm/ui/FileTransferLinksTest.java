package mt.su.nrm.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class FileTransferLinksTest {

    @Test
    void aFolderIsUsedAsTyped() {
        assertEquals("/var/www/example.com", FileTransferLinks.directoryOf("/var/www/example.com", false));
        assertEquals("/var/www/example.com", FileTransferLinks.directoryOf("  /var/www/example.com/ ", false));
        assertEquals("/var/www", FileTransferLinks.directoryOf("\"/var/www\"", false));
        assertEquals("/", FileTransferLinks.directoryOf("/", false));
    }

    @Test
    void aFileOpensTheFolderItIsIn() {
        assertEquals("/etc/letsencrypt/live/a.com",
                FileTransferLinks.directoryOf("/etc/letsencrypt/live/a.com/fullchain.pem", true));
        assertEquals("/", FileTransferLinks.directoryOf("/cert.pem", true));
    }

    @Test
    void nginxVariablesCutThePathAtTheFirstOne() {
        assertEquals("/var/www", FileTransferLinks.directoryOf("/var/www/$host/html", false));
        assertEquals("/etc/ssl", FileTransferLinks.directoryOf("/etc/ssl/$host.pem", true));
        assertEquals("/", FileTransferLinks.directoryOf("/$uri", false));
    }

    @Test
    void relativeOrEmptyValuesHaveNoFolder() {
        assertNull(FileTransferLinks.directoryOf("html", false));
        assertNull(FileTransferLinks.directoryOf("", false));
        assertNull(FileTransferLinks.directoryOf(null, false));
    }
}
