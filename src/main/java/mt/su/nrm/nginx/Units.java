package mt.su.nrm.nginx;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sizes, durations and rates the way people think of them (10 MB, 1 hour, 5 per second) and the
 * way nginx writes them (10m, 1h, 5r/s). Anything that doesn't fit these shapes parses to empty, so
 * a screen can fall back to showing the raw text instead of guessing.
 */
public final class Units {

    private Units() {
    }

    // ------------------------------------------------------------------------------- sizes

    public enum SizeUnit {
        KB("k", "KB", 1024L), MB("m", "MB", 1024L * 1024), GB("g", "GB", 1024L * 1024 * 1024);

        private final String code;
        private final String label;
        private final long bytes;

        SizeUnit(String code, String label, long bytes) {
            this.code = code;
            this.label = label;
            this.bytes = bytes;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** A size such as 10m: an amount in kilobytes, megabytes or gigabytes. */
    public record Size(long amount, SizeUnit unit) {

        private static final Pattern P = Pattern.compile("(\\d{1,9})([kKmMgG])");

        public static Optional<Size> parse(String text) {
            Matcher m = P.matcher(text == null ? "" : text.strip());
            if (!m.matches()) {
                return Optional.empty();
            }
            char c = Character.toLowerCase(m.group(2).charAt(0));
            SizeUnit unit = c == 'k' ? SizeUnit.KB : c == 'm' ? SizeUnit.MB : SizeUnit.GB;
            return Optional.of(new Size(Long.parseLong(m.group(1)), unit));
        }

        /** nginx notation, e.g. {@code 10m}. */
        public String format() {
            return amount + unit.code;
        }

        /** For people, e.g. {@code 10 MB}. */
        public String display() {
            return amount + " " + unit.label;
        }

        public long bytes() {
            return amount * unit.bytes;
        }
    }

    // ------------------------------------------------------------------------------- durations

    public enum TimeUnit {
        SECONDS("s", "seconds", 1L), MINUTES("m", "minutes", 60L), HOURS("h", "hours", 3600L),
        DAYS("d", "days", 86_400L), WEEKS("w", "weeks", 604_800L);

        private final String code;
        private final String label;
        private final long seconds;

        TimeUnit(String code, String label, long seconds) {
            this.code = code;
            this.label = label;
            this.seconds = seconds;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** A length of time such as 60m. A number with no unit is seconds, as in nginx. */
    public record Duration(long amount, TimeUnit unit) {

        private static final Pattern P = Pattern.compile("(\\d{1,9})([smhdw]?)");

        public static Optional<Duration> parse(String text) {
            Matcher m = P.matcher(text == null ? "" : text.strip());
            if (!m.matches()) {
                return Optional.empty();
            }
            String code = m.group(2).isEmpty() ? "s" : m.group(2);
            for (TimeUnit u : TimeUnit.values()) {
                if (u.code.equals(code)) {
                    return Optional.of(new Duration(Long.parseLong(m.group(1)), u));
                }
            }
            return Optional.empty();
        }

        public String format() {
            return amount + unit.code;
        }

        /** For people, e.g. {@code 1 hour} or {@code 90 minutes}. */
        public String display() {
            String label = amount == 1 ? unit.label.substring(0, unit.label.length() - 1) : unit.label;
            return amount + " " + label;
        }

        public long seconds() {
            return amount * unit.seconds;
        }
    }

    // ------------------------------------------------------------------------------- rates

    public enum RateUnit {
        SECOND("s", "per second"), MINUTE("m", "per minute");

        private final String code;
        private final String label;

        RateUnit(String code, String label) {
            this.code = code;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** A request rate such as 10r/s. */
    public record Rate(long amount, RateUnit unit) {

        private static final Pattern P = Pattern.compile("(\\d{1,9})r/([sm])");

        public static Optional<Rate> parse(String text) {
            Matcher m = P.matcher(text == null ? "" : text.strip());
            if (!m.matches()) {
                return Optional.empty();
            }
            return Optional.of(new Rate(Long.parseLong(m.group(1)), m.group(2).equals("s") ? RateUnit.SECOND : RateUnit.MINUTE));
        }

        public String format() {
            return amount + "r/" + unit.code;
        }

        /** For people, e.g. {@code 10 requests per second}. */
        public String display() {
            return amount + (amount == 1 ? " request " : " requests ") + unit.label;
        }
    }
}
