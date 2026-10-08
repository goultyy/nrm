package mt.su.nrm.util;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;

/**
 * The application log: unexpected errors and a short trail of what was done to which server, in
 * {@code %APPDATA%\NRM\logs\app.log}. It never records passwords or file contents;
 * the exact commands sent to a server are in the command log panel instead. The file is rotated once
 * it passes 1 MB (one previous file is kept).
 */
public final class AppLog {

    public enum Level { INFO, WARN, ERROR }

    private static final long MAX_BYTES = 1_000_000;

    private static Path file;
    private static Clock clock = Clock.systemUTC();

    private AppLog() {
    }

    /** Points the log somewhere else (tests). */
    public static synchronized void useFile(Path path, Clock c) {
        file = path;
        clock = c;
    }

    public static void info(String message) {
        write(Level.INFO, message, null);
    }

    public static void warn(String message, Throwable t) {
        write(Level.WARN, message, t);
    }

    public static void error(String message, Throwable t) {
        write(Level.ERROR, message, t);
    }

    /** The log file's location. */
    public static synchronized Path file() {
        return file != null ? file : AppDirs.logFile();
    }

    private static synchronized void write(Level level, String message, Throwable t) {
        try {
            Path target = file();
            Files.createDirectories(target.getParent());
            rotateIfLarge(target);
            StringBuilder sb = new StringBuilder();
            sb.append(Instant.now(clock)).append(" [").append(level).append("] ").append(message).append('\n');
            if (t != null) {
                StringWriter trace = new StringWriter();
                t.printStackTrace(new PrintWriter(trace));
                sb.append(trace);
            }
            Files.writeString(target, sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            // Logging must never take the app down; there is nowhere better to report this.
            System.err.println("Could not write the log: " + e);
        }
    }

    private static void rotateIfLarge(Path target) throws IOException {
        if (Files.exists(target) && Files.size(target) > MAX_BYTES) {
            Files.move(target, target.resolveSibling(target.getFileName() + ".1"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
