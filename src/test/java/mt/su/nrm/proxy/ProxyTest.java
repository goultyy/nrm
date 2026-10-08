package mt.su.nrm.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import mt.su.nrm.model.ConfigLayout;
import mt.su.nrm.nginx.ConfigFile;
import mt.su.nrm.nginx.NginxParseException;
import mt.su.nrm.nginx.RealIp;
import mt.su.nrm.nginx.RemoteConfig;
import mt.su.nrm.nginx.VhostSettings;
import mt.su.nrm.nginx.VhostSettings.RealIpSpec;
import mt.su.nrm.nginx.VhostValidator;
import mt.su.nrm.nginx.VirtualHost;
import mt.su.nrm.ssh.CommandLog;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProxyTest {

    private static RemoteConfig config(String http) throws NginxParseException {
        ConfigFile main = ConfigFile.parse("/etc/nginx/nginx.conf", "events {}\nhttp {\n" + http + "}\n");
        return new RemoteConfig("/etc/nginx", ConfigLayout.CONF_D, main);
    }

    private static String text(RemoteConfig config) {
        return config.mainFile().generate();
    }

    private static RealIpSpec cloudflareLike() {
        return new RealIpSpec(List.of("173.245.48.0/20", "2400:cb00::/32"), "CF-Connecting-IP", false);
    }

    /** The site with this server_name, as the editor sees it. */
    private static VirtualHost site(RemoteConfig config, String name) {
        return config.virtualHosts().stream().filter(h -> h.displayName().equals(name)).findFirst().orElseThrow();
    }

    private static final String TWO_SITES = "    server {\n        listen 80;\n        server_name a.com;\n    }\n"
            + "    server {\n        listen 80;\n        server_name b.com;\n    }\n";

    // ---------------------------------------------------------------- what may be trusted

    @Test
    void addressesAndRangesAreCheckedStrictly() {
        for (String ok : List.of("127.0.0.1", "10.0.0.0/8", "192.168.1.5/32", "::1", "fc00::/7", "2400:cb00::/32",
                "0.0.0.0/0")) {
            assertTrue(RealIp.validSource(ok), ok);
        }
        for (String bad : List.of("", " ", "localhost", "example.com", "10.0.0.256", "10.0.0/8", "10.0.0.0/33",
                "::1/129", "10.0.0.0/-1", "10.0.0.1;", "10.0.0.1 deny all", "unix:/run/x.sock", "1.2.3.4/8/9",
                " 10.0.0.1", "gggg::1")) {
            assertFalse(RealIp.validSource(bad), "'" + bad + "' must not be accepted into a config");
        }
    }

    @Test
    void trustingTheWholeInternetIsRefused() {
        RealIpSpec everyone = new RealIpSpec(List.of("0.0.0.0/0"), "X-Forwarded-For", true);
        assertTrue(RealIp.problems(everyone).stream().anyMatch(p -> p.contains("every address")));
        assertFalse(RealIp.problems(new RealIpSpec(List.of("::/0"), "X-Forwarded-For", true)).isEmpty());
    }

    @Test
    void invalidAddressesAndHeadersAreErrorsButAPartialSetupIsOnlyAWarning() {
        assertFalse(RealIp.problems(new RealIpSpec(List.of("10.0.0.1"), "X-Real IP;", true)).isEmpty());
        assertFalse(RealIp.problems(new RealIpSpec(List.of("not-an-ip"), "X-Forwarded-For", true)).isEmpty());
        assertTrue(RealIp.problems(new RealIpSpec(List.of("10.0.0.1"), "proxy_protocol", false)).isEmpty());
        assertTrue(RealIp.problems(cloudflareLike()).isEmpty());
        // A site may set just one of the two and use the http level's for the other.
        assertTrue(RealIp.problems(new RealIpSpec(List.of(), "X-Forwarded-For", true)).isEmpty());
        assertTrue(RealIp.problems(new RealIpSpec(List.of("10.0.0.1"), "", true)).isEmpty());
        assertTrue(RealIp.warnings(new RealIpSpec(List.of(), "X-Forwarded-For", true)).stream()
                .anyMatch(w -> w.contains("No proxy addresses are listed here")));
        assertTrue(RealIp.warnings(new RealIpSpec(List.of("10.0.0.1"), "", true)).stream()
                .anyMatch(w -> w.contains("No header is chosen here")));
    }

    @Test
    void wideRangesAndAMissingRecursiveGetWarnings() {
        assertTrue(RealIp.warnings(new RealIpSpec(List.of("10.0.0.0/4"), "CF-Connecting-IP", false))
                .stream().anyMatch(w -> w.contains("very wide")));
        assertTrue(RealIp.warnings(new RealIpSpec(List.of("10.0.0.1"), "X-Forwarded-For", false))
                .stream().anyMatch(w -> w.contains("last address")));
        assertTrue(RealIp.warnings(new RealIpSpec(List.of("10.0.0.1"), "proxy_protocol", false))
                .stream().anyMatch(w -> w.contains("listen line")));
        assertTrue(RealIp.warnings(cloudflareLike()).isEmpty());
    }

    // ---------------------------------------------------------------- per site

    @Test
    void eachSiteHasItsOwnProxyAndTheOtherIsUntouched() throws Exception {
        RemoteConfig config = config(TWO_SITES);
        String before = text(config);

        VirtualHost a = site(config, "a.com");
        VhostSettings sa = a.read();
        assertEquals(null, sa.realIp, "a site with no real-IP lines has none");
        sa.realIp = cloudflareLike();
        a.apply(sa);

        VirtualHost b = site(config, "b.com");
        VhostSettings sb = b.read();
        sb.realIp = new RealIpSpec(List.of("10.0.0.0/8"), "X-Forwarded-For", true);
        b.apply(sb);

        String out = text(config);
        ConfigFile.parse("/etc/nginx/nginx.conf", out);
        int aStart = out.indexOf("server_name a.com;");
        int bStart = out.indexOf("server_name b.com;");
        String aBlock = out.substring(aStart, bStart);
        String bBlock = out.substring(bStart);
        assertTrue(aBlock.contains("set_real_ip_from 173.245.48.0/20;") && aBlock.contains("real_ip_header CF-Connecting-IP;"), out);
        assertFalse(aBlock.contains("10.0.0.0/8") || aBlock.contains("X-Forwarded-For"), "a must not get b's proxy: " + out);
        assertTrue(bBlock.contains("set_real_ip_from 10.0.0.0/8;") && bBlock.contains("real_ip_header X-Forwarded-For;")
                && bBlock.contains("real_ip_recursive on;"), out);
        assertFalse(bBlock.contains("CF-Connecting-IP"), out);
        assertFalse(aBlock.contains("real_ip_recursive"), "off is the default and is not written: " + out);

        assertEquals(cloudflareLike(), site(config, "a.com").read().realIp);
        assertEquals(List.of("10.0.0.0/8"), site(config, "b.com").read().realIp.sources);
        assertFalse(before.equals(out));
    }

    @Test
    void removingASitesSettingsRestoresItsTextExactly() throws Exception {
        RemoteConfig config = config(TWO_SITES);
        String before = text(config);
        VirtualHost a = site(config, "a.com");
        VhostSettings s = a.read();
        s.realIp = new RealIpSpec(List.of("127.0.0.1"), "X-Forwarded-For", true);
        a.apply(s);
        assertTrue(text(config).contains("real_ip_recursive on;"));

        VhostSettings again = site(config, "a.com").read();
        again.realIp = null;
        site(config, "a.com").apply(again);
        assertEquals(before, text(config));
    }

    @Test
    void anUntouchedSiteIsWrittenBackExactlyEvenWithHandMadePartialSettings() throws Exception {
        RemoteConfig config = config("    server {\n        listen 80;\n        server_name a.com;\n"
                + "        set_real_ip_from 10.0.0.1;\n        # trusts the office proxy\n        real_ip_header X-Real-IP;\n"
                + "        root /var/www;\n    }\n");
        String before = text(config);
        VirtualHost a = site(config, "a.com");
        VhostSettings s = a.read();
        assertEquals(List.of("10.0.0.1"), s.realIp.sources);
        assertEquals("X-Real-IP", s.realIp.header);
        assertFalse(s.realIp.recursive);
        a.apply(s);
        assertEquals(before, text(config), "saving without changing the real-IP settings must not touch them");
    }

    @Test
    void offIsWrittenOnlyWhenSomethingElseWouldTurnRecursionOn() throws Exception {
        RemoteConfig config = config("    real_ip_recursive on;\n" + TWO_SITES);
        VirtualHost a = site(config, "a.com");
        VhostSettings s = a.read();
        s.realIp = new RealIpSpec(List.of("10.0.0.1"), "CF-Connecting-IP", false);
        s.realIp.recursiveExplicit = true; // the pane sets this when the http level has it on
        a.apply(s);
        String out = text(config);
        assertTrue(out.substring(out.indexOf("server_name a.com;")).contains("real_ip_recursive off;"), out);
        assertTrue(site(config, "a.com").read().realIp.recursiveExplicit);
    }

    @Test
    void theHttpLevelIsReadOnlyAndSitesOwnLinesAreNotMistakenForIt() throws Exception {
        RemoteConfig config = config("    set_real_ip_from 192.0.2.1;\n    real_ip_header X-Real-IP;\n"
                + "    server { listen 80; server_name inner.com; set_real_ip_from 1.2.3.4; real_ip_header CF-Connecting-IP; }\n");
        RealIpSpec inherited = RealIp.inherited(config).orElseThrow();
        assertEquals(List.of("192.0.2.1"), inherited.sources);
        assertEquals("X-Real-IP", inherited.header);
        assertTrue(RealIp.describe(inherited).contains("believes 1 address and reads the visitor from X-Real-IP"));
        assertEquals(Optional.empty(), RealIp.inherited(config("    server { listen 80; set_real_ip_from 1.2.3.4; }\n")),
                "a site's own settings are not the http level's");
    }

    @Test
    void theEditorsCheckFlagsBadSettingsAsErrorsAndNamesTheTab() throws Exception {
        VhostSettings s = new VhostSettings();
        s.serverNames.add("a.com");
        s.listens.add(new VhostSettings.ListenSpec("80"));
        s.realIp = new RealIpSpec(List.of("0.0.0.0/0", "nonsense"), "X-Forwarded-For", true);
        List<VhostValidator.Issue> issues = VhostValidator.validate(s);
        long errors = issues.stream().filter(i -> i.severity() == VhostValidator.Severity.ERROR && i.where().equals("Real IP")).count();
        assertEquals(2, errors, issues.toString());
        s.realIp = cloudflareLike();
        assertTrue(VhostValidator.validate(s).stream().noneMatch(i -> i.where().equals("Real IP")));
        s.realIp = new RealIpSpec(List.of(), "", false);
        assertTrue(VhostValidator.validate(s).stream().noneMatch(i -> i.where().equals("Real IP")), "empty means not set");
    }

    // ---------------------------------------------------------------- presets

    // ---------------------------------------------------------------- presets

    @Test
    void theBuiltInPresetsAreAllUsableAsTheyAre() {
        List<ProxyPreset> presets = ProxyPresets.all();
        assertTrue(presets.size() >= 5);
        for (ProxyPreset p : presets) {
            if (!p.fetched() && !p.sources().isEmpty()) {
                RealIpSpec spec = new RealIpSpec(p.sources(), p.header(), p.recursive());
                assertTrue(RealIp.problems(spec).isEmpty() && RealIp.warnings(spec).isEmpty(),
                        p.id() + " is not valid as shipped");
            }
        }
        assertTrue(ProxyPresets.byId(ProxyPresets.CLOUDFLARE).orElseThrow().fetched());
        assertEquals("CF-Connecting-IP", ProxyPresets.byId(ProxyPresets.CLOUDFLARE_TUNNEL).orElseThrow().header());
        assertEquals(Optional.empty(), ProxyPresets.byId("nope"));
    }

    @Test
    void thePrivateNetworkPresetStillTrustsNothingOnThePublicInternet() {
        ProxyPreset p = ProxyPresets.byId(ProxyPresets.PRIVATE_NETWORK).orElseThrow();
        for (String s : p.sources()) {
            assertTrue(s.startsWith("10.") || s.startsWith("172.16.") || s.startsWith("192.168.") || s.startsWith("fc00"), s);
        }
    }

    // ---------------------------------------------------------------- Cloudflare's list

    private static HttpServer serve(int status, String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static List<String> fetchFrom(HttpServer server, CommandLog log) throws IOException {
        return CloudflareRanges.fetch(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/ips"),
                HttpClient.newHttpClient(), log);
    }

    @Test
    void cloudflaresListIsReadAndTheRequestIsLogged() throws Exception {
        HttpServer server = serve(200, "{\"success\":true,\"result\":{\"etag\":\"x\","
                + "\"ipv4_cidrs\":[\"173.245.48.0/20\",\"103.21.244.0/22\"],\"ipv6_cidrs\":[\"2400:cb00::/32\"]}}");
        try {
            CommandLog log = new CommandLog();
            assertEquals(List.of("173.245.48.0/20", "103.21.244.0/22", "2400:cb00::/32"), fetchFrom(server, log));
            assertTrue(log.snapshot().stream().anyMatch(l -> l.text().startsWith("Cloudflare GET ")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anythingThatIsNotAnAddressInTheListMakesTheWholeListRefused() throws Exception {
        HttpServer server = serve(200, "{\"success\":true,\"result\":{\"ipv4_cidrs\":[\"173.245.48.0/20\","
                + "\"1.2.3.4; deny all\"],\"ipv6_cidrs\":[]}}");
        try {
            IOException e = assertThrows(IOException.class, () -> fetchFrom(server, new CommandLog()));
            assertTrue(e.getMessage().contains("not an address or range"), e.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failuresAndEmptyListsAreRefused() throws Exception {
        HttpServer failing = serve(500, "{\"success\":false}");
        HttpServer empty = serve(200, "{\"success\":true,\"result\":{\"ipv4_cidrs\":[],\"ipv6_cidrs\":[]}}");
        HttpServer html = serve(200, "<html>captive portal</html>");
        try {
            assertThrows(IOException.class, () -> fetchFrom(failing, new CommandLog()));
            assertThrows(IOException.class, () -> fetchFrom(empty, new CommandLog()));
            assertThrows(IOException.class, () -> fetchFrom(html, new CommandLog()));
        } finally {
            failing.stop(0);
            empty.stop(0);
            html.stop(0);
        }
    }
}
