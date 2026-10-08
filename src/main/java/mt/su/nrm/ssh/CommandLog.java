package mt.su.nrm.ssh;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

/**
 * Everything the app sends to or receives from a server, line by line. The SSH layer writes to it
 * on every call, so it is the record behind the command log panel's promise that nothing sent to
 * the server is hidden.
 * <p>
 * Registered secrets are replaced with {@value #MASK} before a line is stored or shown, so they
 * can't reach the panel even if a server echoes them back. Listeners are called on the thread
 * that logged; UI code must hop to the FX thread itself.
 */
public final class CommandLog {

    public static final String MASK = "********";
    public static final int DEFAULT_CAPACITY = 20_000;

    public enum Kind {
        /** A command line or SFTP operation the app sent. */
        COMMAND,
        /** A line the server wrote to stdout. */
        OUTPUT,
        /** A line the server wrote to stderr. */
        ERROR_OUTPUT,
        /** Exit status and other results. */
        STATUS,
        /** Notes from the app itself, e.g. that a password was supplied on stdin. */
        INFO
    }

    public record Line(Instant time, Kind kind, String text) {
    }

    private final Clock clock;
    private final int capacity;
    private final Deque<Line> lines = new ArrayDeque<>();
    private final Set<String> secrets = new CopyOnWriteArraySet<>();
    private final List<Consumer<Line>> listeners = new CopyOnWriteArrayList<>();

    public CommandLog() {
        this(Clock.systemUTC(), DEFAULT_CAPACITY);
    }

    public CommandLog(Clock clock, int capacity) {
        this.clock = clock;
        this.capacity = capacity;
    }

    /** Text to hide from now on. Blank values are ignored. */
    public void addSecret(String secret) {
        if (secret != null && !secret.isBlank()) {
            secrets.add(secret);
        }
    }

    public void addListener(Consumer<Line> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<Line> listener) {
        listeners.remove(listener);
    }

    /** Logs text that may span several lines; each line is stored separately, in full. */
    public void log(Kind kind, String text) {
        for (String line : text.split("\r?\n", -1)) {
            append(kind, line);
        }
    }

    public synchronized List<Line> snapshot() {
        return new ArrayList<>(lines);
    }

    public synchronized void clear() {
        lines.clear();
    }

    /** Applies the same masking the log itself uses. */
    public String mask(String text) {
        String result = text;
        // Longest first, so a secret that contains another isn't left half visible.
        List<String> ordered = new ArrayList<>(secrets);
        ordered.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (String secret : ordered) {
            result = result.replace(secret, MASK);
        }
        return result;
    }

    private void append(Kind kind, String rawLine) {
        Line line = new Line(clock.instant(), kind, mask(rawLine));
        synchronized (this) {
            lines.addLast(line);
            while (lines.size() > capacity) {
                lines.removeFirst();
            }
        }
        for (Consumer<Line> listener : listeners) {
            listener.accept(line);
        }
    }
}
