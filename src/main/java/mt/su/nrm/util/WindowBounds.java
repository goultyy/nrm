package mt.su.nrm.util;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * Where the main window was, so it opens in the same place next time. The saved position is only
 * used if it is still on a screen (a monitor may have been unplugged since).
 */
public record WindowBounds(double x, double y, double width, double height, boolean maximized) {

    /** A screen's usable area. */
    public record Area(double x, double y, double width, double height) {
    }

    public static final double MIN_WIDTH = 640;
    public static final double MIN_HEIGHT = 420;

    /** True if enough of the window is on one of the screens to grab and move it. */
    public boolean isVisibleOn(List<Area> screens) {
        for (Area s : screens) {
            double overlapWidth = Math.min(x + width, s.x() + s.width()) - Math.max(x, s.x());
            double overlapHeight = Math.min(y + height, s.y() + s.height()) - Math.max(y, s.y());
            if (overlapWidth >= 120 && overlapHeight >= 60) {
                return true;
            }
        }
        return false;
    }

    public boolean isPlausible() {
        return width >= MIN_WIDTH && height >= MIN_HEIGHT && width < 20_000 && height < 20_000;
    }

    public void save(Path file) throws IOException {
        Properties p = new Properties();
        p.setProperty("x", Double.toString(x));
        p.setProperty("y", Double.toString(y));
        p.setProperty("width", Double.toString(width));
        p.setProperty("height", Double.toString(height));
        p.setProperty("maximized", Boolean.toString(maximized));
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            p.store(w, "NRM window position");
        }
    }

    /** The saved bounds that are usable on these screens, or empty (no file, damaged file, or off screen). */
    public static Optional<WindowBounds> load(Path file, List<Area> screens) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
            WindowBounds b = new WindowBounds(Double.parseDouble(p.getProperty("x")), Double.parseDouble(p.getProperty("y")),
                    Double.parseDouble(p.getProperty("width")), Double.parseDouble(p.getProperty("height")),
                    Boolean.parseBoolean(p.getProperty("maximized", "false")));
            return b.isPlausible() && b.isVisibleOn(screens) ? Optional.of(b) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
