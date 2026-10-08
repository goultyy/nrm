package mt.su.nrm.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mt.su.nrm.model.ConfigLayout;
import mt.su.nrm.network.IpOverview.Configured;
import mt.su.nrm.network.IpOverview.Overview;
import mt.su.nrm.network.IpOverview.Row;
import mt.su.nrm.nginx.ConfigFile;
import mt.su.nrm.nginx.ListenEndpoint;
import mt.su.nrm.nginx.RemoteConfig;
import mt.su.nrm.ssh.NetworkService.Facts;
import mt.su.nrm.ssh.NetworkService.ListeningPort;
import mt.su.nrm.ssh.NetworkService.LocalAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class IpOverviewTest {

    private static LocalAddress v4(String a, String iface) {
        return new LocalAddress(a, false, iface, a.startsWith("127.") ? "host" : "global");
    }

    private static LocalAddress v6(String a, String iface) {
        return new LocalAddress(a, true, iface, a.startsWith("fe80") ? "link" : a.equals("::1") ? "host" : "global");
    }

    private static Configured site(String name, String listen, boolean ssl) {
        return new Configured(name, ListenEndpoint.parse(listen).orElseThrow(), ssl);
    }

    private static ListeningPort nginx(String address, int port) {
        return new ListeningPort(address, port, true);
    }

    private static Row row(Overview o, String address) {
        return o.rows().stream().filter(r -> r.address().address().equals(address)).findFirst().orElseThrow();
    }

    private static String all(Overview o) {
        return String.join("\n", o.warnings());
    }

    // ---------------------------------------------------------------- matching

    @Test
    void aWildcardCoversEveryAddressOfItsFamilyOnly() {
        Facts facts = new Facts(List.of(v4("10.0.0.5", "eth0"), v4("127.0.0.1", "lo"), v6("2001:db8::5", "eth0")),
                List.of(nginx("0.0.0.0", 80), nginx("::", 443)), true);
        Overview o = IpOverview.build(facts, List.of(site("a.com", "80", false), site("a.com", "[::]:443", true)));
        assertEquals(List.of(80), row(o, "10.0.0.5").listening());
        assertEquals(List.of(80), row(o, "127.0.0.1").listening());
        assertEquals(List.of(443), row(o, "2001:db8::5").listening(), "[::] is IPv6 only");
        assertEquals(1, row(o, "10.0.0.5").configured().size());
        assertEquals(443, row(o, "2001:db8::5").configured().get(0).endpoint().port());
        assertTrue(row(o, "2001:db8::5").configured().get(0).ssl());
    }

    @Test
    void aSpecificAddressCoversOnlyThatAddressAndBracketsDontMatter() {
        Facts facts = new Facts(List.of(v4("10.0.0.5", "eth0"), v4("10.0.0.6", "eth0"), v6("::1", "lo")),
                List.of(nginx("10.0.0.5", 8080), nginx("::1", 9000)), true);
        Overview o = IpOverview.build(facts, List.of(site("a.com", "10.0.0.5:8080", false), site("b.com", "[::1]:9000", false)));
        assertEquals(List.of(8080), row(o, "10.0.0.5").listening());
        assertEquals(List.of(), row(o, "10.0.0.6").listening());
        assertEquals(List.of(9000), row(o, "::1").listening());
        assertEquals("b.com", row(o, "::1").configured().get(0).site());
        assertTrue(row(o, "10.0.0.6").configured().isEmpty());
    }

    @Test
    void whenNginxsOwnSocketsCantBeToldApartEveryListenerIsShownAndMarkedUncertain() {
        Facts facts = new Facts(List.of(v4("10.0.0.5", "eth0")),
                List.of(new ListeningPort("0.0.0.0", 22, false), new ListeningPort("0.0.0.0", 80, false)), false);
        Row r = row(IpOverview.build(facts, List.of()), "10.0.0.5");
        assertEquals(List.of(22, 80), r.listening());
        assertFalse(r.listeningCertain());
    }

    @Test
    void publicAddressesComeFirstThenPrivateLoopbackAndLinkLocal() {
        Facts facts = new Facts(List.of(v6("fe80::1", "eth0"), v4("127.0.0.1", "lo"), v4("10.0.0.5", "eth0"),
                v4("203.0.113.9", "eth1")), List.of(), false);
        List<String> order = IpOverview.build(facts, List.of()).rows().stream().map(r -> r.address().address()).toList();
        assertEquals(List.of("203.0.113.9", "10.0.0.5", "127.0.0.1", "fe80::1"), order);
    }

    // ---------------------------------------------------------------- warnings

    @Test
    void anAddressNginxIsTold_ToUseButThisMachineLacksIsFlagged() {
        Facts facts = new Facts(List.of(v4("10.0.0.5", "eth0")), List.of(nginx("0.0.0.0", 80)), true);
        Overview o = IpOverview.build(facts, List.of(site("old.com", "192.0.2.77:80", false)));
        assertTrue(all(o).contains("old.com listens on 192.0.2.77:80, but this server has no such address"), all(o));
    }

    @Test
    void configuredButNotListeningAndListeningButNotConfiguredAreBothReported() {
        Facts facts = new Facts(List.of(v4("10.0.0.5", "eth0")), List.of(nginx("0.0.0.0", 80), nginx("0.0.0.0", 8443)), true);
        Overview o = IpOverview.build(facts, List.of(site("a.com", "80", false), site("a.com", "443", true)));
        assertTrue(all(o).contains("a.com is set to listen on port 443, but nginx is not listening there"), all(o));
        assertTrue(all(o).contains("nginx is listening on 0.0.0.0:8443, but no site"), all(o));
        assertFalse(all(o).contains("port 80, but nginx is not listening"), "port 80 is fine: " + all(o));
    }

    @Test
    void withoutRootRightsNothingIsSaidAboutWhatNginxIsOrIsntListeningOn() {
        Facts facts = new Facts(List.of(v4("10.0.0.5", "eth0")), List.of(new ListeningPort("0.0.0.0", 80, false)), false);
        Overview o = IpOverview.build(facts, List.of(site("a.com", "443", true)));
        assertFalse(all(o).contains("not listening"), "it can't tell, so it must not claim: " + all(o));
        assertFalse(all(o).contains("is listening on"), all(o));
    }

    @Test
    void loopbackOnlyAndIpv4OnlyAreCalledOut() {
        Facts facts = new Facts(List.of(v4("10.0.0.5", "eth0"), v6("2001:db8::5", "eth0")),
                List.of(nginx("0.0.0.0", 80), nginx("127.0.0.1", 8089)), true);
        Overview o = IpOverview.build(facts, List.of(site("a.com", "80", false), site("status", "127.0.0.1:8089", false)));
        assertTrue(all(o).contains("status listens only on 127.0.0.1:8089, so it can be reached from this server alone"), all(o));
        assertTrue(all(o).contains("a.com is offered on port 80 over IPv4 only"), all(o));

        Overview both = IpOverview.build(facts, List.of(site("a.com", "80", false), site("a.com", "[::]:80", false)));
        assertFalse(all(both).contains("IPv4 only"), all(both));
    }

    @Test
    void aServerWithoutAGlobalIpv6AddressIsNotToldToAddOne() {
        Facts facts = new Facts(List.of(v4("10.0.0.5", "eth0"), v6("fe80::1", "eth0")), List.of(nginx("0.0.0.0", 80)), true);
        assertFalse(all(IpOverview.build(facts, List.of(site("a.com", "80", false)))).contains("IPv4 only"));
    }

    @Test
    void matchingHandlesZonesCaseAndNamedHosts() {
        assertEquals("fe80::1", IpOverview.normalize("[FE80::1%eth0]"));
        assertEquals("::1", IpOverview.normalize("[::1]"));
        assertTrue(IpOverview.covers("*", v4("10.0.0.5", "eth0")) && IpOverview.covers("*", v6("::1", "lo")));
        assertFalse(IpOverview.covers("example.com", v4("10.0.0.5", "eth0")), "a host name is never guessed at");
        Facts facts = new Facts(List.of(v4("10.0.0.5", "eth0")), List.of(nginx("0.0.0.0", 80)), true);
        assertFalse(all(IpOverview.build(facts, List.of(site("a.com", "internal.example.com:80", false))))
                .contains("no such address"), "a name can't be checked against the local addresses");
    }

    // ---------------------------------------------------------------- reading the real configuration

    @Test
    void theConfiguredListensAreReadFromEverySite() throws Exception {
        ConfigFile main = ConfigFile.parse("/etc/nginx/nginx.conf", "events {}\nhttp {\n"
                + "    server { listen 80; listen [::]:80; server_name a.com www.a.com; }\n"
                + "    server { listen 10.0.0.5:443 ssl; server_name b.com; }\n}\n");
        RemoteConfig config = new RemoteConfig("/etc/nginx", ConfigLayout.CONF_D, main);
        List<Configured> listens = IpOverview.configuredFrom(config);
        assertEquals(3, listens.size());
        assertEquals("a.com", listens.get(0).site());
        assertTrue(listens.get(2).ssl());
        assertEquals(443, listens.get(2).endpoint().port());
        assertTrue(IpOverview.configuredFrom(null).isEmpty());
    }
}
