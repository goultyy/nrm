package mt.su.nrm.ui;

import mt.su.nrm.model.ServerProfile;
import mt.su.nrm.nginx.LimitKey;
import mt.su.nrm.nginx.LimitZone;
import mt.su.nrm.nginx.LimitZoneSettings;
import mt.su.nrm.nginx.RemoteConfig;
import mt.su.nrm.nginx.Units;
import javafx.stage.Window;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Rate limits: the zones ({@code limit_req_zone}, {@code limit_conn_zone}) that locations refer to, created with a wizard. */
final class LimitZonesPanel extends HttpObjectsPanel<LimitZone> {

    LimitZonesPanel(ServerProfile profile, ServerConnection connection, ConnectionManager manager) {
        super(profile, connection, manager, "No rate limits. Add one, then use it in a location's Limits tab.");
        addColumn("Zone", 130, z -> z.read().zoneName());
        addColumn("Limits", 190, z -> z.read().kind.toString());
        addColumn("Counted per", 220, z -> LimitKey.of(z.read().key) == LimitKey.CUSTOM ? z.read().key
                : LimitKey.of(z.read().key).toString());
        addColumn("Allowed rate", 140, z -> {
            String rate = z.read().rate;
            return rate.isBlank() ? "" : Units.Rate.parse(rate).map(Units.Rate::display).orElse(rate);
        });
        addColumn("File", 200, z -> z.file().path());
        start();
    }

    @Override
    List<LimitZone> items(RemoteConfig config) {
        return config.limitZones();
    }

    @Override
    boolean add(RemoteConfig config, Window owner) {
        LimitZoneSettings s = new LimitZoneSettings();
        if (!LimitZoneWizard.run(owner, s, names(config, null), true)) {
            return false;
        }
        config.createLimitZone(s.kind).apply(s);
        return true;
    }

    @Override
    boolean edit(RemoteConfig config, LimitZone item, Window owner) {
        LimitZoneSettings s = item.read();
        if (!LimitZoneWizard.run(owner, s, names(config, item), false)) {
            return false;
        }
        item.apply(s);
        return true;
    }

    private static Set<String> names(RemoteConfig config, LimitZone except) {
        Set<String> names = new HashSet<>();
        for (LimitZone z : config.limitZones()) {
            if (except == null || z.directive() != except.directive()) {
                LimitZoneSettings s = z.read();
                names.add(s.kind + ":" + s.zoneName());
            }
        }
        return names;
    }

    @Override
    void delete(RemoteConfig config, LimitZone item) {
        config.deleteLimitZone(item);
    }

    @Override
    String describe(LimitZone item) {
        return "limit zone " + item.read().zoneName();
    }

    @Override
    String readOnlyReason(RemoteConfig config, LimitZone item) {
        return config.readOnlyReason(item.file());
    }
}
