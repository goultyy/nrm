package mt.su.nrm.cloudflare;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One rule in a tunnel's ingress list: requests for {@code hostname} (and optionally {@code path})
 * go to {@code service}. A rule with no hostname is the catch-all, which Cloudflare requires as
 * the last rule.
 * <p>
 * The rule keeps Cloudflare's own map, so settings this app doesn't know about (such as
 * {@code originRequest}) survive a read-modify-write unchanged.
 */
public record IngressRule(Map<String, Object> raw) {

    public IngressRule {
        raw = new LinkedHashMap<>(raw);
    }

    /** A published application route. */
    public static IngressRule route(String hostname, String service) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hostname", hostname);
        m.put("service", service);
        return new IngressRule(m);
    }

    /** The default catch-all: anything not matched above gets a 404. */
    public static IngressRule catchAll() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("service", "http_status:404");
        return new IngressRule(m);
    }

    public String hostname() {
        return Json.asString(raw.get("hostname"));
    }

    public String path() {
        return Json.asString(raw.get("path"));
    }

    public String service() {
        return Json.asString(raw.get("service"));
    }

    public boolean isCatchAll() {
        return hostname().isEmpty();
    }

    /** True when this rule answers the same hostname and path as the other one. */
    public boolean sameRoute(String otherHostname, String otherPath) {
        return hostname().equalsIgnoreCase(otherHostname) && path().equals(otherPath);
    }
}
