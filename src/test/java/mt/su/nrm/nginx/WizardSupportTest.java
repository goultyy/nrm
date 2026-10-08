package mt.su.nrm.nginx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class WizardSupportTest {

    // ------------------------------------------------------------------------------- units

    @Test
    void sizesParseAndFormatBothWays() {
        Units.Size s = Units.Size.parse("10m").orElseThrow();
        assertEquals(new Units.Size(10, Units.SizeUnit.MB), s);
        assertEquals("10m", s.format());
        assertEquals("10 MB", s.display());
        assertEquals(10L * 1024 * 1024, s.bytes());
        assertEquals("1g", Units.Size.parse("1G").orElseThrow().format());
        assertEquals("512 KB", Units.Size.parse("512k").orElseThrow().display());
        for (String bad : List.of("", "10", "m", "1.5g", "-1m", "10 mb", "ten", "1t")) {
            assertTrue(Units.Size.parse(bad).isEmpty(), bad);
        }
    }

    @Test
    void durationsParseAndAreReadable() {
        assertEquals("1 hour", Units.Duration.parse("1h").orElseThrow().display());
        assertEquals("90 minutes", Units.Duration.parse("90m").orElseThrow().display());
        assertEquals("30 seconds", Units.Duration.parse("30").orElseThrow().display(), "no unit means seconds");
        assertEquals("7d", Units.Duration.parse("7d").orElseThrow().format());
        assertEquals(604_800, Units.Duration.parse("1w").orElseThrow().seconds());
        assertEquals(3600, Units.Duration.parse("60m").orElseThrow().seconds());
        for (String bad : List.of("", "1M", "1y", "500ms", "h", "1.5h", "-5m")) {
            assertTrue(Units.Duration.parse(bad).isEmpty(), bad);
        }
    }

    @Test
    void ratesParseAndAreReadable() {
        Units.Rate r = Units.Rate.parse("10r/s").orElseThrow();
        assertEquals("10 requests per second", r.display());
        assertEquals("10r/s", r.format());
        assertEquals("1 request per minute", Units.Rate.parse("1r/m").orElseThrow().display());
        assertEquals("30r/m", new Units.Rate(30, Units.RateUnit.MINUTE).format());
        for (String bad : List.of("", "10", "10r", "10r/h", "r/s", "10 r/s")) {
            assertTrue(Units.Rate.parse(bad).isEmpty(), bad);
        }
    }

    @Test
    void whatWeParseMatchesWhatTheZoneSettingsAccept() {
        // Everything the wizards write must pass the validators that guard the files.
        CacheZoneSettings c = new CacheZoneSettings();
        c.path = "/var/cache/nginx/x";
        c.keysZone = "x:" + new Units.Size(50, Units.SizeUnit.MB).format();
        c.maxSize = new Units.Size(2, Units.SizeUnit.GB).format();
        c.inactive = new Units.Duration(7, Units.TimeUnit.DAYS).format();
        assertTrue(c.problems().isEmpty(), c.problems().toString());
        LimitZoneSettings l = new LimitZoneSettings();
        l.zone = "z:10m";
        l.rate = new Units.Rate(5, Units.RateUnit.MINUTE).format();
        assertTrue(l.problems().isEmpty(), l.problems().toString());
    }

    // ------------------------------------------------------------------------------- limit keys

    @Test
    void limitKeysMapToVariablesAndBack() {
        assertEquals("$binary_remote_addr", LimitKey.CLIENT_IP.variable());
        assertEquals(LimitKey.CLIENT_IP, LimitKey.of("$binary_remote_addr"));
        assertEquals(LimitKey.SITE, LimitKey.of("$server_name"));
        assertEquals(LimitKey.CUSTOM, LimitKey.of("$http_authorization"));
        assertEquals("perip_req", LimitKey.CLIENT_IP.suggestedZoneName(LimitZoneSettings.Kind.REQUEST));
        assertEquals("persite_conn", LimitKey.SITE.suggestedZoneName(LimitZoneSettings.Kind.CONNECTION));
        assertEquals(160_000, LimitKey.keysInLimitZone(10));
        assertEquals(400_000, LimitKey.itemsInCacheIndex(50));
        for (LimitKey k : LimitKey.values()) {
            assertFalse(k.toString().contains("$"), "labels are plain words: " + k);
        }
    }

    // ------------------------------------------------------------------------------- upstream servers

    @Test
    void serverLinesRoundTripThroughTheFields() {
        for (String line : List.of("10.0.0.1:8080", "10.0.0.1:8080 weight=3 backup", "app1.internal max_fails=2 fail_timeout=30s",
                "[::1]:9000 down", "unix:/run/app.sock", "10.0.0.2:80 weight=2 slow_start=30s", "example.com:443 resolve")) {
            assertEquals(line, UpstreamServer.parse(line).format(), line);
        }
        UpstreamServer s = UpstreamServer.parse("10.0.0.1:8080 weight=3 max_fails=2 fail_timeout=30s backup slow_start=5s");
        assertEquals("10.0.0.1", s.address);
        assertEquals("8080", s.port);
        assertEquals("3", s.weight);
        assertEquals("2", s.maxFails);
        assertEquals("30s", s.failTimeout);
        assertEquals(UpstreamServer.Role.BACKUP, s.role);
        assertEquals(List.of("slow_start=5s"), s.extra, "parameters the fields don't cover are kept");
    }

    @Test
    void serversAreBuiltFromFieldsAndIpv6GetsBrackets() {
        UpstreamServer s = new UpstreamServer();
        s.address = "2001:db8::1";
        s.port = "8080";
        s.weight = "2";
        s.role = UpstreamServer.Role.DOWN;
        assertEquals("[2001:db8::1]:8080 weight=2 down", s.format());
        assertEquals("2001:db8::1", UpstreamServer.parse(s.format()).address);
    }

    @Test
    void serverProblemsAreExplained() {
        UpstreamServer s = new UpstreamServer();
        assertTrue(s.isBlank());
        assertTrue(s.problems().stream().anyMatch(p -> p.contains("needs an address")));
        s.address = "bad host!";
        assertTrue(s.problems().stream().anyMatch(p -> p.contains("not a valid address")));
        s.address = "10.0.0.1";
        s.port = "99999";
        assertTrue(s.problems().stream().anyMatch(p -> p.contains("port")));
        s.port = "80";
        s.weight = "0";
        assertTrue(s.problems().stream().anyMatch(p -> p.contains("weight")));
        s.weight = "2";
        s.failTimeout = "soon";
        assertTrue(s.problems().stream().anyMatch(p -> p.contains("fail timeout")));
        s.failTimeout = "30s";
        assertTrue(s.problems().isEmpty(), s.problems().toString());
        UpstreamServer socket = UpstreamServer.parse("unix:/run/a.sock");
        socket.port = "80";
        assertFalse(socket.problems().isEmpty());
    }

    // ------------------------------------------------------------------------------- previews

    @Test
    void previewsShowExactlyWhatWillBeWritten() throws Exception {
        UpstreamSettings u = new UpstreamSettings();
        u.name = "app";
        u.method = "least_conn";
        u.servers.add("10.0.0.1:8080 weight=3");
        u.servers.add("10.0.0.2:8080 backup");
        u.keepalive = "32";
        assertEquals("upstream app {\n    least_conn;\n    server 10.0.0.1:8080 weight=3;\n    server 10.0.0.2:8080 backup;\n"
                + "    keepalive 32;\n}", u.preview());

        CacheZoneSettings c = new CacheZoneSettings();
        c.path = "/var/cache/nginx/site";
        c.levels = "1:2";
        c.keysZone = "site:10m";
        c.maxSize = "1g";
        c.inactive = "1d";
        c.useTempPath = "off";
        assertEquals("proxy_cache_path /var/cache/nginx/site levels=1:2 keys_zone=site:10m max_size=1g inactive=1d "
                + "use_temp_path=off;", c.preview());

        LimitZoneSettings r = new LimitZoneSettings();
        r.zone = "perip_req:10m";
        r.rate = "10r/s";
        assertEquals("limit_req_zone $binary_remote_addr zone=perip_req:10m rate=10r/s;", r.preview());
        r.kind = LimitZoneSettings.Kind.CONNECTION;
        assertEquals("limit_conn_zone $binary_remote_addr zone=perip_req:10m;", r.preview());

        // What the preview shows is what the config engine really writes.
        ConfigFile file = ConfigFile.parse("/etc/nginx/nginx.conf", "http {\n}\n");
        RemoteConfig config = new RemoteConfig("/etc/nginx", mt.su.nrm.model.ConfigLayout.SITES_AVAILABLE, file);
        config.createUpstream("app").apply(u);
        String written = file.generate();
        assertTrue(written.contains(u.preview().replace("\n", "\n    ")), written);
        assertTrue(u.problems().isEmpty(), u.problems().toString());
    }
}
