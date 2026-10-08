package mt.su.nrm.ui;

import mt.su.nrm.model.ServerProfile;
import mt.su.nrm.nginx.RemoteConfig;
import mt.su.nrm.nginx.Upstream;
import mt.su.nrm.nginx.UpstreamSettings;
import javafx.stage.Window;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Load balancing: the {@code upstream} groups that locations can proxy to, created and edited with a wizard. */
final class UpstreamsPanel extends HttpObjectsPanel<Upstream> {

    UpstreamsPanel(ServerProfile profile, ServerConnection connection, ConnectionManager manager) {
        super(profile, connection, manager, "No load balancing groups. Add one to share requests across several servers.");
        addColumn("Name", 150, u -> u.name());
        addColumn("Sharing", 190, u -> describeMethod(u.read().method));
        addColumn("Servers", 80, u -> String.valueOf(u.read().servers.size()));
        addColumn("File", 260, u -> u.file().path());
        start();
    }

    /** The balancing method in plain words. */
    static String describeMethod(String method) {
        String m = method.strip();
        if (m.isEmpty()) {
            return "Evenly (round robin)";
        }
        if (m.equals("least_conn")) {
            return "Least busy server";
        }
        if (m.equals("ip_hash")) {
            return "Same visitor, same server";
        }
        if (m.equals("random")) {
            return "Random";
        }
        return m.startsWith("hash ") ? "By " + m.split("\\s+")[1] : m;
    }

    @Override
    List<Upstream> items(RemoteConfig config) {
        return config.upstreams();
    }

    @Override
    boolean add(RemoteConfig config, Window owner) {
        UpstreamSettings s = new UpstreamSettings();
        if (!UpstreamWizard.run(owner, s, names(config, null), true)) {
            return false;
        }
        config.createUpstream(s.name).apply(s);
        return true;
    }

    @Override
    boolean edit(RemoteConfig config, Upstream item, Window owner) {
        UpstreamSettings s = item.read();
        if (!UpstreamWizard.run(owner, s, names(config, item), false)) {
            return false;
        }
        item.apply(s);
        return true;
    }

    private static Set<String> names(RemoteConfig config, Upstream except) {
        Set<String> names = new HashSet<>();
        for (Upstream u : config.upstreams()) {
            if (except == null || u.block() != except.block()) {
                names.add(u.name());
            }
        }
        return names;
    }

    @Override
    void delete(RemoteConfig config, Upstream item) {
        config.deleteUpstream(item);
    }

    @Override
    String describe(Upstream item) {
        return "load balancing group " + item.name();
    }

    @Override
    String readOnlyReason(RemoteConfig config, Upstream item) {
        return config.readOnlyReason(item.file());
    }
}
