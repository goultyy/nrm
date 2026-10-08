package mt.su.nrm.cloudflare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CloudflareSessionTest {

    private static final Zone A = new Zone("za", "a.com", "acc");
    private static final Zone B = new Zone("zb", "b.com", "acc");
    private static final Tunnel T = new Tunnel("t1", "edge", "healthy");

    private final List<String> calls = new ArrayList<>();
    private CloudflareSession session;

    @BeforeEach
    void setUp() throws Exception {
        CloudflareApi api = new CloudflareApi() {
            @Override
            public List<Zone> verifyToken() {
                return listZones();
            }

            @Override
            public List<Zone> listZones() {
                return List.of(B, A);
            }

            @Override
            public List<Tunnel> listTunnels(String accountId) {
                return List.of(T);
            }

            @Override
            public TunnelConfig getTunnelConfig(String accountId, String tunnelId) {
                return TunnelConfig.fromReply(Json.parse("{\"source\":\"cloudflare\",\"config\":{\"ingress\":["
                        + "{\"service\":\"http_status:404\"}]}}"));
            }

            @Override
            public void putTunnelConfig(String accountId, String tunnelId, TunnelConfig config) {
            }

            @Override
            public void deleteZone(String zoneId) {
                calls.add("deleteZone " + zoneId);
            }

            @Override
            public void deleteTunnel(String accountId, String tunnelId) {
                calls.add("deleteTunnel " + accountId + " " + tunnelId);
            }

            @Override
            public ZoneCreated createZone(String accountId, String name) {
                calls.add("createZone " + name);
                return new ZoneCreated(new Zone("zc", name, accountId), List.of("ns1", "ns2"), "pending");
            }

            @Override
            public TunnelCreated createTunnel(String accountId, String name) {
                calls.add("createTunnel " + name);
                return new TunnelCreated(new Tunnel("t2", name, "inactive"), accountId, "tok");
            }

            @Override
            public List<DnsRecord> listDnsRecords(String zoneId) {
                return List.of(new DnsRecord("r1", "A", "www.a.com", "1.2.3.4", false, 1, ""));
            }

            @Override
            public DnsRecord createDnsRecord(String zoneId, DnsRecord record) {
                return record;
            }

            @Override
            public DnsRecord updateDnsRecord(String zoneId, DnsRecord record) {
                return record;
            }

            @Override
            public void deleteDnsRecord(String zoneId, String recordId) {
            }
        };
        session = CloudflareSession.open("tok", api);
    }

    @Test
    void zonesAreListedByNameAndAccountsByTheirZones() {
        assertEquals(List.of(A, B), session.zones());
        assertEquals(List.of("acc"), session.accountIds());
        assertEquals("a.com, b.com", session.accountLabel("acc"));
        assertEquals("other", session.accountLabel("other"));
    }

    @Test
    void deletingGoesToTheApiWithTheRightIds() throws Exception {
        session.deleteZone(A);
        session.deleteTunnel(T);
        assertEquals(List.of("deleteZone za", "deleteTunnel acc t1"), calls);
    }

    @Test
    void withoutZoneDropsItAndWhatWasLoadedForIt() throws Exception {
        session.workspace().loadDns(A);
        CloudflareSession after = session.withoutZone(A);
        assertEquals(List.of(B), after.zones());
        assertFalse(session.workspace().isDnsLoaded(A));
        assertEquals(List.of(A, B), session.zones(), "the old session is unchanged");
    }

    @Test
    void withoutTunnelDropsItAndItsAccountMapping() throws Exception {
        session.workspace().loadTunnel("acc", T);
        CloudflareSession after = session.withoutTunnel(T);
        assertTrue(after.tunnels().isEmpty());
        assertFalse(session.workspace().isTunnelLoaded(T));
    }

    @Test
    void addingAThingKeepsTheStagedChanges() throws Exception {
        session.workspace().loadDns(A);
        session.workspace().stageDnsDelete(A, "r1");
        ZoneCreated created = session.createZone("acc", "c.com");
        CloudflareSession after = session.withZone(created.zone());
        assertEquals(List.of(A, B, created.zone()), after.zones());
        assertSame(session.workspace(), after.workspace(), "staged changes must be shared, not copied");
        assertTrue(after.workspace().hasStagedChanges(A));

        TunnelCreated tunnel = session.createTunnel("acc", "edge2");
        CloudflareSession withTunnel = after.withTunnel(tunnel.tunnel(), tunnel.accountId());
        assertEquals("acc", withTunnel.accountOf(tunnel.tunnel()));
    }

    @Test
    void aTunnelWithAnUnknownAccountCannotBeDeleted() {
        Tunnel stranger = new Tunnel("tx", "stranger", "down");
        assertThrows(CloudflareException.class, () -> session.deleteTunnel(stranger));
        assertTrue(calls.isEmpty());
    }
}
