package mt.su.nrm.nginx;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A {@code limit_req_zone} or {@code limit_conn_zone}: the shared memory that counts requests or
 * connections per key (usually the client address). Locations refer to it by name.
 */
public final class LimitZoneSettings {

    public enum Kind {
        REQUEST("Requests per second"),
        CONNECTION("Simultaneous connections");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final Pattern ZONE = Pattern.compile("[A-Za-z0-9_]+:\\d+[kKmM]");
    private static final Pattern RATE = Pattern.compile("\\d+r/[sm]");

    public Kind kind = Kind.REQUEST;
    /** What is counted per, e.g. {@code $binary_remote_addr}. */
    public String key = "$binary_remote_addr";
    /** Zone name and size, e.g. {@code perip:10m}. */
    public String zone = "";
    /** Requests only, e.g. {@code 10r/s}. */
    public String rate = "";

    public LimitZoneSettings copy() {
        LimitZoneSettings c = new LimitZoneSettings();
        c.kind = kind;
        c.key = key;
        c.zone = zone;
        c.rate = rate;
        return c;
    }

    public String zoneName() {
        int colon = zone.indexOf(':');
        return colon < 0 ? zone : zone.substring(0, colon);
    }

    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (!key.startsWith("$") || key.length() < 2 || key.chars().anyMatch(c -> c < 0x21)) {
            problems.add("The key must be an nginx variable such as $binary_remote_addr.");
        }
        if (!ZONE.matcher(zone).matches()) {
            problems.add("The zone must look like name:10m (a name, a colon and a size).");
        }
        if (kind == Kind.REQUEST && !RATE.matcher(rate).matches()) {
            problems.add("The rate must look like 10r/s or 30r/m.");
        }
        return problems;
    }

    /** The directive as it would be written to nginx.conf, for the review step of the wizard. */
    public String preview() {
        return (kind == Kind.REQUEST ? "limit_req_zone " : "limit_conn_zone ") + key + " zone=" + zone
                + (kind == Kind.REQUEST && !rate.isBlank() ? " rate=" + rate : "") + ";";
    }
}
