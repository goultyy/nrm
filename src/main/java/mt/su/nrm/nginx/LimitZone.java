package mt.su.nrm.nginx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A {@code limit_req_zone} or {@code limit_conn_zone} directive, viewed for editing. */
public final class LimitZone {

    private final ConfigFile file;
    private final Directive directive;

    LimitZone(ConfigFile file, Directive directive) {
        this.file = file;
        this.directive = directive;
    }

    public ConfigFile file() {
        return file;
    }

    public Directive directive() {
        return directive;
    }

    public LimitZoneSettings read() {
        List<String> v = directive.values();
        LimitZoneSettings s = new LimitZoneSettings();
        s.kind = directive.name().equals("limit_req_zone") ? LimitZoneSettings.Kind.REQUEST
                : LimitZoneSettings.Kind.CONNECTION;
        s.key = v.isEmpty() ? "" : v.get(0);
        s.zone = Params.get(v, 1, "zone");
        s.rate = Params.get(v, 1, "rate");
        return s;
    }

    /** Writes the key, zone and rate. The kind of an existing zone can't change (it is a different directive). */
    public void apply(LimitZoneSettings s) {
        List<String> current = new ArrayList<>(directive.values());
        if (current.isEmpty()) {
            current.add(s.key);
        }
        current.set(0, s.key);
        Map<String, String> wanted = new LinkedHashMap<>();
        wanted.put("zone", s.zone.strip());
        wanted.put("rate", directive.name().equals("limit_req_zone") ? s.rate.strip() : "");
        directive.setArgs(Params.update(current, 1, wanted));
    }
}
