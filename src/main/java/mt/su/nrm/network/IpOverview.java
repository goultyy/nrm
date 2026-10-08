package mt.su.nrm.network;

import mt.su.nrm.nginx.ListenEndpoint;
import mt.su.nrm.nginx.RemoteConfig;
import mt.su.nrm.nginx.VhostSettings;
import mt.su.nrm.nginx.VirtualHost;
import mt.su.nrm.ssh.NetworkService.Facts;
import mt.su.nrm.ssh.NetworkService.ListeningPort;
import mt.su.nrm.ssh.NetworkService.LocalAddress;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Puts a server's addresses next to what nginx does with them: the ports it is really listening on (from the
 * server) and the ones its configuration asks for (from the loaded files), and says where the two disagree. Pure
 * matching rules over data already read; nothing here touches a server.
 * <p>
 * How nginx reads {@code listen}: no address, {@code *} or {@code 0.0.0.0} is every IPv4 address; {@code [::]} is
 * every IPv6 address (IPv4 is not included); anything else is that one address.
 */
public final class IpOverview {

    /** One {@code listen} of one site, in the loaded configuration. */
    public record Configured(String site, ListenEndpoint endpoint, boolean ssl) {
    }

    /**
     * One address of the server.
     *
     * @param listening     ports nginx is listening on at this address now (every listener there if nginx's own can't
     *                      be told apart, see {@code listeningCertain})
     * @param configured    the sites whose {@code listen} covers this address
     */
    public record Row(LocalAddress address, List<Integer> listening, boolean listeningCertain,
                      List<Configured> configured) {
    }

    /** @param warnings places where the running server and the configuration disagree, or that are easy to miss */
    public record Overview(List<Row> rows, List<String> warnings) {
    }

    private IpOverview() {
    }

    /** The {@code listen} lines of every site in the loaded configuration. */
    public static List<Configured> configuredFrom(RemoteConfig config) {
        List<Configured> list = new ArrayList<>();
        if (config == null) {
            return list;
        }
        for (VirtualHost host : config.virtualHosts()) {
            VhostSettings s = host.read();
            String name = s.serverNames.isEmpty() ? host.displayName() : s.serverNames.get(0);
            for (VhostSettings.ListenSpec l : s.listens) {
                ListenEndpoint.parse(l.endpoint).ifPresent(e -> list.add(new Configured(name, e, l.ssl())));
            }
        }
        return list;
    }

    public static Overview build(Facts facts, List<Configured> configured) {
        List<Row> rows = new ArrayList<>();
        for (LocalAddress a : facts.addresses()) {
            Set<Integer> listening = new TreeSet<>();
            for (ListeningPort p : facts.ports()) {
                if ((p.nginx() || !facts.processKnown()) && covers(p.address(), a)) {
                    listening.add(p.port());
                }
            }
            List<Configured> sites = new ArrayList<>();
            for (Configured c : configured) {
                if (covers(hostOf(c.endpoint()), a)) {
                    sites.add(c);
                }
            }
            rows.add(new Row(a, List.copyOf(listening), facts.processKnown(), List.copyOf(sites)));
        }
        rows.sort(java.util.Comparator.comparingInt((Row r) -> order(r.address())).thenComparing(r -> r.address().ipv6())
                .thenComparing(r -> r.address().address()));
        return new Overview(List.copyOf(rows), warnings(facts, configured));
    }

    // ---------------------------------------------------------------- matching

    /** Whether a listening/listen address (as nginx or ss writes it) takes in this local address. */
    static boolean covers(String listenHost, LocalAddress a) {
        String h = normalize(listenHost);
        if (h.equals("*")) {
            return true;
        }
        if (h.equals("0.0.0.0") || h.isEmpty()) {
            return !a.ipv6();
        }
        if (h.equals("::")) {
            return a.ipv6();
        }
        return h.equals(a.address().toLowerCase(Locale.ROOT));
    }

    private static String hostOf(ListenEndpoint e) {
        return e.host();
    }

    /** Brackets, a zone suffix and case removed: {@code [::1]} and {@code ::1} are the same address. */
    static String normalize(String host) {
        String h = host == null ? "" : host.strip().toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        int zone = h.indexOf('%');
        return zone >= 0 ? h.substring(0, zone) : h;
    }

    private static boolean isWildcard(String host) {
        String h = normalize(host);
        return h.isEmpty() || h.equals("*") || h.equals("0.0.0.0") || h.equals("::");
    }

    private static boolean isIpv6Wildcard(String host) {
        return normalize(host).equals("::");
    }

    private static boolean isIpLiteral(String host) {
        String h = normalize(host);
        return h.matches("[0-9.]+") || h.contains(":");
    }

    private static int order(LocalAddress a) {
        return switch (a.kind()) {
            case "public" -> 0;
            case "private" -> 1;
            case "loopback" -> 2;
            default -> 3;
        };
    }

    // ---------------------------------------------------------------- where they disagree

    static List<String> warnings(Facts facts, List<Configured> configured) {
        Set<String> out = new LinkedHashSet<>();
        boolean haveAddresses = !facts.addresses().isEmpty();
        boolean haveSockets = !facts.ports().isEmpty() && facts.processKnown();

        for (Configured c : configured) {
            String host = c.endpoint().host();
            String where = describe(c.endpoint());
            // An address nginx is told to use that this machine does not have: nginx cannot bind it.
            if (haveAddresses && !isWildcard(host) && isIpLiteral(host)
                    && facts.addresses().stream().noneMatch(a -> covers(host, a))) {
                out.add(c.site() + " listens on " + where + ", but this server has no such address. nginx cannot "
                        + "bind it, so it will fail to start or reload.");
            }
            // Asked for but not listening: not applied yet, or nginx failed to bind it.
            if (haveSockets && facts.ports().stream().noneMatch(p -> p.nginx() && p.port() == c.endpoint().port()
                    && sameListener(p.address(), host))) {
                out.add(c.site() + " is set to listen on " + where + ", but nginx is not listening there. The "
                        + "change may not be applied yet, or nginx could not bind it.");
            }
            if (!isWildcard(host) && isIpLiteral(host) && isLoopback(host)) {
                out.add(c.site() + " listens only on " + where + ", so it can be reached from this server alone.");
            }
        }

        // nginx is listening somewhere no loaded site asks for.
        if (haveSockets) {
            for (ListeningPort p : facts.ports()) {
                if (p.nginx() && configured.stream().noneMatch(c -> c.endpoint().port() == p.port())) {
                    out.add("nginx is listening on " + p.address() + ":" + p.port() + ", but no site in the loaded "
                            + "configuration asks for that port. It may come from a stream block, a file that isn't "
                            + "loaded, or an older nginx that is still running.");
                }
            }
        }

        // IPv4 only: the server has a global IPv6 address, but a port is not offered on it.
        boolean hasGlobalV6 = facts.addresses().stream().anyMatch(a -> a.ipv6() && !a.isLoopback() && !a.isLinkLocal());
        if (hasGlobalV6) {
            Set<String> reported = new LinkedHashSet<>();
            for (Configured c : configured) {
                int port = c.endpoint().port();
                boolean v6 = configured.stream().anyMatch(o -> o.endpoint().port() == port
                        && (isIpv6Wildcard(o.endpoint().host()) || (isIpLiteral(o.endpoint().host())
                        && normalize(o.endpoint().host()).contains(":"))));
                if (!v6 && reported.add(c.site() + ":" + port)) {
                    out.add(c.site() + " is offered on port " + port + " over IPv4 only. This server has an IPv6 "
                            + "address; add a listen on [::]:" + port + " to serve it there too.");
                }
            }
        }
        return List.copyOf(out);
    }

    /** Whether what ss reports and what a listen line says are the same listener. */
    private static boolean sameListener(String socketAddress, String listenHost) {
        String s = normalize(socketAddress);
        String l = normalize(listenHost);
        if (s.equals("*")) {
            return true;
        }
        boolean listenV4Wildcard = l.isEmpty() || l.equals("*") || l.equals("0.0.0.0");
        if (listenV4Wildcard) {
            return s.equals("0.0.0.0");
        }
        return s.equals(l);
    }

    private static boolean isLoopback(String host) {
        String h = normalize(host);
        return h.startsWith("127.") || h.equals("::1");
    }

    private static String describe(ListenEndpoint e) {
        String h = e.host();
        return (h.isEmpty() ? "port " : h + ":") + e.port();
    }
}
