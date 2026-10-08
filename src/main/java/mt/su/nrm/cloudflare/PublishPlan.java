package mt.su.nrm.cloudflare;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the "Add to Cloudflare" wizard decided for a virtual host's hostname: either a DNS record pointing straight at
 * the server, or a route on a tunnel (with the proxied CNAME that makes the hostname reach it). Kept free of JavaFX so
 * the checks and the staging can be tested. Nothing is sent to Cloudflare: {@link #stageInto} only stages.
 */
public final class PublishPlan {

    public enum Kind { DNS_RECORD, TUNNEL }

    private final Kind kind;
    private final Zone zone;
    private final Tunnel tunnel;
    private final String hostname;
    private final String address;
    private final boolean proxied;
    private final String service;
    private final boolean skipTlsVerify;

    private PublishPlan(Kind kind, Zone zone, Tunnel tunnel, String hostname, String address, boolean proxied,
            String service, boolean skipTlsVerify) {
        this.kind = kind;
        this.zone = zone;
        this.tunnel = tunnel;
        this.hostname = hostname.strip().toLowerCase(Locale.ROOT);
        this.address = address.strip();
        this.proxied = proxied;
        this.service = service.strip();
        this.skipTlsVerify = skipTlsVerify;
    }

    /** An A record (IPv4) or AAAA record (IPv6) for the hostname. */
    public static PublishPlan dnsRecord(Zone zone, String hostname, String address, boolean proxied) {
        return new PublishPlan(Kind.DNS_RECORD, zone, null, hostname, address, proxied, "", false);
    }

    /** A route on the tunnel to {@code service}, plus the CNAME. {@code skipTlsVerify} is for an https origin. */
    public static PublishPlan tunnel(Zone zone, Tunnel tunnel, String hostname, String service, boolean skipTlsVerify) {
        return new PublishPlan(Kind.TUNNEL, zone, tunnel, hostname, "", true, service, skipTlsVerify);
    }

    /**
     * The hostnames of a site's {@code server_name} list that can be published: real names such as app.example.com or
     * *.example.com. Skips {@code _}, regular expressions and names without a dot, and reads nginx's
     * {@code .example.com} as both example.com and *.example.com.
     */
    public static List<String> hostnameChoices(List<String> serverNames) {
        java.util.LinkedHashSet<String> found = new java.util.LinkedHashSet<>();
        for (String raw : serverNames) {
            String name = raw.strip().toLowerCase(Locale.ROOT);
            if (name.isEmpty() || name.startsWith("~") || name.contains("$")) {
                continue;
            }
            List<String> candidates = name.startsWith(".") && name.length() > 1
                    ? List.of(name.substring(1), "*" + name) : List.of(name);
            for (String candidate : candidates) {
                if (CloudflareWorkspace.routeProblems(candidate, "", "http_status:404").isEmpty()) {
                    found.add(candidate);
                }
            }
        }
        return List.copyOf(found);
    }

    public Kind kind() {
        return kind;
    }

    public Zone zone() {
        return zone;
    }

    public Tunnel tunnel() {
        return tunnel;
    }

    public String hostname() {
        return hostname;
    }

    /** "A" for an IPv4 address, "AAAA" for IPv6. */
    public String recordType() {
        return address.contains(":") ? "AAAA" : "A";
    }

    // ---------------------------------------------------------------- what already exists

    /**
     * What Cloudflare already has under a hostname.
     *
     * @param found           one line per DNS record or tunnel route with exactly this hostname
     * @param dnsChecked      false if the zone's records aren't loaded, so a missing record proves nothing
     * @param tunnelsChecked  false if some tunnel's routes aren't loaded
     */
    public record Existing(List<String> found, boolean dnsChecked, boolean tunnelsChecked) {
    }

    /**
     * Looks for a DNS record or a tunnel route that already uses {@code hostname}. Staged changes count (they are
     * marked), because applying them would create the same clash. Only what is loaded in the workspace can be seen.
     */
    public static Existing existing(CloudflareWorkspace workspace, Zone zone, List<Tunnel> tunnels, String hostname) {
        String host = hostname.strip().toLowerCase(Locale.ROOT);
        List<String> found = new ArrayList<>();
        boolean dnsChecked = zone != null && workspace.isDnsLoaded(zone);
        if (dnsChecked && !host.isEmpty()) {
            for (DnsRecord r : workspace.dnsRecords(zone)) {
                if (r.name().equalsIgnoreCase(host)) {
                    found.add("DNS " + zone.name() + " already has " + r.type() + " " + r.name() + " -> " + r.content()
                            + (r.proxied() ? " (proxied)" : "") + (CloudflareWorkspace.isNew(r) ? " (staged, not sent yet)" : ""));
                }
            }
        }
        boolean tunnelsChecked = true;
        for (Tunnel t : tunnels) {
            if (!workspace.isTunnelLoaded(t)) {
                tunnelsChecked = false;
                continue;
            }
            if (host.isEmpty()) {
                continue;
            }
            for (IngressRule r : workspace.tunnelConfig(t).routes()) {
                if (r.hostname().equalsIgnoreCase(host)) {
                    found.add("Tunnel " + t.name() + " already publishes " + r.hostname() + r.path() + " -> " + r.service());
                }
            }
        }
        return new Existing(List.copyOf(found), dnsChecked, tunnelsChecked);
    }

    // ---------------------------------------------------------------- checking

    /** Problems with the hostname and its zone, worded for the user. */
    public List<String> hostProblems() {
        List<String> problems = new ArrayList<>();
        if (hostname.isEmpty()) {
            problems.add("Choose or enter a hostname.");
        }
        if (zone == null) {
            problems.add("Choose the Cloudflare zone this hostname belongs to.");
        } else if (!hostname.isEmpty() && !hostname.equals(zone.name()) && !hostname.endsWith("." + zone.name())) {
            problems.add(hostname + " is not inside " + zone.name() + ". Choose the matching zone.");
        }
        return problems;
    }

    /** Problems with where the hostname points, checked with the same rules staging uses. */
    public List<String> targetProblems(CloudflareWorkspace workspace) {
        List<String> problems = new ArrayList<>();
        if (kind == Kind.TUNNEL) {
            if (tunnel == null) {
                problems.add("Choose a tunnel.");
            }
            problems.addAll(CloudflareWorkspace.routeProblems(hostname, "", service));
            // What staging would refuse, found now rather than after the wizard.
            if (tunnel != null && workspace.isTunnelLoaded(tunnel)
                    && workspace.tunnelConfig(tunnel).routes().stream().anyMatch(r -> r.sameRoute(hostname, ""))) {
                problems.add(hostname + " is already published on tunnel " + tunnel.name() + ".");
            }
            if (zone != null && workspace.isDnsLoaded(zone)) {
                workspace.dnsRecords(zone).stream().filter(r -> r.name().equalsIgnoreCase(hostname))
                        .filter(r -> !(r.type().equals("CNAME") && tunnel != null && r.content().equals(tunnel.cnameTarget())))
                        .findFirst().ifPresent(r -> problems.add(hostname + " already has a " + r.type()
                                + " record, and a tunnel needs a CNAME there. Change or delete that record first."));
            }
        } else if (zone != null) {
            problems.addAll(workspace.dnsProblems(zone, DnsRecord.of(recordType(), hostname, address, proxied)));
        } else if (address.isEmpty()) {
            problems.add("Choose or enter the server's IP address.");
        }
        return problems;
    }

    // ---------------------------------------------------------------- describing and staging

    /** One line per change this plan makes, for the review page. */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        String zoneName = zone == null ? "?" : zone.name();
        if (kind == Kind.DNS_RECORD) {
            lines.add("DNS " + zoneName + ": add " + recordType() + " " + hostname + " -> " + address
                    + (proxied ? " (proxied)" : " (DNS only)"));
        } else {
            lines.add("Tunnel " + (tunnel == null ? "?" : tunnel.name()) + ": publish " + hostname + " -> " + service
                    + (skipTlsVerify ? " (certificate not verified)" : ""));
            lines.add("DNS " + zoneName + ": add CNAME " + hostname + " -> "
                    + (tunnel == null ? "?" : tunnel.cnameTarget()) + " (proxied), unless one already points there");
        }
        return lines;
    }

    /**
     * Stages the plan. The zone's records (and the tunnel's routes, for a tunnel plan) must be loaded in the
     * workspace. Throws {@link IllegalArgumentException} with a readable reason if Cloudflare's rules would refuse it.
     */
    public void stageInto(CloudflareWorkspace workspace) {
        List<String> problems = new ArrayList<>(hostProblems());
        problems.addAll(targetProblems(workspace));
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join(" ", problems));
        }
        if (kind == Kind.DNS_RECORD) {
            workspace.stageDnsCreate(zone, DnsRecord.of(recordType(), hostname, address, proxied));
        } else {
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("hostname", hostname);
            raw.put("service", service);
            if (skipTlsVerify) {
                Map<String, Object> origin = new LinkedHashMap<>();
                origin.put("noTLSVerify", Boolean.TRUE);
                raw.put("originRequest", origin);
            }
            workspace.stagePublish(zone, tunnel, new IngressRule(raw));
        }
    }
}
