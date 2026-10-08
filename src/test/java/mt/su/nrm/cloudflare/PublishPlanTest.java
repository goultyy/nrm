package mt.su.nrm.cloudflare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PublishPlanTest {

    private static final Zone ZONE = new Zone("z1", "example.com", "acc");
    private static final Tunnel TUNNEL = new Tunnel("t1", "edge", "healthy");

    private CloudflareWorkspace ws;

    @BeforeEach
    void setUp() throws Exception {
        CloudflareApi api = new CloudflareApi() {
            @Override
            public List<Zone> verifyToken() {
                return listZones();
            }

            @Override
            public List<Zone> listZones() {
                return List.of(ZONE);
            }

            @Override
            public List<Tunnel> listTunnels(String accountId) {
                return List.of(TUNNEL);
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
                throw new AssertionError("a plan never deletes zones");
            }

            @Override
            public void deleteTunnel(String accountId, String tunnelId) {
                throw new AssertionError("a plan never deletes tunnels");
            }

            @Override
            public ZoneCreated createZone(String accountId, String name) {
                throw new AssertionError("a plan never creates zones");
            }

            @Override
            public TunnelCreated createTunnel(String accountId, String name) {
                throw new AssertionError("a plan never creates tunnels");
            }

            @Override
            public List<DnsRecord> listDnsRecords(String zoneId) {
                return List.of(new DnsRecord("r1", "A", "www.example.com", "203.0.113.10", true, 1, ""));
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
        ws = new CloudflareWorkspace(api);
        ws.loadDns(ZONE);
        ws.loadTunnel("acc", TUNNEL);
    }

    @Test
    void anIpv4AddressMakesAnARecordAndIpv6AnAaaa() {
        assertEquals("A", PublishPlan.dnsRecord(ZONE, "app.example.com", "203.0.113.20", true).recordType());
        assertEquals("AAAA", PublishPlan.dnsRecord(ZONE, "app.example.com", "2001:db8::20", true).recordType());
    }

    @Test
    void aDnsPlanStagesOneRecord() {
        PublishPlan plan = PublishPlan.dnsRecord(ZONE, "App.Example.com", "203.0.113.20", true);
        plan.stageInto(ws);
        assertEquals(1, ws.changeCount());
        assertTrue(ws.summary().get(0).contains("add A app.example.com -> 203.0.113.20"), ws.summary().toString());
    }

    @Test
    void aTunnelPlanStagesTheRouteAndTheCname() {
        PublishPlan.tunnel(ZONE, TUNNEL, "app.example.com", "http://localhost:8080", false).stageInto(ws);
        assertEquals(2, ws.changeCount());
        assertEquals("http://localhost:8080", ws.tunnelConfig(TUNNEL).routes().get(0).service());
    }

    @Test
    void skippingCertificateVerificationIsCarriedOnTheRoute() {
        PublishPlan.tunnel(ZONE, TUNNEL, "app.example.com", "https://localhost:443", true).stageInto(ws);
        IngressRule route = ws.tunnelConfig(TUNNEL).routes().get(0);
        assertEquals(Boolean.TRUE, Json.asObject(route.raw().get("originRequest")).get("noTLSVerify"));
    }

    @Test
    void withoutTheOptionNoOriginSettingsAreAdded() {
        PublishPlan.tunnel(ZONE, TUNNEL, "app.example.com", "https://localhost:443", false).stageInto(ws);
        assertFalse(ws.tunnelConfig(TUNNEL).routes().get(0).raw().containsKey("originRequest"));
    }

    @Test
    void aHostnameOutsideTheZoneIsRefused() {
        PublishPlan plan = PublishPlan.dnsRecord(ZONE, "app.other.org", "203.0.113.20", true);
        assertFalse(plan.hostProblems().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> plan.stageInto(ws));
        assertFalse(ws.hasChanges());
    }

    @Test
    void aMissingZoneOrTunnelIsReported() {
        assertTrue(PublishPlan.dnsRecord(null, "app.example.com", "203.0.113.20", true).hostProblems()
                .get(0).contains("zone"));
        assertTrue(PublishPlan.tunnel(ZONE, null, "app.example.com", "http://localhost:80", false)
                .targetProblems(ws).contains("Choose a tunnel."));
    }

    @Test
    void badAddressesAndServicesAreReportedWithTheStagingRules() {
        assertFalse(PublishPlan.dnsRecord(ZONE, "app.example.com", "999.1.1.1", true).targetProblems(ws).isEmpty());
        assertFalse(PublishPlan.tunnel(ZONE, TUNNEL, "app.example.com", "localhost:80", false)
                .targetProblems(ws).isEmpty());
    }

    @Test
    void anIdenticalExistingRecordIsRefusedButAnotherAddressIsAllowed() {
        assertFalse(PublishPlan.dnsRecord(ZONE, "www.example.com", "203.0.113.10", true).targetProblems(ws).isEmpty());
        assertTrue(PublishPlan.dnsRecord(ZONE, "www.example.com", "203.0.113.20", true).targetProblems(ws).isEmpty());
    }

    @Test
    void aTunnelPlanCannotReplaceAnExistingARecord() {
        // A tunnel needs a CNAME at the hostname, and a CNAME can't share its name with the existing A record.
        assertThrows(IllegalArgumentException.class, () ->
                PublishPlan.tunnel(ZONE, TUNNEL, "www.example.com", "http://localhost:80", false).stageInto(ws));
        assertFalse(ws.hasChanges());
    }

    @Test
    void existingFindsADnsRecordIgnoringCase() {
        PublishPlan.Existing e = PublishPlan.existing(ws, ZONE, List.of(TUNNEL), "WWW.Example.com");
        assertEquals(1, e.found().size());
        assertTrue(e.found().get(0).contains("A www.example.com -> 203.0.113.10"), e.found().toString());
        assertTrue(e.dnsChecked() && e.tunnelsChecked());
    }

    @Test
    void existingFindsATunnelRouteAndMarksStagedOnes() {
        ws.stageRouteAdd(TUNNEL, IngressRule.route("app.example.com", "http://localhost:8080"));
        ws.stageDnsCreate(ZONE, DnsRecord.of("TXT", "app.example.com", "hello", false));
        PublishPlan.Existing e = PublishPlan.existing(ws, ZONE, List.of(TUNNEL), "app.example.com");
        assertEquals(2, e.found().size(), e.found().toString());
        assertTrue(e.found().stream().anyMatch(l -> l.contains("Tunnel edge already publishes app.example.com")));
        assertTrue(e.found().stream().anyMatch(l -> l.contains("TXT") && l.contains("staged")));
    }

    @Test
    void aFreeNameHasNothingExisting() {
        assertTrue(PublishPlan.existing(ws, ZONE, List.of(TUNNEL), "free.example.com").found().isEmpty());
        assertTrue(PublishPlan.existing(ws, ZONE, List.of(TUNNEL), "").found().isEmpty());
    }

    @Test
    void whatIsNotLoadedIsReportedAsNotChecked() {
        CloudflareWorkspace fresh = new CloudflareWorkspace(null);
        PublishPlan.Existing e = PublishPlan.existing(fresh, ZONE, List.of(TUNNEL), "www.example.com");
        assertFalse(e.dnsChecked());
        assertFalse(e.tunnelsChecked());
        assertTrue(e.found().isEmpty(), "nothing can be claimed about what isn't loaded");
        assertTrue(PublishPlan.existing(fresh, null, List.of(), "x.example.com").tunnelsChecked(),
                "no tunnels means nothing is left unchecked");
    }

    @Test
    void aTunnelPlanIsBlockedEarlyByAnExistingRecordOrRoute() {
        List<String> clash = PublishPlan.tunnel(ZONE, TUNNEL, "www.example.com", "http://localhost:80", false)
                .targetProblems(ws);
        assertTrue(clash.stream().anyMatch(p -> p.contains("already has a A record")), clash.toString());

        ws.stageRouteAdd(TUNNEL, IngressRule.route("app.example.com", "http://localhost:1"));
        List<String> published = PublishPlan.tunnel(ZONE, TUNNEL, "app.example.com", "http://localhost:80", false)
                .targetProblems(ws);
        assertTrue(published.stream().anyMatch(p -> p.contains("already published on tunnel edge")), published.toString());
    }

    @Test
    void aTunnelPlanIsNotBlockedByACnameAlreadyPointingAtTheTunnel() throws Exception {
        ws.stageDnsCreate(ZONE, DnsRecord.of("CNAME", "app.example.com", "t1.cfargotunnel.com", true));
        assertTrue(PublishPlan.tunnel(ZONE, TUNNEL, "app.example.com", "http://localhost:80", false)
                .targetProblems(ws).isEmpty());
    }

    @Test
    void onlyRealHostnamesAreOfferedFromServerNames() {
        assertEquals(List.of("app.example.com", "*.example.com", "example.com"),
                PublishPlan.hostnameChoices(List.of("App.Example.com", "_", "~^www\\d+\\.example\\.com$", "localhost",
                        "*.example.com", "", ".example.com", "$host", "app.example.com")));
    }

    @Test
    void theReviewLinesNameEveryChange() {
        List<String> tunnel = PublishPlan.tunnel(ZONE, TUNNEL, "app.example.com", "http://localhost:80", false).describe();
        assertEquals(2, tunnel.size());
        assertTrue(tunnel.get(1).contains("t1.cfargotunnel.com"));
        assertEquals(1, PublishPlan.dnsRecord(ZONE, "app.example.com", "203.0.113.20", false).describe().size());
    }
}
