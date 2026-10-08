package mt.su.nrm.ui;

import mt.su.nrm.util.AppLog;
import mt.su.nrm.util.SystemTheme;
import mt.su.nrm.util.ThemeMode;
import mt.su.nrm.util.ThemeSettings;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.ListChangeListener;
import javafx.scene.Scene;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.IOException;
import java.util.List;

/**
 * Light or dark colours for every window. Dark is a stylesheet added on top of JavaFX's standard theme, so light is
 * just that stylesheet missing. Every window that is shown, dialogs and wizards included, gets the current choice,
 * and changing it restyles the open ones straight away. With {@link ThemeMode#SYSTEM} the app follows Windows,
 * including when the Windows setting changes while the app is running.
 * <p>
 * Used on the JavaFX thread only.
 */
public final class Theme {

    /** How often the Windows setting is looked at while following it; reading it is a single cheap registry call. */
    private static final Duration FOLLOW_INTERVAL = Duration.seconds(3);
    private static final String LISTENING = "nrm.theme.listening";

    private static ThemeMode mode = ThemeMode.SYSTEM;
    private static boolean dark;
    private static boolean installed;
    private static Timeline follow;

    private Theme() {
    }

    /** The dark stylesheet's address. */
    static String darkSheet() {
        return Theme.class.getResource("dark.css").toExternalForm();
    }

    /** Reads the saved choice and starts styling windows. Call once at start-up, before the first window shows. */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        mode = ThemeSettings.load(ThemeSettings.file());
        dark = effectiveDark();
        Window.getWindows().forEach(Theme::apply);
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                if (change.wasAdded()) {
                    change.getAddedSubList().forEach(Theme::apply);
                }
            }
        });
        follow = new Timeline(new KeyFrame(FOLLOW_INTERVAL, e -> refresh()));
        follow.setCycleCount(Animation.INDEFINITE);
        follow.play();
    }

    public static ThemeMode mode() {
        return mode;
    }

    /** True if windows are being drawn dark right now. */
    public static boolean isDark() {
        return dark;
    }

    /** Switches to this choice, remembers it, and restyles the open windows if the colours change. */
    public static void setMode(ThemeMode chosen) {
        mode = chosen;
        try {
            ThemeSettings.save(ThemeSettings.file(), chosen);
        } catch (IOException e) {
            // The choice still applies for this session.
            AppLog.warn("Could not save the theme choice", e);
        }
        refresh();
    }

    private static boolean effectiveDark() {
        return switch (mode) {
            case DARK -> true;
            case LIGHT -> false;
            case SYSTEM -> SystemTheme.isDark().orElse(false);
        };
    }

    /** Looks again at what the colours should be, and restyles only if they changed. */
    private static void refresh() {
        boolean now = effectiveDark();
        if (now != dark) {
            dark = now;
            Window.getWindows().forEach(Theme::apply);
        }
    }

    private static void apply(Window window) {
        if (window.getScene() != null) {
            apply(window.getScene());
        }
        // A window can get a new scene later (a dialog filled after it is created), and a shown window is listed
        // again each time it is shown, so the listener is added only once.
        if (window.getProperties().putIfAbsent(LISTENING, Boolean.TRUE) == null) {
            window.sceneProperty().addListener((obs, old, now) -> {
                if (now != null) {
                    apply(now);
                }
            });
        }
    }

    /**
     * A tile's hover or selection style, adjusted for the current theme. The tiles draw their highlight in a bright
     * blue, which is right on a light background but loud on a dark one, so in dark mode the same style gets a soft
     * neutral tint instead. Light mode returns the style untouched. Inline styles can't be changed from a
     * stylesheet, which is why this is done where the style is applied.
     */
    public static String tint(String style) {
        if (!dark) {
            return style;
        }
        return style
                .replace("rgba(60,120,200,0.9)", "rgba(165,172,184,0.5)")
                .replace("rgba(60,120,200,0.22)", "rgba(255,255,255,0.14)")
                .replace("rgba(60,120,200,0.14)", "rgba(255,255,255,0.10)")
                .replace("rgba(60,120,200,0.10)", "rgba(255,255,255,0.07)");
    }

    /** Gives a scene the current theme: the dark stylesheet when dark, none when light. */
    public static void apply(Scene scene) {
        List<String> sheets = scene.getStylesheets();
        String sheet = darkSheet();
        if (dark) {
            if (!sheets.contains(sheet)) {
                sheets.add(sheet);
            }
        } else {
            sheets.remove(sheet);
        }
    }
}
