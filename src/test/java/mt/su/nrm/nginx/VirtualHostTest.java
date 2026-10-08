package mt.su.nrm.nginx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mt.su.nrm.nginx.VhostSettings.HeaderSpec;
import mt.su.nrm.nginx.VhostSettings.ListenSpec;
import mt.su.nrm.nginx.VhostSettings.RuleSpec;
import java.util.List;
import org.junit.jupiter.api.Test;

class VirtualHostTest {

    private static ConfigFile load(String resource) throws Exception {
        return ConfigFile.parse("/etc/nginx/" + resource, NginxRoundTripTest.resource(resource));
    }

    @Test
    void applyingUnchangedSettingsChangesNothingInAnyCorpusFile() throws Exception {
        for (String name : NginxRoundTripTest.CORPUS) {
            ConfigFile file = load(name);
            for (Block server : file.serverBlocks()) {
                VirtualHost host = new VirtualHost(file, server);
                host.apply(host.read());
            }
            assertEquals(file.originalText(), file.generate(), name);
            assertFalse(file.isModified(), name);
        }
    }

    @Test
    void readsTheSettingsOfARealServerBlock() throws Exception {
        ConfigFile file = load("confd-app.conf");
        VhostSettings s = new VirtualHost(file, file.serverBlocks().get(0)).read();

        assertEquals(List.of("app.example.com"), s.serverNames);
        assertEquals("80", s.listens.get(0).endpoint);
        assertEquals(80, s.listens.get(0).port());
        assertEquals("/usr/share/nginx/html", s.root);
        assertEquals("index.html", s.index);
        assertEquals("20m", s.clientMaxBodySize);
        assertEquals(List.of("/var/log/nginx/app.access.log main"), s.accessLogs);
        assertEquals("/var/log/nginx/app.error.log warn", s.errorLog);

        assertEquals(4, s.locations.size());
        assertEquals(LocationSettings.Type.PROXY, s.locations.get(0).type);
        assertEquals("http://127.0.0.1:3000", s.locations.get(0).proxyPass);
        assertEquals(List.of("Host $host", "X-Forwarded-For $proxy_add_x_forwarded_for"),
                s.locations.get(0).proxySetHeaders);
        assertEquals(LocationSettings.Type.STATIC, s.locations.get(1).type);
        assertEquals("/srv/app/static/", s.locations.get(1).alias);
        assertEquals(LocationSettings.Type.REDIRECT, s.locations.get(2).type);
        assertEquals("301", s.locations.get(2).redirectCode);
        assertEquals("=", s.locations.get(3).modifier);
        assertEquals("/404.html", s.locations.get(3).path);
    }

    @Test
    void readsListenParametersAndSslFromAComplexServer() throws Exception {
        ConfigFile file = load("complex.conf");
        VhostSettings s = new VirtualHost(file, file.serverBlocks().get(0)).read();

        assertEquals(List.of("example.com", "www.example.com", "*.example.org"), s.serverNames);
        assertEquals(2, s.listens.size());
        assertTrue(s.listens.get(0).ssl());
        assertTrue(s.listens.get(0).has("http2"));
        assertEquals("[::]:443", s.listens.get(1).endpoint);
        assertEquals(443, s.listens.get(1).port());
        assertEquals("/etc/ssl/certs/example.pem", s.sslCertificate);
        assertEquals(1, s.headers.size());
        assertEquals("Strict-Transport-Security", s.headers.get(0).name);
        assertEquals("max-age=31536000; includeSubDomains", s.headers.get(0).value);
        assertTrue(s.headers.get(0).always);
        assertEquals(List.of(new RuleSpec("rewrite", "^/old/(.*)$ /new/$1 permanent").arguments),
                s.rewrites.stream().filter(r -> r.directive.equals("rewrite")).map(r -> r.arguments).toList());
    }

    @Test
    void statementsTheEditorDoesNotManageAreListedAndSurviveEdits() throws Exception {
        ConfigFile file = load("complex.conf");
        VirtualHost host = new VirtualHost(file, file.serverBlocks().get(0));
        List<String> unmanaged = host.unmanaged();
        assertTrue(unmanaged.contains("if ($request_method = POST) {"), unmanaged.toString());
        assertTrue(unmanaged.contains("set $foo \"${bar}baz\";"), unmanaged.toString());

        VhostSettings s = host.read();
        s.serverNames.add("extra.example.com");
        host.apply(s);

        String expected = file.originalText().replace(
                "server_name example.com www.example.com *.example.org;",
                "server_name example.com www.example.com *.example.org extra.example.com;");
        assertEquals(expected, file.generate());
        assertTrue(file.generate().contains("content_by_lua_block {"));
    }

    @Test
    void changingServerNamesKeepsTheTrailingComment() throws Exception {
        ConfigFile file = load("confd-app.conf");
        VirtualHost host = new VirtualHost(file, file.serverBlocks().get(0));
        VhostSettings s = host.read();
        s.serverNames.add("www.app.example.com");
        host.apply(s);
        assertTrue(file.generate().contains("server_name app.example.com www.app.example.com;    # the app"),
                file.generate());
    }

    @Test
    void newDirectivesGoInASensibleOrder() throws Exception {
        ConfigFile file = load("confd-app.conf");
        VirtualHost host = new VirtualHost(file, file.serverBlocks().get(0));
        VhostSettings s = host.read();
        s.headers.add(new HeaderSpec("X-Frame-Options", "DENY", true));
        s.sslProtocols = "TLSv1.2 TLSv1.3";
        host.apply(s);

        String out = file.generate();
        assertTrue(out.contains("index        index.html;\n    ssl_protocols TLSv1.2 TLSv1.3;\n"), out);
        assertTrue(out.contains("error_log   /var/log/nginx/app.error.log warn;\n    add_header X-Frame-Options DENY always;\n"),
                out);
        // The rest is untouched.
        assertTrue(out.contains("    error_page 404 /404.html;\n"));
    }

    @Test
    void removingSettingsRemovesTheirDirectives() throws Exception {
        ConfigFile file = load("confd-app.conf");
        VirtualHost host = new VirtualHost(file, file.serverBlocks().get(0));
        VhostSettings s = host.read();
        s.clientMaxBodySize = "";
        s.accessLogs.clear();
        host.apply(s);
        String out = file.generate();
        assertFalse(out.contains("client_max_body_size"));
        assertFalse(out.contains("access_log"));
        assertTrue(out.contains("error_log"));
        // Still valid syntax.
        ConfigFile.parse("x", out);
    }

    @Test
    void locationsCanBeAddedEditedAndRemoved() throws Exception {
        ConfigFile file = load("confd-app.conf");
        VirtualHost host = new VirtualHost(file, file.serverBlocks().get(0));
        VhostSettings s = host.read();

        s.locations.remove(3);                                   // = /404.html
        s.locations.get(2).redirectTarget = "https://new.example.com/";
        s.locations.get(0).proxyPass = "http://127.0.0.1:4000";
        s.locations.add(LocationSettings.newProxy("/api", "http://127.0.0.1:9000"));
        host.apply(s);

        String out = file.generate();
        assertFalse(out.contains("location = /404.html"));
        assertTrue(out.contains("return 301 https://new.example.com/;"), out);
        assertTrue(out.contains("proxy_pass         http://127.0.0.1:3000;") == false);
        assertTrue(out.contains("proxy_pass http://127.0.0.1:4000;"), out);
        assertTrue(out.contains("    location /api {\n        proxy_pass http://127.0.0.1:9000;\n"
                + "        proxy_set_header Host $host;\n"), out);

        // The new location comes after the last existing one, before the unmanaged error_page.
        assertTrue(out.indexOf("location /api") > out.indexOf("location /old"));

        VhostSettings again = new VirtualHost(ConfigFile.parse("x", out), ConfigFile.parse("x", out).serverBlocks().get(0)).read();
        assertEquals(4, again.locations.size());
        assertEquals("/api", again.locations.get(3).path);
    }

    @Test
    void changingALocationTypeClearsTheOldTypesDirectives() throws Exception {
        ConfigFile file = load("confd-app.conf");
        VirtualHost host = new VirtualHost(file, file.serverBlocks().get(0));
        VhostSettings s = host.read();
        LocationSettings root = s.locations.get(0);
        root.type = LocationSettings.Type.STATIC;
        root.root = "/srv/site";
        host.apply(s);

        Block location = file.serverBlocks().get(0).blocks("location").get(0);
        assertEquals(null, location.first("proxy_pass"));
        assertTrue(location.directives("proxy_set_header").isEmpty());
        assertEquals("/srv/site", location.first("root").arg(0));
    }

    @Test
    void locationWithUnmanagedDirectivesKeepsThemWhenEdited() throws Exception {
        ConfigFile file = ConfigFile.parse("x",
                "server {\n    location / {\n        proxy_pass http://a;\n        proxy_read_timeout 300s;\n"
                        + "        gzip on;\n    }\n}\n");
        VirtualHost host = new VirtualHost(file, file.serverBlocks().get(0));
        VhostSettings s = host.read();
        s.locations.get(0).proxyPass = "http://b";
        host.apply(s);
        assertEquals("server {\n    location / {\n        proxy_pass http://b;\n        proxy_read_timeout 300s;\n"
                + "        gzip on;\n    }\n}\n", file.generate());
    }

    @Test
    void newVirtualHostIsGeneratedCleanly() throws Exception {
        ConfigFile file = ConfigFile.empty("/etc/nginx/conf.d/example.com.conf");
        VirtualHost host = VirtualHost.createNew(file);
        VhostSettings s = new VhostSettings();
        s.serverNames.add("example.com");
        s.listens.add(new ListenSpec("80"));
        s.root = "/var/www/example.com";
        s.index = "index.html";
        host.apply(s);

        assertEquals("server {\n    listen 80;\n    server_name example.com;\n    root /var/www/example.com;\n"
                + "    index index.html;\n}\n", file.generate());
    }

    @Test
    void newSslVirtualHostWithAProxyLocation() throws Exception {
        ConfigFile file = ConfigFile.empty("/etc/nginx/sites-available/api.example.com");
        VirtualHost host = VirtualHost.createNew(file);
        VhostSettings s = new VhostSettings();
        s.serverNames.add("api.example.com");
        s.listens.add(new ListenSpec("443", "ssl", "http2"));
        s.sslCertificate = "/etc/ssl/certs/api.pem";
        s.sslCertificateKey = "/etc/ssl/private/api.key";
        s.locations.add(LocationSettings.newProxy("/", "http://127.0.0.1:3000"));
        host.apply(s);

        String expected = "server {\n"
                + "    listen 443 ssl http2;\n"
                + "    server_name api.example.com;\n"
                + "    ssl_certificate /etc/ssl/certs/api.pem;\n"
                + "    ssl_certificate_key /etc/ssl/private/api.key;\n"
                + "    location / {\n"
                + "        proxy_pass http://127.0.0.1:3000;\n"
                + "        proxy_set_header Host $host;\n"
                + "        proxy_set_header X-Real-IP $remote_addr;\n"
                + "        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\n"
                + "        proxy_set_header X-Forwarded-Proto $scheme;\n"
                + "    }\n"
                + "}\n";
        assertEquals(expected, file.generate());
        ConfigFile.parse("x", file.generate());
    }

    @Test
    void aVirtualHostCanBeAddedToAnExistingFileAndRemoved() throws Exception {
        ConfigFile file = load("ubuntu-default-site");
        int before = file.serverBlocks().size();
        VirtualHost added = VirtualHost.createNew(file);
        VhostSettings s = new VhostSettings();
        s.serverNames.add("new.example.com");
        s.listens.add(new ListenSpec("8080"));
        added.apply(s);
        assertEquals(before + 1, ConfigFile.parse("x", file.generate()).serverBlocks().size());

        added.remove();
        // The commented-out example at the end of the file is kept; only the added block is gone.
        assertEquals(file.originalText().stripTrailing(), file.generate().stripTrailing());
    }

    @Test
    void listenParamsKeepTheirOrderAndPortsAreParsed() {
        ListenSpec l = new ListenSpec("127.0.0.1:8443", "default_server", "ssl");
        l.set("http2", true);
        l.set("default_server", false);
        assertEquals(List.of("ssl", "http2"), l.params);
        assertEquals(8443, l.port());
        assertEquals(80, new ListenSpec("127.0.0.1").port());
        assertEquals(80, new ListenSpec("[::]").port());
        assertEquals(443, new ListenSpec("[::]:443").port());
        assertEquals(-1, new ListenSpec("unix:/run/x.sock").port());
        assertEquals(-1, new ListenSpec("1:2:3").port());
    }
}
