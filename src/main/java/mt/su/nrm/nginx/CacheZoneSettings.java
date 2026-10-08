package mt.su.nrm.nginx;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** A {@code proxy_cache_path} directive: where cached responses are kept and how big the cache may grow. */
public final class CacheZoneSettings {

    private static final Pattern KEYS_ZONE = Pattern.compile("[A-Za-z0-9_]+:\\d+[kKmM]");
    private static final Pattern SIZE = Pattern.compile("\\d+[kKmMgG]");
    private static final Pattern LEVELS = Pattern.compile("[12](:[12]){0,2}");
    private static final Pattern TIME = Pattern.compile("\\d+(ms|s|m|h|d|w|M|y)?");

    public String path = "";
    /** Folder levels, e.g. {@code 1:2}. */
    public String levels = "";
    /** Zone name and shared memory size for the key index, e.g. {@code mycache:10m}. */
    public String keysZone = "";
    public String maxSize = "";
    public String inactive = "";
    /** {@code on}, {@code off} or empty for not set. */
    public String useTempPath = "";

    public CacheZoneSettings copy() {
        CacheZoneSettings c = new CacheZoneSettings();
        c.path = path;
        c.levels = levels;
        c.keysZone = keysZone;
        c.maxSize = maxSize;
        c.inactive = inactive;
        c.useTempPath = useTempPath;
        return c;
    }

    /** The zone's name (the part of keys_zone before the colon). */
    public String zoneName() {
        int colon = keysZone.indexOf(':');
        return colon < 0 ? keysZone : keysZone.substring(0, colon);
    }

    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (!path.startsWith("/") || path.chars().anyMatch(c -> c < 0x20 || Character.isWhitespace(c))) {
            problems.add("The cache folder must be an absolute path without spaces.");
        }
        if (!KEYS_ZONE.matcher(keysZone).matches()) {
            problems.add("The zone must look like name:10m (a name, a colon and a size).");
        }
        if (!levels.isBlank() && !LEVELS.matcher(levels).matches()) {
            problems.add("Levels must look like 1:2.");
        }
        if (!maxSize.isBlank() && !SIZE.matcher(maxSize).matches()) {
            problems.add("The maximum size must look like 1g or 500m.");
        }
        if (!inactive.isBlank() && !TIME.matcher(inactive).matches()) {
            problems.add("Inactive time must look like 60m or 1d.");
        }
        if (!useTempPath.isBlank() && !useTempPath.equals("on") && !useTempPath.equals("off")) {
            problems.add("use_temp_path must be on or off.");
        }
        return problems;
    }

    /** The directive as it would be written to nginx.conf, for the review step of the wizard. */
    public String preview() {
        StringBuilder sb = new StringBuilder("proxy_cache_path ").append(path);
        param(sb, "levels", levels);
        param(sb, "keys_zone", keysZone);
        param(sb, "max_size", maxSize);
        param(sb, "inactive", inactive);
        param(sb, "use_temp_path", useTempPath);
        return sb.append(";").toString();
    }

    private static void param(StringBuilder sb, String key, String value) {
        if (!value.isBlank()) {
            sb.append(' ').append(key).append('=').append(value.strip());
        }
    }
}
