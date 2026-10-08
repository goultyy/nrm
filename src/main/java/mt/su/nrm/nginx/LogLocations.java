package mt.su.nrm.nginx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the log files nginx writes, from the loaded configuration: the general access and error logs
 * (main config and http block, or the usual defaults) and any log a virtual host sets for itself.
 * Pure configuration reading; nothing is fetched from the server.
 */
public final class LogLocations {

    /** Where the packaged nginx normally writes when the configuration doesn't say. */
    public static final String DEFAULT_ACCESS = "/var/log/nginx/access.log";
    public static final String DEFAULT_ERROR = "/var/log/nginx/error.log";

    /**
     * @param group the virtual host's name, or "General"
     * @param error true for an error log, false for an access log
     * @param path  the log file on the server
     */
    public record LogSource(String group, boolean error, String path) {
        public String label() {
            return group + " - " + (error ? "error log" : "access log") + "  (" + path + ")";
        }

        @Override
        public String toString() {
            return label();
        }
    }

    private LogLocations() {
    }

    /** All log files the configuration names, general ones first, each path once. */
    public static List<LogSource> discover(RemoteConfig config) {
        Map<String, LogSource> found = new LinkedHashMap<>();
        boolean generalAccess = false;
        boolean generalError = false;
        if (config != null) {
            Block root = config.mainFile().root();
            for (Directive d : root.directives("error_log")) {
                generalError |= add(found, "General", true, d.arg(0));
            }
            for (Block http : root.blocks("http")) {
                for (Directive d : http.directives("access_log")) {
                    generalAccess |= add(found, "General", false, d.arg(0));
                }
                for (Directive d : http.directives("error_log")) {
                    generalError |= add(found, "General", true, d.arg(0));
                }
            }
        }
        if (!generalAccess) {
            add(found, "General", false, DEFAULT_ACCESS);
        }
        if (!generalError) {
            add(found, "General", true, DEFAULT_ERROR);
        }
        if (config != null) {
            for (VirtualHost host : config.virtualHosts()) {
                VhostSettings s = host.read();
                String name = s.serverNames.isEmpty() ? host.displayName() : s.serverNames.get(0);
                if (name.equals("_")) {
                    name = "default";
                }
                for (String line : s.accessLogs) {
                    List<String> v = Arg.parseValues(line);
                    if (!v.isEmpty()) {
                        add(found, name, false, v.get(0));
                    }
                }
                if (!s.errorLog.isBlank()) {
                    List<String> v = Arg.parseValues(s.errorLog);
                    if (!v.isEmpty()) {
                        add(found, name, true, v.get(0));
                    }
                }
            }
        }
        return new ArrayList<>(found.values());
    }

    /** True if the path is a real file path (not off, stderr, syslog or a variable). */
    static boolean isFile(String path) {
        return path != null && path.startsWith("/") && !path.contains("$") && !path.contains("..")
                && path.chars().allMatch(c -> c > 0x20 && c < 0x7f && c != ';' && c != '\'' && c != '"' && c != '\\');
    }

    private static boolean add(Map<String, LogSource> found, String group, boolean error, String path) {
        if (!isFile(path) || found.containsKey(path + (error ? "#e" : "#a"))) {
            return isFile(path);
        }
        found.put(path + (error ? "#e" : "#a"), new LogSource(group, error, path));
        return true;
    }
}
