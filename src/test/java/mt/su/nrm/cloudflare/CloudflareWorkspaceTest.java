package mt.su.nrm.cloudflare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CloudflareWorkspaceTest {

    /** An in-memory Cloudflare that remembers the order of the changes it receives. */
    private static final class Fake implements CloudflareApi {
        final List<DnsRecord> records = new ArrayList<>();
        TunnelConfig config = TunnelConfig.fromReply(Json.parse(
                "{\"source\":\"cloudflare\",\"config\":{\"ingress\":["
                        + "{\"hostname\":\"old.example.com\",\"service\":\"http://localhost:1\"},"
                        + "{\"service\":\"http_status:404\"}]}}"));
        final List<String> calls = new ArrayList<>();
        String failOn = "";
        int nextId = 100;

        @Override
        public List<Zone> verifyToken() {
            return listZones();
        }

        @Override
        public List<Zone> listZones() {
            return List.of(new Zone("z1", "example.com", "acc"));
        }

        @Override
        public List<Tunnel> listTunnels(String accountId) {
            return List.of(new Tunnel("t1", "edge", "healthy"));
        }

        @Override
        public TunnelConfig getTunnelConfig(String accountId, String tunnelId) {
            return config;
        }

        @Override
        public void putTunnelConfig(String accountId, String tunnelId, TunnelConfig c) throws CloudflareException {
            fail("put");
            calls.add("put");
            config = c;
        }

        @Override
        public void deleteZone(String zoneId) {
            throw new AssertionError("the workspace never deletes zones");
        }

        @Override
        public void deleteTunnel(String accountId, String tunnelId) {
            throw new AssertionError("the workspace never deletes tunnels");
        }

        @Override
        public ZoneCreated createZone(String accountId, String name) {
            throw new AssertionError("the workspace never creates zones");
        }

        @Override
        public TunnelCreated createTunnel(String accountId, String name) {
            throw new AssertionError("the workspace never creates tunnels");
        }

        @Override
        public List<DnsRecord> listDnsRecords(String zoneId) {
            return List.copyOf(records);
        }

        @Override
        public DnsRecord createDnsRecord(String zoneId, DnsRecord r) throws CloudflareException {
            fail("create");
            calls.add("create " + r.name());
            DnsRecord stored = new DnsRecord("r" + nextId++, r.type(), r.name(), r.content(), r.proxied(), r.ttl(),
                    r.comment());
            records.add(stored);
            return stored;
        }

        @Override
        public DnsRecord updateDnsRecord(String zoneId, DnsRecord r) throws CloudflareException {
            fail("update");
            calls.add("update " + r.name());
            records.replaceAll(x -> x.id().equals(r.id()) ? r : x);
            return r;
        }

        @Override
        public void deleteDnsRecord(String zoneId, String id) throws CloudflareException {
            fail("delete");
            calls.add("delete " + id);
            records.removeIf(r -> r.id().equals(id));
        }

        private void fail(String what) throws CloudflareException {
            if (failOn.equals(what)) {
                throw new CloudflareException("Cloudflare refused the " + what + ".");
            }
        }
    }

    private static final Zone ZONE = new Zone("z1", "example.com", "acc");
    private static final Tunnel TUNNEL = new Tunnel("t1", "edge", "healthy");

    private Fake fake;
    private CloudflareWorkspace ws;

    @BeforeEach
    void setUp() throws Exception {
        fake = new Fake();
        fake.records.add(new DnsRecord("r1", "A", "www.example.com", "203.0.113.10", true, 1, ""));
        fake.records.add(new DnsRecord("r2", "TXT", "example.com", "v=spf1 -all", false, 1, ""));
        ws = new CloudflareWorkspace(fake);
        ws.loadDns(ZONE);
        ws.loadTunnel("acc", TUNNEL);
    }

    private static DnsRecord cname(String name, String target) {
        return DnsRecord.of("CNAME", name, target, true);
    }

    @Test
    void nothingIsStagedAfterLoading() {
        assertFalse(ws.hasChanges());
        assertEquals(2, ws.dnsRecords(ZONE).size());
    }

    @Test
    void stagedChangesAreSummarisedAndNothingIsSentYet() {
        ws.stageDnsCreate(ZONE, cname("app.example.com", "t1.cfargotunnel.com"));
        ws.stageDnsUpdate(ZONE, new DnsRecord("r1", "A", "www.example.com", "203.0.113.99", true, 1, ""));
        ws.stageDnsDelete(ZONE, "r2");
        ws.stageRouteAdd(TUNNEL, IngressRule.route("app.example.com", "http://localhost:8080"));

        assertEquals(4, ws.changeCount());
        List<String> lines = ws.summary();
        assertTrue(lines.stream().anyMatch(l -> l.contains("add CNAME app.example.com")), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.contains("203.0.113.99")), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.contains("delete TXT")), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.contains("publish app.example.com")), lines.toString());
        assertEquals(List.of(), fake.calls);
    }

    @Test
    void stagedChangesAreTrackedPerZoneAndPerTunnel() {
        assertFalse(ws.hasStagedChanges(ZONE));
        assertFalse(ws.hasStagedChanges(TUNNEL));
        ws.stageDnsDelete(ZONE, "r2");
        assertTrue(ws.hasStagedChanges(ZONE));
        assertFalse(ws.hasStagedChanges(TUNNEL));
        ws.stageRouteRemove(TUNNEL, "old.example.com", "");
        assertTrue(ws.hasStagedChanges(TUNNEL), "a staged removal is a change too");
        ws.discardAll();
        assertFalse(ws.hasStagedChanges(ZONE) || ws.hasStagedChanges(TUNNEL));
    }

    @Test
    void forgettingAZoneOrTunnelDropsWhatWasLoadedForIt() {
        ws.forget(ZONE);
        ws.forget(TUNNEL);
        assertFalse(ws.isDnsLoaded(ZONE));
        assertFalse(ws.isTunnelLoaded(TUNNEL));
        assertFalse(ws.hasStagedChanges(ZONE));
    }

    @Test
    void discardAllRestoresTheLoadedState() {
        ws.stageDnsDelete(ZONE, "r1");
        ws.stageRouteRemove(TUNNEL, "old.example.com", "");
        ws.discardAll();
        assertFalse(ws.hasChanges());
        assertEquals(2, ws.dnsRecords(ZONE).size());
        assertEquals(1, ws.tunnelConfig(TUNNEL).routes().size());
    }

    @Test
    void editingARecordBackToItsOriginalIsNotAChange() {
        ws.stageDnsUpdate(ZONE, new DnsRecord("r1", "A", "www.example.com", "1.2.3.4", true, 1, ""));
        ws.stageDnsUpdate(ZONE, new DnsRecord("r1", "A", "www.example.com", "203.0.113.10", true, 1, ""));
        assertFalse(ws.hasChanges());
    }

    @Test
    void invalidRecordsAreRefusedWithAReason() {
        assertThrows(IllegalArgumentException.class,
                () -> ws.stageDnsCreate(ZONE, DnsRecord.of("A", "a.example.com", "999.1.1.1", false)));
        assertThrows(IllegalArgumentException.class,
                () -> ws.stageDnsCreate(ZONE, DnsRecord.of("A", "a.other.org", "1.2.3.4", false)));
        assertThrows(IllegalArgumentException.class,
                () -> ws.stageDnsCreate(ZONE, DnsRecord.of("TXT", "t.example.com", "x", true)));
        assertThrows(IllegalArgumentException.class, () -> ws.stageDnsCreate(ZONE,
                new DnsRecord("", "A", "b.example.com", "1.2.3.4", true, 300, "")));
        assertThrows(IllegalArgumentException.class,
                () -> ws.stageDnsCreate(ZONE, DnsRecord.of("MX", "m.example.com", "mail.example.com", false)));
        assertFalse(ws.hasChanges());
    }

    @Test
    void aCnameCannotShareANameWithAnotherRecord() {
        assertThrows(IllegalArgumentException.class,
                () -> ws.stageDnsCreate(ZONE, cname("www.example.com", "t1.cfargotunnel.com")));
        ws.stageDnsCreate(ZONE, cname("app.example.com", "t1.cfargotunnel.com"));
        assertThrows(IllegalArgumentException.class,
                () -> ws.stageDnsCreate(ZONE, DnsRecord.of("A", "app.example.com", "1.2.3.4", false)));
    }

    @Test
    void aRecordsTypeCannotChange() {
        assertThrows(IllegalArgumentException.class,
                () -> ws.stageDnsUpdate(ZONE, new DnsRecord("r1", "AAAA", "www.example.com", "::1", true, 1, "")));
    }

    @Test
    void publishStagesTheRouteAndTheCname() {
        ws.stagePublish(ZONE, TUNNEL, "App.Example.com", "", "http://localhost:8080");
        assertEquals(2, ws.changeCount());
        DnsRecord created = ws.dnsRecords(ZONE).stream().filter(r -> r.name().equals("app.example.com"))
                .findFirst().orElseThrow();
        assertEquals("t1.cfargotunnel.com", created.content());
        assertTrue(created.proxied());
        assertTrue(CloudflareWorkspace.isNew(created));
        assertEquals("app.example.com", ws.tunnelConfig(TUNNEL).routes().get(1).hostname());
    }

    @Test
    void publishStagesNothingWhenOnePartIsRefused() {
        // old.example.com is already a route, so the CNAME must not be staged either.
        assertThrows(IllegalArgumentException.class,
                () -> ws.stagePublish(ZONE, TUNNEL, "old.example.com", "", "http://localhost:8080"));
        assertFalse(ws.hasChanges());
        // www.example.com has an A record, which a tunnel CNAME would replace.
        assertThrows(IllegalArgumentException.class,
                () -> ws.stagePublish(ZONE, TUNNEL, "www.example.com", "", "http://localhost:8080"));
        assertFalse(ws.hasChanges());
    }

    @Test
    void publishReusesACnameThatAlreadyPointsAtTheTunnel() throws Exception {
        fake.records.add(new DnsRecord("r3", "CNAME", "app.example.com", "t1.cfargotunnel.com", true, 1, ""));
        ws.loadDns(ZONE);
        ws.stagePublish(ZONE, TUNNEL, "app.example.com", "", "http://localhost:8080");
        assertEquals(1, ws.changeCount(), "only the route is new: " + ws.summary());
    }

    @Test
    void publishWithAPathKeepsItInTheRoute() {
        ws.stagePublish(ZONE, TUNNEL, "app.example.com", "^/api/", "http://localhost:9000");
        assertEquals("^/api/", ws.tunnelConfig(TUNNEL).routes().get(1).path());
    }

    @Test
    void unpublishRemovesTheCnameOnlyWhenNoRouteStillUsesTheHostname() throws Exception {
        ws.stagePublish(ZONE, TUNNEL, "app.example.com", "", "http://localhost:1");
        ws.stagePublish(ZONE, TUNNEL, "app.example.com", "^/api/", "http://localhost:2");
        ws.stageUnpublish(ZONE, TUNNEL, "app.example.com", "^/api/", true);
        assertTrue(ws.dnsRecords(ZONE).stream().anyMatch(r -> r.name().equals("app.example.com")),
                "the other route still needs the CNAME");
        ws.stageUnpublish(ZONE, TUNNEL, "app.example.com", "", true);
        assertTrue(ws.dnsRecords(ZONE).stream().noneMatch(r -> r.name().equals("app.example.com")));
    }

    @Test
    void unpublishLeavesADnsRecordThatDoesNotPointAtTheTunnel() throws Exception {
        fake.records.add(new DnsRecord("r9", "CNAME", "old.example.com", "elsewhere.example.net", false, 1, ""));
        ws.loadDns(ZONE);
        ws.stageUnpublish(ZONE, TUNNEL, "old.example.com", "", true);
        assertEquals(1, ws.changeCount());
    }

    @Test
    void applySendsDeletesThenRoutesThenAdditions() throws Exception {
        ws.stageDnsDelete(ZONE, "r2");
        ws.stagePublish(ZONE, TUNNEL, "app.example.com", "", "http://localhost:8080");
        ws.stageDnsUpdate(ZONE, new DnsRecord("r1", "A", "www.example.com", "203.0.113.99", true, 1, ""));

        List<String> done = ws.apply();

        // Deletions first, then the tunnel, then the DNS additions and changes (in either order).
        assertEquals(List.of("delete r2", "put"), fake.calls.subList(0, 2));
        assertEquals(java.util.Set.of("create app.example.com", "update www.example.com"),
                new java.util.HashSet<>(fake.calls.subList(2, 4)));
        assertEquals(4, fake.calls.size());
        assertEquals(4, done.size());
        assertTrue(fake.config.routes().stream().anyMatch(r -> r.hostname().equals("app.example.com")));
    }

    @Test
    void applyDropsTheViewsSoTheyAreLoadedAgain() throws Exception {
        ws.stageDnsDelete(ZONE, "r2");
        ws.apply();
        assertFalse(ws.isDnsLoaded(ZONE));
        assertTrue(ws.isTunnelLoaded(TUNNEL), "an untouched tunnel stays loaded");
    }

    @Test
    void applyRefusesAndSendsNothingIfCloudflareChangedMeanwhile() {
        ws.stageDnsUpdate(ZONE, new DnsRecord("r1", "A", "www.example.com", "203.0.113.99", true, 1, ""));
        ws.stageDnsDelete(ZONE, "r2");
        fake.records.replaceAll(r -> r.id().equals("r2")
                ? new DnsRecord("r2", "TXT", "example.com", "someone-else-edited-this", false, 1, "") : r);

        CloudflareException e = assertThrows(CloudflareException.class, () -> ws.apply());
        assertTrue(e.getMessage().contains("Nothing was sent"), e.getMessage());
        assertEquals(List.of(), fake.calls);
    }

    @Test
    void applyRefusesIfTheTunnelRoutesChangedMeanwhile() {
        ws.stageRouteAdd(TUNNEL, IngressRule.route("app.example.com", "http://localhost:8080"));
        fake.config = fake.config.withRoute(IngressRule.route("sneaky.example.com", "http://localhost:3"));
        CloudflareException e = assertThrows(CloudflareException.class, () -> ws.apply());
        assertTrue(e.getMessage().contains("Nothing was sent"), e.getMessage());
        assertEquals(List.of(), fake.calls);
    }

    @Test
    void aFailurePartWayReportsHowFarItGot() {
        ws.stageDnsDelete(ZONE, "r2");
        ws.stagePublish(ZONE, TUNNEL, "app.example.com", "", "http://localhost:8080");
        fake.failOn = "create";

        CloudflareException e = assertThrows(CloudflareException.class, () -> ws.apply());
        assertTrue(e.getMessage().contains("Stopped after 2 of 3 changes"), e.getMessage());
        assertEquals(List.of("delete r2", "put"), fake.calls);
        assertFalse(ws.isDnsLoaded(ZONE), "stale state must not be kept after a partial failure");
    }

    @Test
    void routeProblemsAreWordedForTheUser() {
        assertTrue(CloudflareWorkspace.routeProblems("app.example.com", "", "http://localhost:8080").isEmpty());
        assertTrue(CloudflareWorkspace.routeProblems("*.example.com", "^/x", "https://10.0.0.5:8443").isEmpty());
        assertFalse(CloudflareWorkspace.routeProblems("", "", "http://localhost").isEmpty());
        assertFalse(CloudflareWorkspace.routeProblems("localhost", "", "http://localhost").isEmpty());
        assertFalse(CloudflareWorkspace.routeProblems("a.example.com", "([", "http://localhost").isEmpty());
        assertFalse(CloudflareWorkspace.routeProblems("a.example.com", "", "localhost:8080").isEmpty());
        assertFalse(CloudflareWorkspace.routeProblems("a.example.com", "", "").isEmpty());
    }
}
