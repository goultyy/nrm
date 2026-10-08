package mt.su.nrm.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mt.su.nrm.ssh.AuthenticationFailedException;
import mt.su.nrm.ssh.ErrorMessages;
import mt.su.nrm.ssh.HostKeyChangedException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PolishTest {

    // ------------------------------------------------------------------------------- error messages

    @Test
    void connectionErrorsAreWordedForAPerson() {
        String host = "web1.example.com";
        assertTrue(ErrorMessages.describe(new UnknownHostException("web1"), host, 22).contains("Could not find \"web1.example.com\""));
        assertTrue(ErrorMessages.describe(new ConnectException("Connection refused: connect"), host, 2222)
                .contains("refused the connection"));
        assertTrue(ErrorMessages.describe(new ConnectException("Connection refused: connect"), host, 2222)
                .contains("web1.example.com:2222"));
        assertTrue(ErrorMessages.describe(new SocketTimeoutException("Connect timed out"), host, 22).contains("did not answer in time"));
        assertTrue(ErrorMessages.describe(new ConnectException("Connection timed out: getsockopt"), host, 22)
                .contains("did not answer in time"));
        assertTrue(ErrorMessages.describe(new AuthenticationFailedException("x", null), host, 22)
                .contains("username, password or private key was not accepted"));
        assertTrue(ErrorMessages.describe(new SocketException("Connection reset"), host, 22).contains("was lost"));
        assertTrue(ErrorMessages.describe(new IOException("Broken pipe"), host, 22).contains("was lost"));
        assertTrue(ErrorMessages.describe(new FileNotFoundException("C:\\keys\\id_rsa"), host, 22).contains("could not be found"));
        assertTrue(ErrorMessages.describe(new IOException("Algorithm negotiation fail"), host, 22).contains("no SSH algorithms in common"));
    }

    @Test
    void theRealCauseIsFoundInsideWrappers() {
        Throwable wrapped = new java.util.concurrent.CompletionException(new RuntimeException("outer",
                new UnknownHostException("h")));
        assertTrue(ErrorMessages.describe(wrapped, "h", 22).contains("Could not find"));
    }

    @Test
    void aChangedHostKeyKeepsItsFullWarning() {
        HostKeyChangedException e = new HostKeyChangedException("h", "SHA256:a", "SHA256:b");
        assertEquals(e.getMessage(), ErrorMessages.describe(e, "h", 22));
    }

    @Test
    void unknownErrorsFallBackToTheirOwnMessage() {
        assertEquals("something odd", ErrorMessages.describe(new IllegalStateException("something odd"), "h", 22));
        assertEquals("IllegalStateException", ErrorMessages.describe(new IllegalStateException(), "h", 22));
    }

    // ------------------------------------------------------------------------------- application log

    @Test
    void theLogRecordsLevelsAndStackTracesAndRotates(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("logs").resolve("app.log");
        AppLog.useFile(log, Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC));
        try {
            AppLog.info("hello");
            AppLog.error("it broke", new IllegalStateException("boom"));
            String text = Files.readString(log);
            assertTrue(text.contains("2026-01-02T03:04:05Z [INFO] hello"));
            assertTrue(text.contains("[ERROR] it broke"));
            assertTrue(text.contains("java.lang.IllegalStateException: boom"));

            Files.writeString(log, "x".repeat(1_100_000));
            AppLog.info("after rotation");
            assertTrue(Files.exists(log.resolveSibling("app.log.1")));
            assertTrue(Files.readString(log).contains("after rotation"));
            assertTrue(Files.size(log) < 1000);
        } finally {
            AppLog.useFile(null, Clock.systemUTC());
        }
    }

    @Test
    void loggingNeverThrowsEvenIfTheFileCannotBeWritten(@TempDir Path dir) throws Exception {
        Path blocker = dir.resolve("blocker");
        Files.writeString(blocker, "a file where a folder is needed");
        AppLog.useFile(blocker.resolve("app.log"), Clock.systemUTC());
        try {
            AppLog.error("cannot be written", new RuntimeException("x"));
        } finally {
            AppLog.useFile(null, Clock.systemUTC());
        }
    }

    // ------------------------------------------------------------------------------- window position

    private static final List<WindowBounds.Area> ONE_SCREEN = List.of(new WindowBounds.Area(0, 0, 1920, 1040));

    @Test
    void windowBoundsSurviveASaveAndLoad(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("window.properties");
        new WindowBounds(100.5, 50, 1100, 720, true).save(file);
        Optional<WindowBounds> loaded = WindowBounds.load(file, ONE_SCREEN);
        assertEquals(new WindowBounds(100.5, 50, 1100, 720, true), loaded.orElseThrow());
    }

    @Test
    void aWindowOffEveryScreenIsNotRestored(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("window.properties");
        new WindowBounds(3000, 100, 1100, 720, false).save(file);   // was on a monitor that is gone
        assertTrue(WindowBounds.load(file, ONE_SCREEN).isEmpty());
        assertTrue(WindowBounds.load(file, List.of(new WindowBounds.Area(2560, 0, 1920, 1080))).isPresent());
    }

    @Test
    void aWindowMostlyOffScreenButStillReachableIsKept() {
        assertTrue(new WindowBounds(1800, 300, 1100, 720, false).isVisibleOn(ONE_SCREEN));
        assertFalse(new WindowBounds(1900, 300, 1100, 720, false).isVisibleOn(ONE_SCREEN));
    }

    @Test
    void damagedOrImplausibleFilesAreIgnored(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("window.properties");
        assertTrue(WindowBounds.load(file, ONE_SCREEN).isEmpty());
        Files.writeString(file, "x=abc\nwidth=\n");
        assertTrue(WindowBounds.load(file, ONE_SCREEN).isEmpty());
        new WindowBounds(0, 0, 10, 10, false).save(file);
        assertTrue(WindowBounds.load(file, ONE_SCREEN).isEmpty(), "too small to be a real window");
    }

    // ------------------------------------------------------------------------------- app info

    @Test
    void theVersionComesFromTheBuild() {
        String v = AppInfo.version();
        assertFalse(v.isBlank());
        assertFalse(v.startsWith("${"), v);
        assertEquals("NRM", AppInfo.NAME);
    }
}
