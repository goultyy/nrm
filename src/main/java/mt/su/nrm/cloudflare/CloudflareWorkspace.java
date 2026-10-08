package mt.su.nrm.cloudflare;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The Cloudflare data the screen shows and the edits staged on it. Nothing reaches Cloudflare
 * until {@link #apply()}, so the user can review every change first, as with the nginx pending
 * changes.
 * <p>
 * Each zone's DNS records and each tunnel's configuration is loaded once ({@code baseline}) and
 * edited as a {@code working} copy; the changes are the difference between the two. Before
 * applying, everything that will be touched is read again and must still match the baseline, so
 * an edit made on Cloudflare in the meantime is never overwritten.
 * <p>
 * Not thread safe: the screen runs one operation at a time. {@link #loadDns}, {@link #loadTunnel}
 * and {@link #apply} block on the network and belong off the JavaFX thread.
 */
public final class CloudflareWorkspace {

    private static final String NEW_PREFIX = "new:";
    private static final Set<String> CREATABLE_TYPES = Set.of("A", "AAAA", "CNAME", "TXT");
    private static final Set<String> PROXIABLE_TYPES = Set.of("A", "AAAA", "CNAME");
    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
    private static final Pattern ROUTE_HOST =
            Pattern.compile("(\\*\\.)?([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z0-9-]{2,63}");
    private static final Pattern ROUTE_SERVICE =
            Pattern.compile("(https?|ssh|rdp|tcp|smb)://[^\\s/]+(/\\S*)?|unix:/\\S+|http_status:\\d{3}");

    public enum Kind { CREATE, UPDATE, DELETE }

    /** One staged DNS change; {@code before} is null for a create, {@code after} for a delete. */
    public record DnsChange(Kind kind, DnsRecord before, DnsRecord after) {
    }

    private static final class DnsView {
        final Zone zone;
        final List<DnsRecord> baseline;
        final List<DnsRecord> working;

        DnsView(Zone zone, List<DnsRecord> records) {
            this.zone = zone;
            this.baseline = List.copyOf(records);
            this.working = new ArrayList<>(records);
        }
    }

    private static final class TunnelView {
        final String accountId;
        final Tunnel tunnel;
        final TunnelConfig baseline;
        TunnelConfig working;

        TunnelView(String accountId, Tunnel tunnel, TunnelConfig config) {
            this.accountId = accountId;
            this.tunnel = tunnel;
            this.baseline = config;
            this.working = config;
        }
    }

    private final CloudflareApi api;
    private final Map<String, DnsView> dns = new LinkedHashMap<>();
    private final Map<String, TunnelView> tunnels = new LinkedHashMap<>();
    private int newCounter;

    public CloudflareWorkspace(CloudflareApi api) {
        this.api = api;
    }

    // ---------------------------------------------------------------- loading and reading

    /** Reads the zone's records, dropping any changes staged for that zone. */
    public void loadDns(Zone zone) throws CloudflareException {
        dns.put(zone.id(), new DnsView(zone, api.listDnsRecords(zone.id())));
    }

    /** Reads the tunnel's configuration, dropping any changes staged for that tunnel. */
    public void loadTunnel(String accountId, Tunnel tunnel) throws CloudflareException {
        tunnels.put(tunnel.id(), new TunnelView(accountId, tunnel, api.getTunnelConfig(accountId, tunnel.id())));
    }

    public boolean isDnsLoaded(Zone zone) {
        return dns.containsKey(zone.id());
    }

    public boolean isTunnelLoaded(Tunnel tunnel) {
        return tunnels.containsKey(tunnel.id());
    }

    /** The zone's records as they would be after applying, sorted by name then type. */
    public List<DnsRecord> dnsRecords(Zone zone) {
        List<DnsRecord> records = new ArrayList<>(view(zone).working);
        records.sort(Comparator.comparing(DnsRecord::name).thenComparing(DnsRecord::type));
        return records;
    }

    /** The tunnel's configuration as it would be after applying. */
    public TunnelConfig tunnelConfig(Tunnel tunnel) {
        return view(tunnel).working;
    }

    /** True for a record staged for creation, which Cloudflare doesn't know yet. */
    public static boolean isNew(DnsRecord record) {
        return record.id().startsWith(NEW_PREFIX);
    }

    // ---------------------------------------------------------------- staging DNS

    public DnsRecord stageDnsCreate(Zone zone, DnsRecord record) {
        DnsView v = view(zone);
        DnsRecord staged = new DnsRecord(NEW_PREFIX + (++newCounter), record.type(), record.name().toLowerCase(Locale.ROOT),
                record.content(), record.proxied(), record.ttl(), record.comment());
        if (!CREATABLE_TYPES.contains(staged.type())) {
            throw new IllegalArgumentException("Records of type " + staged.type()
                    + " can't be created here (A, AAAA, CNAME and TXT can).");
        }
        requireValid(zone, staged);
        v.working.add(staged);
        return staged;
    }

    /** Replaces the record with the same id; its type can't change. */
    public void stageDnsUpdate(Zone zone, DnsRecord record) {
        DnsView v = view(zone);
        int index = indexOf(v.working, record.id());
        if (index < 0) {
            throw new IllegalArgumentException("That record no longer exists.");
        }
        DnsRecord current = v.working.get(index);
        if (!current.type().equals(record.type())) {
            throw new IllegalArgumentException("A record's type can't be changed; delete it and add a new one.");
        }
        DnsRecord lowered = new DnsRecord(record.id(), record.type(), record.name().toLowerCase(Locale.ROOT),
                record.content(), record.proxied(), record.ttl(), record.comment());
        requireValid(zone, lowered);
        v.working.set(index, lowered);
    }

    public void stageDnsDelete(Zone zone, String recordId) {
        DnsView v = view(zone);
        if (!v.working.removeIf(r -> r.id().equals(recordId))) {
            throw new IllegalArgumentException("That record no longer exists.");
        }
    }

    /** What is wrong with the record, worded for the user; empty when it can be staged. */
    public List<String> dnsProblems(Zone zone, DnsRecord record) {
        List<String> problems = new ArrayList<>();
        String name = record.name().strip().toLowerCase(Locale.ROOT);
        String content = record.content().strip();
        String type = record.type();

        if (name.isEmpty()) {
            problems.add("Enter a name.");
        } else if (!name.equals(zone.name()) && !name.endsWith("." + zone.name())) {
            problems.add("The name must end in ." + zone.name() + ".");
        }
        if (content.isEmpty()) {
            problems.add("Enter what the record points to.");
        } else {
            switch (type) {
                case "A" -> {
                    if (!isIpv4(content)) {
                        problems.add("An A record needs an IPv4 address such as 203.0.113.10.");
                    }
                }
                case "AAAA" -> {
                    if (!content.contains(":") || content.chars().anyMatch(Character::isWhitespace)) {
                        problems.add("An AAAA record needs an IPv6 address.");
                    }
                }
                case "CNAME" -> {
                    if (content.chars().anyMatch(Character::isWhitespace)) {
                        problems.add("A CNAME must point to a hostname.");
                    }
                }
                default -> { }
            }
        }
        if (record.proxied() && !PROXIABLE_TYPES.contains(type)) {
            problems.add(type + " records can't be proxied.");
        }
        if (record.proxied() && record.ttl() != DnsRecord.TTL_AUTOMATIC) {
            problems.add("A proxied record must use automatic TTL.");
        }

        DnsView v = dns.get(zone.id());
        if (v != null && !name.isEmpty()) {
            for (DnsRecord other : v.working) {
                if (other.id().equals(record.id()) || !other.name().equals(name)) {
                    continue;
                }
                if (type.equals("CNAME") || other.type().equals("CNAME")) {
                    problems.add(name + " already has a " + other.type()
                            + " record, and a CNAME can't share a name with another record.");
                    break;
                }
                if (other.type().equals(type) && other.content().equals(content)) {
                    problems.add("An identical " + type + " record already exists.");
                    break;
                }
            }
        }
        return problems;
    }

    // ---------------------------------------------------------------- staging routes

    public void stageRouteAdd(Tunnel tunnel, IngressRule route) {
        requireValidRoute(route);
        TunnelView v = view(tunnel);
        v.working = v.working.withRoute(route);
    }

    public void stageRouteReplace(Tunnel tunnel, String hostname, String path, IngressRule replacement) {
        requireValidRoute(replacement);
        TunnelView v = view(tunnel);
        if (!replacement.sameRoute(hostname, path)) {
            // Moving to another hostname or path must not collide with an existing route.
            for (IngressRule existing : v.working.routes()) {
                if (!existing.sameRoute(hostname, path) && existing.sameRoute(replacement.hostname(), replacement.path())) {
                    throw new IllegalArgumentException(replacement.hostname() + replacement.path()
                            + " is already published.");
                }
            }
        }
        v.working = v.working.withReplacedRoute(hostname, path, replacement);
    }

    public void stageRouteRemove(Tunnel tunnel, String hostname, String path) {
        TunnelView v = view(tunnel);
        v.working = v.working.withoutRoute(hostname, path);
    }

    /** What is wrong with a route, worded for the user; empty when it is acceptable. */
    public static List<String> routeProblems(String hostname, String path, String service) {
        List<String> problems = new ArrayList<>();
        String host = hostname.strip().toLowerCase(Locale.ROOT);
        if (host.isEmpty()) {
            problems.add("Enter a hostname.");
        } else if (!ROUTE_HOST.matcher(host).matches()) {
            problems.add("The hostname must look like app.example.com (or *.example.com).");
        }
        if (!path.isEmpty()) {
            try {
                Pattern.compile(path);
            } catch (PatternSyntaxException e) {
                problems.add("The path is not a valid pattern: " + e.getDescription() + ".");
            }
        }
        if (service.strip().isEmpty()) {
            problems.add("Enter the address the route sends traffic to, such as http://localhost:8080.");
        } else if (!ROUTE_SERVICE.matcher(service.strip()).matches()) {
            problems.add("The service must be like http://localhost:8080, https://..., tcp://..., "
                    + "ssh://..., rdp://..., unix:/path or http_status:404.");
        }
        return problems;
    }

    // ---------------------------------------------------------------- publishing

    /**
     * Publishes an application: a route on the tunnel plus the proxied CNAME that makes the
     * hostname reach it. Both are staged, or neither is.
     */
    public void stagePublish(Zone zone, Tunnel tunnel, String hostname, String path, String service) {
        String host = hostname.strip().toLowerCase(Locale.ROOT);
        List<String> problems = routeProblems(host, path, service);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join(" ", problems));
        }
        stagePublish(zone, tunnel,
                path.isEmpty() ? IngressRule.route(host, service.strip()) : withPath(host, path, service.strip()));
    }

    /** As above, for a route built elsewhere (for example one that also carries origin settings). */
    public void stagePublish(Zone zone, Tunnel tunnel, IngressRule route) {
        List<String> problems = routeProblems(route.hostname(), route.path(), route.service());
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join(" ", problems));
        }
        String host = route.hostname().strip().toLowerCase(Locale.ROOT);
        DnsView dv = view(zone);
        TunnelView tv = view(tunnel);

        TunnelConfig nextConfig = tv.working.withRoute(route);

        DnsRecord existing = null;
        for (DnsRecord r : dv.working) {
            if (r.name().equals(host)) {
                existing = r;
                break;
            }
        }
        DnsRecord toCreate = null;
        if (existing == null) {
            toCreate = new DnsRecord("", "CNAME", host, tunnel.cnameTarget(), true, DnsRecord.TTL_AUTOMATIC, "");
            List<String> dnsProblems = dnsProblems(zone, toCreate);
            if (!dnsProblems.isEmpty()) {
                throw new IllegalArgumentException(String.join(" ", dnsProblems));
            }
        } else if (!(existing.type().equals("CNAME") && existing.content().equals(tunnel.cnameTarget()))) {
            throw new IllegalArgumentException(host + " already has a " + existing.type() + " record pointing to "
                    + existing.content() + ". Change or delete that record first.");
        }

        tv.working = nextConfig;
        if (toCreate != null) {
            stageDnsCreate(zone, toCreate);
        }
    }

    /**
     * Removes a route and, if asked, the CNAME for its hostname, but only when no other route
     * still uses that hostname and the CNAME really points at this tunnel.
     */
    public void stageUnpublish(Zone zone, Tunnel tunnel, String hostname, String path, boolean removeDns) {
        TunnelView tv = view(tunnel);
        TunnelConfig next = tv.working.withoutRoute(hostname, path);
        DnsRecord toDelete = null;
        if (removeDns && dns.containsKey(zone.id())) {
            boolean stillUsed = next.routes().stream().anyMatch(r -> r.hostname().equalsIgnoreCase(hostname));
            if (!stillUsed) {
                for (DnsRecord r : view(zone).working) {
                    if (r.type().equals("CNAME") && r.name().equalsIgnoreCase(hostname)
                            && r.content().equals(tunnel.cnameTarget())) {
                        toDelete = r;
                    }
                }
            }
        }
        tv.working = next;
        if (toDelete != null) {
            stageDnsDelete(zone, toDelete.id());
        }
    }

    // ---------------------------------------------------------------- changes

    public List<DnsChange> dnsChanges(Zone zone) {
        return changesOf(view(zone));
    }

    public boolean hasChanges() {
        return changeCount() > 0;
    }

    /** The number of lines {@link #summary()} would show. */
    public int changeCount() {
        return summary().size();
    }

    /** One line per staged change, worded for the review dialog. */
    public List<String> summary() {
        List<String> lines = new ArrayList<>();
        for (DnsView v : dns.values()) {
            for (DnsChange c : changesOf(v)) {
                String where = "DNS " + v.zone.name() + ": ";
                lines.add(switch (c.kind()) {
                    case CREATE -> where + "add " + describe(c.after());
                    case DELETE -> where + "delete " + describe(c.before());
                    case UPDATE -> where + "change " + describe(c.before()) + "  ->  " + describe(c.after());
                });
            }
        }
        for (TunnelView v : tunnels.values()) {
            String where = "Tunnel " + v.tunnel.name() + ": ";
            List<IngressRule> before = v.baseline.routes();
            List<IngressRule> after = v.working.routes();
            for (IngressRule r : after) {
                IngressRule old = find(before, r);
                if (old == null) {
                    lines.add(where + "publish " + describe(r));
                } else if (!old.equals(r)) {
                    lines.add(where + "change " + describe(old) + "  ->  " + describe(r));
                }
            }
            for (IngressRule r : before) {
                if (find(after, r) == null) {
                    lines.add(where + "remove " + describe(r));
                }
            }
        }
        return lines;
    }

    /**
     * For each route of the tunnel that is staged, "New" or "Changed", keyed by hostname and path
     * (see {@link #routeKey}). Routes that are unchanged are absent.
     */
    public Map<String, String> routeStatus(Tunnel tunnel) {
        TunnelView v = view(tunnel);
        Map<String, String> status = new LinkedHashMap<>();
        List<IngressRule> before = v.baseline.routes();
        for (IngressRule r : v.working.routes()) {
            IngressRule old = find(before, r);
            if (old == null) {
                status.put(routeKey(r), "New");
            } else if (!old.equals(r)) {
                status.put(routeKey(r), "Changed");
            }
        }
        return status;
    }

    public static String routeKey(IngressRule route) {
        return route.hostname().toLowerCase(Locale.ROOT) + route.path();
    }

    /** True if applying would change something in this zone's DNS records. */
    public boolean hasStagedChanges(Zone zone) {
        DnsView v = dns.get(zone.id());
        return v != null && !changesOf(v).isEmpty();
    }

    /** True if applying would change this tunnel's routes. */
    public boolean hasStagedChanges(Tunnel tunnel) {
        TunnelView v = tunnels.get(tunnel.id());
        return v != null && !v.working.ingress().equals(v.baseline.ingress());
    }

    /** Drops everything loaded for a zone that no longer exists. */
    public void forget(Zone zone) {
        dns.remove(zone.id());
    }

    /** Drops everything loaded for a tunnel that no longer exists. */
    public void forget(Tunnel tunnel) {
        tunnels.remove(tunnel.id());
    }

    public void discardAll() {
        for (DnsView v : dns.values()) {
            v.working.clear();
            v.working.addAll(v.baseline);
        }
        for (TunnelView v : tunnels.values()) {
            v.working = v.baseline;
        }
    }

    // ---------------------------------------------------------------- applying

    /**
     * Sends the staged changes to Cloudflare and returns a line for each step done. Everything
     * is first read again to make sure nothing changed there since it was loaded; if it did,
     * nothing is sent. Order: DNS deletions, tunnel routes, then DNS additions and changes, so a
     * hostname never points at a route that doesn't exist yet, or stays after its route is gone.
     * <p>
     * Afterwards the touched zones and tunnels are dropped from the workspace, whatever the
     * outcome, because they no longer match what was loaded: load them again.
     */
    public List<String> apply() throws CloudflareException {
        List<DnsView> dnsTouched = new ArrayList<>();
        for (DnsView v : dns.values()) {
            if (!changesOf(v).isEmpty()) {
                dnsTouched.add(v);
            }
        }
        List<TunnelView> tunnelsTouched = new ArrayList<>();
        for (TunnelView v : tunnels.values()) {
            if (!v.working.ingress().equals(v.baseline.ingress())) {
                tunnelsTouched.add(v);
            }
        }
        preflight(dnsTouched, tunnelsTouched);

        List<String> done = new ArrayList<>();
        int total = changeCount();
        try {
            for (DnsView v : dnsTouched) {
                for (DnsChange c : changesOf(v)) {
                    if (c.kind() == Kind.DELETE) {
                        api.deleteDnsRecord(v.zone.id(), c.before().id());
                        done.add("Deleted " + describe(c.before()));
                    }
                }
            }
            for (TunnelView v : tunnelsTouched) {
                api.putTunnelConfig(v.accountId, v.tunnel.id(), v.working);
                done.add("Updated routes of tunnel " + v.tunnel.name());
            }
            for (DnsView v : dnsTouched) {
                for (DnsChange c : changesOf(v)) {
                    if (c.kind() == Kind.CREATE) {
                        api.createDnsRecord(v.zone.id(), c.after());
                        done.add("Added " + describe(c.after()));
                    } else if (c.kind() == Kind.UPDATE) {
                        api.updateDnsRecord(v.zone.id(), c.after());
                        done.add("Changed " + describe(c.after()));
                    }
                }
            }
        } catch (CloudflareException e) {
            dropTouched(dnsTouched, tunnelsTouched);
            throw new CloudflareException("Stopped after " + done.size() + " of " + total
                    + " changes. " + e.getMessage() + " Reload to see the current state.", e.httpStatus(), e);
        }
        dropTouched(dnsTouched, tunnelsTouched);
        return done;
    }

    private void preflight(List<DnsView> dnsTouched, List<TunnelView> tunnelsTouched) throws CloudflareException {
        for (DnsView v : dnsTouched) {
            List<DnsRecord> fresh = api.listDnsRecords(v.zone.id());
            for (DnsChange c : changesOf(v)) {
                if (c.kind() != Kind.CREATE && !fresh.contains(c.before())) {
                    throw new CloudflareException(describe(c.before()) + " was changed or removed on Cloudflare "
                            + "after you loaded " + v.zone.name() + ". Nothing was sent; reload and redo the change.");
                }
            }
        }
        for (TunnelView v : tunnelsTouched) {
            TunnelConfig fresh = api.getTunnelConfig(v.accountId, v.tunnel.id());
            if (!fresh.ingress().equals(v.baseline.ingress())) {
                throw new CloudflareException("The routes of tunnel " + v.tunnel.name() + " were changed on "
                        + "Cloudflare after you loaded them. Nothing was sent; reload and redo the change.");
            }
        }
    }

    private void dropTouched(List<DnsView> dnsTouched, List<TunnelView> tunnelsTouched) {
        for (DnsView v : dnsTouched) {
            dns.remove(v.zone.id());
        }
        for (TunnelView v : tunnelsTouched) {
            tunnels.remove(v.tunnel.id());
        }
    }

    // ---------------------------------------------------------------- helpers

    private static List<DnsChange> changesOf(DnsView v) {
        List<DnsChange> changes = new ArrayList<>();
        for (DnsRecord now : v.working) {
            int i = indexOf(v.baseline, now.id());
            if (i < 0) {
                changes.add(new DnsChange(Kind.CREATE, null, now));
            } else if (!v.baseline.get(i).equals(now)) {
                changes.add(new DnsChange(Kind.UPDATE, v.baseline.get(i), now));
            }
        }
        for (DnsRecord was : v.baseline) {
            if (indexOf(v.working, was.id()) < 0) {
                changes.add(new DnsChange(Kind.DELETE, was, null));
            }
        }
        return changes;
    }

    private static int indexOf(List<DnsRecord> records, String id) {
        for (int i = 0; i < records.size(); i++) {
            if (records.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private static IngressRule find(List<IngressRule> rules, IngressRule like) {
        for (IngressRule r : rules) {
            if (r.sameRoute(like.hostname(), like.path())) {
                return r;
            }
        }
        return null;
    }

    private static IngressRule withPath(String host, String path, String service) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("hostname", host);
        raw.put("path", path);
        raw.put("service", service);
        return new IngressRule(raw);
    }

    private static String describe(DnsRecord r) {
        String mode = PROXIABLE_TYPES.contains(r.type()) ? (r.proxied() ? ", proxied" : ", DNS only") : "";
        return r.type() + " " + r.name() + " -> " + r.content() + mode;
    }

    private static String describe(IngressRule r) {
        return r.hostname() + r.path() + " -> " + r.service();
    }

    private void requireValid(Zone zone, DnsRecord record) {
        List<String> problems = dnsProblems(zone, record);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join(" ", problems));
        }
    }

    private static void requireValidRoute(IngressRule route) {
        List<String> problems = routeProblems(route.hostname(), route.path(), route.service());
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join(" ", problems));
        }
    }

    private static boolean isIpv4(String s) {
        var m = IPV4.matcher(s);
        if (!m.matches()) {
            return false;
        }
        for (int i = 1; i <= 4; i++) {
            if (Integer.parseInt(m.group(i)) > 255) {
                return false;
            }
        }
        return true;
    }

    private DnsView view(Zone zone) {
        DnsView v = dns.get(zone.id());
        if (v == null) {
            throw new IllegalStateException("The records of " + zone.name() + " are not loaded.");
        }
        return v;
    }

    private TunnelView view(Tunnel tunnel) {
        TunnelView v = tunnels.get(tunnel.id());
        if (v == null) {
            throw new IllegalStateException("The routes of tunnel " + tunnel.name() + " are not loaded.");
        }
        return v;
    }
}
