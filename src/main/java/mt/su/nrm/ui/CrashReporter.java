package mt.su.nrm.ui;

import mt.su.nrm.util.AppLog;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextArea;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Last line of defence: an exception nobody handled is written to the application log and shown
 * once in a dialog, instead of vanishing (or leaving a half-working window). The app stays open.
 */
public final class CrashReporter {

    private static final AtomicBoolean SHOWING = new AtomicBoolean(false);

    private CrashReporter() {
    }

    /** Routes uncaught exceptions on every thread here. */
    public static void install() {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> report(thread.getName(), error));
    }

    static void report(String threadName, Throwable error) {
        AppLog.error("Unexpected error on thread " + threadName, error);
        if (!Platform.isFxApplicationThread()) {
            try {
                Platform.runLater(() -> show(error));
            } catch (IllegalStateException toolkitNotRunning) {
                System.err.println("Unexpected error: " + error);
            }
            return;
        }
        show(error);
    }

    private static void show(Throwable error) {
        // One dialog at a time: a failing screen can throw repeatedly.
        if (!SHOWING.compareAndSet(false, true)) {
            return;
        }
        try {
            StringWriter trace = new StringWriter();
            error.printStackTrace(new PrintWriter(trace));
            Alert alert = new Alert(Alert.AlertType.ERROR,
                    "Something went wrong inside the app. Your saved servers are not affected, and nothing was "
                            + "changed on a server by this error.\n\nThe details were saved to:\n" + AppLog.file(),
                    ButtonType.OK);
            alert.setTitle("NRM");
            alert.setHeaderText("Unexpected error: " + (error.getMessage() == null ? error.getClass().getSimpleName()
                    : error.getMessage()));
            TextArea details = new TextArea(trace.toString());
            details.setEditable(false);
            details.setPrefRowCount(12);
            alert.getDialogPane().setExpandableContent(details);
            alert.setResizable(true);
            Dialogs.tighten(alert);
            alert.showAndWait();
        } catch (RuntimeException secondFailure) {
            AppLog.error("Could not show the error dialog", secondFailure);
        } finally {
            SHOWING.set(false);
        }
    }
}
