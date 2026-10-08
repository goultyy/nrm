package mt.su.nrm.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import mt.su.nrm.nginx.VhostSettings;
import mt.su.nrm.nginx.PendingChange;
import mt.su.nrm.security.SecurityHeaders.Choice;
import mt.su.nrm.ssh.BackupService;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The security headers dialog's rows and the history list's wording. Skipped where JavaFX can't start. */
class SecurityHeadersDialogTest {

    private static boolean toolkitAvailable;

    @BeforeAll
    static void startToolkit() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyRunning) {
            started.countDown();
        } catch (Throwable t) {
            return;
        }
        toolkitAvailable = started.await(15, TimeUnit.SECONDS);
        if (toolkitAvailable) {
            Platform.setImplicitExit(false);
        }
    }

    private static <T> T onFx(Callable<T> work) throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available");
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result.set(work.call());
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(20, TimeUnit.SECONDS)) {
            throw new AssertionError("FX thread did not finish");
        }
        if (failure.get() != null) {
            throw new AssertionError("failed on the FX thread: " + failure.get(), failure.get());
        }
        return result.get();
    }

    @Test
    void onlyTheTickedHeadersAreReturnedWithTheirShownValues() throws Exception {
        List<Choice> chosen = onFx(() -> SecurityHeadersDialog.forTest(new VhostSettings(), "",
                List.of("X-Frame-Options", "Referrer-Policy")).get());
        assertEquals(List.of(new Choice("X-Frame-Options", "SAMEORIGIN"),
                new Choice("Referrer-Policy", "strict-origin-when-cross-origin")), chosen);
        assertEquals(List.of(), onFx(() -> SecurityHeadersDialog.forTest(new VhostSettings(), "", List.of()).get()));
    }

    @Test
    void theHistoryListSaysWhatEachChangeDid() {
        BackupService.Backup b = new BackupService.Backup("20260304-050607-abcd", "/var/backups/nrm/20260304-050607-abcd",
                Instant.now(), List.of(
                new BackupService.Entry(PendingChange.Kind.MODIFY, 0, "/etc/nginx/sites-available/a", List.of()),
                new BackupService.Entry(PendingChange.Kind.MODIFY, 1, "/etc/nginx/sites-available/b", List.of()),
                new BackupService.Entry(PendingChange.Kind.CREATE, 2, "/etc/nginx/sites-available/c", List.of()),
                new BackupService.Entry(PendingChange.Kind.DELETE, 3, "/etc/nginx/sites-available/d", List.of())));
        assertEquals("2 changed, 1 created, 1 deleted: a, b, c, ...", HistoryPanel.summary(b));
    }
}
