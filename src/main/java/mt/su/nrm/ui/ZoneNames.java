package mt.su.nrm.ui;

import java.util.Set;

/**
 * Names of the request-limit, connection-limit and cache zones and the load balancing groups defined on a server, so the
 * virtual host editor can warn when a location refers to a zone that doesn't exist.
 */
record ZoneNames(Set<String> request, Set<String> connection, Set<String> cache, Set<String> upstreams) {

    /** No zones known: every reference is flagged. Used where the configuration isn't available. */
    static final ZoneNames NONE = new ZoneNames(Set.of(), Set.of(), Set.of(), Set.of());

    /** The zones and groups defined in a loaded configuration (empty if there is none). */
    static ZoneNames of(mt.su.nrm.nginx.RemoteConfig config) {
        Set<String> request = new java.util.HashSet<>();
        Set<String> connection = new java.util.HashSet<>();
        Set<String> cache = new java.util.HashSet<>();
        Set<String> upstreams = new java.util.HashSet<>();
        if (config != null) {
            for (mt.su.nrm.nginx.LimitZone z : config.limitZones()) {
                mt.su.nrm.nginx.LimitZoneSettings s = z.read();
                (s.kind == mt.su.nrm.nginx.LimitZoneSettings.Kind.REQUEST ? request : connection).add(s.zoneName());
            }
            config.cacheZones().forEach(z -> cache.add(z.read().zoneName()));
            config.upstreams().forEach(u -> upstreams.add(u.name()));
        }
        return new ZoneNames(request, connection, cache, upstreams);
    }
}
