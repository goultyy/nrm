package mt.su.nrm.nginx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A {@code proxy_cache_path} directive, viewed for editing. Parameters the editor doesn't know are kept. */
public final class CacheZone {

    private final ConfigFile file;
    private final Directive directive;

    CacheZone(ConfigFile file, Directive directive) {
        this.file = file;
        this.directive = directive;
    }

    public ConfigFile file() {
        return file;
    }

    public Directive directive() {
        return directive;
    }

    public CacheZoneSettings read() {
        List<String> v = directive.values();
        CacheZoneSettings s = new CacheZoneSettings();
        s.path = v.isEmpty() ? "" : v.get(0);
        s.levels = Params.get(v, 1, "levels");
        s.keysZone = Params.get(v, 1, "keys_zone");
        s.maxSize = Params.get(v, 1, "max_size");
        s.inactive = Params.get(v, 1, "inactive");
        s.useTempPath = Params.get(v, 1, "use_temp_path");
        return s;
    }

    public void apply(CacheZoneSettings s) {
        List<String> current = new ArrayList<>(directive.values());
        if (current.isEmpty()) {
            current.add(s.path);
        }
        current.set(0, s.path);
        Map<String, String> wanted = new LinkedHashMap<>();
        wanted.put("levels", s.levels.strip());
        wanted.put("keys_zone", s.keysZone.strip());
        wanted.put("max_size", s.maxSize.strip());
        wanted.put("inactive", s.inactive.strip());
        wanted.put("use_temp_path", s.useTempPath.strip());
        directive.setArgs(Params.update(current, 1, wanted));
    }
}
