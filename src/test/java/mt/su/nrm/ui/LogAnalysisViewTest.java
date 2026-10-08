package mt.su.nrm.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mt.su.nrm.logs.LogParser;
import mt.su.nrm.logs.LogStats;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The analysis page shows what the numbers say, and says so plainly when there is nothing. Skipped without JavaFX. */
class LogAnalysisViewTest {

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

    private static String line(String ip, String path, int status, int second) {
        return ip + " - - [07/Oct/2026:22:15:" + String.format("%02d", second) + " +0000] \"GET " + path + " HTTP/1.1\" "
                + status + " 500 \"-\" \"Firefox\" rt=0.25";
    }

    @Test
    void theNumbersReachTheTablesAndTheChart() throws Exception {
        String log = String.join("\n", line("1.1.1.1", "/a", 200, 1), line("1.1.1.1", "/a?x=1", 200, 5),
                line("2.2.2.2", "/b", 500, 9), "garbage");
        Object[] result = onFx(() -> {
            LogAnalysisView v = new LogAnalysisView(text -> { });
            v.show(LogStats.ofLines(log, LogParser.auto(), 10, 4));
            return new Object[]{v.rowsOf("pages"), v.rowsOf("failing"), v.rowsOf("visitors"), v.rowsOf("statuses"),
                    v.rowsOf("slowest"), v.summaryText(), v.messageText(), v.chartBuckets(), v.showingResults()};
        });
        assertEquals(2, result[0], "/a and /b");
        assertEquals(1, result[1]);
        assertEquals(2, result[2]);
        assertEquals(2, result[3], "200 and 500");
        assertEquals(3, result[4]);
        assertTrue(result[5].toString().contains("3 requests") && result[5].toString().contains("33.3% failed"), result[5].toString());
        assertTrue(result[5].toString().contains("Average 250 ms"), result[5].toString());
        assertTrue(result[6].toString().contains("1 line(s) did not match"), result[6].toString());
        assertEquals(4, result[7]);
        assertEquals(true, result[8]);
    }

    @Test
    void anEmptyOrUnreadableLogGetsAnExplanationInsteadOfEmptyCharts() throws Exception {
        String[] messages = onFx(() -> {
            LogAnalysisView v = new LogAnalysisView(text -> { });
            v.show(LogStats.ofLines("", LogParser.auto(), 10, 4));
            String empty = v.messageText() + "|" + v.showingResults();
            v.show(LogStats.ofLines("nonsense\nmore nonsense", LogParser.auto(), 10, 4));
            return new String[]{empty, v.messageText() + "|" + v.showingResults()};
        });
        assertEquals("There are no lines to analyse.|false", messages[0]);
        assertTrue(messages[1].startsWith("None of the 2 lines could be read") && messages[1].endsWith("|false"), messages[1]);
        assertFalse(messages[1].isBlank());
    }

    @Test
    void sizesAndStatusNamesAreReadable() {
        assertEquals("512 B", LogAnalysisView.size(512));
        assertEquals("1.5 KB", LogAnalysisView.size(1536));
        assertEquals("120 MB", LogAnalysisView.size(120L * 1024 * 1024));
        assertEquals("Not found", LogAnalysisView.meaning(404));
        assertEquals("", LogAnalysisView.meaning(418));
    }
}
