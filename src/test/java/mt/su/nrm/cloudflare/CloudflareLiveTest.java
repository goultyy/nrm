package mt.su.nrm.cloudflare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import mt.su.nrm.ssh.CommandLog;
import org.junit.jupiter.api.Test;

/**
 * Read-only checks against the real Cloudflare API. Skipped unless NRM_TEST_CF_TOKEN is set, like
 * the SSH tests that need a real server. The token is never written to a file.
 */
class CloudflareLiveTest {

    private CloudflareClient client(CommandLog log) {
        String token = System.getenv("NRM_TEST_CF_TOKEN");
        assumeTrue(token != null && !token.isBlank(), "NRM_TEST_CF_TOKEN not set");
        return new CloudflareClient(token, log);
    }

    @Test
    void verifiesListsZonesAndTunnels() throws Exception {
        CommandLog log = new CommandLog();
        CloudflareClient client = client(log);

        client.verifyToken();
        List<Zone> zones = client.listZones();
        System.out.println("zones: " + zones);
        assertFalse(zones.isEmpty(), "the token can see no zones");

        for (String accountId : zones.stream().map(Zone::accountId).distinct().toList()) {
            System.out.println("tunnels in " + accountId + ": " + client.listTunnels(accountId));
        }
        for (CommandLog.Line line : log.snapshot()) {
            assertFalse(line.text().contains(System.getenv("NRM_TEST_CF_TOKEN")), "token leaked into the log");
        }
    }

    /**
     * Creates a throwaway tunnel, checks its run token stays out of the command log, and deletes it again. Zone
     * creation is deliberately not tested live: a domain added to a real account is not something a test should leave
     * behind. Needs NRM_TEST_CF_WRITE=1 and NRM_TEST_CF_ZONE (to find the account).
     */
    @Test
    void createsAndDeletesAThrowawayTunnel() throws Exception {
        assumeTrue("1".equals(System.getenv("NRM_TEST_CF_WRITE")), "NRM_TEST_CF_WRITE=1 not set");
        String zoneName = System.getenv("NRM_TEST_CF_ZONE");
        assumeTrue(zoneName != null, "NRM_TEST_CF_ZONE not set");

        CommandLog log = new CommandLog();
        CloudflareClient client = client(log);
        Zone zone = client.listZones().stream().filter(z -> z.name().equals(zoneName)).findFirst().orElseThrow();
        String name = "nrm-test-" + Long.toHexString(System.nanoTime());

        TunnelCreated created = null;
        try {
            final TunnelCreated made = client.createTunnel(zone.accountId(), name);
            created = made;
            assertEquals(name, made.tunnel().name());
            assertFalse(made.token().isBlank(), "no run token came back");
            assertFalse(log.snapshot().stream().anyMatch(l -> l.text().contains(made.token())),
                    "the tunnel's run token leaked into the command log");
            assertTrue(client.listTunnels(zone.accountId()).stream().anyMatch(t -> t.name().equals(name)));
        } finally {
            if (created != null) {
                client.deleteTunnel(zone.accountId(), created.tunnel().id());
            }
        }
        assertFalse(client.listTunnels(zone.accountId()).stream().anyMatch(t -> t.name().equals(name)),
                "the throwaway tunnel was not deleted");
        System.out.println("live tunnel create/delete ok for " + name);
    }

    /**
     * Adds one route to a real tunnel and puts the original configuration back. The route's
     * hostname has no DNS record, so nothing can reach it. Same environment variables as the DNS
     * test. The original is restored in a finally block and then compared with what Cloudflare
     * holds.
     */
    @Test
    void addsAThrowawayRouteAndRestoresTheTunnel() throws Exception {
        assumeTrue("1".equals(System.getenv("NRM_TEST_CF_WRITE")), "NRM_TEST_CF_WRITE=1 not set");
        String zoneName = System.getenv("NRM_TEST_CF_ZONE");
        String tunnelName = System.getenv("NRM_TEST_CF_TUNNEL");
        String parent = System.getenv("NRM_TEST_CF_PREFIX");
        assumeTrue(zoneName != null && tunnelName != null && parent != null, "zone/tunnel/prefix not set");

        CloudflareClient client = client(new CommandLog());
        Zone zone = client.listZones().stream().filter(z -> z.name().equals(zoneName)).findFirst().orElseThrow();
        Tunnel tunnel = client.listTunnels(zone.accountId()).stream()
                .filter(t -> t.name().equals(tunnelName)).findFirst().orElseThrow();
        String account = zone.accountId();

        TunnelConfig original = client.getTunnelConfig(account, tunnel.id());
        assumeTrue(original.isRemotelyManaged(), "the tunnel is configured by a file on the server");
        List<IngressRule> before = original.ingress();
        System.out.println("original ingress has " + before.size() + " rules");

        String hostname = "nrm-test-" + Long.toHexString(System.nanoTime()) + "." + parent;
        assertTrue(hostname.endsWith("." + zone.name()));
        boolean changed = false;
        try {
            TunnelConfig edited = original.withRoute(IngressRule.route(hostname, "http://localhost:9"));
            client.putTunnelConfig(account, tunnel.id(), edited);
            changed = true;

            List<IngressRule> during = client.getTunnelConfig(account, tunnel.id()).ingress();
            assertEquals(before.size() + 1, during.size());
            assertTrue(during.stream().anyMatch(r -> r.hostname().equals(hostname)));
            assertTrue(during.get(during.size() - 1).isCatchAll());
            // Every original rule must still be there, in order, unchanged.
            List<IngressRule> without = during.stream().filter(r -> !r.hostname().equals(hostname)).toList();
            assertEquals(before, without, "an existing rule changed while adding the test route");
        } finally {
            if (changed) {
                client.putTunnelConfig(account, tunnel.id(), original);
            }
        }
        List<IngressRule> after = client.getTunnelConfig(account, tunnel.id()).ingress();
        assertEquals(before, after, "the tunnel was not restored to its original rules");
        System.out.println("live tunnel route round trip ok for " + hostname);
    }

    /**
     * Creates, edits and deletes one throwaway CNAME. Needs NRM_TEST_CF_WRITE=1 as well as the
     * token, NRM_TEST_CF_ZONE (e.g. sch.mt), NRM_TEST_CF_TUNNEL (the tunnel's name) and
     * NRM_TEST_CF_PREFIX (e.g. test.sch.mt, under which the record is made).
     */
    @Test
    void createsEditsAndDeletesAThrowawayRecord() throws Exception {
        assumeTrue("1".equals(System.getenv("NRM_TEST_CF_WRITE")), "NRM_TEST_CF_WRITE=1 not set");
        String zoneName = System.getenv("NRM_TEST_CF_ZONE");
        String tunnelName = System.getenv("NRM_TEST_CF_TUNNEL");
        String parent = System.getenv("NRM_TEST_CF_PREFIX");
        assumeTrue(zoneName != null && tunnelName != null && parent != null, "zone/tunnel/prefix not set");

        CommandLog log = new CommandLog();
        CloudflareClient client = client(log);
        Zone zone = client.listZones().stream().filter(z -> z.name().equals(zoneName)).findFirst().orElseThrow();
        Tunnel tunnel = client.listTunnels(zone.accountId()).stream()
                .filter(t -> t.name().equals(tunnelName)).findFirst().orElseThrow();

        String name = "nrm-test-" + Long.toHexString(System.nanoTime()) + "." + parent;
        assertTrue(name.endsWith("." + zone.name()), "the record would not be in the test zone");

        DnsRecord created = null;
        try {
            final DnsRecord made = client.createDnsRecord(zone.id(),
                    DnsRecord.of("CNAME", name, tunnel.cnameTarget(), true));
            created = made;
            assertEquals(name, made.name());
            assertEquals(tunnel.cnameTarget(), made.content());
            assertTrue(made.proxied());

            DnsRecord edited = client.updateDnsRecord(zone.id(), new DnsRecord(made.id(), made.type(),
                    made.name(), made.content(), made.proxied(), made.ttl(), "NRM live test"));
            assertEquals("NRM live test", edited.comment());
            assertTrue(client.listDnsRecords(zone.id()).stream().anyMatch(r -> r.id().equals(made.id())));
        } finally {
            if (created != null) {
                client.deleteDnsRecord(zone.id(), created.id());
            }
        }
        final String id = created.id();
        assertFalse(client.listDnsRecords(zone.id()).stream().anyMatch(r -> r.id().equals(id)),
                "the throwaway record was not deleted");
        System.out.println("live DNS round trip ok for " + name);
    }
}
