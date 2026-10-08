package mt.su.nrm.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mt.su.nrm.logformat.LogFields;
import mt.su.nrm.logformat.LogFormatDesign;
import mt.su.nrm.logformat.LogFormatDesign.Style;
import mt.su.nrm.nginx.LogFormatSettings;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The log format builder's behaviour (not its looks). Skipped where JavaFX can't start. */
class LogFormatBuilderTest {

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

    private static LogFormatBuilder builder(LogFormatDesign design, AtomicInteger changes) {
        return new LogFormatBuilder(design, () -> "fmt", changes::incrementAndGet);
    }

    @Test
    void fieldsAreAddedWithASpaceBetweenAndTheFormatFollows() throws Exception {
        String[] result = onFx(() -> {
            AtomicInteger changes = new AtomicInteger();
            LogFormatBuilder b = builder(LogFormatDesign.blank(), changes);
            b.addVariable("$remote_addr");
            b.addVariable("$status");
            return new String[]{b.formatString(), b.sampleLine(), String.valueOf(changes.get()),
                    String.join("|", b.rowTexts())};
        });
        assertEquals("$remote_addr $status", result[0]);
        assertEquals("203.0.113.7 200", result[1]);
        assertEquals("2", result[2], "the wizard is told after each change, so it re-checks its page");
        assertEquals("$remote_addr|‹ ›|$status", result[3]);
    }

    @Test
    void quotingWrapsTheNextFieldInQuoteMarks() throws Exception {
        String text = onFx(() -> {
            LogFormatBuilder b = builder(LogFormatDesign.blank(), new AtomicInteger());
            b.addVariable("$remote_addr");
            b.setQuoteFields(true);
            b.addVariable("$request");
            return b.formatString();
        });
        assertEquals("$remote_addr \"$request\"", text);
    }

    @Test
    void newRowsGoAfterTheSelectedRowAndCanBeMovedAndRemoved() throws Exception {
        List<String> texts = onFx(() -> {
            LogFormatDesign d = LogFormatDesign.blank();
            d.style(Style.JSON);
            LogFormatBuilder b = builder(d, new AtomicInteger());
            b.addVariable("$a_first");
            b.addVariable("$c_last");
            b.selectRow(0);
            b.addVariable("$b_middle");
            b.moveSelected(1);
            b.removeSelected();
            return b.rowTexts();
        });
        assertEquals(List.of("$a_first", "$c_last"), texts);
    }

    @Test
    void aJsonFieldGetsAKeyThatIsNotAlreadyTaken() throws Exception {
        String text = onFx(() -> {
            LogFormatDesign d = LogFormatDesign.blank();
            d.style(Style.JSON);
            LogFormatBuilder b = builder(d, new AtomicInteger());
            b.addVariable("$status");
            b.addVariable("$status");
            return b.formatString();
        });
        assertEquals("{\"status\":$status,\"status_2\":$status}", text);
    }

    @Test
    void textRowsAreNotAddedInTheJsonStyle() throws Exception {
        List<String> texts = onFx(() -> {
            LogFormatDesign d = LogFormatDesign.blank();
            d.style(Style.JSON);
            LogFormatBuilder b = builder(d, new AtomicInteger());
            b.addVariable("$status");
            b.addText(" - ");
            return b.rowTexts();
        });
        assertEquals(List.of("$status"), texts);
    }

    @Test
    void anExistingFormatIsLoadedIntoRowsAndComesOutUnchanged() throws Exception {
        String original = "$remote_addr - $remote_user [$time_local] \"$request\" $status";
        String[] result = onFx(() -> {
            LogFormatSettings s = new LogFormatSettings();
            s.text = original;
            LogFormatBuilder b = builder(LogFormatDesign.from(s), new AtomicInteger());
            return new String[]{b.formatString(), b.design().text(), String.valueOf(b.rowTexts().size())};
        });
        assertEquals(original, result[0]);
        assertEquals(original, result[1]);
        assertTrue(Integer.parseInt(result[2]) > 5);
    }

    @Test
    void theSearchNarrowsTheListOfFields() throws Exception {
        int[] sizes = onFx(() -> {
            LogFormatBuilder b = builder(LogFormatDesign.blank(), new AtomicInteger());
            int all = b.paletteSize();
            b.search("status", LogFormatBuilder.allGroups());
            int found = b.paletteSize();
            b.search("zzzz-nothing", LogFormatBuilder.allGroups());
            return new int[]{all, found, b.paletteSize()};
        });
        assertEquals(LogFields.all().size(), sizes[0]);
        assertTrue(sizes[1] > 0 && sizes[1] < sizes[0], "search must narrow: " + sizes[1]);
        assertEquals(0, sizes[2]);
    }

    @Test
    void theReviewTextShowsTheExactDirective() throws Exception {
        LogFormatDesign d = LogFormatDesign.blank();
        d.style(Style.JSON);
        d.elements().add(LogFormatDesign.Element.field("$status").withKey("s"));
        String review = LogFormatWizard.review(d, "j");
        assertTrue(review.startsWith("log_format j escape=json '{\"s\":$status}';"), review);
        assertTrue(review.contains("{\"s\":200}"), review);
    }
}
