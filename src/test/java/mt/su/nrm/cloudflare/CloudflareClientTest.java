package mt.su.nrm.cloudflare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import mt.su.nrm.ssh.CommandLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CloudflareClientTest {

    private static final String TOKEN = "cf-secret-token-123";

    private HttpServer server;
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private Function<String, String[]> handler = uri -> new String[] {"404", "{}"};
    private volatile String lastMethod = "";
    private volatile String lastBody = "";
    private CommandLog log;
    private CloudflareClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            lastMethod = exchange.getRequestMethod();
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String[] reply = handler.apply(exchange.getRequestURI().toString());
            byte[] body = reply[1].getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(Integer.parseInt(reply[0]), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        log = new CommandLog();
        client = new CloudflareClient(TOKEN, URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                log, HttpClient.newHttpClient());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String logText() {
        StringBuilder sb = new StringBuilder();
        for (CommandLog.Line line : log.snapshot()) {
            sb.append(line.text()).append('\n');
        }
        return sb.toString();
    }

    private static final String ONE_ZONE = "{\"success\":true,\"result\":[{\"id\":\"z1\",\"name\":\"a.com\","
            + "\"account\":{\"id\":\"acc\"}}],\"result_info\":{\"total_pages\":1}}";
    private static final String RECORD = "{\"success\":true,\"result\":{\"id\":\"r1\",\"type\":\"CNAME\","
            + "\"name\":\"app.a.com\",\"content\":\"t.cfargotunnel.com\",\"proxied\":true,\"ttl\":1,"
            + "\"comment\":\"\"}}";

    @Test
    void verifyTokenReturnsTheZonesItCanSee() throws Exception {
        handler = uri -> new String[] {"200", ONE_ZONE};
        assertEquals(1, client.verifyToken().size());
        assertEquals("Bearer " + TOKEN, authHeaders.get(0));
    }

    @Test
    void verifyTokenRejectsTokenWithNoZones() {
        handler = uri -> new String[] {"200", "{\"success\":true,\"result\":[],\"result_info\":{\"total_pages\":1}}"};
        CloudflareException e = assertThrows(CloudflareException.class, () -> client.verifyToken());
        assertTrue(e.getMessage().contains("Zone: Read"));
    }

    @Test
    void createDnsRecordPostsTheRecordAndReturnsTheStoredOne() throws Exception {
        handler = uri -> new String[] {"200", RECORD};
        DnsRecord created = client.createDnsRecord("z1",
                DnsRecord.of("CNAME", "app.a.com", "t.cfargotunnel.com", true));
        assertEquals("POST", lastMethod);
        assertEquals("r1", created.id());
        assertTrue(created.proxied());
        assertEquals("CNAME", Json.asString(Json.asObject(Json.parse(lastBody)).get("type")));
        assertEquals(Boolean.TRUE, Json.asObject(Json.parse(lastBody)).get("proxied"));
    }

    @Test
    void updateDnsRecordPatchesByIdAndRefusesARecordWithoutOne() throws Exception {
        handler = uri -> new String[] {"200", RECORD};
        DnsRecord existing = new DnsRecord("r1", "CNAME", "app.a.com", "t.cfargotunnel.com", true, 1, "x");
        client.updateDnsRecord("z1", existing);
        assertEquals("PATCH", lastMethod);
        assertThrows(IllegalArgumentException.class,
                () -> client.updateDnsRecord("z1", DnsRecord.of("A", "b.a.com", "1.2.3.4", false)));
    }

    @Test
    void tunnelConfigIsReadThenPutBackWholeUnderConfig() throws Exception {
        handler = uri -> new String[] {"200", "{\"success\":true,\"result\":{\"source\":\"cloudflare\","
                + "\"config\":{\"ingress\":[{\"service\":\"http_status:404\"}],\"warp-routing\":{\"enabled\":false}}}}"};
        TunnelConfig config = client.getTunnelConfig("acc", "tun");
        assertEquals("GET", lastMethod);
        client.putTunnelConfig("acc", "tun", config.withRoute(IngressRule.route("a.a.com", "http://localhost:1")));
        assertEquals("PUT", lastMethod);
        Map<String, Object> sent = Json.asObject(Json.asObject(Json.parse(lastBody)).get("config"));
        assertEquals(2, Json.asList(sent.get("ingress")).size());
        assertTrue(sent.containsKey("warp-routing"), "an unmodelled setting was dropped from the PUT");
    }

    @Test
    void putIsRefusedForATunnelConfiguredByFile() throws Exception {
        handler = uri -> new String[] {"200", "{\"success\":true,\"result\":{\"source\":\"local\","
                + "\"config\":{\"ingress\":[{\"service\":\"http_status:404\"}]}}}"};
        TunnelConfig config = client.getTunnelConfig("acc", "tun");
        lastMethod = "";
        assertThrows(CloudflareException.class, () -> client.putTunnelConfig("acc", "tun", config));
        assertEquals("", lastMethod, "a request was sent although the tunnel is file-managed");
    }

    private static final String RUN_TOKEN = "eyJhIjoiYWNjIiwidCI6InQxIiwicyI6InNlY3JldCJ9";

    @Test
    void createZonePostsTheDomainAndAccountAndReturnsTheNameservers() throws Exception {
        handler = uri -> new String[] {"200", "{\"success\":true,\"result\":{\"id\":\"z9\",\"name\":\"new.example\","
                + "\"status\":\"pending\",\"name_servers\":[\"ada.ns.cloudflare.com\",\"bob.ns.cloudflare.com\"],"
                + "\"account\":{\"id\":\"acc\"}}}"};
        ZoneCreated created = client.createZone("acc", "new.example");

        assertEquals("POST", lastMethod);
        Map<String, Object> sent = Json.asObject(Json.parse(lastBody));
        assertEquals("new.example", sent.get("name"));
        assertEquals("acc", Json.asObject(sent.get("account")).get("id"));
        assertEquals(new Zone("z9", "new.example", "acc"), created.zone());
        assertEquals(List.of("ada.ns.cloudflare.com", "bob.ns.cloudflare.com"), created.nameServers());
        assertEquals("pending", created.status());
    }

    @Test
    void createTunnelAsksForARemotelyManagedTunnelAndHidesItsToken() throws Exception {
        handler = uri -> new String[] {"200", "{\"success\":true,\"result\":{\"id\":\"t9\",\"name\":\"edge2\","
                + "\"status\":\"inactive\",\"token\":\"" + RUN_TOKEN + "\"}}"};
        TunnelCreated created = client.createTunnel("acc", "edge2");

        assertEquals("POST", lastMethod);
        assertEquals("cloudflare", Json.asObject(Json.parse(lastBody)).get("config_src"));
        assertEquals(new Tunnel("t9", "edge2", "inactive"), created.tunnel());
        assertEquals(RUN_TOKEN, created.token());
        assertEquals("sudo cloudflared service install " + RUN_TOKEN, created.installCommand());

        assertFalse(logText().contains(RUN_TOKEN), "the tunnel's run token leaked into the command log");
        assertFalse(created.toString().contains(RUN_TOKEN), "the tunnel's run token leaked into toString");
        assertTrue(logText().contains(CommandLog.MASK));
    }

    @Test
    void createTunnelFetchesTheTokenWhenTheReplyHasNone() throws Exception {
        handler = uri -> uri.endsWith("/token")
                ? new String[] {"200", "{\"success\":true,\"result\":\"" + RUN_TOKEN + "\"}"}
                : new String[] {"200", "{\"success\":true,\"result\":{\"id\":\"t9\",\"name\":\"edge2\"}}"};
        TunnelCreated created = client.createTunnel("acc", "edge2");

        assertEquals(RUN_TOKEN, created.token());
        assertEquals("inactive", created.tunnel().status());
        assertFalse(logText().contains(RUN_TOKEN), "the tunnel's run token leaked into the command log");
    }

    @Test
    void aRefusedCreationIsReportedWithWhatIsMissing() {
        handler = uri -> new String[] {"403", "{\"success\":false,\"errors\":[{\"message\":\"Authentication error\"}]}"};
        CloudflareException e = assertThrows(CloudflareException.class, () -> client.createZone("acc", "new.example"));
        assertEquals(403, e.httpStatus());
        assertTrue(e.getMessage().contains("permission"), e.getMessage());
    }

    @Test
    void deleteTunnelSendsDelete() throws Exception {
        handler = uri -> new String[] {"200", "{\"success\":true,\"result\":{\"id\":\"t9\"}}"};
        client.deleteTunnel("acc", "t9");
        assertEquals("DELETE", lastMethod);
        assertThrows(IllegalArgumentException.class, () -> client.deleteTunnel("acc", " "));
    }

    @Test
    void deleteZoneSendsDeleteForThatZoneOnly() throws Exception {
        List<String> uris = new CopyOnWriteArrayList<>();
        handler = uri -> {
            uris.add(uri);
            return new String[] {"200", "{\"success\":true,\"result\":{\"id\":\"z9\"}}"};
        };
        client.deleteZone("z9");
        assertEquals("DELETE", lastMethod);
        assertEquals(List.of("/zones/z9"), uris);
        assertThrows(IllegalArgumentException.class, () -> client.deleteZone(""));
    }

    @Test
    void aTunnelThatIsStillConnectedIsRefusedWithCloudflaresReason() {
        handler = uri -> new String[] {"400", "{\"success\":false,\"errors\":[{\"message\":\"Cannot delete tunnel "
                + "because it has active connections\"}]}"};
        CloudflareException e = assertThrows(CloudflareException.class, () -> client.deleteTunnel("acc", "t9"));
        assertTrue(e.getMessage().contains("active connections"), e.getMessage());
    }

    @Test
    void deleteDnsRecordSendsDelete() throws Exception {
        handler = uri -> new String[] {"200", "{\"success\":true,\"result\":{\"id\":\"r1\"}}"};
        client.deleteDnsRecord("z1", "r1");
        assertEquals("DELETE", lastMethod);
        assertThrows(IllegalArgumentException.class, () -> client.deleteDnsRecord("z1", " "));
    }

    @Test
    void listDnsRecordsParsesEveryField() throws Exception {
        handler = uri -> new String[] {"200", "{\"success\":true,\"result\":[{\"id\":\"r1\",\"type\":\"A\","
                + "\"name\":\"x.a.com\",\"content\":\"1.2.3.4\",\"proxied\":false,\"ttl\":300,\"comment\":\"hi\"}],"
                + "\"result_info\":{\"total_pages\":1}}"};
        assertEquals(List.of(new DnsRecord("r1", "A", "x.a.com", "1.2.3.4", false, 300, "hi")),
                client.listDnsRecords("z1"));
    }

    @Test
    void tokenNeverAppearsInTheLogOrInErrors() {
        handler = uri -> new String[] {"403",
                "{\"success\":false,\"errors\":[{\"code\":9109,\"message\":\"bad " + TOKEN + "\"}]}"};
        CloudflareException e = assertThrows(CloudflareException.class, () -> client.listZones());
        assertFalse(logText().contains(TOKEN), "token leaked into the command log");
        assertFalse(e.getMessage().contains(TOKEN), "token leaked into the exception message");
        assertTrue(logText().contains(CommandLog.MASK));
        assertEquals(403, e.httpStatus());
        assertTrue(e.getMessage().contains("permission"));
    }

    @Test
    void everyRequestIsLoggedBeforeItsReply() throws Exception {
        handler = uri -> new String[] {"200", "{\"success\":true,\"result\":[],\"result_info\":{\"total_pages\":1}}"};
        client.listZones();
        String text = logText();
        assertTrue(text.contains("Cloudflare GET "), text);
        assertTrue(text.indexOf("Cloudflare GET ") < text.indexOf("Cloudflare replied HTTP 200"));
    }

    @Test
    void listZonesFollowsPagination() throws Exception {
        handler = uri -> {
            if (uri.contains("page=1")) {
                return new String[] {"200", "{\"success\":true,\"result\":[{\"id\":\"z1\",\"name\":\"a.com\","
                        + "\"account\":{\"id\":\"acc\"}}],\"result_info\":{\"total_pages\":2}}"};
            }
            return new String[] {"200", "{\"success\":true,\"result\":[{\"id\":\"z2\",\"name\":\"b.com\","
                    + "\"account\":{\"id\":\"acc\"}}],\"result_info\":{\"total_pages\":2}}"};
        };
        List<Zone> zones = client.listZones();
        assertEquals(List.of(new Zone("z1", "a.com", "acc"), new Zone("z2", "b.com", "acc")), zones);
    }

    @Test
    void listTunnelsReadsStatusAndBuildsCnameTarget() throws Exception {
        handler = uri -> new String[] {"200", "{\"success\":true,\"result\":[{\"id\":\"abc-123\","
                + "\"name\":\"web1\",\"status\":\"healthy\"}],\"result_info\":{\"total_pages\":1}}"};
        List<Tunnel> tunnels = client.listTunnels("acc");
        assertEquals(1, tunnels.size());
        assertEquals("healthy", tunnels.get(0).status());
        assertEquals("abc-123.cfargotunnel.com", tunnels.get(0).cnameTarget());
    }

    @Test
    void apiFailureWithHttp200IsStillAnError() {
        handler = uri -> new String[] {"200", "{\"success\":false,\"errors\":[{\"message\":\"nope\"}]}"};
        CloudflareException e = assertThrows(CloudflareException.class, () -> client.listZones());
        assertTrue(e.getMessage().contains("nope"));
    }

    @Test
    void nonJsonReplyIsReportedCleanly() {
        handler = uri -> new String[] {"502", "<html>Bad gateway</html>"};
        CloudflareException e = assertThrows(CloudflareException.class, () -> client.listZones());
        assertEquals(502, e.httpStatus());
    }

    @Test
    void blankTokenIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CloudflareClient("  ", log));
    }
}
