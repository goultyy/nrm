package mt.su.nrm.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class NetworkServiceTest {

    private static final String IP_OUTPUT = """
            1: lo    inet 127.0.0.1/8 scope host lo\\       valid_lft forever preferred_lft forever
            1: lo    inet6 ::1/128 scope host \\       valid_lft forever preferred_lft forever
            2: eth0    inet 10.0.0.5/24 brd 10.0.0.255 scope global eth0\\       valid_lft forever preferred_lft forever
            2: eth0    inet 203.0.113.10/32 scope global eth0\\       valid_lft forever preferred_lft forever
            2: eth0    inet6 2001:db8::10/64 scope global \\       valid_lft forever preferred_lft forever
            2: eth0    inet6 fe80::1/64 scope link \\       valid_lft forever preferred_lft forever
            """;

    @Test
    void readsEveryAddressFromIpOutputWithItsScope() {
        List<NetworkService.LocalAddress> found = NetworkService.parseAddresses(IP_OUTPUT);
        assertEquals(6, found.size());
        assertEquals("loopback", found.get(0).kind());
        assertEquals("private", found.get(2).kind());
        assertEquals("public", found.get(3).kind());
        assertTrue(found.get(4).ipv6());
        assertEquals("public", found.get(4).kind());
        assertEquals("link-local", found.get(5).kind());
        assertEquals("eth0", found.get(2).iface());
    }

    @Test
    void fallsBackToThePlainListOfHostnameDashI() {
        List<NetworkService.LocalAddress> found = NetworkService.parseAddresses("192.168.1.20 203.0.113.9 2001:db8::9 \n");
        assertEquals(3, found.size());
        assertEquals("private", found.get(0).kind());
        assertEquals("public", found.get(1).kind());
        assertTrue(found.get(2).ipv6());
    }

    @Test
    void privateRangesAreRecognisedAtTheirEdges() {
        assertEquals("private", NetworkService.parseAddresses("172.16.0.1").get(0).kind());
        assertEquals("private", NetworkService.parseAddresses("172.31.255.1").get(0).kind());
        assertEquals("public", NetworkService.parseAddresses("172.32.0.1").get(0).kind());
        assertEquals("private", NetworkService.parseAddresses("100.64.0.1").get(0).kind());
        assertEquals("public", NetworkService.parseAddresses("100.128.0.1").get(0).kind());
        assertEquals("private", NetworkService.parseAddresses("fd12:3456::1").get(0).kind());
    }

    @Test
    void duplicatesAndGarbageAreIgnored() {
        assertEquals(1, NetworkService.parseAddresses("10.0.0.1 10.0.0.1\nnot an address\n").size());
        assertTrue(NetworkService.parseAddresses("").isEmpty());
    }

    private static final String SS_WITH_PROCESSES = """
            State   Recv-Q  Send-Q   Local Address:Port    Peer Address:Port  Process
            LISTEN  0       511            0.0.0.0:80           0.0.0.0:*      users:(("nginx",pid=812,fd=6),("nginx",pid=811,fd=6))
            LISTEN  0       511            0.0.0.0:443          0.0.0.0:*      users:(("nginx",pid=812,fd=7))
            LISTEN  0       511               [::]:80              [::]:*      users:(("nginx",pid=812,fd=8))
            LISTEN  0       128            0.0.0.0:22           0.0.0.0:*      users:(("sshd",pid=700,fd=3))
            LISTEN  0       4096     127.0.0.53%lo:53           0.0.0.0:*      users:(("systemd-resolve",pid=500,fd=14))
            LISTEN  0       511          127.0.0.1:8080         0.0.0.0:*      users:(("nginx",pid=812,fd=9))
            """;

    @Test
    void findsThePortsNginxListensOn() {
        List<NetworkService.ListeningPort> ports = NetworkService.parsePorts(SS_WITH_PROCESSES);
        NetworkService.Facts facts = new NetworkService.Facts(List.of(), ports, true);
        assertEquals(List.of(80, 443, 8080), facts.nginxPorts());
        assertEquals(List.of(22, 53, 80, 443, 8080), facts.allPorts());
    }

    @Test
    void readsIpv6AndZoneSuffixedAddresses() {
        List<NetworkService.ListeningPort> ports = NetworkService.parsePorts(SS_WITH_PROCESSES);
        assertTrue(ports.stream().anyMatch(p -> p.address().equals("::") && p.port() == 80 && p.nginx()));
        assertTrue(ports.stream().anyMatch(p -> p.address().equals("127.0.0.53") && p.port() == 53 && !p.nginx()));
    }

    @Test
    void withoutProcessNamesNoPortIsClaimedForNginx() {
        String plain = """
                State   Recv-Q  Send-Q   Local Address:Port    Peer Address:Port
                LISTEN  0       511            0.0.0.0:80           0.0.0.0:*
                LISTEN  0       128            0.0.0.0:22           0.0.0.0:*
                """;
        NetworkService.Facts facts = new NetworkService.Facts(List.of(), NetworkService.parsePorts(plain), false);
        assertTrue(facts.nginxPorts().isEmpty());
        assertEquals(List.of(22, 80), facts.allPorts());
    }

    @Test
    void onlyListeningSocketsCount() {
        assertTrue(NetworkService.parsePorts("ESTAB 0 0 10.0.0.5:22 10.0.0.9:50000\n").isEmpty());
        assertFalse(NetworkService.parsePorts("LISTEN 0 1 0.0.0.0:99 0.0.0.0:*\n").isEmpty());
    }
}
