package mt.su.nrm.nginx;

import java.util.ArrayList;
import java.util.List;

/** An {@code upstream} block in the config, viewed for editing. */
public final class Upstream {

    private static final List<String> METHODS = List.of("least_conn", "ip_hash", "random", "hash", "least_time");
    private static final List<String> ORDER = List.of("least_conn", "ip_hash", "random", "hash", "least_time",
            "server", "keepalive");

    private final ConfigFile file;
    private final Block block;

    Upstream(ConfigFile file, Block block) {
        this.file = file;
        this.block = block;
    }

    public ConfigFile file() {
        return file;
    }

    public Block block() {
        return block;
    }

    public String name() {
        return block.arg(0) == null ? "" : block.arg(0);
    }

    public UpstreamSettings read() {
        UpstreamSettings s = new UpstreamSettings();
        s.name = name();
        Directive method = method();
        if (method != null) {
            s.method = (method.name + " " + Arg.displayJoin(method.args)).strip();
        }
        block.directives("server").forEach(d -> s.servers.add(Arg.displayJoin(d.args)));
        Directive keepalive = block.first("keepalive");
        s.keepalive = keepalive == null || keepalive.args.isEmpty() ? "" : keepalive.arg(0);
        return s;
    }

    public void apply(UpstreamSettings s) {
        block.setArgs(List.of(s.name));

        List<String> wanted = Arg.parseValues(s.method);
        Directive existing = method();
        if (wanted.isEmpty()) {
            removeMethods();
        } else if (existing != null && existing.name.equals(wanted.get(0))) {
            existing.setArgs(wanted.subList(1, wanted.size()));
        } else {
            removeMethods();
            VirtualHost.insertOrdered(block, Directive.create(wanted.get(0), wanted.subList(1, wanted.size())), ORDER);
        }

        List<List<String>> servers = new ArrayList<>();
        for (String line : s.servers) {
            List<String> v = Arg.parseValues(line);
            if (!v.isEmpty()) {
                servers.add(v);
            }
        }
        VirtualHost.syncList(block, "server", servers, ORDER);
        VirtualHost.syncFirstArg(block, "keepalive", s.keepalive, ORDER);
    }

    private Directive method() {
        for (Node n : block.children) {
            if (n instanceof Directive && METHODS.contains(n.name)) {
                return (Directive) n;
            }
        }
        return null;
    }

    private void removeMethods() {
        for (Node n : new ArrayList<>(block.children)) {
            if (n instanceof Directive && METHODS.contains(n.name)) {
                block.remove(n);
            }
        }
    }
}
