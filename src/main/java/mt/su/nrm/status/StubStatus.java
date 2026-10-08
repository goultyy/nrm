package mt.su.nrm.status;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One reading of nginx's {@code stub_status} page: the number of connections now, and counters that only grow
 * (until nginx restarts). It looks like this:
 * <pre>
 * Active connections: 291
 * server accepts handled requests
 *  16630948 16630948 31070465
 * Reading: 6 Writing: 179 Waiting: 106
 * </pre>
 */
public record StubStatus(long active, long accepts, long handled, long requests, long reading, long writing,
                         long waiting) {

    private static final Pattern ACTIVE = Pattern.compile("Active connections:\\s*(\\d+)");
    private static final Pattern COUNTERS = Pattern.compile(
            "server accepts handled requests\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)");
    private static final Pattern STATES = Pattern.compile("Reading:\\s*(\\d+)\\s+Writing:\\s*(\\d+)\\s+Waiting:\\s*(\\d+)");

    /** Reads a stub_status page; throws {@link IllegalArgumentException} if the text is anything else. */
    public static StubStatus parse(String text) {
        Matcher active = ACTIVE.matcher(text);
        Matcher counters = COUNTERS.matcher(text);
        Matcher states = STATES.matcher(text);
        if (!active.find() || !counters.find() || !states.find()) {
            throw new IllegalArgumentException("That is not an nginx stub_status page.");
        }
        try {
            return new StubStatus(Long.parseLong(active.group(1)), Long.parseLong(counters.group(1)),
                    Long.parseLong(counters.group(2)), Long.parseLong(counters.group(3)),
                    Long.parseLong(states.group(1)), Long.parseLong(states.group(2)), Long.parseLong(states.group(3)));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("A number on the stub_status page is out of range.", e);
        }
    }

    /** Connections nginx accepted but could not handle, which means it ran out of a resource (such as worker connections). */
    public long dropped() {
        return Math.max(0, accepts - handled);
    }

    /** How fast the counters grew between two readings. */
    public record Rates(double requestsPerSecond, double acceptsPerSecond, double handledPerSecond) {
    }

    /**
     * The rates between two readings taken {@code seconds} apart. Empty when they can't be compared: no time has
     * passed, or a counter went backwards, which means nginx restarted in between.
     */
    public static Optional<Rates> rates(StubStatus before, StubStatus after, double seconds) {
        if (seconds <= 0 || after.requests < before.requests || after.accepts < before.accepts
                || after.handled < before.handled) {
            return Optional.empty();
        }
        return Optional.of(new Rates((after.requests - before.requests) / seconds,
                (after.accepts - before.accepts) / seconds, (after.handled - before.handled) / seconds));
    }
}
