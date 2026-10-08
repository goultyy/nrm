package mt.su.nrm.status;

import mt.su.nrm.nginx.Block;
import mt.su.nrm.nginx.ConfigFile;
import mt.su.nrm.nginx.Directive;
import mt.su.nrm.nginx.ListenEndpoint;
import mt.su.nrm.nginx.Node;
import mt.su.nrm.nginx.RemoteConfig;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The nginx side of the status page: a small {@code server} of its own that listens on this machine only and serves
 * {@code stub_status}. It is ordinary configuration that goes through the pending changes like any other edit, and
 * {@link #disable} removes exactly what {@link #enable} added.
 * <p>
 * A separate loopback server is used, rather than a location added to one of the sites, so the page can never be
 * reached from outside and can't interfere with a site or the default server.
 * <p>
 * A {@code stub_status} that someone set up by hand is found too ({@link #find}), so the page can show it; it is
 * never changed or removed.
 */
public final class StatusPageConfig {

    /** The server name that marks the server this app adds. */
    public static final String SERVER_NAME = "nrm-status";
    public static final String PATH = "/nrm_status";
    public static final int DEFAULT_PORT = 8089;

    private StatusPageConfig() {
    }

    /**
     * Where a stub_status page is served, and how to ask for it from the server itself.
     *
     * @param host       the address to connect to (always one that reaches this machine)
     * @param hostHeader the {@code Host} to send so nginx picks the right server, or null for none
     * @param ours       true if this app added it
     * @param restricted false if nothing limits who may read it (it would then be open to anyone who can reach that port)
     * @param file       the configuration file it is in
     */
    public record Endpoint(String host, int port, String path, String hostHeader, boolean ours, boolean restricted,
                           String file) {
        public String url() {
            return "http://" + host + ":" + port + path;
        }
    }

    /** What {@link #enable} did: why nothing was changed, or null. */
    public record Result(String problem) {
        public boolean ok() {
            return problem == null;
        }
    }

    // ---------------------------------------------------------------- finding

    /** The stub_status page in the configuration, preferring the one this app added. */
    public static Optional<Endpoint> find(RemoteConfig config) {
        List<Endpoint> found = new ArrayList<>();
        for (ConfigFile file : config.files()) {
            collect(file.root(), file.path(), found);
        }
        return found.stream().filter(Endpoint::ours).findFirst().or(() -> found.stream().findFirst());
    }

    private static void collect(Block block, String file, List<Endpoint> into) {
        for (Node n : block.children()) {
            if (!(n instanceof Block child) || child.isOpaque()) {
                continue;
            }
            if (child.name().equals("server")) {
                endpointOf(child, file).ifPresent(into::add);
            } else {
                collect(child, file, into);
            }
        }
    }

    private static Optional<Endpoint> endpointOf(Block server, String file) {
        Block location = null;
        for (Block candidate : server.blocks("location")) {
            if (!candidate.isOpaque() && candidate.first("stub_status") != null && pathOf(candidate) != null) {
                location = candidate;
                break;
            }
        }
        if (location == null) {
            return Optional.empty();
        }
        ListenEndpoint listen = null;
        for (Directive d : server.directives("listen")) {
            listen = ListenEndpoint.parse(d.arg(0)).orElse(null);
            if (listen != null) {
                break;
            }
        }
        if (listen == null) {
            return Optional.empty();
        }
        String host = listen.host();
        if (host.isEmpty() || host.equals("*") || host.equals("0.0.0.0") || host.equals("[::]")) {
            host = "127.0.0.1";
        }
        String hostHeader = null;
        for (Directive d : server.directives("server_name")) {
            for (String name : d.values()) {
                if (hostHeader == null && name.matches("[A-Za-z0-9][A-Za-z0-9.-]*")) {
                    hostHeader = name;
                }
            }
        }
        boolean ours = hostHeader != null && hostHeader.equals(SERVER_NAME) && PATH.equals(pathOf(location));
        boolean restricted = !location.directives("allow").isEmpty() || !location.directives("deny").isEmpty()
                || location.first("auth_basic") != null || location.first("auth_request") != null;
        return Optional.of(new Endpoint(host, listen.port(), pathOf(location), hostHeader, ours, restricted, file));
    }

    /** The URL path of a location that can be requested as written, or null for a regular expression. */
    private static String pathOf(Block location) {
        List<String> values = location.values();
        if (values.isEmpty()) {
            return null;
        }
        String first = values.get(0);
        if (first.equals("~") || first.equals("~*")) {
            return null;
        }
        String path = values.get(values.size() - 1);
        return path.startsWith("/") ? path : null;
    }

    // ---------------------------------------------------------------- enabling and disabling

    /** Adds the status server, listening on 127.0.0.1 at {@code port}. */
    public static Result enable(RemoteConfig config, int port, Collection<Integer> portsInUse) {
        String reason = config.mainReadOnlyReason();
        if (reason != null) {
            return new Result(reason);
        }
        List<Block> https = config.mainFile().root().blocks("http");
        if (https.isEmpty()) {
            return new Result("The main nginx.conf has no http block.");
        }
        Optional<Endpoint> existing = find(config);
        if (existing.isPresent()) {
            return new Result("A stub_status page already exists in " + existing.get().file() + ".");
        }
        List<String> problems = portProblems(port, portsInUse);
        if (!problems.isEmpty()) {
            return new Result(String.join(" ", problems));
        }
        Block server = Block.create("server", List.of());
        https.get(0).add(server);
        // Attached first so the lines inside pick up the right indentation.
        server.add(Directive.create("listen", List.of("127.0.0.1:" + port)));
        server.add(Directive.create("server_name", List.of(SERVER_NAME)));
        server.add(Directive.create("access_log", List.of("off")));
        Block location = Block.create("location", List.of("=", PATH));
        server.add(location);
        location.add(Directive.create("stub_status", List.of()));
        location.add(Directive.create("allow", List.of("127.0.0.1")));
        location.add(Directive.create("deny", List.of("all")));
        return new Result(null);
    }

    /** Removes the status server this app added; returns how many were removed. A hand-made one is left alone. */
    public static int disable(RemoteConfig config) {
        int removed = 0;
        for (ConfigFile file : config.files()) {
            if (config.readOnlyReason(file) == null) {
                removed += strip(file.root(), file.path());
            }
        }
        return removed;
    }

    private static int strip(Block block, String file) {
        int removed = 0;
        for (Node n : new ArrayList<>(block.children())) {
            if (!(n instanceof Block child) || child.isOpaque()) {
                continue;
            }
            if (child.name().equals("server")) {
                if (endpointOf(child, file).map(Endpoint::ours).orElse(false)) {
                    block.remove(child);
                    removed++;
                }
            } else {
                removed += strip(child, file);
            }
        }
        return removed;
    }

    // ---------------------------------------------------------------- the port

    /** Every port a {@code listen} line in the configuration uses. */
    public static java.util.Set<Integer> listenPorts(RemoteConfig config) {
        java.util.Set<Integer> ports = new java.util.TreeSet<>();
        for (ConfigFile file : config.files()) {
            gatherPorts(file.root(), ports);
        }
        return ports;
    }

    private static void gatherPorts(Block block, java.util.Set<Integer> into) {
        for (Node n : block.children()) {
            if (n instanceof Directive d && d.name().equals("listen")) {
                ListenEndpoint.parse(d.arg(0)).ifPresent(e -> into.add(e.port()));
            } else if (n instanceof Block child && !child.isOpaque()) {
                gatherPorts(child, into);
            }
        }
    }

    /** What is wrong with the port, worded for the user; empty if it can be used. */
    public static List<String> portProblems(int port, Collection<Integer> portsInUse) {
        List<String> problems = new ArrayList<>();
        if (port < 1 || port > 65535) {
            problems.add("The port must be between 1 and 65535.");
        } else if (portsInUse.contains(port)) {
            problems.add("Port " + port + " is already in use on this server.");
        }
        return problems;
    }

    /** The first port from {@link #DEFAULT_PORT} up that isn't in use. */
    public static int suggestPort(Collection<Integer> portsInUse) {
        int port = DEFAULT_PORT;
        while (portsInUse.contains(port) && port < 65535) {
            port++;
        }
        return port;
    }
}
