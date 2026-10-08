package mt.su.nrm.nginx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mt.su.nrm.model.ConfigLayout;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class Step7ModelTest {

    private static RemoteConfig config(String resource) throws Exception {
        ConfigFile main = ConfigFile.parse("/etc/nginx/nginx.conf", NginxRoundTripTest.resource(resource));
        return new RemoteConfig("/etc/nginx", ConfigLayout.SITES_AVAILABLE, main);
    }

    private static boolean hasIssue(List<VhostValidator.Issue> issues, String fragment) {
        return issues.stream().anyMatch(i -> i.message().contains(fragment));
    }

    // ------------------------------------------------------------------------------- upstreams

    @Test
    void upstreamsAreReadAndUnchangedApplyChangesNothing() throws Exception {
        RemoteConfig config = config("complex.conf");
        Upstream upstream = config.upstreams().get(0);
        UpstreamSettings s = upstream.read();

        assertEquals("app_backend", s.name);
        assertEquals("least_conn", s.method);
        assertEquals(List.of("10.0.0.1:8080 weight=3 max_fails=2 fail_timeout=30s", "10.0.0.2:8080 backup"), s.servers);
        assertEquals("32", s.keepalive);
        assertTrue(s.problems().isEmpty(), s.problems().toString());

        upstream.apply(s);
        assertFalse(config.hasPending());
    }

    @Test
    void upstreamMethodAndServersCanBeChanged() throws Exception {
        RemoteConfig config = config("complex.conf");
        Upstream upstream = config.upstreams().get(0);
        UpstreamSettings s = upstream.read();
        s.method = "ip_hash";
        s.servers.remove(1);
        s.servers.add("10.0.0.3:8080 weight=2");
        upstream.apply(s);

        String text = config.mainFile().generate();
        assertTrue(text.contains("    upstream app_backend {\n        ip_hash;\n        server 10.0.0.1:8080 weight=3"), text);
        assertFalse(text.contains("least_conn"));
        assertTrue(text.contains("server 10.0.0.3:8080 weight=2;"));
        assertFalse(text.contains("backup"));
        assertTrue(text.contains("keepalive 32;"));
        ConfigFile.parse("x", text);
    }

    @Test
    void newUpstreamsGoIntoTheHttpBlockAndCanBeDeleted() throws Exception {
        RemoteConfig config = config("ubuntu-nginx.conf");
        Upstream created = config.createUpstream("api");
        UpstreamSettings s = new UpstreamSettings();
        s.name = "api";
        s.method = "least_conn";
        s.servers.add("127.0.0.1:3000");
        s.servers.add("127.0.0.1:3001 backup");
        s.keepalive = "16";
        created.apply(s);

        String text = config.mainFile().generate();
        assertTrue(text.contains("\tupstream api {\n\t\tleast_conn;\n\t\tserver 127.0.0.1:3000;\n"
                + "\t\tserver 127.0.0.1:3001 backup;\n\t\tkeepalive 16;\n\t}"), text);
        assertEquals(1, config.upstreams().size());
        assertEquals(1, config.pendingChanges().size());

        config.deleteUpstream(config.upstreams().get(0));
        assertFalse(config.hasPending() && config.mainFile().generate().contains("upstream"),
                "deleting it again restores the original text");
        assertEquals(config.mainFile().originalText(), config.mainFile().generate());
    }

    @Test
    void upstreamProblemsAreReported() {
        UpstreamSettings s = new UpstreamSettings();
        s.name = "bad name";
        assertTrue(hasIssueText(s.problems(), "letters, digits"));
        assertTrue(hasIssueText(s.problems(), "at least one server"));
        s.name = "ok";
        s.servers.add("!!bad address");
        assertTrue(hasIssueText(s.problems(), "must start with an address"));
        s.servers.set(0, "10.0.0.1:80 weight=heavy");
        assertTrue(hasIssueText(s.problems(), "needs a number"));
        s.servers.set(0, "10.0.0.1:80 bogus");
        assertTrue(hasIssueText(s.problems(), "not a server parameter"));
        s.servers.set(0, "10.0.0.1:80 weight=2 backup");
        s.method = "sideways";
        assertTrue(hasIssueText(s.problems(), "balancing method"));
        s.method = "hash $request_uri consistent";
        assertTrue(s.problems().isEmpty(), s.problems().toString());
    }

    private static boolean hasIssueText(List<String> problems, String fragment) {
        return problems.stream().anyMatch(p -> p.contains(fragment));
    }

    // ------------------------------------------------------------------------------- cache and limit zones

    @Test
    void cacheZonesKeepUnknownParametersAndOrder() throws Exception {
        ConfigFile main = ConfigFile.parse("/etc/nginx/nginx.conf", "http {\n    proxy_cache_path /var/cache/n levels=1:2 "
                + "keys_zone=c1:10m loader_files=100 max_size=1g inactive=60m;\n}\n");
        RemoteConfig config = new RemoteConfig("/etc/nginx", ConfigLayout.SITES_AVAILABLE, main);
        CacheZone zone = config.cacheZones().get(0);
        CacheZoneSettings s = zone.read();
        assertEquals("/var/cache/n", s.path);
        assertEquals("1:2", s.levels);
        assertEquals("c1:10m", s.keysZone);
        assertEquals("c1", s.zoneName());
        assertEquals("1g", s.maxSize);
        assertEquals("60m", s.inactive);

        zone.apply(s);
        assertFalse(config.hasPending());

        s.maxSize = "5g";
        s.inactive = "";
        s.useTempPath = "off";
        zone.apply(s);
        assertEquals("http {\n    proxy_cache_path /var/cache/n levels=1:2 keys_zone=c1:10m loader_files=100 "
                + "max_size=5g use_temp_path=off;\n}\n", config.mainFile().generate());
    }

    @Test
    void newCacheAndLimitZonesAreAddedToTheHttpBlock() throws Exception {
        RemoteConfig config = config("ubuntu-nginx.conf");
        CacheZone cache = config.createCacheZone("/var/cache/nginx/site");
        CacheZoneSettings c = cache.read();
        c.keysZone = "site:10m";
        c.levels = "1:2";
        c.maxSize = "1g";
        cache.apply(c);
        LimitZone limit = config.createLimitZone(LimitZoneSettings.Kind.REQUEST);
        LimitZoneSettings l = limit.read();
        l.zone = "perip:10m";
        l.rate = "10r/s";
        limit.apply(l);
        LimitZone conn = config.createLimitZone(LimitZoneSettings.Kind.CONNECTION);
        LimitZoneSettings cs = conn.read();
        cs.zone = "conn:10m";
        conn.apply(cs);

        String text = config.mainFile().generate();
        assertTrue(text.contains("proxy_cache_path /var/cache/nginx/site levels=1:2 keys_zone=site:10m max_size=1g;"), text);
        assertTrue(text.contains("limit_req_zone $binary_remote_addr zone=perip:10m rate=10r/s;"), text);
        assertTrue(text.contains("limit_conn_zone $binary_remote_addr zone=conn:10m;"), text);
        assertEquals(1, config.cacheZones().size());
        assertEquals(2, config.limitZones().size());
        assertEquals("perip", config.limitZones().get(0).read().zoneName());
        ConfigFile.parse("x", text);
    }

    @Test
    void zoneProblemsAreReported() {
        CacheZoneSettings c = new CacheZoneSettings();
        assertTrue(hasIssueText(c.problems(), "absolute path"));
        assertTrue(hasIssueText(c.problems(), "name:10m"));
        c.path = "/var/cache/x";
        c.keysZone = "x:10m";
        c.levels = "3:3";
        assertTrue(hasIssueText(c.problems(), "Levels"));
        c.levels = "1:2";
        c.maxSize = "big";
        assertTrue(hasIssueText(c.problems(), "maximum size"));
        c.maxSize = "1g";
        assertTrue(c.problems().isEmpty());

        LimitZoneSettings l = new LimitZoneSettings();
        l.key = "remote_addr";
        assertTrue(hasIssueText(l.problems(), "nginx variable"));
        l.key = "$binary_remote_addr";
        l.zone = "z:10m";
        assertTrue(hasIssueText(l.problems(), "rate"));
        l.rate = "10r/s";
        assertTrue(l.problems().isEmpty());
        l.kind = LimitZoneSettings.Kind.CONNECTION;
        l.rate = "";
        assertTrue(l.problems().isEmpty());
    }

    // ------------------------------------------------------------------------------- global settings

    @Test
    void globalSettingsAreReadAndChangedInPlace() throws Exception {
        RemoteConfig config = config("ubuntu-nginx.conf");
        GlobalConfig global = config.global();
        GlobalSettings s = global.read();
        assertEquals("auto", s.workerProcesses);
        assertEquals("768", s.workerConnections);
        assertEquals("on", s.sendfile);
        assertEquals("on", s.gzip);
        assertEquals("TLSv1 TLSv1.1 TLSv1.2 TLSv1.3", s.sslProtocols);
        assertEquals("/var/log/nginx/error.log", s.errorLog);

        global.apply(s);
        assertFalse(config.hasPending());

        s.workerConnections = "2048";
        s.serverTokens = "off";
        s.sslProtocols = "TLSv1.2 TLSv1.3";
        s.gzip = "";
        global.apply(s);

        String text = config.mainFile().generate();
        assertTrue(text.contains("worker_connections 2048;"));
        assertTrue(text.contains("server_tokens off;"));
        assertTrue(text.contains("ssl_protocols TLSv1.2 TLSv1.3; # Dropping SSLv3, ref: POODLE"), text);
        assertFalse(text.contains("\tgzip on;"));
        assertTrue(text.contains("# server_tokens off;"), "the commented-out example is untouched");
        ConfigFile.parse("x", text);
    }

    @Test
    void globalSettingsProblemsAreReported() {
        GlobalSettings s = new GlobalSettings();
        assertTrue(s.problems().isEmpty());
        s.workerProcesses = "lots";
        s.gzipCompLevel = "12";
        s.sslProtocols = "TLSv9";
        s.errorLog = "relative.log";
        s.serverTokens = "maybe";
        assertEquals(5, s.problems().size(), s.problems().toString());
    }

    @Test
    void mainConfigIsEditableAndItsChangesArePending() throws Exception {
        RemoteConfig config = config("ubuntu-nginx.conf");
        assertNull(config.mainReadOnlyReason());
        config.global().apply(withServerTokensOff(config.global().read()));
        List<PendingChange> changes = config.pendingChanges();
        assertEquals(1, changes.size());
        assertEquals("/etc/nginx/nginx.conf", changes.get(0).path());
        config.discardAll();
        assertFalse(config.hasPending());
    }

    private static GlobalSettings withServerTokensOff(GlobalSettings s) {
        s.serverTokens = "off";
        return s;
    }

    // ------------------------------------------------------------------------------- locations and pages

    @Test
    void locationExtrasAreReadAndUnchangedApplyChangesNothing() throws Exception {
        String text = "server {\n    location /admin/ {\n        auth_basic \"Restricted area\";\n"
                + "        auth_basic_user_file /etc/nginx/.htpasswd;\n        allow 10.0.0.0/8;\n        deny all;\n"
                + "        limit_req zone=perip burst=5 nodelay;\n        gzip on;\n        gzip_types text/css text/plain;\n"
                + "        proxy_pass http://app;\n        proxy_cache c1;\n        proxy_cache_valid 200 302 10m;\n"
                + "        proxy_read_timeout 90s;\n        error_page 502 /down.html;\n    }\n}\n";
        ConfigFile file = ConfigFile.parse("x", text);
        VirtualHost host = new VirtualHost(file, file.serverBlocks().get(0));
        LocationSettings l = host.read().locations.get(0);

        assertEquals("Restricted area", l.authBasic);
        assertEquals("/etc/nginx/.htpasswd", l.authBasicUserFile);
        assertEquals(List.of("allow 10.0.0.0/8", "deny all"), l.accessRules);
        assertEquals(List.of("zone=perip burst=5 nodelay"), l.limitReq);
        assertEquals("on", l.gzip);
        assertEquals("text/css text/plain", l.gzipTypes);
        assertEquals("c1", l.proxyCache);
        assertEquals(List.of("200 302 10m"), l.proxyCacheValid);
        assertEquals("90s", l.proxyReadTimeout);
        assertEquals(List.of("502 /down.html"), l.errorPages);

        host.apply(host.read());
        assertEquals(text, file.generate());
    }

    @Test
    void locationExtrasCanBeAddedChangedAndRemoved() throws Exception {
        ConfigFile file = ConfigFile.parse("x", "server {\n    location / {\n        proxy_pass http://app;\n    }\n}\n");
        VirtualHost host = new VirtualHost(file, file.serverBlocks().get(0));
        VhostSettings s = host.read();
        LocationSettings l = s.locations.get(0);
        l.authBasic = "Members";
        l.authBasicUserFile = "/etc/nginx/.htpasswd";
        l.accessRules = List.of("allow 192.168.0.0/16", "deny all");
        l.limitReq = List.of("zone=perip burst=10");
        l.gzip = "on";
        l.gzipTypes = "application/json";
        l.proxyCache = "c1";
        l.proxyCacheValid = List.of("200 5m");
        host.apply(s);

        String out = file.generate();
        assertEquals("server {\n    location / {\n        proxy_pass http://app;\n        proxy_cache c1;\n"
                + "        proxy_cache_valid 200 5m;\n        auth_basic Members;\n"
                + "        auth_basic_user_file /etc/nginx/.htpasswd;\n        allow 192.168.0.0/16;\n        deny all;\n"
                + "        limit_req zone=perip burst=10;\n        gzip on;\n        gzip_types application/json;\n    }\n}\n", out);

        // Reorder the rules and drop the login: only those lines change.
        VhostSettings again = new VirtualHost(file, file.serverBlocks().get(0)).read();
        LocationSettings l2 = again.locations.get(0);
        l2.accessRules = List.of("deny 10.9.9.9", "allow all");
        l2.authBasic = "";
        l2.authBasicUserFile = "";
        new VirtualHost(file, file.serverBlocks().get(0)).apply(again);
        String out2 = file.generate();
        assertFalse(out2.contains("auth_basic"));
        assertTrue(out2.contains("        deny 10.9.9.9;\n        allow all;\n        limit_req"), out2);
        ConfigFile.parse("x", out2);
    }

    @Test
    void serverErrorPagesAreManagedAndValidated() throws Exception {
        ConfigFile file = ConfigFile.parse("x", "server {\n    listen 80;\n    location / { }\n    error_page 404 /404.html;\n}\n");
        VirtualHost host = new VirtualHost(file, file.serverBlocks().get(0));
        VhostSettings s = host.read();
        assertEquals(List.of("404 /404.html"), s.errorPages);
        s.errorPages = List.of("404 /404.html", "500 502 503 504 /50x.html");
        host.apply(s);
        assertTrue(file.generate().contains("error_page 500 502 503 504 /50x.html;"));

        s.errorPages = List.of("nonsense");
        assertTrue(hasIssue(VhostValidator.validate(s), "Error page"));
        s.errorPages = List.of("404 =200 /empty.gif");
        assertFalse(hasIssue(VhostValidator.validate(s), "Error page"));
    }

    @Test
    void newServerDirectivesGoAboveTheFirstLocationEvenWhenLaterOnesExist() throws Exception {
        ConfigFile file = ConfigFile.parse("x", "server {\n    listen 80;\n    location / { }\n    add_header X 1;\n}\n");
        VirtualHost host = new VirtualHost(file, file.serverBlocks().get(0));
        VhostSettings s = host.read();
        s.serverNames.add("a.example.com");
        host.apply(s);
        assertEquals("server {\n    listen 80;\n    server_name a.example.com;\n    location / { }\n    add_header X 1;\n}\n",
                file.generate());
    }

    @Test
    void locationExtrasAndReferencesAreValidated() {
        VhostSettings s = new VhostSettings();
        s.serverNames.add("a.example.com");
        s.listens.add(new VhostSettings.ListenSpec("80"));
        LocationSettings l = LocationSettings.newProxy("/", "http://app");
        l.authBasic = "Members";
        l.accessRules = List.of("permit all");
        l.limitReq = List.of("burst=5");
        l.gzip = "maybe";
        l.gzipCompLevel = "11";
        l.proxyCacheValid = List.of("200 soon");
        l.proxyReadTimeout = "slow";
        s.locations.add(l);
        List<VhostValidator.Issue> issues = VhostValidator.validate(s);
        assertTrue(hasIssue(issues, "no password file"));
        assertTrue(hasIssue(issues, "Access rule"));
        assertTrue(hasIssue(issues, "needs a zone"));
        assertTrue(hasIssue(issues, "must be on or off"));
        assertTrue(hasIssue(issues, "1 to 9"));
        assertTrue(hasIssue(issues, "Cache time"));
        assertTrue(hasIssue(issues, "read timeout"));

        LocationSettings ok = LocationSettings.newProxy("/", "http://app");
        ok.limitReq = List.of("zone=perip burst=5");
        ok.limitConn = List.of("conn 10");
        ok.proxyCache = "c1";
        VhostSettings t = new VhostSettings();
        t.locations.add(ok);
        assertEquals(3, VhostValidator.checkReferences(t, Set.of(), Set.of(), Set.of()).size());
        assertTrue(VhostValidator.checkReferences(t, Set.of("perip"), Set.of("conn"), Set.of("c1")).isEmpty());

        // A cache zone on a location that doesn't proxy does nothing, so it is flagged.
        LocationSettings staticLoc = new LocationSettings();
        staticLoc.proxyCache = "c1";
        VhostSettings st = new VhostSettings();
        st.locations.add(staticLoc);
        List<VhostValidator.Issue> flagged = VhostValidator.checkReferences(st, Set.of(), Set.of(), Set.of("c1"));
        assertEquals(1, flagged.size());
        assertTrue(hasIssue(flagged, "doesn't proxy"));
    }
}
