package mt.su.nrm.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class PhpServiceTest {

    @Test
    void parsesADebianServerWithPhpRunning() {
        PhpService.Status s = PhpService.parse("NRM-BIN /usr/sbin/php-fpm8.2\n"
                + "NRM-VERSION PHP 8.2.7 (fpm-fcgi) (built: Jun 9 2023)\n"
                + "NRM-SOCK /run/php/php8.2-fpm.sock\nNRM-SOCK /run/php/php-fpm.sock\n"
                + "NRM-RUNNING php8.2-fpm.service\nNRM-SNIPPET snippets/fastcgi-php.conf\nNRM-PARAMS yes\nNRM-PM apt\n");
        assertTrue(s.installed());
        assertTrue(s.running());
        assertTrue(s.ready());
        assertEquals("unix:/run/php/php8.2-fpm.sock", s.preferredEndpoint());
        assertEquals("snippets/fastcgi-php.conf", s.snippet());
        assertTrue(s.hasFastcgiParams());
        assertEquals("apt", s.packageManager());
        assertTrue(s.version().startsWith("PHP 8.2"));
    }

    @Test
    void anEmptyServerIsNotReadyAndUnrelatedLinesAreIgnored() {
        PhpService.Status s = PhpService.parse("motd banner\nNRM-PM dnf\nNRM-TCP 127.0.0.1:9000\n");
        assertFalse(s.installed());
        assertFalse(s.ready());
        assertEquals(List.of("127.0.0.1:9000"), s.endpoints());
        assertEquals("dnf", s.packageManager());
    }

    @Test
    void installScriptsUseOnlyFixedPackagesAndEndWithTheMarker() throws Exception {
        String apt = PhpService.installScript("apt", "abc");
        assertTrue(apt.contains("apt-get install -y php-fpm"));
        assertTrue(apt.contains("systemctl enable --now"));
        assertTrue(apt.contains("@@NRM-abc DONE"));
        assertTrue(PhpService.installScript("dnf", "abc").contains("dnf install -y php-fpm"));
        assertTrue(PhpService.installScript("yum", "abc").contains("yum install -y php-fpm"));
    }

    @Test
    void anUnknownPackageManagerIsRefusedWithoutRunningAnything() {
        assertThrows(IOException.class, () -> PhpService.installScript("", "abc"));
        assertThrows(IOException.class, () -> PhpService.installScript("apt; rm -rf /", "abc"));
    }

    @Test
    void detectionIsReadOnly() {
        String script = PhpService.detectScript("/etc/nginx");
        assertFalse(script.contains("install"));
        assertFalse(script.contains("sudo"));
        assertTrue(script.contains("'/etc/nginx/snippets/fastcgi-php.conf'"));
    }
}
