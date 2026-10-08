package mt.su.nrm.nginx;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** A load balancing group: an {@code upstream} block with its servers. */
public final class UpstreamSettings {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final Pattern ADDRESS = Pattern.compile(
            "(\\[[0-9A-Fa-f:.]+\\]|[A-Za-z0-9_.-]+)(:\\d{1,5})?|unix:/\\S+");
    private static final Pattern METHOD = Pattern.compile("least_conn|ip_hash|random(\\s.*)?|least_time\\s.*|hash\\s\\S.*");

    public String name = "";
    /** Empty for round robin, or one of {@code least_conn}, {@code ip_hash}, {@code random}, {@code hash $key [consistent]}. */
    public String method = "";
    /** Arguments of each {@code server}, e.g. {@code 10.0.0.1:8080 weight=3 backup}. */
    public List<String> servers = new ArrayList<>();
    /** Idle connections kept open to the servers; empty for not set. */
    public String keepalive = "";

    public UpstreamSettings copy() {
        UpstreamSettings c = new UpstreamSettings();
        c.name = name;
        c.method = method;
        c.servers = new ArrayList<>(servers);
        c.keepalive = keepalive;
        return c;
    }

    /** What is wrong with these settings, worded for the user; empty if fine. */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (!NAME.matcher(name).matches()) {
            problems.add("The name must use only letters, digits, dots, dashes and underscores.");
        }
        if (servers.isEmpty()) {
            problems.add("Add at least one server.");
        }
        for (String line : servers) {
            List<String> v = Arg.parseValues(line);
            if (v.isEmpty() || !ADDRESS.matcher(v.get(0)).matches()) {
                problems.add("\"" + line + "\" must start with an address such as 10.0.0.1:8080.");
                continue;
            }
            for (String param : v.subList(1, v.size())) {
                if (param.startsWith("weight=") || param.startsWith("max_fails=") || param.startsWith("max_conns=")) {
                    if (!param.substring(param.indexOf('=') + 1).matches("\\d+")) {
                        problems.add("\"" + param + "\" needs a number.");
                    }
                } else if (param.startsWith("fail_timeout=") || param.startsWith("slow_start=")) {
                    if (!param.substring(param.indexOf('=') + 1).matches("\\d+(ms|s|m|h|d)?")) {
                        problems.add("\"" + param + "\" needs a time such as 30s.");
                    }
                } else if (!param.equals("backup") && !param.equals("down") && !param.contains("=")) {
                    problems.add("\"" + param + "\" is not a server parameter (weight=, max_fails=, fail_timeout=, backup, down).");
                }
            }
        }
        if (!method.isBlank() && !METHOD.matcher(method.strip()).matches()) {
            problems.add("The balancing method must be least_conn, ip_hash, random or hash $key.");
        }
        if (!keepalive.isBlank() && !keepalive.strip().matches("\\d+")) {
            problems.add("Keepalive must be a number.");
        }
        for (String s : servers) {
            if (s.chars().anyMatch(c -> c < 0x20)) {
                problems.add("A server line contains invalid characters.");
            }
        }
        return problems;
    }

    /** The block as it would be written to nginx.conf, for the review step of the wizard. */
    public String preview() {
        StringBuilder sb = new StringBuilder("upstream ").append(name).append(" {\n");
        if (!method.isBlank()) {
            sb.append("    ").append(method.strip()).append(";\n");
        }
        for (String s : servers) {
            sb.append("    server ").append(s).append(";\n");
        }
        if (!keepalive.isBlank()) {
            sb.append("    keepalive ").append(keepalive.strip()).append(";\n");
        }
        return sb.append("}").toString();
    }
}
