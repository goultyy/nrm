package mt.su.nrm.ui;

import mt.su.nrm.model.ServerProfile;
import mt.su.nrm.nginx.CacheZone;
import mt.su.nrm.nginx.CacheZoneSettings;
import mt.su.nrm.nginx.RemoteConfig;
import mt.su.nrm.nginx.Units;
import javafx.stage.Window;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Cache zones: the {@code proxy_cache_path} zones that reverse proxy locations can cache into, created with a wizard. */
final class CacheZonesPanel extends HttpObjectsPanel<CacheZone> {

    private final ServerProfile profile;
    private final ServerConnection connection;

    CacheZonesPanel(ServerProfile profile, ServerConnection connection, ConnectionManager manager) {
        super(profile, connection, manager, "No cache zones. Add one to cache responses from proxied servers.");
        this.profile = profile;
        this.connection = connection;
        addColumn("Zone", 130, z -> z.read().zoneName());
        addColumn("Folder", 200, z -> z.read().path);
        addColumn("Disk limit", 90, z -> friendlySize(z.read().maxSize));
        addColumn("Index memory", 100, z -> {
            String zone = z.read().keysZone;
            return friendlySize(zone.contains(":") ? zone.substring(zone.indexOf(':') + 1) : "");
        });
        addColumn("Keeps unused for", 120, z -> friendlyTime(z.read().inactive));
        addColumn("File", 200, z -> z.file().path());
        start();
    }

    static String friendlySize(String nginx) {
        return nginx.isBlank() ? "no limit" : Units.Size.parse(nginx).map(Units.Size::display).orElse(nginx);
    }

    static String friendlyTime(String nginx) {
        return nginx.isBlank() ? "10 minutes (default)" : Units.Duration.parse(nginx).map(Units.Duration::display).orElse(nginx);
    }

    @Override
    javafx.scene.Node extraView(RemoteConfig config) {
        return new CacheStatsPane(profile, connection, config, window(), this::changed);
    }

    @Override
    List<CacheZone> items(RemoteConfig config) {
        return config.cacheZones();
    }

    @Override
    boolean add(RemoteConfig config, Window owner) {
        CacheZoneSettings s = new CacheZoneSettings();
        if (!CacheZoneWizard.run(owner, s, names(config, null), true)) {
            return false;
        }
        config.createCacheZone(s.path).apply(s);
        return true;
    }

    @Override
    boolean edit(RemoteConfig config, CacheZone item, Window owner) {
        CacheZoneSettings s = item.read();
        if (!CacheZoneWizard.run(owner, s, names(config, item), false)) {
            return false;
        }
        item.apply(s);
        return true;
    }

    private static Set<String> names(RemoteConfig config, CacheZone except) {
        Set<String> names = new HashSet<>();
        for (CacheZone z : config.cacheZones()) {
            if (except == null || z.directive() != except.directive()) {
                names.add(z.read().zoneName());
            }
        }
        return names;
    }

    @Override
    void delete(RemoteConfig config, CacheZone item) {
        config.deleteCacheZone(item);
    }

    @Override
    String describe(CacheZone item) {
        return "cache zone " + item.read().zoneName();
    }

    @Override
    String readOnlyReason(RemoteConfig config, CacheZone item) {
        return config.readOnlyReason(item.file());
    }
}
