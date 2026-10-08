package mt.su.nrm.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mt.su.nrm.util.AppDirs;
import mt.su.nrm.util.ThemeMode;
import mt.su.nrm.util.ThemeSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Paint;
import javafx.scene.paint.Stop;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The dark stylesheet on the JavaFX thread: it must parse without complaint, actually darken a standard control, and
 * be removable again. Settings go to a temporary folder, never the real one. Skipped where JavaFX can't start.
 */
class ThemeTest {

    private static boolean toolkitAvailable;

    @TempDir
    Path dataDir;

    private String savedDataDir;

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

    @BeforeEach
    void useATemporaryDataFolder() {
        savedDataDir = System.getProperty(AppDirs.DATA_DIR_PROPERTY);
        System.setProperty(AppDirs.DATA_DIR_PROPERTY, dataDir.toString());
    }

    @AfterEach
    void restoreTheDataFolder() {
        if (savedDataDir == null) {
            System.clearProperty(AppDirs.DATA_DIR_PROPERTY);
        } else {
            System.setProperty(AppDirs.DATA_DIR_PROPERTY, savedDataDir);
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

    /** How light a paint is, 0 (black) to 1 (white); a gradient is averaged over its stops. */
    private static double brightness(Paint paint) {
        if (paint instanceof Color c) {
            return c.getBrightness();
        }
        if (paint instanceof LinearGradient g) {
            return g.getStops().stream().map(Stop::getColor).mapToDouble(Color::getBrightness).average().orElse(1);
        }
        throw new AssertionError("unexpected paint " + paint);
    }

    /** How light the hint text drawn inside a field, area or editable combo box is, found by its words. */
    private static double promptBrightness(javafx.scene.Node control, String prompt) {
        for (javafx.scene.Node node : control.lookupAll(".text")) {
            if (node instanceof javafx.scene.text.Text text && prompt.equals(text.getText())) {
                return brightness(text.getFill());
            }
        }
        throw new AssertionError("no prompt text '" + prompt + "' was drawn by " + control.getClass().getSimpleName());
    }

    /** The brightness of a button's body in a scene, after the stylesheets have been applied. */
    private static double buttonBrightness(Scene scene, Button button) {
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        var fills = button.getBackground().getFills();
        return brightness(fills.get(fills.size() - 1).getFill());
    }

    private static Scene sceneWithButton(Button button) {
        return new Scene(new StackPane(button), 200, 100);
    }

    @Test
    void theDarkStylesheetIsFoundAndAppliesWithoutCssWarnings() throws Exception {
        List<LogRecord> complaints = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    complaints.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger css = Logger.getLogger("javafx.css");
        css.addHandler(handler);
        try {
            onFx(() -> {
                Theme.setMode(ThemeMode.DARK);
                Button button = new Button("Test");
                Scene scene = sceneWithButton(button);
                Theme.apply(scene);
                buttonBrightness(scene, button);
                return null;
            });
        } finally {
            css.removeHandler(handler);
        }
        assertTrue(complaints.isEmpty(), "dark.css produced CSS warnings: " + complaints.stream()
                .map(LogRecord::getMessage).toList());
    }

    @Test
    void darkMakesAStandardControlDarkAndLightLeavesItLight() throws Exception {
        double[] result = onFx(() -> {
            Theme.setMode(ThemeMode.LIGHT);
            Button lightButton = new Button("Test");
            Scene lightScene = sceneWithButton(lightButton);
            Theme.apply(lightScene);
            double light = buttonBrightness(lightScene, lightButton);

            Theme.setMode(ThemeMode.DARK);
            Button darkButton = new Button("Test");
            Scene darkScene = sceneWithButton(darkButton);
            Theme.apply(darkScene);
            double dark = buttonBrightness(darkScene, darkButton);
            return new double[] {light, dark};
        });
        assertTrue(result[0] > 0.6, "light button should be light, was " + result[0]);
        assertTrue(result[1] < 0.45, "dark button should be dark, was " + result[1]);
    }

    @Test
    void promptTextIsReadableOnTheDarkBackgroundOfFieldsAndAreas() throws Exception {
        // The standard theme works a prompt out as "a bit darker than the background", which vanishes on dark.
        double[] result = onFx(() -> {
            Theme.setMode(ThemeMode.DARK);
            javafx.scene.control.TextField field = new javafx.scene.control.TextField();
            field.setPromptText("e.g. example.com");
            javafx.scene.control.TextArea area = new javafx.scene.control.TextArea();
            area.setPromptText("optional");
            javafx.scene.control.ComboBox<String> combo = new javafx.scene.control.ComboBox<>();
            combo.setEditable(true);
            combo.setPromptText("pick or type");
            Scene scene = new Scene(new StackPane(field, area, combo), 300, 300);
            Theme.apply(scene);
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            return new double[] {promptBrightness(field, "e.g. example.com"), promptBrightness(area, "optional"),
                    promptBrightness(combo, "pick or type")};
        });
        for (double brightness : result) {
            assertTrue(brightness > 0.45, "prompt text is too dark to read on the dark background: " + brightness);
        }
    }

    @Test
    void lightPromptTextIsLeftToTheStandardTheme() throws Exception {
        double light = onFx(() -> {
            Theme.setMode(ThemeMode.LIGHT);
            javafx.scene.control.TextField field = new javafx.scene.control.TextField();
            field.setPromptText("e.g. example.com");
            Scene scene = new Scene(new StackPane(field), 300, 100);
            Theme.apply(scene);
            scene.getRoot().applyCss();
            return promptBrightness(field, "e.g. example.com");
        });
        assertTrue(light < 0.85, "light prompt text should stay the standard grey, was " + light);
    }

    @Test
    void switchingBackToLightRemovesTheStylesheet() throws Exception {
        List<Boolean> has = onFx(() -> {
            Theme.setMode(ThemeMode.DARK);
            Scene scene = sceneWithButton(new Button("Test"));
            Theme.apply(scene);
            boolean withDark = scene.getStylesheets().contains(Theme.darkSheet());
            Theme.setMode(ThemeMode.LIGHT);
            Theme.apply(scene);
            return List.of(withDark, scene.getStylesheets().contains(Theme.darkSheet()));
        });
        assertTrue(has.get(0));
        assertFalse(has.get(1));
    }

    @Test
    void applyingTwiceDoesNotAddTheStylesheetTwice() throws Exception {
        long count = onFx(() -> {
            Theme.setMode(ThemeMode.DARK);
            Scene scene = sceneWithButton(new Button("Test"));
            Theme.apply(scene);
            Theme.apply(scene);
            return scene.getStylesheets().stream().filter(Theme.darkSheet()::equals).count();
        });
        assertEquals(1, count);
    }

    @Test
    void tileHighlightsLoseTheirBrightBlueInDarkModeAndKeepItInLight() throws Exception {
        String[] styles = {
                "-fx-background-color: rgba(60,120,200,0.10); -fx-border-color: transparent;",
                "-fx-background-color: rgba(60,120,200,0.14); -fx-border-color: rgba(60,120,200,0.9);",
                "-fx-background-color: rgba(60,120,200,0.22); -fx-border-color: rgba(60,120,200,0.9);"};
        List<String> light = onFx(() -> {
            Theme.setMode(ThemeMode.LIGHT);
            return List.of(Theme.tint(styles[0]), Theme.tint(styles[1]), Theme.tint(styles[2]));
        });
        List<String> dark = onFx(() -> {
            Theme.setMode(ThemeMode.DARK);
            return List.of(Theme.tint(styles[0]), Theme.tint(styles[1]), Theme.tint(styles[2]));
        });
        for (int i = 0; i < styles.length; i++) {
            assertEquals(styles[i], light.get(i), "light mode must not change a tile's look");
            assertFalse(dark.get(i).contains("60,120,200"), "dark mode still has the bright blue: " + dark.get(i));
            assertTrue(dark.get(i).contains("rgba("), "dark mode must still highlight: " + dark.get(i));
        }
        assertTrue(dark.get(2).contains("rgba(255,255,255,0.14)"));
    }

    @Test
    void theTileClassesStillHaveTheirOriginalTextAndUseTheTint() throws Exception {
        // Guards the tile classes against being mangled by a bulk edit, and checks each one applies the tint.
        for (String name : new String[] {"NavTile", "SectionTile", "SiteTile", "ServerTile"}) {
            Path source = Path.of("src", "main", "java", "mt", "su", "nrm", "ui", name + ".java");
            String text = Files.readString(source);
            assertTrue(text.startsWith("package mt.su.nrm.ui;"), name + " lost its package line");
            assertTrue(text.contains("final class " + name + " extends VBox"), name + " lost its class line");
            assertTrue(text.contains("Theme.tint("), name + " does not apply the dark-mode tint");
        }
    }

    @Test
    void theChoiceIsSavedToTheSettingsFile() throws Exception {
        onFx(() -> {
            Theme.setMode(ThemeMode.DARK);
            return null;
        });
        assertEquals(ThemeMode.DARK, ThemeSettings.load(dataDir.resolve("settings.properties")));
        assertTrue(Files.exists(dataDir.resolve("settings.properties")));
    }
}
