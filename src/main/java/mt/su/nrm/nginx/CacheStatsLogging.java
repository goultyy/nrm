package mt.su.nrm.nginx;

import java.util.ArrayList;
import java.util.List;

/**
 * The optional "cache statistics" logging: a {@code log_format} that records {@code $upstream_cache_status},
 * a {@code map} that keeps requests which never touch a cache out of the log, and an {@code access_log} line
 * writing to its own file. Nothing here is hidden: it is ordinary configuration that goes through the pending
 * changes like any other edit, and {@link #disable} removes exactly what {@link #enable} added.
 * <p>
 * An {@code access_log} set at server or location level replaces the one inherited from above, so the extra
 * line is added at http level and, additively, next to every server or location that already has its own
 * {@code access_log}. Blocks that log nothing ({@code access_log off}) are left alone: that was a choice.
 */
public final class CacheStatsLogging {

    public static final String FORMAT = "nrm_cache";
    public static final String VARIABLE = "$nrm_cache_logged";
    public static final String LOG_PATH = "/var/log/nginx/nrm-cache.log";
    /** Fields, in order: time, host, cache status, response status, request time, URI. */
    public static final String FORMAT_TEXT = "$time_iso8601 $host $upstream_cache_status $status $request_time \"$request_uri\"";

    private CacheStatsLogging() {
    }

    /**
     * @param problem   why nothing was changed, or null
     * @param covered   server or location blocks that got the extra line this time
     * @param readOnly  blocks that need it but sit in files the app may not edit
     * @param loggingOff blocks left alone because they have {@code access_log off}
     */
    public record Result(String problem, int covered, int readOnly, int loggingOff) {
        public boolean ok() {
            return problem == null;
        }
    }

    /** True if the logging format is defined anywhere in the configuration. */
    public static boolean isEnabled(RemoteConfig config) {
        for (ConfigFile f : config.files()) {
            for (Directive d : f.findDirectives(List.of("log_format"))) {
                if (FORMAT.equals(d.arg(0))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** How many blocks would still get the extra line if {@link #enable} ran now (0 once everything is covered). */
    public static int uncovered(RemoteConfig config) {
        return scan(config, false)[0];
    }

    /** Adds whatever is missing; running it again only fills gaps (for example for a site added later). */
    public static Result enable(RemoteConfig config) {
        String reason = config.mainReadOnlyReason();
        if (reason != null) {
            return new Result(reason, 0, 0, 0);
        }
        List<Block> https = config.mainFile().root().blocks("http");
        if (https.isEmpty()) {
            return new Result("The main nginx.conf has no http block.", 0, 0, 0);
        }
        if (!hasHttpAccessLog(config)) {
            return new Result("The http block has no access_log of its own, so nginx is using its built-in default. "
                    + "Adding one here would switch that default off. Set the access log under Global Settings first.", 0, 0, 0);
        }
        Block http = https.get(0);
        int at = 0;
        if (!hasFormat(config)) {
            http.insert(at++, Directive.create("log_format", List.of(FORMAT, FORMAT_TEXT)));
        }
        if (!hasMap(config)) {
            Block map = Block.create("map", List.of("$upstream_cache_status", VARIABLE));
            http.insert(at++, map);
            // Attached first so the lines inside pick up the right indentation. Only a non-empty status is logged.
            map.add(Directive.create("default", List.of("0")));
            map.add(Directive.create("~.", List.of("1")));
        }
        if (!hasOwnLine(http)) {
            // After the format and map: nginx needs the format defined before it is used.
            int after = at;
            List<Node> kids = http.children();
            for (int i = 0; i < kids.size(); i++) {
                if (isOurs(kids.get(i)) || kids.get(i).name().equals("access_log")) {
                    after = Math.max(after, i + 1);
                }
            }
            http.insert(after, ourLine());
        }
        int[] counts = scan(config, true);
        return new Result(null, counts[0], counts[1], counts[2]);
    }

    /** Removes everything {@link #enable} added; returns how many statements were removed. */
    public static int disable(RemoteConfig config) {
        int removed = 0;
        for (ConfigFile f : config.files()) {
            if (config.readOnlyReason(f) == null) {
                removed += strip(f.root());
            }
        }
        return removed;
    }

    // ------------------------------------------------------------------------------- helpers

    private static Directive ourLine() {
        return Directive.create("access_log", List.of(LOG_PATH, FORMAT, "if=" + VARIABLE));
    }

    private static boolean isOurs(Node n) {
        if (!(n instanceof Directive) || !n.name().equals("access_log")) {
            return false;
        }
        return n.values().contains(FORMAT);
    }

    private static boolean hasOwnLine(Block b) {
        for (Directive d : b.directives("access_log")) {
            if (isOurs(d)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasFormat(RemoteConfig config) {
        return isEnabled(config);
    }

    private static boolean hasMap(RemoteConfig config) {
        for (ConfigFile f : config.files()) {
            if (findMap(f.root()) != null) {
                return true;
            }
        }
        return false;
    }

    private static Block findMap(Block b) {
        for (Node n : b.children()) {
            if (n instanceof Block && !((Block) n).isOpaque()) {
                Block child = (Block) n;
                if (child.name().equals("map") && VARIABLE.equals(child.arg(1))) {
                    return child;
                }
                Block deeper = findMap(child);
                if (deeper != null) {
                    return deeper;
                }
            }
        }
        return null;
    }

    /** An access_log (not "off") somewhere at http level: in the http block, or at the top of an included file. */
    private static boolean hasHttpAccessLog(RemoteConfig config) {
        for (ConfigFile f : config.files()) {
            if (anyAccessLog(f.root())) {
                return true;
            }
            for (Block http : f.root().blocks("http")) {
                if (!http.isOpaque() && anyAccessLog(http)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean anyAccessLog(Block b) {
        return !b.directives("access_log").isEmpty();
    }

    /**
     * Walks every server and location block.
     *
     * @return {blocks needing the line in editable files (added when {@code modify}), blocks needing it in
     *         read-only files, blocks with logging off}
     */
    private static int[] scan(RemoteConfig config, boolean modify) {
        int[] counts = new int[3];
        for (ConfigFile f : config.files()) {
            boolean editable = config.readOnlyReason(f) == null;
            walk(f.root(), editable, modify, counts);
        }
        return counts;
    }

    private static void walk(Block block, boolean editable, boolean modify, int[] counts) {
        for (Node n : new ArrayList<>(block.children())) {
            if (!(n instanceof Block) || ((Block) n).isOpaque()) {
                continue;
            }
            Block child = (Block) n;
            if (child.name().equals("server") || child.name().equals("location")) {
                List<Directive> logs = child.directives("access_log");
                boolean own = logs.stream().anyMatch(d -> !"off".equals(d.arg(0)));
                boolean ours = logs.stream().anyMatch(CacheStatsLogging::isOurs);
                if (!logs.isEmpty() && !own) {
                    counts[2]++;
                } else if (own && !ours) {
                    if (!editable) {
                        counts[1]++;
                    } else {
                        counts[0]++;
                        if (modify) {
                            Directive last = logs.get(logs.size() - 1);
                            child.insert(child.children().indexOf(last) + 1, ourLine());
                        }
                    }
                }
            }
            walk(child, editable, modify, counts);
        }
    }

    private static int strip(Block block) {
        int removed = 0;
        for (Node n : new ArrayList<>(block.children())) {
            boolean drop = isOurs(n)
                    || (n instanceof Directive && n.name().equals("log_format") && FORMAT.equals(n.arg(0)))
                    || (n instanceof Block && n.name().equals("map") && VARIABLE.equals(n.arg(1)));
            if (drop) {
                block.remove(n);
                removed++;
            } else if (n instanceof Block && !((Block) n).isOpaque()) {
                removed += strip((Block) n);
            }
        }
        return removed;
    }
}
