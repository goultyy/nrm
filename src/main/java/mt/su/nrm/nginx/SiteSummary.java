package mt.su.nrm.nginx;

import java.util.ArrayList;
import java.util.List;

/**
 * A virtual host boiled down to what a site list shows, the way IIS Manager lists sites: its name,
 * where it listens, what it serves, and whether it uses SSL.
 *
 * @param name      the first server name (or a placeholder)
 * @param allNames  every server name, space separated
 * @param bindings  where it listens, e.g. {@code http :80, https :443}
 * @param content   what it serves: a folder, a proxy target, or a redirect
 * @param ssl       the certificate file it uses, or "" for none

 * @param locations how many location blocks it has
 * @param kind      whether it serves files, proxies to another server, or redirects
 * @param https     true if any listener uses ssl
 */
public record SiteSummary(String name, String allNames, String bindings, String content, String ssl, int locations,
                          Kind kind, boolean https) {

    /** What the site mostly does, for choosing its icon. */
    public enum Kind { STATIC, PROXY, REDIRECT, OTHER }

    public static SiteSummary of(VhostSettings s) {
        String name = s.serverNames.isEmpty() ? "(no server_name)" : s.serverNames.get(0).equals("_") ? "default" : s.serverNames.get(0);
        return new SiteSummary(name, String.join(" ", s.serverNames), bindings(s), content(s), s.sslCertificate,
                s.locations.size(), kind(s), s.usesSsl());
    }

    /** Files if there is a document root, otherwise what the main location does. */
    static Kind kind(VhostSettings s) {
        if (!s.root.isBlank()) {
            return Kind.STATIC;
        }
        LocationSettings chosen = null;
        for (LocationSettings l : s.locations) {
            if (l.modifier.isEmpty() && l.path.equals("/")) {
                chosen = l;
                break;
            }
        }
        if (chosen == null && !s.locations.isEmpty()) {
            chosen = s.locations.get(0);
        }
        if (chosen != null) {
            switch (chosen.type) {
                case PROXY:
                    return Kind.PROXY;
                case REDIRECT:
                    return Kind.REDIRECT;
                default:
                    return Kind.STATIC;
            }
        }
        for (VhostSettings.RuleSpec r : s.rewrites) {
            if (r.directive.equals("return")) {
                return r.arguments.matches("30[1278]\s.*") ? Kind.REDIRECT : Kind.OTHER;
            }
        }
        return Kind.OTHER;
    }

    /** {@code http :80, https :443 (default)}; a bare address without a port means 80. */
    static String bindings(VhostSettings s) {
        if (s.listens.isEmpty()) {
            return "http :80";
        }
        List<String> parts = new ArrayList<>();
        for (VhostSettings.ListenSpec l : s.listens) {
            int port = l.port();
            String endpoint = l.endpoint;
            String text;
            if (endpoint.startsWith("unix:")) {
                text = endpoint;
            } else {
                String address = address(endpoint);
                text = (l.ssl() ? "https " : "http ") + (address.isEmpty() ? "" : address) + ":" + (port < 0 ? "?" : port);
            }
            parts.add(text + (l.defaultServer() ? " (default)" : ""));
        }
        return String.join(", ", parts);
    }

    /** The address part of a listen endpoint ("" for all addresses). */
    private static String address(String endpoint) {
        if (endpoint.startsWith("[")) {
            int close = endpoint.indexOf(']');
            String host = close < 0 ? endpoint : endpoint.substring(0, close + 1);
            return host.equals("[::]") ? "" : host;
        }
        int colon = endpoint.indexOf(':');
        String host = colon < 0 ? (endpoint.chars().allMatch(Character::isDigit) ? "" : endpoint) : endpoint.substring(0, colon);
        return host.equals("*") || host.equals("0.0.0.0") ? "" : host;
    }

    /** The document root, else what the root location proxies or redirects to, else the first location's. */
    static String content(VhostSettings s) {
        if (!s.root.isBlank()) {
            return s.root;
        }
        LocationSettings chosen = null;
        for (LocationSettings l : s.locations) {
            if (l.modifier.isEmpty() && l.path.equals("/")) {
                chosen = l;
                break;
            }
        }
        if (chosen == null && !s.locations.isEmpty()) {
            chosen = s.locations.get(0);
        }
        if (chosen != null) {
            switch (chosen.type) {
                case PROXY:
                    return "proxy to " + chosen.proxyPass;
                case REDIRECT:
                    return "redirect to " + chosen.redirectTarget;
                case FASTCGI:
                    return "PHP via " + chosen.fastcgiPass;
                default:
                    if (!chosen.root.isBlank()) {
                        return chosen.root;
                    }
                    if (!chosen.alias.isBlank()) {
                        return chosen.alias;
                    }
            }
        }
        for (RuleSpecView r : RuleSpecView.of(s)) {
            if (r.directive.equals("return")) {
                return "returns " + r.arguments;
            }
        }
        return "";
    }

    /** Server-level rules, viewed without exposing the settings class's mutable fields. */
    private static final class RuleSpecView {
        final String directive;
        final String arguments;

        RuleSpecView(String directive, String arguments) {
            this.directive = directive;
            this.arguments = arguments;
        }

        static List<RuleSpecView> of(VhostSettings s) {
            List<RuleSpecView> list = new ArrayList<>();
            for (VhostSettings.RuleSpec r : s.rewrites) {
                list.add(new RuleSpecView(r.directive, r.arguments));
            }
            return list;
        }
    }
}
