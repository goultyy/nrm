package mt.su.nrm.cloudflare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TunnelConfigTest {

    /** A reply as Cloudflare sends it, with settings the app doesn't model. */
    private static final String REPLY = """
            {"tunnel_id":"t1","version":7,"source":"cloudflare","config":{
              "ingress":[
                {"hostname":"a.example.com","service":"http://localhost:8080",
                 "originRequest":{"connectTimeout":30,"noTLSVerify":true}},
                {"hostname":"b.example.com","path":"/api","service":"http://localhost:9000"},
                {"service":"http_status:404"}],
              "warp-routing":{"enabled":false}}}
            """;

    private static TunnelConfig load(String reply) {
        return TunnelConfig.fromReply(Json.parse(reply));
    }

    @Test
    void separatesRoutesFromTheCatchAll() {
        TunnelConfig c = load(REPLY);
        assertEquals(3, c.ingress().size());
        assertEquals(2, c.routes().size());
        assertTrue(c.ingress().get(2).isCatchAll());
        assertTrue(c.isRemotelyManaged());
    }

    @Test
    void newRouteGoesBeforeTheCatchAll() {
        TunnelConfig c = load(REPLY).withRoute(IngressRule.route("c.example.com", "http://localhost:1"));
        List<IngressRule> rules = c.ingress();
        assertEquals(4, rules.size());
        assertEquals("c.example.com", rules.get(2).hostname());
        assertTrue(rules.get(3).isCatchAll());
    }

    @Test
    void emptyConfigGetsADefaultCatchAll() {
        TunnelConfig empty = TunnelConfig.fromReply(Json.parse("{\"config\":{},\"source\":\"cloudflare\"}"));
        TunnelConfig c = empty.withRoute(IngressRule.route("a.example.com", "http://localhost:1"));
        assertEquals("http_status:404", c.ingress().get(1).service());
    }

    @Test
    void duplicateRouteIsRefusedButSameHostWithAnotherPathIsNot() {
        TunnelConfig c = load(REPLY);
        assertThrows(IllegalArgumentException.class,
                () -> c.withRoute(IngressRule.route("A.example.com", "http://localhost:2")));
        c.withRoute(IngressRule.route("b.example.com", "http://localhost:2"));
    }

    @Test
    void editsKeepSettingsTheAppDoesNotModel() {
        TunnelConfig c = load(REPLY)
                .withReplacedRoute("b.example.com", "/api", IngressRule.route("b.example.com", "http://localhost:3"))
                .withoutRoute("a.example.com", "");
        Map<String, Object> config = Json.asObject(c.toRequestBody().get("config"));
        assertEquals(Map.of("enabled", Boolean.FALSE), config.get("warp-routing"));
        assertEquals(2, c.ingress().size());

        TunnelConfig untouched = load(REPLY).withRoute(IngressRule.route("c.example.com", "http://localhost:1"));
        assertEquals(Json.asList(Json.asObject(Json.asObject(Json.parse(REPLY)).get("config")).get("ingress"))
                        .subList(0, 2),
                untouched.ingress().subList(0, 2).stream().map(IngressRule::raw).toList(),
                "an untouched rule changed, e.g. lost its originRequest");
    }

    @Test
    void removingOrReplacingAMissingRouteFails() {
        TunnelConfig c = load(REPLY);
        assertThrows(IllegalArgumentException.class, () -> c.withoutRoute("nope.example.com", ""));
        assertThrows(IllegalArgumentException.class,
                () -> c.withReplacedRoute("nope.example.com", "", IngressRule.route("x.example.com", "http://l")));
    }

    @Test
    void requestBodyRequiresACatchAllAtTheEnd() {
        TunnelConfig noCatchAll = TunnelConfig.fromReply(Json.parse(
                "{\"config\":{\"ingress\":[{\"hostname\":\"a.example.com\",\"service\":\"http://l\"}]}}"));
        assertThrows(IllegalArgumentException.class, noCatchAll::toRequestBody);
        TunnelConfig catchAllFirst = TunnelConfig.fromReply(Json.parse(
                "{\"config\":{\"ingress\":[{\"service\":\"http_status:404\"},"
                        + "{\"hostname\":\"a.example.com\",\"service\":\"http://l\"}]}}"));
        assertThrows(IllegalArgumentException.class, catchAllFirst::toRequestBody);
    }

    @Test
    void aTunnelConfiguredByFileIsNotEditable() {
        TunnelConfig local = load(REPLY.replace("\"cloudflare\"", "\"local\""));
        assertFalse(local.isRemotelyManaged());
    }

    @Test
    void routeAccessorsReadPathAndService() {
        IngressRule r = load(REPLY).routes().get(1);
        assertEquals("/api", r.path());
        assertEquals("http://localhost:9000", r.service());
    }
}
