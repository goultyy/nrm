package mt.su.nrm.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mt.su.nrm.model.ConfigLayout;
import mt.su.nrm.nginx.ConfigFile;
import mt.su.nrm.nginx.NginxParseException;
import mt.su.nrm.nginx.RemoteConfig;
import mt.su.nrm.ssh.CommandLog;
import mt.su.nrm.ssh.FakeSessions;
import mt.su.nrm.ssh.FakeSessions.Reply;
import mt.su.nrm.ssh.SshSession;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StatusTest {

    private static final String PAGE = """
            Active connections: 291
            server accepts handled requests
             16630948 16630948 31070465
            Reading: 6 Writing: 179 Waiting: 106
            """;

    private static RemoteConfig config(String http) throws NginxParseException {
        ConfigFile main = ConfigFile.parse("/etc/nginx/nginx.conf", "events {}\nhttp {\n" + http + "}\n");
        return new RemoteConfig("/etc/nginx", ConfigLayout.CONF_D, main);
    }

    private static String text(RemoteConfig config) {
        return config.mainFile().generate();
    }

    // ---------------------------------------------------------------- reading the page

    @Test
    void aStubStatusPageIsParsed() {
        StubStatus s = StubStatus.parse(PAGE);
        assertEquals(291, s.active());
        assertEquals(16630948L, s.accepts());
        assertEquals(31070465L, s.requests());
        assertEquals(6, s.reading());
        assertEquals(179, s.writing());
        assertEquals(106, s.waiting());
        assertEquals(0, s.dropped());
    }

    @Test
    void anythingElseIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> StubStatus.parse("<html>502 Bad Gateway</html>"));
        assertThrows(IllegalArgumentException.class, () -> StubStatus.parse(""));
        assertThrows(IllegalArgumentException.class,
                () -> StubStatus.parse("Active connections: 3\nserver accepts handled requests\n 1 2\n"));
    }

    @Test
    void droppedConnectionsAreAcceptedMinusHandled() {
        assertEquals(5, new StubStatus(1, 100, 95, 200, 0, 1, 0).dropped());
    }

    @Test
    void ratesAreTheGrowthOverTheTimeBetweenReadings() {
        StubStatus a = new StubStatus(10, 1000, 1000, 5000, 1, 2, 3);
        StubStatus b = new StubStatus(12, 1100, 1090, 5500, 1, 2, 3);
        StubStatus.Rates r = StubStatus.rates(a, b, 10).orElseThrow();
        assertEquals(50.0, r.requestsPerSecond(), 0.001);
        assertEquals(10.0, r.acceptsPerSecond(), 0.001);
        assertEquals(9.0, r.handledPerSecond(), 0.001);
    }

    @Test
    void readingsThatCannotBeComparedGiveNoRates() {
        StubStatus a = new StubStatus(10, 1000, 1000, 5000, 1, 2, 3);
        StubStatus restarted = new StubStatus(2, 5, 5, 9, 0, 1, 1);
        assertEquals(Optional.empty(), StubStatus.rates(a, restarted, 5), "counters went back: nginx restarted");
        assertEquals(Optional.empty(), StubStatus.rates(a, a, 0));
    }

    // ---------------------------------------------------------------- the configuration

    @Test
    void enablingAddsALoopbackOnlyServerAndNothingElse() throws Exception {
        RemoteConfig config = config("    server { listen 80; server_name a.com; }\n");
        assertTrue(StatusPageConfig.enable(config, 8089, List.of(80)).ok());
        String out = text(config);
        assertTrue(out.contains("listen 127.0.0.1:8089;"), out);
        assertTrue(out.contains("server_name nrm-status;"), out);
        assertTrue(out.contains("location = /nrm_status"), out);
        assertTrue(out.contains("stub_status;"), out);
        assertTrue(out.contains("allow 127.0.0.1;") && out.contains("deny all;"), out);
        assertTrue(out.contains("server_name a.com;"), "the existing site must be untouched");
        // It must still be valid configuration that this app can read back.
        ConfigFile.parse("/etc/nginx/nginx.conf", out);
    }

    @Test
    void theAddedServerIsFoundAsOursAndRestricted() throws Exception {
        RemoteConfig config = config("");
        StatusPageConfig.enable(config, 8123, List.of());
        StatusPageConfig.Endpoint e = StatusPageConfig.find(config).orElseThrow();
        assertTrue(e.ours());
        assertTrue(e.restricted());
        assertEquals("http://127.0.0.1:8123/nrm_status", e.url());
        assertEquals("nrm-status", e.hostHeader());
    }

    @Test
    void enablingTwiceOrOverAHandMadePageIsRefused() throws Exception {
        RemoteConfig config = config("");
        assertTrue(StatusPageConfig.enable(config, 8089, List.of()).ok());
        assertFalse(StatusPageConfig.enable(config, 8090, List.of()).ok());

        RemoteConfig manual = config("    server { listen 127.0.0.1:81; location /status { stub_status; } }\n");
        StatusPageConfig.Result r = StatusPageConfig.enable(manual, 8089, List.of());
        assertFalse(r.ok());
        assertTrue(r.problem().contains("already exists"), r.problem());
    }

    @Test
    void disablingRemovesOnlyTheServerThisAppAdded() throws Exception {
        RemoteConfig config = config("    server { listen 80; server_name a.com; }\n");
        String before = text(config);
        StatusPageConfig.enable(config, 8089, List.of());
        assertEquals(1, StatusPageConfig.disable(config));
        assertEquals(before, text(config), "disable must leave the file exactly as it was");
        assertEquals(0, StatusPageConfig.disable(config));
    }

    @Test
    void aHandMadePageIsFoundButNeverRemoved() throws Exception {
        RemoteConfig config = config("    server { listen 8080; server_name mon.example.com;\n"
                + "      location = /basic_status { stub_status; } }\n");
        StatusPageConfig.Endpoint e = StatusPageConfig.find(config).orElseThrow();
        assertFalse(e.ours());
        assertFalse(e.restricted(), "nothing limits who may read it, which the page should warn about");
        assertEquals("http://127.0.0.1:8080/basic_status", e.url());
        assertEquals("mon.example.com", e.hostHeader());
        String before = text(config);
        assertEquals(0, StatusPageConfig.disable(config));
        assertEquals(before, text(config));
    }

    @Test
    void aLocationWithARegularExpressionCannotBeRequestedAndIsIgnored() throws Exception {
        RemoteConfig config = config("    server { listen 80; location ~ ^/st { stub_status; } }\n");
        assertEquals(Optional.empty(), StatusPageConfig.find(config));
    }

    @Test
    void theAddressUsedToReachItIsAlwaysThisMachine() throws Exception {
        RemoteConfig config = config("    server { listen 0.0.0.0:9000; location = /s { stub_status; allow 127.0.0.1; deny all; } }\n");
        assertEquals("127.0.0.1", StatusPageConfig.find(config).orElseThrow().host());
    }

    @Test
    void thePortIsCheckedAndASpareOneSuggested() {
        assertEquals(8089, StatusPageConfig.suggestPort(List.of(80, 443)));
        assertEquals(8091, StatusPageConfig.suggestPort(List.of(8089, 8090)));
        assertFalse(StatusPageConfig.portProblems(8089, List.of(8089)).isEmpty());
        assertFalse(StatusPageConfig.portProblems(0, List.of()).isEmpty());
        assertFalse(StatusPageConfig.portProblems(70000, List.of()).isEmpty());
        assertTrue(StatusPageConfig.portProblems(8089, List.of(80)).isEmpty());
    }

    @Test
    void enablingRefusesAPortThatIsInUse() throws Exception {
        RemoteConfig config = config("");
        assertFalse(StatusPageConfig.enable(config, 8089, List.of(8089)).ok());
        assertEquals("events {}\nhttp {\n}\n", text(config), "a refused change must change nothing");
    }

    // ---------------------------------------------------------------- fetching it

    @Test
    void theCommandIsBuiltFromCheckedAndQuotedValuesOnly() {
        String script = StatusService.script("127.0.0.1", 8089, "/nrm_status", "nrm-status");
        assertTrue(script.contains("curl -fsS --max-time 5 -H 'Host: nrm-status' 'http://127.0.0.1:8089/nrm_status'"), script);
        assertTrue(script.contains("wget"), "wget is the fallback");
        assertFalse(StatusService.script("127.0.0.1", 8089, "/s", null).contains("Host:"));

        assertFalse(StatusService.problems("example.com", 80, "/s", null).isEmpty(), "must stay on this machine");
        assertFalse(StatusService.problems("127.0.0.1", 80, "/s; rm -rf /", null).isEmpty());
        assertFalse(StatusService.problems("127.0.0.1", 80, "/s", "a b; id").isEmpty());
        assertFalse(StatusService.problems("127.0.0.1", 0, "/s", null).isEmpty());
        assertTrue(StatusService.problems("127.0.0.1", 8089, "/nrm_status", "nrm-status").isEmpty());
    }

    @Test
    void aReadingIsTakenThroughTheSessionAndShowsInTheCommandLog() throws IOException {
        List<String> ran = new ArrayList<>();
        CommandLog log = new CommandLog();
        SshSession session = FakeSessions.session(c -> Reply.ok(PAGE), ran, log);

        StubStatus s = StatusService.sample(session, "127.0.0.1", 8089, "/nrm_status", "nrm-status");

        assertEquals(291, s.active());
        assertEquals(1, ran.size());
        assertTrue(ran.get(0).contains("http://127.0.0.1:8089/nrm_status"));
        assertTrue(log.snapshot().stream().anyMatch(l -> l.text().contains("http://127.0.0.1:8089/nrm_status")),
                "the command must be visible in the command log");
    }

    @Test
    void failuresAreExplainedInPlainWords() {
        CommandLog log = new CommandLog();
        IOException refused = assertThrows(IOException.class, () -> StatusService.sample(
                FakeSessions.session(c -> Reply.fail(7, "curl: (7) Failed to connect"), new ArrayList<>(), log),
                "127.0.0.1", 8089, "/nrm_status", null));
        assertTrue(refused.getMessage().contains("apply the pending changes"), refused.getMessage());

        IOException noTool = assertThrows(IOException.class, () -> StatusService.sample(
                FakeSessions.session(c -> Reply.fail(127, ""), new ArrayList<>(), log),
                "127.0.0.1", 8089, "/nrm_status", null));
        assertTrue(noTool.getMessage().contains("curl nor wget"), noTool.getMessage());

        IOException html = assertThrows(IOException.class, () -> StatusService.sample(
                FakeSessions.session(c -> Reply.ok("<html>hello</html>"), new ArrayList<>(), log),
                "127.0.0.1", 8089, "/nrm_status", null));
        assertTrue(html.getMessage().contains("not with an nginx status page"), html.getMessage());

        assertThrows(IOException.class, () -> StatusService.sample(
                FakeSessions.session(c -> Reply.ok(PAGE), new ArrayList<>(), log), "evil.example.com", 80, "/s", null));
    }
}
