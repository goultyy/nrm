package mt.su.nrm.ssh;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads which IP addresses a server has and which ports nginx is listening on, so the Cloudflare wizard can suggest
 * them. Both are read-only commands that go through the session (and so the command log). Missing information is
 * never an error: the wizard still works with the user typing the values.
 */
public final class NetworkService {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    /** {@code ip -o addr show}: "2: eth0    inet 10.0.0.5/24 brd ... scope global eth0". */
    private static final Pattern IP_LINE =
            Pattern.compile("^\\d+:\\s+(\\S+)\\s+(inet6?)\\s+([0-9a-fA-F:.]+)/\\d+(?:\\s.*?scope\\s+(\\S+))?.*$");
    private static final Pattern IP_TOKEN = Pattern.compile("[0-9a-fA-F:.]+");

    private NetworkService() {
    }

    /** One address of the server. {@code scope} is the kernel's word: global, host (loopback) or link. */
    public record LocalAddress(String address, boolean ipv6, String iface, String scope) {

        public boolean isLoopback() {
            return address.startsWith("127.") || address.equals("::1");
        }

        public boolean isLinkLocal() {
            String a = address.toLowerCase(Locale.ROOT);
            return scope.equals("link") || a.startsWith("169.254.") || a.startsWith("fe80:");
        }

        /** Private ranges: not reachable from the internet unless the router forwards to them. */
        public boolean isPrivate() {
            String a = address.toLowerCase(Locale.ROOT);
            if (ipv6) {
                return a.startsWith("fc") || a.startsWith("fd");
            }
            String[] p = a.split("\\.");
            if (p.length != 4) {
                return false;
            }
            try {
                int first = Integer.parseInt(p[0]);
                int second = Integer.parseInt(p[1]);
                return first == 10 || (first == 172 && second >= 16 && second <= 31)
                        || (first == 192 && second == 168) || (first == 100 && second >= 64 && second <= 127);
            } catch (NumberFormatException e) {
                return false;
            }
        }

        /** "loopback", "private" or "public", for the label next to a suggestion. */
        public String kind() {
            return isLoopback() ? "loopback" : isLinkLocal() ? "link-local" : isPrivate() ? "private" : "public";
        }
    }

    /** One listening socket; {@code nginx} is true when the process behind it is known to be nginx. */
    public record ListeningPort(String address, int port, boolean nginx) {
    }

    /**
     * @param processKnown false when the process names could not be read (no root rights), in which case every
     *                     listening port is listed and none is marked as nginx's
     */
    public record Facts(List<LocalAddress> addresses, List<ListeningPort> ports, boolean processKnown) {

        public static final Facts EMPTY = new Facts(List.of(), List.of(), false);

        /** The ports nginx listens on, lowest first. */
        public List<Integer> nginxPorts() {
            TreeSet<Integer> found = new TreeSet<>();
            ports.stream().filter(ListeningPort::nginx).forEach(p -> found.add(p.port()));
            return List.copyOf(found);
        }

        /** Every port something listens on, lowest first (used when nginx's own can't be told apart). */
        public List<Integer> allPorts() {
            TreeSet<Integer> found = new TreeSet<>();
            ports.forEach(p -> found.add(p.port()));
            return List.copyOf(found);
        }
    }

    /** Reads the server's addresses and listening ports; anything that can't be read is simply left out. */
    public static Facts read(SshSession session) {
        List<LocalAddress> addresses = List.of();
        try {
            CommandResult r = session.exec("ip -o addr show 2>/dev/null || hostname -I 2>/dev/null", TIMEOUT);
            addresses = parseAddresses(r.stdout());
        } catch (IOException ignored) {
            // The wizard works without suggestions.
        }
        List<ListeningPort> ports = List.of();
        boolean known = false;
        try {
            // Process names of root-owned sockets (nginx's master) are only visible with root rights.
            CommandResult r = session.execPrivileged("ss -ltnp", TIMEOUT);
            if (r.ok()) {
                ports = parsePorts(r.stdout());
                known = true;
            }
        } catch (IOException ignored) {
            // Fall through to the plain listing.
        }
        if (!known) {
            try {
                ports = parsePorts(session.exec("ss -ltn", TIMEOUT).stdout());
            } catch (IOException ignored) {
                // No ports to suggest.
            }
        }
        return new Facts(addresses, ports, known);
    }

    /** Understands {@code ip -o addr show} and the plain list of {@code hostname -I}. */
    static List<LocalAddress> parseAddresses(String output) {
        Map<String, LocalAddress> found = new LinkedHashMap<>();
        for (String line : output.split("\\r?\\n")) {
            String text = line.strip();
            if (text.isEmpty()) {
                continue;
            }
            Matcher m = IP_LINE.matcher(text);
            if (m.matches()) {
                String address = m.group(3);
                found.putIfAbsent(address, new LocalAddress(address, m.group(2).equals("inet6"), m.group(1),
                        m.group(4) == null ? "global" : m.group(4)));
                continue;
            }
            for (String token : text.split("\\s+")) {
                if (IP_TOKEN.matcher(token).matches() && (token.contains(".") || token.contains(":"))) {
                    found.putIfAbsent(token, new LocalAddress(token, token.contains(":"), "", "global"));
                }
            }
        }
        return List.copyOf(found.values());
    }

    /** Understands {@code ss -ltn} and {@code ss -ltnp}; only listening sockets are returned. */
    static List<ListeningPort> parsePorts(String output) {
        Map<String, ListeningPort> found = new LinkedHashMap<>();
        for (String line : output.split("\\r?\\n")) {
            String[] parts = line.strip().split("\\s+");
            if (parts.length < 5 || !parts[0].equals("LISTEN")) {
                continue;
            }
            String local = parts[3];
            int colon = local.lastIndexOf(':');
            if (colon < 0) {
                continue;
            }
            int port;
            try {
                port = Integer.parseInt(local.substring(colon + 1));
            } catch (NumberFormatException e) {
                continue;
            }
            String address = local.substring(0, colon).replace("[", "").replace("]", "");
            int percent = address.indexOf('%');
            if (percent >= 0) {
                address = address.substring(0, percent);
            }
            boolean nginx = line.contains("\"nginx\"");
            String key = address + "/" + port;
            ListeningPort existing = found.get(key);
            if (existing == null || (nginx && !existing.nginx())) {
                found.put(key, new ListeningPort(address, port, nginx));
            }
        }
        return List.copyOf(new ArrayList<>(found.values()));
    }
}
