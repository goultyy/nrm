package mt.su.nrm.cloudflare;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tunnel's remotely managed configuration. Cloudflare replaces the whole ingress list on every
 * update, so edits go through this class: it keeps the catch-all rule last and carries every
 * setting it doesn't understand (origin settings, WARP routing) through unchanged.
 * <p>
 * Instances are immutable; each edit returns a new one.
 */
public final class TunnelConfig {

    private final Map<String, Object> raw;
    private final String source;

    TunnelConfig(Map<String, Object> raw, String source) {
        this.raw = new LinkedHashMap<>(raw);
        this.source = source;
    }

    /** Builds a configuration from the {@code result} of Cloudflare's configuration reply. */
    public static TunnelConfig fromReply(Object result) {
        Map<String, Object> reply = Json.asObject(result);
        return new TunnelConfig(Json.asObject(reply.get("config")), Json.asString(reply.get("source")));
    }

    /**
     * False when the tunnel is configured by a file on the server, in which case Cloudflare
     * ignores changes made through the API.
     */
    public boolean isRemotelyManaged() {
        return source.isEmpty() || source.equals("cloudflare");
    }

    /** All rules in order, the catch-all last (or absent, for a tunnel with no config yet). */
    public List<IngressRule> ingress() {
        List<IngressRule> rules = new ArrayList<>();
        for (Object item : Json.asList(raw.get("ingress"))) {
            rules.add(new IngressRule(Json.asObject(item)));
        }
        return rules;
    }

    /** The published application routes: every rule except the catch-all. */
    public List<IngressRule> routes() {
        return ingress().stream().filter(r -> !r.isCatchAll()).toList();
    }

    /** Adds a route just before the catch-all, creating a 404 catch-all if there is none. */
    public TunnelConfig withRoute(IngressRule route) {
        if (route.isCatchAll()) {
            throw new IllegalArgumentException("A route needs a hostname");
        }
        List<IngressRule> rules = ingress();
        for (IngressRule existing : rules) {
            if (existing.sameRoute(route.hostname(), route.path())) {
                throw new IllegalArgumentException(route.hostname() + route.path() + " is already published");
            }
        }
        List<IngressRule> updated = new ArrayList<>(routes());
        updated.add(route);
        updated.add(catchAllOf(rules));
        return withIngress(updated);
    }

    /** Replaces the route that has the given hostname and path. */
    public TunnelConfig withReplacedRoute(String hostname, String path, IngressRule replacement) {
        List<IngressRule> updated = new ArrayList<>(ingress());
        for (int i = 0; i < updated.size(); i++) {
            if (!updated.get(i).isCatchAll() && updated.get(i).sameRoute(hostname, path)) {
                updated.set(i, replacement);
                return withIngress(updated);
            }
        }
        throw new IllegalArgumentException(hostname + path + " is not published");
    }

    public TunnelConfig withoutRoute(String hostname, String path) {
        List<IngressRule> updated = new ArrayList<>(ingress());
        if (!updated.removeIf(r -> !r.isCatchAll() && r.sameRoute(hostname, path))) {
            throw new IllegalArgumentException(hostname + path + " is not published");
        }
        return withIngress(updated);
    }

    /** The body for the update call; refuses a list that Cloudflare would reject. */
    Map<String, Object> toRequestBody() {
        List<IngressRule> rules = ingress();
        if (rules.isEmpty() || !rules.get(rules.size() - 1).isCatchAll()) {
            throw new IllegalArgumentException("The last ingress rule must be a catch-all with no hostname");
        }
        for (int i = 0; i < rules.size() - 1; i++) {
            if (rules.get(i).isCatchAll()) {
                throw new IllegalArgumentException("Only the last ingress rule may be a catch-all");
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("config", new LinkedHashMap<>(raw));
        return body;
    }

    private static IngressRule catchAllOf(List<IngressRule> rules) {
        if (!rules.isEmpty() && rules.get(rules.size() - 1).isCatchAll()) {
            return rules.get(rules.size() - 1);
        }
        return IngressRule.catchAll();
    }

    private TunnelConfig withIngress(List<IngressRule> rules) {
        Map<String, Object> copy = new LinkedHashMap<>(raw);
        List<Object> list = new ArrayList<>();
        for (IngressRule rule : rules) {
            list.add(rule.raw());
        }
        copy.put("ingress", list);
        return new TunnelConfig(copy, source);
    }
}
