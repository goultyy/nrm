package mt.su.nrm.nginx;

import mt.su.nrm.nginx.VhostSettings.HeaderSpec;
import mt.su.nrm.nginx.VhostSettings.ListenSpec;
import mt.su.nrm.nginx.VhostSettings.RuleSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A {@code server} block viewed as a virtual host. {@link #read()} extracts the settings the
 * editor manages; {@link #apply} writes changes back into the tree, editing only the statements
 * whose values changed. Everything the editor doesn't manage (other directives, comments, nested
 * blocks, formatting) stays exactly as it was.
 */
public final class VirtualHost {

    /** Where new server-level directives go: after the last managed directive that sorts before them. */
    private static final List<String> SERVER_ORDER = List.of("listen", "server_name", "root", "index",
            "ssl_certificate", "ssl_certificate_key", "ssl_protocols", "ssl_ciphers", "set_real_ip_from",
            "real_ip_header", "real_ip_recursive", "client_max_body_size",
            "limit_rate", "limit_req", "limit_conn", "access_log", "error_log", "error_page", "add_header", "rewrite",
            "return");

    private static final List<String> STATIC_ORDER = List.of("root", "alias", "index", "try_files");
    private static final List<String> PROXY_ORDER = List.of("proxy_pass", "proxy_set_header");
    private static final List<String> FASTCGI_ORDER = List.of("include", "fastcgi_pass", "fastcgi_param",
            "fastcgi_read_timeout", "try_files");
    private static final List<String> REDIRECT_ORDER = List.of("return");
    private static final List<String> ACCESS_NAMES = List.of("allow", "deny");
    private static final List<String> LOCATION_ORDER = List.of("root", "alias", "proxy_pass", "return", "index",
            "try_files", "include", "fastcgi_pass", "fastcgi_param", "fastcgi_read_timeout",
            "proxy_set_header", "proxy_cache", "proxy_cache_valid", "proxy_read_timeout", "satisfy", "auth_basic",
            "auth_basic_user_file", "allow", "deny", "limit_req", "limit_conn", "gzip", "gzip_types",
            "gzip_min_length", "gzip_comp_level", "error_page");

    private static final Set<String> MODIFIERS = Set.of("=", "~", "~*", "^~");
    private static final Set<String> REDIRECT_CODES = Set.of("301", "302", "303", "307", "308");

    private final ConfigFile file;
    private final Block server;

    public VirtualHost(ConfigFile file, Block server) {
        this.file = file;
        this.server = server;
    }

    /** Adds an empty {@code server} block to the file and returns it as a virtual host. */
    public static VirtualHost createNew(ConfigFile file) {
        Block block = Block.create("server", List.of());
        file.root().add(block);
        if (file.root().children.size() == 1) {
            block.leading = "";
        }
        return new VirtualHost(file, block);
    }

    public ConfigFile file() {
        return file;
    }

    /**
     * {@code RemoteConfig.virtualHosts()} builds fresh wrappers on every call, so two wrappers are the
     * same virtual host when they wrap the same server block.
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof VirtualHost v && v.server == server;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(server);
    }

    public Block block() {
        return server;
    }

    /** The first server name, or a placeholder for a server without one. */
    public String displayName() {
        Directive names = server.first("server_name");
        return names == null || names.args.isEmpty() ? "(no server_name)" : names.arg(0);
    }

    /** Removes this server block from its file. */
    public void remove() {
        server.parent.remove(server);
    }

    /** Heads of the statements in this server block that the editor does not manage. */
    public List<String> unmanaged() {
        List<String> result = new ArrayList<>();
        for (Node n : server.children) {
            boolean managed = n instanceof Directive
                    ? SERVER_ORDER.contains(n.name)
                    : n.name.equals("location");
            if (!managed) {
                result.add(n.head().strip());
            }
        }
        return result;
    }

    // ------------------------------------------------------------------------------- reading

    public VhostSettings read() {
        VhostSettings s = new VhostSettings();
        for (Directive d : server.directives("server_name")) {
            s.serverNames.addAll(d.values());
        }
        for (Directive d : server.directives("listen")) {
            ListenSpec spec = new ListenSpec();
            List<String> v = d.values();
            spec.endpoint = v.isEmpty() ? "" : v.get(0);
            spec.params.addAll(v.subList(Math.min(1, v.size()), v.size()));
            s.listens.add(spec);
        }
        s.root = single(server, "root");
        s.index = joined(server.first("index"));
        s.sslCertificate = single(server, "ssl_certificate");
        s.sslCertificateKey = single(server, "ssl_certificate_key");
        s.sslProtocols = joined(server.first("ssl_protocols"));
        s.sslCiphers = single(server, "ssl_ciphers");
        s.realIp = readRealIp();
        s.clientMaxBodySize = single(server, "client_max_body_size");
        s.limitRate = single(server, "limit_rate");
        server.directives("limit_req").forEach(d -> s.limitReq.add(Arg.displayJoin(d.args)));
        server.directives("limit_conn").forEach(d -> s.limitConn.add(Arg.displayJoin(d.args)));
        server.directives("access_log").forEach(d -> s.accessLogs.add(Arg.displayJoin(d.args)));
        s.errorLog = joined(server.first("error_log"));
        server.directives("error_page").forEach(d -> s.errorPages.add(Arg.displayJoin(d.args)));
        for (Directive d : server.directives("add_header")) {
            s.headers.add(HeaderSpec.fromValues(d.values()));
        }
        for (Node n : server.children) {
            if (n instanceof Directive && (n.name.equals("rewrite") || n.name.equals("return"))) {
                s.rewrites.add(new RuleSpec(n.name, Arg.displayJoin(n.args)));
            }
        }
        for (Block loc : server.blocks("location")) {
            s.locations.add(readLocation(loc));
        }
        return s;
    }

    /** The real-IP statements in this server block, or null if it has none. */
    private VhostSettings.RealIpSpec readRealIp() {
        List<Directive> from = server.directives("set_real_ip_from");
        Directive header = server.first("real_ip_header");
        Directive recursive = server.first("real_ip_recursive");
        if (from.isEmpty() && header == null && recursive == null) {
            return null;
        }
        VhostSettings.RealIpSpec spec = new VhostSettings.RealIpSpec();
        for (Directive d : from) {
            if (d.arg(0) != null && !spec.sources.contains(d.arg(0))) {
                spec.sources.add(d.arg(0));
            }
        }
        spec.header = header == null || header.arg(0) == null ? "" : header.arg(0);
        spec.recursive = recursive != null && "on".equals(recursive.arg(0));
        spec.recursiveExplicit = recursive != null;
        return spec;
    }

    private void syncRealIp(VhostSettings.RealIpSpec spec) {
        if (spec == null || spec.isEmpty()) {
            for (String name : List.of("set_real_ip_from", "real_ip_header", "real_ip_recursive")) {
                syncList(server, name, List.of(), SERVER_ORDER);
            }
            return;
        }
        syncList(server, "set_real_ip_from", spec.sources.stream().map(List::of).toList(), SERVER_ORDER);
        syncFirstArg(server, "real_ip_header", spec.header, SERVER_ORDER);
        if (spec.recursive) {
            syncFirstArg(server, "real_ip_recursive", "on", SERVER_ORDER);
        } else if (spec.recursiveExplicit) {
            syncFirstArg(server, "real_ip_recursive", "off", SERVER_ORDER);
        } else {
            syncList(server, "real_ip_recursive", List.of(), SERVER_ORDER);
        }
    }

    private static LocationSettings readLocation(Block loc) {
        LocationSettings l = new LocationSettings();
        l.source = loc;
        List<String> header = loc.values();
        if (header.size() >= 2 && MODIFIERS.contains(header.get(0))) {
            l.modifier = header.get(0);
            l.path = header.get(1);
        } else {
            l.path = header.isEmpty() ? "" : header.get(0);
        }
        l.root = single(loc, "root");
        l.alias = single(loc, "alias");
        l.index = joined(loc.first("index"));
        l.tryFiles = joined(loc.first("try_files"));
        l.proxyPass = single(loc, "proxy_pass");
        for (Directive d : loc.directives("proxy_set_header")) {
            List<String> v = d.values();
            l.proxySetHeaders.add(v.size() > 1 ? v.get(0) + " " + v.get(1) : String.join(" ", v));
        }
        l.fastcgiPass = single(loc, "fastcgi_pass");
        Directive fcgiInclude = fastcgiInclude(loc);
        l.fastcgiInclude = fcgiInclude == null ? "" : fcgiInclude.arg(0);
        for (Directive d : loc.directives("fastcgi_param")) {
            List<String> v = d.values();
            l.fastcgiParams.add(v.size() > 1 ? v.get(0) + " " + String.join(" ", v.subList(1, v.size())) : String.join(" ", v));
        }
        l.fastcgiReadTimeout = single(loc, "fastcgi_read_timeout");
        l.authBasic = single(loc, "auth_basic");
        l.authBasicUserFile = single(loc, "auth_basic_user_file");
        l.satisfy = single(loc, "satisfy");
        for (Node n : loc.children) {
            if (n instanceof Directive && ACCESS_NAMES.contains(n.name)) {
                l.accessRules.add(n.name + " " + Arg.displayJoin(n.args));
            }
        }
        loc.directives("limit_req").forEach(d -> l.limitReq.add(Arg.displayJoin(d.args)));
        loc.directives("limit_conn").forEach(d -> l.limitConn.add(Arg.displayJoin(d.args)));
        l.gzip = single(loc, "gzip");
        l.gzipTypes = joined(loc.first("gzip_types"));
        l.gzipMinLength = single(loc, "gzip_min_length");
        l.gzipCompLevel = single(loc, "gzip_comp_level");
        l.proxyCache = single(loc, "proxy_cache");
        loc.directives("proxy_cache_valid").forEach(d -> l.proxyCacheValid.add(Arg.displayJoin(d.args)));
        l.proxyReadTimeout = single(loc, "proxy_read_timeout");
        loc.directives("error_page").forEach(d -> l.errorPages.add(Arg.displayJoin(d.args)));
        Directive ret = loc.first("return");
        if (ret != null) {
            l.redirectCode = ret.arg(0) == null ? "" : ret.arg(0);
            l.redirectTarget = ret.arg(1) == null ? "" : ret.arg(1);
        }
        if (loc.first("fastcgi_pass") != null) {
            l.type = LocationSettings.Type.FASTCGI;
        } else if (loc.first("proxy_pass") != null) {
            l.type = LocationSettings.Type.PROXY;
        } else if (ret != null && REDIRECT_CODES.contains(l.redirectCode)) {
            l.type = LocationSettings.Type.REDIRECT;
        } else {
            l.type = LocationSettings.Type.STATIC;
        }
        l.originalType = l.type;
        return l;
    }

    // ------------------------------------------------------------------------------- writing

    /** Writes the settings into the block, changing only what differs from what is there now. */
    public void apply(VhostSettings s) {
        syncServerNames(s.serverNames);
        syncList(server, "listen", s.listens.stream().map(ListenSpec::toValues).toList(), SERVER_ORDER);
        syncFirstArg(server, "root", s.root, SERVER_ORDER);
        syncSingle(server, "index", Arg.parseValues(s.index), SERVER_ORDER);
        syncFirstArg(server, "ssl_certificate", s.sslCertificate, SERVER_ORDER);
        syncFirstArg(server, "ssl_certificate_key", s.sslCertificateKey, SERVER_ORDER);
        syncSingle(server, "ssl_protocols", Arg.parseValues(s.sslProtocols), SERVER_ORDER);
        syncFirstArg(server, "ssl_ciphers", s.sslCiphers, SERVER_ORDER);
        syncRealIp(s.realIp);
        syncFirstArg(server, "client_max_body_size", s.clientMaxBodySize, SERVER_ORDER);
        syncFirstArg(server, "limit_rate", s.limitRate, SERVER_ORDER);
        syncList(server, "limit_req", parseAll(s.limitReq), SERVER_ORDER);
        syncList(server, "limit_conn", parseAll(s.limitConn), SERVER_ORDER);
        syncList(server, "access_log", parseAll(s.accessLogs), SERVER_ORDER);
        syncSingle(server, "error_log", Arg.parseValues(s.errorLog), SERVER_ORDER);
        syncList(server, "error_page", parseAll(s.errorPages), SERVER_ORDER);
        syncList(server, "add_header", s.headers.stream().map(HeaderSpec::toValues).toList(), SERVER_ORDER);
        syncRules(s.rewrites);
        syncLocations(s.locations);
    }

    private void syncServerNames(List<String> desired) {
        List<String> current = new ArrayList<>();
        server.directives("server_name").forEach(d -> current.addAll(d.values()));
        if (current.equals(desired)) {
            return;
        }
        syncList(server, "server_name", desired.isEmpty() ? List.of() : List.of(desired), SERVER_ORDER);
    }

    private void syncRules(List<RuleSpec> rules) {
        for (String name : List.of("rewrite", "return")) {
            List<List<String>> desired = new ArrayList<>();
            for (RuleSpec r : rules) {
                if (r.directive.equals(name)) {
                    desired.add(Arg.parseValues(r.arguments));
                }
            }
            syncList(server, name, desired, SERVER_ORDER);
        }
    }

    private void syncLocations(List<LocationSettings> desired) {
        List<Block> keep = new ArrayList<>();
        for (LocationSettings l : desired) {
            if (l.source != null) {
                keep.add(l.source);
            }
        }
        for (Block existing : new ArrayList<>(server.blocks("location"))) {
            if (!keep.contains(existing)) {
                server.remove(existing);
            }
        }
        for (LocationSettings l : desired) {
            if (l.source == null) {
                Block block = Block.create("location", locationHeader(l));
                server.insert(afterLastLocation(), block);
                l.source = block;
                l.originalType = l.type;
            }
            applyLocation(l);
        }
    }

    private int afterLastLocation() {
        int at = server.children.size();
        for (int i = server.children.size() - 1; i >= 0; i--) {
            if (server.children.get(i) instanceof Block && server.children.get(i).name.equals("location")) {
                return i + 1;
            }
        }
        return at;
    }

    private static List<String> locationHeader(LocationSettings l) {
        return l.modifier.isEmpty() ? List.of(l.path) : List.of(l.modifier, l.path);
    }

    private static void applyLocation(LocationSettings l) {
        Block loc = l.source;
        if (loc.isOpaque()) {
            return;
        }
        loc.setArgs(locationHeader(l));
        if (l.type != l.originalType) {
            clearType(loc, l.originalType);
        }
        switch (l.type) {
            case STATIC:
                syncFirstArg(loc, "root", l.root, LOCATION_ORDER);
                syncFirstArg(loc, "alias", l.alias, LOCATION_ORDER);
                syncSingle(loc, "index", Arg.parseValues(l.index), LOCATION_ORDER);
                syncSingle(loc, "try_files", Arg.parseValues(l.tryFiles), LOCATION_ORDER);
                break;
            case PROXY:
                syncFirstArg(loc, "proxy_pass", l.proxyPass, LOCATION_ORDER);
                syncList(loc, "proxy_set_header", l.proxySetHeaders.stream().map(VirtualHost::headerValues).toList(),
                        LOCATION_ORDER);
                break;
            case FASTCGI:
                syncFastcgiInclude(loc, l.fastcgiInclude);
                syncFirstArg(loc, "fastcgi_pass", l.fastcgiPass, LOCATION_ORDER);
                syncList(loc, "fastcgi_param", parseAll(l.fastcgiParams), LOCATION_ORDER);
                syncFirstArg(loc, "fastcgi_read_timeout", l.fastcgiReadTimeout, LOCATION_ORDER);
                syncSingle(loc, "try_files", Arg.parseValues(l.tryFiles), LOCATION_ORDER);
                break;
            default:
                List<String> ret = new ArrayList<>();
                ret.add(l.redirectCode);
                if (!l.redirectTarget.isBlank()) {
                    ret.add(l.redirectTarget);
                }
                syncSingle(loc, "return", ret, LOCATION_ORDER);
        }
        applyCommon(loc, l);
        l.originalType = l.type;
    }

    /** Settings that apply to every kind of location: access control, limits, compression, caching. */
    private static void applyCommon(Block loc, LocationSettings l) {
        syncFirstArg(loc, "auth_basic", l.authBasic, LOCATION_ORDER);
        syncFirstArg(loc, "auth_basic_user_file", l.authBasicUserFile, LOCATION_ORDER);
        syncFirstArg(loc, "satisfy", l.satisfy, LOCATION_ORDER);
        List<List<String>> rules = new ArrayList<>();
        for (String rule : l.accessRules) {
            List<String> v = Arg.parseValues(rule);
            if (!v.isEmpty()) {
                rules.add(v);
            }
        }
        syncOrdered(loc, ACCESS_NAMES, rules, LOCATION_ORDER);
        syncList(loc, "limit_req", parseAll(l.limitReq), LOCATION_ORDER);
        syncList(loc, "limit_conn", parseAll(l.limitConn), LOCATION_ORDER);
        syncFirstArg(loc, "gzip", l.gzip, LOCATION_ORDER);
        syncSingle(loc, "gzip_types", Arg.parseValues(l.gzipTypes), LOCATION_ORDER);
        syncFirstArg(loc, "gzip_min_length", l.gzipMinLength, LOCATION_ORDER);
        syncFirstArg(loc, "gzip_comp_level", l.gzipCompLevel, LOCATION_ORDER);
        syncFirstArg(loc, "proxy_cache", l.proxyCache, LOCATION_ORDER);
        syncList(loc, "proxy_cache_valid", parseAll(l.proxyCacheValid), LOCATION_ORDER);
        syncFirstArg(loc, "proxy_read_timeout", l.proxyReadTimeout, LOCATION_ORDER);
        syncList(loc, "error_page", parseAll(l.errorPages), LOCATION_ORDER);
    }

    /**
     * Makes the block's directives with these names match {@code desired} in order. Each desired
     * entry starts with the directive name. Used where order between different names matters
     * (allow/deny: the first match wins).
     */
    static void syncOrdered(Block block, List<String> names, List<List<String>> desired, List<String> order) {
        List<Directive> existing = new ArrayList<>();
        for (Node n : block.children) {
            if (n instanceof Directive && names.contains(n.name)) {
                existing.add((Directive) n);
            }
        }
        boolean sameNames = existing.size() == desired.size();
        for (int i = 0; sameNames && i < existing.size(); i++) {
            sameNames = existing.get(i).name.equals(desired.get(i).get(0));
        }
        if (sameNames) {
            for (int i = 0; i < existing.size(); i++) {
                existing.get(i).setArgs(desired.get(i).subList(1, desired.get(i).size()));
            }
            return;
        }
        for (Directive d : existing) {
            block.remove(d);
        }
        Directive previous = null;
        for (List<String> entry : desired) {
            Directive d = Directive.create(entry.get(0), entry.subList(1, entry.size()));
            if (previous == null) {
                insertOrdered(block, d, order);
            } else {
                block.insert(block.children.indexOf(previous) + 1, d);
            }
            previous = d;
        }
    }

    private static void clearType(Block loc, LocationSettings.Type old) {
        List<String> names = old == LocationSettings.Type.STATIC ? STATIC_ORDER
                : old == LocationSettings.Type.PROXY ? PROXY_ORDER
                : old == LocationSettings.Type.FASTCGI ? FASTCGI_ORDER : REDIRECT_ORDER;
        Directive managedInclude = fastcgiInclude(loc);
        for (String name : names) {
            for (Directive d : loc.directives(name)) {
                // Other includes in the block are not ours; only the one naming a fastcgi file goes.
                if (!name.equals("include") || d == managedInclude) {
                    loc.remove(d);
                }
            }
        }
    }

    /** The include that pulls in the FastCGI parameters: the first one naming a fastcgi file. */
    private static Directive fastcgiInclude(Block loc) {
        for (Directive d : loc.directives("include")) {
            if (d.arg(0) != null && d.arg(0).contains("fastcgi")) {
                return d;
            }
        }
        return null;
    }

    /** Only the include naming a fastcgi file is managed; any other include is left alone. */
    private static void syncFastcgiInclude(Block loc, String value) {
        Directive existing = fastcgiInclude(loc);
        String wanted = value == null ? "" : value.strip();
        if (wanted.isEmpty()) {
            if (existing != null) {
                loc.remove(existing);
            }
        } else if (existing == null) {
            insertOrdered(loc, Directive.create("include", List.of(wanted)), LOCATION_ORDER);
        } else if (!wanted.equals(existing.arg(0))) {
            existing.setArgs(List.of(wanted));
        }
    }

    /** {@code "Name value"} to its two arguments; the value keeps any spaces it has. */
    private static List<String> headerValues(String entry) {
        String e = entry.strip();
        int space = e.indexOf(' ');
        return space < 0 ? List.of(e) : List.of(e.substring(0, space), e.substring(space + 1).strip());
    }

    // ------------------------------------------------------------------------------- helpers

    private static String single(Block block, String name) {
        Directive d = block.first(name);
        return d == null || d.args.isEmpty() ? "" : d.arg(0);
    }

    private static String joined(Directive d) {
        return d == null ? "" : Arg.displayJoin(d.args);
    }

    private static List<String> one(String value) {
        return value == null || value.isBlank() ? List.of() : List.of(value.strip());
    }

    private static List<List<String>> parseAll(List<String> lines) {
        List<List<String>> result = new ArrayList<>();
        for (String line : lines) {
            List<String> v = Arg.parseValues(line);
            if (!v.isEmpty()) {
                result.add(v);
            }
        }
        return result;
    }

    /** Makes the block's directives with this name match {@code desired}, one for one. */
    static void syncList(Block block, String name, List<List<String>> desired, List<String> order) {
        List<Directive> existing = block.directives(name);
        int common = Math.min(existing.size(), desired.size());
        for (int i = 0; i < common; i++) {
            existing.get(i).setArgs(desired.get(i));
        }
        for (int i = common; i < existing.size(); i++) {
            block.remove(existing.get(i));
        }
        // New entries follow the last existing one of the same name, so the list keeps its order
        // (which matters for rewrite and return); with none yet they go where the name belongs.
        Directive anchor = common > 0 ? existing.get(common - 1) : null;
        for (int i = common; i < desired.size(); i++) {
            Directive added = Directive.create(name, desired.get(i));
            if (anchor == null) {
                insertOrdered(block, added, order);
            } else {
                block.insert(block.children.indexOf(anchor) + 1, added);
            }
            anchor = added;
        }
    }

    /**
     * For directives that take one value: the editor owns the first argument of the first directive
     * with this name. Any further arguments are kept, so nothing the editor doesn't understand is
     * lost. A blank value removes the directive.
     */
    static void syncFirstArg(Block block, String name, String value, List<String> order) {
        String wanted = value == null ? "" : value.strip();
        List<Directive> existing = block.directives(name);
        if (wanted.isEmpty()) {
            for (Directive d : existing) {
                block.remove(d);
            }
        } else if (existing.isEmpty()) {
            insertOrdered(block, Directive.create(name, List.of(wanted)), order);
        } else if (!wanted.equals(existing.get(0).arg(0))) {
            List<String> args = existing.get(0).values();
            List<String> updated = new ArrayList<>();
            updated.add(wanted);
            updated.addAll(args.subList(Math.min(1, args.size()), args.size()));
            existing.get(0).setArgs(updated);
        }
    }

    /**
     * Sets the first directive with this name, adds one if there is none, or removes them all if
     * {@code values} is empty. Further directives of the same name are left alone.
     */
    static void syncSingle(Block block, String name, List<String> values, List<String> order) {
        List<Directive> existing = block.directives(name);
        if (values.isEmpty()) {
            for (Directive d : existing) {
                block.remove(d);
            }
        } else if (existing.isEmpty()) {
            insertOrdered(block, Directive.create(name, values), order);
        } else {
            existing.get(0).setArgs(values);
        }
    }

    static void insertOrdered(Block block, Directive directive, List<String> order) {
        int rank = order.indexOf(directive.name);
        int at = block.children.size();
        if (rank >= 0) {
            at = 0;
            for (int i = 0; i < block.children.size(); i++) {
                Node child = block.children.get(i);
                if (child instanceof Block) {
                    break; // new directives go above the first nested block
                }
                int childRank = child instanceof Directive ? order.indexOf(child.name) : -1;
                if (childRank >= 0 && childRank <= rank) {
                    at = i + 1;
                }
            }
        }
        block.insert(at, directive);
    }
}
