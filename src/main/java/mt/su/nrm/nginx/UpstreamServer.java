package mt.su.nrm.nginx;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One server in a load balancing group, as separate fields instead of nginx's one-line syntax
 * ({@code 10.0.0.1:8080 weight=3 backup}). Parameters the fields don't cover are kept in
 * {@link #extra} and written back unchanged.
 */
public final class UpstreamServer {

    public enum Role {
        ACTIVE("Active (takes traffic)"), BACKUP("Backup (only if the others fail)"), DOWN("Down (switched off)");

        private final String label;

        Role(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]+");

    /** Host name or IP address (IPv6 without brackets), or a {@code unix:/path} socket. */
    public String address = "";
    /** 1 to 65535, or empty (nginx then uses port 80). */
    public String port = "";
    /** Relative share of the traffic; empty is 1. */
    public String weight = "";
    public Role role = Role.ACTIVE;
    /** Failed attempts before the server is considered unavailable; empty is nginx's default (1). */
    public String maxFails = "";
    /** How long it stays unavailable, e.g. {@code 30s}; empty is nginx's default (10s). */
    public String failTimeout = "";
    /** Other parameters, kept exactly. */
    public final List<String> extra = new ArrayList<>();

    /** Reads one {@code server} line's arguments. */
    public static UpstreamServer parse(String line) {
        List<String> tokens = Arg.parseValues(line);
        UpstreamServer s = new UpstreamServer();
        if (tokens.isEmpty()) {
            return s;
        }
        String first = tokens.get(0);
        if (first.startsWith("unix:")) {
            s.address = first;
        } else if (first.startsWith("[")) {
            int close = first.indexOf(']');
            s.address = close < 0 ? first : first.substring(1, close);
            if (close >= 0 && first.length() > close + 2 && first.charAt(close + 1) == ':') {
                s.port = first.substring(close + 2);
            }
        } else {
            int colon = first.lastIndexOf(':');
            if (colon > 0 && first.indexOf(':') == colon) {
                s.address = first.substring(0, colon);
                s.port = first.substring(colon + 1);
            } else {
                s.address = first;
            }
        }
        for (String t : tokens.subList(1, tokens.size())) {
            if (t.startsWith("weight=")) {
                s.weight = t.substring(7);
            } else if (t.startsWith("max_fails=")) {
                s.maxFails = t.substring(10);
            } else if (t.startsWith("fail_timeout=")) {
                s.failTimeout = t.substring(13);
            } else if (t.equals("backup")) {
                s.role = Role.BACKUP;
            } else if (t.equals("down")) {
                s.role = Role.DOWN;
            } else {
                s.extra.add(t);
            }
        }
        return s;
    }

    /** The arguments of the {@code server} directive. */
    public String format() {
        List<String> parts = new ArrayList<>();
        String host = address.strip();
        String p = port.strip();
        if (host.startsWith("unix:")) {
            parts.add(host);
        } else if (host.contains(":")) {
            parts.add("[" + host + "]" + (p.isEmpty() ? "" : ":" + p));
        } else {
            parts.add(host + (p.isEmpty() ? "" : ":" + p));
        }
        if (!weight.isBlank()) {
            parts.add("weight=" + weight.strip());
        }
        if (!maxFails.isBlank()) {
            parts.add("max_fails=" + maxFails.strip());
        }
        if (!failTimeout.isBlank()) {
            parts.add("fail_timeout=" + failTimeout.strip());
        }
        if (role == Role.BACKUP) {
            parts.add("backup");
        } else if (role == Role.DOWN) {
            parts.add("down");
        }
        parts.addAll(extra);
        List<String> quoted = new ArrayList<>();
        for (String part : parts) {
            quoted.add(Arg.of(part).raw());
        }
        return String.join(" ", quoted);
    }

    /** True if nothing has been filled in. */
    public boolean isBlank() {
        return address.isBlank() && port.isBlank() && weight.isBlank() && maxFails.isBlank() && failTimeout.isBlank();
    }

    /** What is wrong with this server, worded for the user; empty if fine. */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        String a = address.strip();
        if (a.isEmpty()) {
            problems.add("Every server needs an address.");
        } else if (a.startsWith("unix:")) {
            if (a.length() < 7 || a.charAt(5) != '/' || !port.isBlank()) {
                problems.add("A unix socket looks like unix:/run/app.sock and has no port.");
            }
        } else if (a.contains(":") ? !IPV6.matcher(a).matches() : !HOST.matcher(a).matches()) {
            problems.add("\"" + a + "\" is not a valid address.");
        }
        if (!port.isBlank() && !(port.strip().matches("\\d{1,5}") && Integer.parseInt(port.strip()) >= 1
                && Integer.parseInt(port.strip()) <= 65535)) {
            problems.add("The port must be a number from 1 to 65535.");
        }
        if (!weight.isBlank() && !weight.strip().matches("[1-9]\\d{0,5}")) {
            problems.add("The weight must be a whole number of 1 or more.");
        }
        if (!maxFails.isBlank() && !maxFails.strip().matches("\\d{1,4}")) {
            problems.add("Failures allowed must be a whole number (0 means never mark as failed).");
        }
        if (!failTimeout.isBlank() && !failTimeout.strip().matches("\\d+(ms|s|m|h|d)?")) {
            problems.add("The fail timeout must look like 30s or 2m.");
        }
        return problems;
    }
}
