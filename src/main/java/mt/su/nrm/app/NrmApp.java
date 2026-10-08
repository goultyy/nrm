package mt.su.nrm.app;

import mt.su.nrm.config.EncryptedProfileStore;
import mt.su.nrm.config.KeyProtectors;
import mt.su.nrm.config.ProfileRepository;
import mt.su.nrm.config.ProfileStoreException;
import mt.su.nrm.ui.CrashReporter;
import mt.su.nrm.ui.MainWindow;
import mt.su.nrm.ui.Theme;
import mt.su.nrm.util.AppDirs;
import mt.su.nrm.util.AppInfo;
import mt.su.nrm.util.AppLog;
import mt.su.nrm.util.WindowBounds;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * JavaFX entry point. Shows the main window, remembers where it was, and routes any error nobody
 * handled to the application log and a dialog instead of letting it vanish.
 */
public class NrmApp extends Application {

    private static final double DEFAULT_WIDTH = 1100;
    private static final double DEFAULT_HEIGHT = 720;

    /** The window's position while it is in its normal (not maximized) state. */
    private WindowBounds lastNormal;

    @Override
    public void init() {
        CrashReporter.install();
    }

    @Override
    public void start(Stage stage) {
        stage.setTitle(AppInfo.NAME);
        addIcons(stage);
        AppIcons.install(); // every other window (pop-outs, editors, dialogs) gets the logo too
        Theme.install(); // and the light or dark colours, before the first window shows
        AppLog.info(AppInfo.NAME + " " + AppInfo.version() + " started");

        ProfileRepository repository;
        try {
            EncryptedProfileStore store =
                    new EncryptedProfileStore(AppDirs.profileStoreFile(), KeyProtectors.platformDefault());
            repository = ProfileRepository.open(store);
        } catch (ProfileStoreException | RuntimeException e) {
            // Don't offer to carry on with an empty list: the next save would overwrite the store.
            AppLog.error("The profile store could not be opened", e);
            showProblem(stage, "The saved server profiles could not be opened:\n\n" + e.getMessage()
                    + "\n\nDetails are in " + AppLog.file());
            return;
        }
        try {
            MainWindow window = new MainWindow(repository);
            stage.setScene(new Scene(window, DEFAULT_WIDTH, DEFAULT_HEIGHT));
            restoreBounds(stage);
            trackBounds(stage);
            stage.setOnCloseRequest(e -> {
                if (window.canClose()) {
                    saveBounds(stage);
                    window.shutdown();
                    AppLog.info("Closed");
                } else {
                    e.consume();
                }
            });
            stage.show();
        } catch (RuntimeException e) {
            AppLog.error("The main window could not be created", e);
            showProblem(stage, "The main window could not be created:\n\n" + e + "\n\nDetails are in " + AppLog.file());
        }
    }

    private static void showProblem(Stage stage, String text) {
        Label message = new Label(text);
        message.setWrapText(true);
        VBox box = new VBox(message);
        box.setPadding(new Insets(24));
        stage.setScene(new Scene(box, 560, 240));
        stage.show();
    }

    private static void addIcons(Stage stage) {
        stage.getIcons().addAll(AppIcons.images());
    }

    // ---------------------------------------------------------------- window position

    private static Path boundsFile() {
        return AppDirs.dataDir().resolve("window.properties");
    }

    private static List<WindowBounds.Area> screens() {
        List<WindowBounds.Area> areas = new ArrayList<>();
        for (Screen s : Screen.getScreens()) {
            Rectangle2D b = s.getVisualBounds();
            areas.add(new WindowBounds.Area(b.getMinX(), b.getMinY(), b.getWidth(), b.getHeight()));
        }
        return areas;
    }

    private void restoreBounds(Stage stage) {
        WindowBounds.load(boundsFile(), screens()).ifPresent(b -> {
            stage.setX(b.x());
            stage.setY(b.y());
            stage.setWidth(b.width());
            stage.setHeight(b.height());
            stage.setMaximized(b.maximized());
            lastNormal = new WindowBounds(b.x(), b.y(), b.width(), b.height(), false);
        });
    }

    /** Remembers the normal-state bounds, so a maximized window restores to its previous size. */
    private void trackBounds(Stage stage) {
        Runnable capture = () -> {
            if (!stage.isMaximized() && !stage.isIconified() && stage.getWidth() > 0) {
                lastNormal = new WindowBounds(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight(), false);
            }
        };
        stage.xProperty().addListener((o, a, b) -> capture.run());
        stage.yProperty().addListener((o, a, b) -> capture.run());
        stage.widthProperty().addListener((o, a, b) -> capture.run());
        stage.heightProperty().addListener((o, a, b) -> capture.run());
    }

    private void saveBounds(Stage stage) {
        WindowBounds normal = lastNormal != null ? lastNormal
                : new WindowBounds(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight(), false);
        try {
            new WindowBounds(normal.x(), normal.y(), normal.width(), normal.height(), stage.isMaximized())
                    .save(boundsFile());
        } catch (IOException e) {
            AppLog.warn("Could not save the window position", e);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
