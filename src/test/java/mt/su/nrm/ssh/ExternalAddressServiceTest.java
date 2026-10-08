package mt.su.nrm.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mt.su.nrm.ssh.ExternalAddressService.Found;
import mt.su.nrm.ssh.ExternalAddressService.Metadata;
import mt.su.nrm.ssh.FakeSessions.Reply;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ExternalAddressServiceTest {

    // ---------------------------------------------------------------- the cloud's metadata

    @Test
    void awsGivesBothAddressesAndAnInstanceWithoutAPublicOneGivesOnlyThePrivate() {
        List<Metadata> m = ExternalAddressService.parseMetadata("@@NRM-META aws\nlocal=10.0.1.20\npublic=54.1.2.3\n@@NRM-META end\n");
        assertEquals(1, m.size());
        assertEquals("Amazon Web Services", m.get(0).provider());
        assertEquals(Map.of("10.0.1.20", "54.1.2.3"), m.get(0).external());

        List<Metadata> none = ExternalAddressService.parseMetadata("@@NRM-META aws\nlocal=10.0.1.20\npublic=\n");
        assertTrue(none.get(0).external().isEmpty());
        assertEquals(List.of("10.0.1.20"), none.get(0).privateOnly());
    }

    @Test
    void googleCloudIsReadTheSameWay() {
        List<Metadata> m = ExternalAddressService.parseMetadata("@@NRM-META gcp\nlocal=10.128.0.2\npublic=35.1.2.3\n");
        assertEquals("Google Cloud", m.get(0).provider());
        assertEquals("35.1.2.3", m.get(0).external().get("10.128.0.2"));
    }

    @Test
    void azureListsEveryInterfaceAndAddress() {
        String json = "[{\"ipv4\":{\"ipAddress\":[{\"privateIpAddress\":\"10.1.0.4\",\"publicIpAddress\":\"20.1.2.3\"},"
                + "{\"privateIpAddress\":\"10.1.0.5\",\"publicIpAddress\":\"\"}],\"subnet\":[]},\"macAddress\":\"X\"}]";
        List<Metadata> m = ExternalAddressService.parseMetadata("@@NRM-META azure\n" + json + "\n");
        assertEquals("Microsoft Azure", m.get(0).provider());
        assertEquals(Map.of("10.1.0.4", "20.1.2.3"), m.get(0).external());
        assertEquals(List.of("10.1.0.5"), m.get(0).privateOnly());
    }

    @Test
    void oracleNamesThePrivateAddressAndLeavesTheExternalToTheEchoService() {
        String json = "[{\"vnicId\":\"ocid1.vnic.x\",\"privateIp\":\"10.0.0.17\",\"vlanTag\":1,\"macAddr\":\"02:00\","
                + "\"virtualRouterIp\":\"10.0.0.1\",\"subnetCidrBlock\":\"10.0.0.0/24\",\"nicIndex\":0}]";
        List<Metadata> m = ExternalAddressService.parseMetadata("@@NRM-META oracle\n" + json + "\n");
        assertEquals("Oracle Cloud", m.get(0).provider());
        assertTrue(m.get(0).external().isEmpty());
        assertEquals(List.of("10.0.0.17"), m.get(0).privateOnly());

        // If its metadata ever includes a public address, it is used.
        List<Metadata> withPublic = ExternalAddressService.parseMetadata(
                "@@NRM-META oracle\n[{\"privateIp\":\"10.0.0.17\",\"publicIp\":\"129.1.2.3\"}]\n");
        assertEquals("129.1.2.3", withPublic.get(0).external().get("10.0.0.17"));
    }

    @Test
    void noMetadataServiceNoCurlAndGarbageGiveNothingAndNeverAnError() {
        assertTrue(ExternalAddressService.parseMetadata("@@NRM-META none\n").isEmpty());
        assertTrue(ExternalAddressService.parseMetadata("@@NRM-META nocurl\n").isEmpty());
        assertTrue(ExternalAddressService.parseMetadata("").isEmpty());
        assertTrue(ExternalAddressService.parseMetadata("<html>captive portal</html>").isEmpty());
        assertTrue(ExternalAddressService.parseMetadata("@@NRM-META aws\nlocal=not-an-ip\npublic=1.2.3.4\n").isEmpty());
        assertTrue(ExternalAddressService.parseMetadata("@@NRM-META azure\n[{broken\n").isEmpty());
        assertTrue(ExternalAddressService.parseMetadata("@@NRM-META aws\nlocal=10.0.0.5\npublic=<script>\n").get(0).external().isEmpty(),
                "a public address that isn't an address is dropped");
    }

    @Test
    void theMetadataScriptTakesNothingFromTheUserAndStopsEarlyWhereThereIsNoCloud() {
        String script = ExternalAddressService.metadataScript();
        assertTrue(script.contains("M=169.254.169.254"));
        assertTrue(script.indexOf("@@NRM-META none") < script.indexOf("computeMetadata"), "it gives up before asking each cloud");
        assertTrue(script.contains("connect-timeout 1"));
        assertFalse(script.contains("$1"), "no argument of the caller is interpolated");
        assertTrue(ExternalAddressService.inSh(script).startsWith("sh -c '"), "run with sh whatever the login shell is");
    }

    @Test
    void metadataIsAskedThroughTheLoggedSessionInOneCommand() {
        List<String> ran = new ArrayList<>();
        CommandLog log = new CommandLog();
        SshSession session = FakeSessions.session(c -> Reply.ok("@@NRM-META gcp\nlocal=10.128.0.2\npublic=35.1.2.3\n"), ran, log);
        List<Metadata> m = ExternalAddressService.metadata(session);
        assertEquals(1, m.size());
        assertEquals(1, ran.size());
        assertTrue(ran.get(0).startsWith("sh -c "), ran.get(0));
        assertTrue(log.snapshot().stream().anyMatch(l -> l.text().contains("169.254.169.254")), "it shows in the command log");
        assertTrue(ExternalAddressService.metadata(FakeSessions.session(c -> Reply.fail(1, "x"), new ArrayList<>(), new CommandLog())).isEmpty());
    }

    // ---------------------------------------------------------------- the echo service

    @Test
    void theAnswerMustBeExactlyOneAddress() {
        assertEquals(Optional.of("203.0.113.9"), ExternalAddressService.parseEcho("203.0.113.9\n"));
        assertEquals(Optional.of("2001:db8::9"), ExternalAddressService.parseEcho(" 2001:db8::9 "));
        for (String bad : List.of("", "  ", "<html>blocked</html>", "203.0.113.9 and more", "999.1.1.1", "1.2.3", "error: rate limited",
                "203.0.113.9\n198.51.100.1", "gggg::1")) {
            assertEquals(Optional.empty(), ExternalAddressService.parseEcho(bad), "'" + bad + "'");
        }
        assertEquals(Optional.empty(), ExternalAddressService.parseEcho(null));
        assertEquals(Optional.empty(), ExternalAddressService.parseEcho("1".repeat(300)));
    }

    @Test
    void theCommandUsesTheLocalAddressAndRefusesAnythingElseBeforeItIsBuilt() throws IOException {
        String v4 = ExternalAddressService.echoCommand("10.0.0.17", "https://api.ipify.org");
        assertTrue(v4.contains("curl -sf -4 --interface '10.0.0.17' --connect-timeout 5 -m 8 'https://api.ipify.org'"), v4);
        assertTrue(ExternalAddressService.echoCommand("2001:db8::5", "https://icanhazip.com").contains("-6 --interface '2001:db8::5'"));

        for (String bad : List.of("", "10.0.0.17; rm -rf /", "$(id)", "eth0", "10.0.0.17 --upload-file /etc/shadow", "1.2.3.4'")) {
            assertThrows(IOException.class, () -> ExternalAddressService.echoCommand(bad, "https://api.ipify.org"), bad);
        }
        for (String bad : List.of("http://api.ipify.org", "https://x.org/a b", "https://x.org/;id", "https://x.org/$(id)",
                "file:///etc/passwd", "https://", "", "https://x.org/../etc")) {
            assertThrows(IOException.class, () -> ExternalAddressService.echoCommand("10.0.0.17", bad), bad);
        }
    }

    @Test
    void anEchoLookupReturnsTheAddressWithItsSourceAndGoesThroughTheLog() throws IOException {
        List<String> ran = new ArrayList<>();
        CommandLog log = new CommandLog();
        SshSession session = FakeSessions.session(c -> Reply.ok("129.146.1.2\n"), ran, log);
        Optional<Found> found = ExternalAddressService.echo(session, "10.0.0.17", "https://api.ipify.org");
        assertEquals(Optional.of(new Found("129.146.1.2", "api.ipify.org")), found);
        assertTrue(ran.get(0).contains("--interface"), ran.get(0));
        assertTrue(log.snapshot().stream().anyMatch(l -> l.text().contains("api.ipify.org")));
    }

    @Test
    void failuresAreReportedPlainly() {
        SshSession missing = FakeSessions.session(c -> new Reply(127, "", ""), new ArrayList<>(), new CommandLog());
        assertTrue(assertThrows(IOException.class, () -> ExternalAddressService.echo(missing, "10.0.0.17", "https://api.ipify.org"))
                .getMessage().contains("curl is not installed"));
        SshSession down = FakeSessions.session(c -> Reply.fail(28, ""), new ArrayList<>(), new CommandLog());
        assertTrue(assertThrows(IOException.class, () -> ExternalAddressService.echo(down, "10.0.0.17", "https://api.ipify.org"))
                .getMessage().contains("failed (exit 28)"));
        SshSession junk = FakeSessions.session(c -> Reply.ok("<html>blocked</html>"), new ArrayList<>(), new CommandLog());
        assertEquals(Optional.empty(), assertDoesNotThrow(junk));
    }

    private static Optional<Found> assertDoesNotThrow(SshSession session) {
        try {
            return ExternalAddressService.echo(session, "10.0.0.17", "https://api.ipify.org");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    // ---------------------------------------------------------------- putting it together

    private static NetworkService.LocalAddress addr(String a, String scope) {
        return new NetworkService.LocalAddress(a, a.contains(":"), "eth0", scope);
    }

    private static ExternalAddressService.Lookup lookup(java.util.function.Function<String, Reply> script, List<String> ran,
                                                        List<NetworkService.LocalAddress> addresses, List<String> services) {
        return ExternalAddressService.lookup(FakeSessions.session(script, ran, new CommandLog()), addresses, services);
    }

    private static final List<String> TWO = List.of("https://one.example/ip", "https://two.example/ip");

    @Test
    void aPublicAddressIsItsOwnAndLoopbackAndLinkLocalAreSkipped() {
        List<String> ran = new ArrayList<>();
        var result = lookup(c -> Reply.ok("@@NRM-META none\n"), ran,
                List.of(addr("203.0.113.9", "global"), addr("127.0.0.1", "host"), addr("fe80::1", "link")), TWO);
        assertEquals(Map.of("203.0.113.9", new Found("203.0.113.9", "its own address")), result.found());
        assertEquals(1, ran.size(), "only the metadata probe was needed");
    }

    @Test
    void theCloudsMetadataIsPreferredAndNoOutsideServiceIsAsked() {
        List<String> ran = new ArrayList<>();
        var result = lookup(c -> Reply.ok("@@NRM-META aws\nlocal=10.0.1.20\npublic=54.1.2.3\n"), ran,
                List.of(addr("10.0.1.20", "global")), TWO);
        assertEquals(new Found("54.1.2.3", "Amazon Web Services metadata"), result.found().get("10.0.1.20"));
        assertEquals(1, ran.size());
        assertTrue(result.notes().isEmpty(), result.notes().toString());
    }

    @Test
    void oracleFallsBackToAnEchoServiceAndTheNotesSayHowToReadTheAnswer() {
        List<String> ran = new ArrayList<>();
        var result = lookup(c -> c.contains("169.254.169.254")
                        ? Reply.ok("@@NRM-META oracle\n[{\"privateIp\":\"10.0.0.17\"}]\n")
                        : Reply.ok("129.146.1.2\n"), ran,
                List.of(addr("10.0.0.17", "global")), TWO);
        assertEquals(new Found("129.146.1.2", "one.example/ip (outbound)"), result.found().get("10.0.0.17"));
        assertEquals(2, ran.size(), "metadata, then the first service answered");
        assertTrue(ran.get(1).startsWith("sh -c ") && ran.get(1).contains("--interface") && ran.get(1).contains("10.0.0.17"),
                ran.get(1));
        String notes = String.join("\n", result.notes());
        assertTrue(notes.contains("This looks like Oracle Cloud") && notes.contains("\"outbound\" means"), notes);
    }

    @Test
    void aServiceThatFailsOrAnswersWithRubbishIsSkippedForTheNextOne() {
        List<String> ran = new ArrayList<>();
        var result = lookup(c -> {
            if (c.contains("169.254.169.254")) {
                return Reply.ok("@@NRM-META none\n");
            }
            if (c.contains("one.example")) {
                return Reply.fail(28, "");
            }
            return Reply.ok("198.51.100.4");
        }, ran, List.of(addr("192.168.1.10", "global")), TWO);
        assertEquals("198.51.100.4", result.found().get("192.168.1.10").address());
        assertEquals(3, ran.size());
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("192.168.1.10: The request through 192.168.1.10")), result.notes().toString());

        var rubbish = lookup(c -> c.contains("169.254.169.254") ? Reply.ok("@@NRM-META none\n") : Reply.ok("<html>no</html>"),
                new ArrayList<>(), List.of(addr("192.168.1.10", "global")), TWO);
        assertTrue(rubbish.found().isEmpty(), "an answer that isn't an address is never used");
    }

    @Test
    void withoutCurlTheOtherServicesAreNotTriedAndTheUserIsTold() {
        List<String> ran = new ArrayList<>();
        var result = lookup(c -> c.contains("169.254.169.254") ? Reply.ok("@@NRM-META nocurl\n") : new Reply(127, "", ""), ran,
                List.of(addr("192.168.1.10", "global")), TWO);
        assertTrue(result.found().isEmpty());
        assertEquals(2, ran.size(), "metadata, one echo attempt, then it stops");
        assertTrue(result.notes().get(0).contains("curl is not installed"), result.notes().toString());
    }

    @Test
    void everyCommandOfALookupIsInTheCommandLog() {
        CommandLog log = new CommandLog();
        SshSession session = FakeSessions.session(c -> c.contains("169.254.169.254") ? Reply.ok("@@NRM-META none\n")
                : Reply.ok("203.0.113.50"), new ArrayList<>(), log);
        ExternalAddressService.lookup(session, List.of(addr("10.0.0.5", "global")), TWO);
        List<String> logged = log.snapshot().stream().filter(l -> l.kind() == CommandLog.Kind.COMMAND)
                .map(CommandLog.Line::text).toList();
        assertTrue(logged.stream().anyMatch(t -> t.contains("169.254.169.254")), "the metadata probe is shown");
        assertTrue(logged.stream().anyMatch(t -> t.contains("--interface") && t.contains("10.0.0.5")), "and so is the echo request");
        assertTrue(logged.stream().anyMatch(t -> t.contains("one.example")), "with the service it asks");
    }

    @Test
    void addressesAreValidatedStrictly() {
        for (String ok : List.of("10.0.0.1", "255.255.255.255", "::1", "2001:db8::1", "fe80::1")) {
            assertTrue(ExternalAddressService.validIp(ok), ok);
        }
        for (String bad : List.of("", "256.1.1.1", "1.2.3", "1.2.3.4.5", "a.b.c.d", "localhost", "1.2.3.4/24", "::g", "1.2.3.4 ", "-1.2.3.4")) {
            assertFalse(ExternalAddressService.validIp(bad), "'" + bad + "'");
        }
        assertFalse(ExternalAddressService.validIp(null));
    }
}
