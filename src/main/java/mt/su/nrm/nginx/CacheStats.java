package mt.su.nrm.nginx;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Counts cache results from lines written with {@link CacheStatsLogging#FORMAT_TEXT}:
 * {@code time host cache-status status request-time "uri"}. Pure text work; nothing is fetched here.
 */
public final class CacheStats {

    /** The statuses nginx reports in {@code $upstream_cache_status}. */
    private static final Set<String> KNOWN = Set.of("HIT", "MISS", "EXPIRED", "STALE", "UPDATING", "REVALIDATED", "BYPASS");

    /** Counts for one host name. */
    public static final class HostStats {
        private final String host;
        private final Map<String, Long> counts = new LinkedHashMap<>();
        private long total;

        HostStats(String host) {
            this.host = host;
        }

        public String host() {
            return host;
        }

        public long count(String status) {
            return counts.getOrDefault(status, 0L);
        }

        /** Statuses outside the standard seven. */
        public long other() {
            long known = 0;
            for (String s : KNOWN) {
                known += count(s);
            }
            return total - known;
        }

        public long total() {
            return total;
        }

        /** Requests answered from the cache (HIT, STALE, UPDATING, REVALIDATED) as a share of all, 0 to 1. */
        public double hitRatio() {
            return total == 0 ? 0 : (count("HIT") + count("STALE") + count("UPDATING") + count("REVALIDATED")) / (double) total;
        }

        void add(String status) {
            counts.merge(status, 1L, Long::sum);
            total++;
        }
    }

    private final Map<String, HostStats> hosts = new LinkedHashMap<>();
    private final HostStats all = new HostStats("All sites");
    private String first = "";
    private String last = "";
    private int lines;
    private int skipped;

    private CacheStats() {
    }

    /** Reads log text; lines that don't fit the format are counted as skipped, never guessed at. */
    public static CacheStats parse(String text) {
        CacheStats s = new CacheStats();
        for (String line : text.split("\r?\n")) {
            if (line.isBlank()) {
                continue;
            }
            s.lines++;
            String[] f = line.split(" ", 6);
            if (f.length < 5 || !f[0].contains("T")) {
                s.skipped++;
                continue;
            }
            String status = f[2].toUpperCase(Locale.ROOT);
            if (status.equals("-")) {
                continue; // not a cached request; the map should have kept it out, but an old log may hold some
            }
            if (!KNOWN.contains(status)) {
                status = "OTHER";
            }
            s.hosts.computeIfAbsent(f[1].toLowerCase(Locale.ROOT), HostStats::new).add(status);
            s.all.add(status);
            if (s.first.isEmpty()) {
                s.first = f[0];
            }
            s.last = f[0];
        }
        return s;
    }

    /** One entry per host, in the order first seen. */
    public java.util.Collection<HostStats> hosts() {
        return hosts.values();
    }

    public HostStats totals() {
        return all;
    }

    /** Time of the first and last counted request, as written in the log ("" if none). */
    public String firstTime() {
        return first;
    }

    public String lastTime() {
        return last;
    }

    /** Lines read, and how many of them were not in the expected format. */
    public int lines() {
        return lines;
    }

    public int skipped() {
        return skipped;
    }
}
