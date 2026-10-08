package mt.su.nrm.app;

import javafx.collections.ListChangeListener;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The NRM logo for every window. A JavaFX window only gets an icon if it is given one, so the pop-out file
 * explorer, the file editor, dialogs and the like would otherwise show the default Java icon. {@link #install()}
 * gives the logo to every window the app shows, now and in the future, unless the window set its own.
 */
public final class AppIcons {

    private static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};
    private static List<Image> images;
    private static boolean installed;

    private AppIcons() {
    }

    /** The logo in each size, loaded once; empty if the files are missing (the icon is cosmetic). */
    public static synchronized List<Image> images() {
        if (images == null) {
            List<Image> loaded = new ArrayList<>();
            for (int size : SIZES) {
                try (InputStream in = AppIcons.class.getResourceAsStream("icon-" + size + ".png")) {
                    if (in != null) {
                        loaded.add(new Image(in));
                    }
                } catch (IOException e) {
                    // A missing icon is cosmetic.
                }
            }
            images = List.copyOf(loaded);
        }
        return images;
    }

    /** Gives the logo to this window unless it already has an icon. */
    public static void apply(Window window) {
        if (window instanceof Stage stage && stage.getIcons().isEmpty()) {
            stage.getIcons().addAll(images());
        }
    }

    /** From now on, every window that is shown gets the logo. Call once at start-up, on the UI thread. */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        Window.getWindows().forEach(AppIcons::apply);
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                if (change.wasAdded()) {
                    change.getAddedSubList().forEach(AppIcons::apply);
                }
            }
        });
    }
}
