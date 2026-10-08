package mt.su.nrm.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A form re-checks itself when any of its inputs change, including inputs inside a row or group. Before, a text box with
 * a Browse button beside it was missed, and the import dialog kept saying "choose the private key" after it was chosen.
 */
class FormDialogWatchTest {

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
    void aTextBoxBesideABrowseButtonIsWatched() throws Exception {
        int changes = onFx(() -> {
            TextField path = new TextField();
            HBox row = new HBox(6, path, new Button("Browse"));
            AtomicInteger count = new AtomicInteger();
            FormDialog.watch(row, count::incrementAndGet);
            path.setText("C:\\certs\\site.key");
            return count.get();
        });
        assertEquals(1, changes, "choosing a file must re-run the checks, once");
    }

    @Test
    void fieldsNestedSeveralLevelsDeepAreWatchedToo() throws Exception {
        int changes = onFx(() -> {
            TextField inner = new TextField();
            CheckBox box = new CheckBox();
            ComboBox<String> combo = new ComboBox<>();
            TextArea area = new TextArea();
            VBox outer = new VBox(new HBox(new VBox(inner), box), combo, area);
            AtomicInteger count = new AtomicInteger();
            FormDialog.watch(outer, count::incrementAndGet);
            inner.setText("a");
            box.setSelected(true);
            combo.setValue("x");
            area.setText("notes");
            return count.get();
        });
        assertEquals(4, changes);
    }

    @Test
    void aPlainFieldIsStillWatchedExactlyOnce() throws Exception {
        int changes = onFx(() -> {
            TextField field = new TextField();
            AtomicInteger count = new AtomicInteger();
            FormDialog.watch(field, count::incrementAndGet);
            field.setText("one");
            field.setText("two");
            return count.get();
        });
        assertEquals(2, changes, "one call per change, not doubled");
    }

    @Test
    void anEditableComboBoxIsNotWatchedTwiceThroughItsInnerTextBox() throws Exception {
        int changes = onFx(() -> {
            ComboBox<String> combo = new ComboBox<>();
            combo.setEditable(true);
            AtomicInteger count = new AtomicInteger();
            FormDialog.watch(combo, count::incrementAndGet);
            combo.setValue("combined");
            return count.get();
        });
        assertEquals(1, changes);
    }
}
