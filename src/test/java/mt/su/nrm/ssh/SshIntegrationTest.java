package mt.su.nrm.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mt.su.nrm.model.PrivilegeMode;
import mt.su.nrm.model.ServerProfile;
import java.nio.charset.StandardCharsets;
import java.util.List;
import mt.su.nrm.ssl.CertificateInfo;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Runs against a real disposable server. Skipped unless NRM_TEST_HOST, NRM_TEST_USER and
 * NRM_TEST_PASSWORD are set (NRM_TEST_PORT defaults to 22). Never point it at a production server.
 */
class SshIntegrationTest {

    private static ServerProfile profile() {
        String host = System.getenv("NRM_TEST_HOST");
        String user = System.getenv("NRM_TEST_USER");
        String password = System.getenv("NRM_TEST_PASSWORD");
        Assumptions.assumeTrue(host != null && user != null && password != null,
                "NRM_TEST_HOST, NRM_TEST_USER and NRM_TEST_PASSWORD not set");
        ServerProfile p = new ServerProfile();
        p.setName("test");
        p.setHost(host);
        p.setUsername(user);
        p.setPassword(password);
        p.setPort(Integer.parseInt(System.getenv().getOrDefault("NRM_TEST_PORT", "22")));
        p.setPrivilegeMode(PrivilegeMode.SUDO_PASSWORD);
        return p;
    }

    @Test
    void fullRoundTripAgainstARealServer() throws Exception {
        ServerProfile p = profile();
        CommandLog log = new CommandLog();
        AtomicReference<String> pinned = new AtomicReference<>();

        // First connection: trusts and pins the host key.
        try (SshSession s = SshSession.connect(p, Credentials.stored(p), log, pinned::set)) {
            assertNotNull(pinned.get());
            assertTrue(pinned.get().startsWith("SHA256:"));

            assertEquals(p.getUsername(), s.exec("id -un").stdout().strip());
            assertEquals("0", s.execPrivileged("id -u").stdout().strip());

            String path = "/tmp/nrm-it-" + System.nanoTime() + ".txt";
            s.upload("hello nrm".getBytes(StandardCharsets.UTF_8), path);
            assertEquals("hello nrm", new String(s.download(path), StandardCharsets.UTF_8));
            assertEquals("hello nrm", s.exec("cat " + path).stdout());
            s.delete(path);
            assertFalse(s.exec("test -e " + path).ok());
        }

        // Nothing sensitive in the log, and the sudo password stayed off the command line.
        for (CommandLog.Line line : log.snapshot()) {
            assertFalse(line.text().contains(p.getPassword()), line.text());
        }
        assertTrue(log.snapshot().stream().anyMatch(l -> l.text().startsWith("$ sudo -S -p ''")));

        // Second connection with the pin: accepted, and nothing new pinned.
        p.setHostKeyFingerprint(pinned.get());
        AtomicReference<String> again = new AtomicReference<>();
        try (SshSession s = SshSession.connect(p, Credentials.stored(p), new CommandLog(), again::set)) {
            assertTrue(s.exec("true").ok());
        }
        assertEquals(null, again.get());

        // A different pinned key must block the connection before authentication.
        p.setHostKeyFingerprint("SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        HostKeyChangedException e = assertThrows(HostKeyChangedException.class,
                () -> SshSession.connect(p, Credentials.stored(p), new CommandLog(), f -> { }));
        assertEquals(pinned.get(), e.presented());
    }

    @Test
    void requirementCheckAgainstARealServer() throws Exception {
        ServerProfile p = profile();
        CommandLog log = new CommandLog();
        ConnectionTester.Report report = ConnectionTester.test(p, Credentials.stored(p), log, Clock.systemUTC());

        assertTrue(report.requirements().privilegeOk(), report.requirements().privilegeMessage());
        assertTrue(report.requirements().openssl().available());
        assertNotNull(report.newlyPinnedFingerprint());
    }

    @Test
    void fullValidateApplyRollbackDeleteCycleAgainstARealServer() throws Exception {
        ServerProfile p = profile();
        CommandLog log = new CommandLog();
        mt.su.nrm.model.ServerPaths paths = p.getPaths();
        String name = "nrm-it-" + (System.nanoTime() % 100000) + ".test";
        String file = "/etc/nginx/sites-available/" + name;
        String link = "/etc/nginx/sites-enabled/" + name;

        try (SshSession s = SshSession.connect(p, Credentials.stored(p), log, f -> { })) {
            mt.su.nrm.nginx.RemoteConfig config = RemoteConfigService.load(s, paths);
            assertTrue(config.problems().isEmpty(), config.problems().toString());
            int before = config.virtualHosts().size();

            // 1. Create a site, test it, apply it.
            mt.su.nrm.nginx.VirtualHost host = config.createVirtualHost(name);
            mt.su.nrm.nginx.VhostSettings settings = new mt.su.nrm.nginx.VhostSettings();
            settings.serverNames.add(name);
            settings.listens.add(new mt.su.nrm.nginx.VhostSettings.ListenSpec("8089"));
            settings.root = "/var/www/html";
            host.apply(settings);
            assertFalse(mt.su.nrm.nginx.VhostValidator.hasErrors(mt.su.nrm.nginx.VhostValidator.validate(settings)));

            var changes = config.pendingChanges();
            ApplyPipeline.Outcome tested = ApplyPipeline.test(s, paths, config, changes);
            assertTrue(tested.ok(), tested.output());
            assertFalse(s.exec("test -e " + file).ok(), "the test must not touch the live config");

            ApplyPipeline.Outcome applied = ApplyPipeline.apply(s, paths, config, changes, Clock.systemUTC());
            assertTrue(applied.ok(), applied.status() + "\n" + applied.output());
            assertTrue(s.exec("test -f " + file + " && test -L " + link).ok());
            assertEquals("server {\n    listen 8089;\n    server_name " + name + ";\n    root /var/www/html;\n}\n",
                    new String(s.download(file), StandardCharsets.UTF_8));

            // 2. Reload it: the new site is there, and editing it produces a modify.
            config = RemoteConfigService.load(s, paths);
            assertEquals(before + 1, config.virtualHosts().size());
            mt.su.nrm.nginx.VirtualHost again = config.virtualHosts().stream()
                    .filter(h -> h.displayName().equals(name)).findFirst().orElseThrow();
            assertFalse(config.hasPending());

            // 3. A config nginx rejects (missing certificate) fails the test without touching the server...
            mt.su.nrm.nginx.VhostSettings broken = again.read();
            broken.listens.set(0, new mt.su.nrm.nginx.VhostSettings.ListenSpec("8443", "ssl"));
            broken.sslCertificate = "/nonexistent/cert.pem";
            broken.sslCertificateKey = "/nonexistent/key.pem";
            again.apply(broken);
            var badChanges = config.pendingChanges();
            ApplyPipeline.Outcome badTest = ApplyPipeline.test(s, paths, config, badChanges);
            assertEquals(ApplyPipeline.Status.TEST_FAILED, badTest.status());
            assertTrue(badTest.output().contains("cert.pem"), badTest.output());
            assertFalse(badTest.output().contains("nrm-test-"), "scratch paths are mapped back: " + badTest.output());
            assertTrue(badTest.output().contains("/etc/nginx/nginx.conf"), badTest.output());

            // ...and if it is applied anyway, the live test fails and the file is restored.
            ApplyPipeline.Outcome rolledBack = ApplyPipeline.apply(s, paths, config, badChanges, Clock.systemUTC());
            assertEquals(ApplyPipeline.Status.ROLLED_BACK, rolledBack.status(), rolledBack.output());
            assertTrue(new String(s.download(file), StandardCharsets.UTF_8).contains("listen 8089;"),
                    "the original file is back");

            // 4. Delete the site: file and link are removed.
            config = RemoteConfigService.load(s, paths);
            config.deleteVirtualHost(config.virtualHosts().stream()
                    .filter(h -> h.displayName().equals(name)).findFirst().orElseThrow());
            var deletion = config.pendingChanges();
            assertEquals(mt.su.nrm.nginx.PendingChange.Kind.DELETE, deletion.get(0).kind());
            assertTrue(ApplyPipeline.test(s, paths, config, deletion).ok());
            ApplyPipeline.Outcome deleted = ApplyPipeline.apply(s, paths, config, deletion, Clock.systemUTC());
            assertTrue(deleted.ok(), deleted.output());
            assertFalse(s.exec("test -e " + file + " || test -L " + link).ok());
            assertEquals(before, RemoteConfigService.load(s, paths).virtualHosts().size());
        }
    }

    @Test
    void globalUpstreamZoneAndLocationChangesApplyAndRevertExactly() throws Exception {
        ServerProfile p = profile();
        mt.su.nrm.model.ServerPaths paths = p.getPaths();
        String site = "nrm-it7-" + (System.nanoTime() % 100000) + ".test";

        try (SshSession s = SshSession.connect(p, Credentials.stored(p), new CommandLog(), f -> { })) {
            mt.su.nrm.nginx.RemoteConfig config = RemoteConfigService.load(s, paths);
            String originalMain = config.mainFile().originalText();
            assertNull(config.mainReadOnlyReason());

            // Global setting, upstream, request-limit zone, cache zone.
            mt.su.nrm.nginx.GlobalSettings g = config.global().read();
            g.serverTokens = "off";
            config.global().apply(g);
            mt.su.nrm.nginx.Upstream up = config.createUpstream("nrm_it_app");
            mt.su.nrm.nginx.UpstreamSettings us = new mt.su.nrm.nginx.UpstreamSettings();
            us.name = "nrm_it_app";
            us.method = "least_conn";
            us.servers.add("127.0.0.1:65001");
            up.apply(us);
            mt.su.nrm.nginx.LimitZone lz = config.createLimitZone(mt.su.nrm.nginx.LimitZoneSettings.Kind.REQUEST);
            mt.su.nrm.nginx.LimitZoneSettings ls = lz.read();
            ls.zone = "nrm_it_req:1m";
            ls.rate = "5r/s";
            lz.apply(ls);
            mt.su.nrm.nginx.CacheZone cz = config.createCacheZone("/var/cache/nginx/nrm-it");
            mt.su.nrm.nginx.CacheZoneSettings cs = cz.read();
            cs.keysZone = "nrm_it_cache:1m";
            cs.levels = "1:2";
            cs.maxSize = "10m";
            cz.apply(cs);

            // A site that uses them: proxy to the upstream with caching, a limit, compression, auth, error page.
            mt.su.nrm.nginx.VirtualHost host = config.createVirtualHost(site);
            mt.su.nrm.nginx.VhostSettings vs = new mt.su.nrm.nginx.VhostSettings();
            vs.serverNames.add(site);
            vs.listens.add(new mt.su.nrm.nginx.VhostSettings.ListenSpec("8091"));
            vs.errorPages.add("502 /down.html");
            mt.su.nrm.nginx.LocationSettings loc = mt.su.nrm.nginx.LocationSettings.newProxy("/", "http://nrm_it_app");
            loc.limitReq.add("zone=nrm_it_req burst=5 nodelay");
            loc.proxyCache = "nrm_it_cache";
            loc.proxyCacheValid.add("200 1m");
            loc.gzip = "on";
            loc.gzipTypes = "text/plain";
            loc.accessRules = List.of("allow 127.0.0.1", "deny all");
            vs.locations.add(loc);
            assertTrue(mt.su.nrm.nginx.VhostValidator.checkReferences(vs, java.util.Set.of("nrm_it_req"),
                    java.util.Set.of(), java.util.Set.of("nrm_it_cache")).isEmpty());
            host.apply(vs);

            var changes = config.pendingChanges();
            assertEquals(2, changes.size());
            ApplyPipeline.Outcome tested = ApplyPipeline.test(s, paths, config, changes);
            assertTrue(tested.ok(), tested.output());
            ApplyPipeline.Outcome applied = ApplyPipeline.apply(s, paths, config, changes, Clock.systemUTC());
            assertTrue(applied.ok(), applied.status() + "\n" + applied.output());

            // Reload from the server: everything is there.
            config = RemoteConfigService.load(s, paths);
            assertEquals(1, config.upstreams().stream().filter(u -> u.name().equals("nrm_it_app")).count());
            assertEquals("off", config.global().read().serverTokens);
            assertFalse(config.hasPending());

            // Undo all of it. The main config must come back exactly as it was.
            config.deleteUpstream(config.upstreams().stream().filter(u -> u.name().equals("nrm_it_app")).findFirst().orElseThrow());
            config.deleteLimitZone(config.limitZones().stream()
                    .filter(z -> z.read().zoneName().equals("nrm_it_req")).findFirst().orElseThrow());
            config.deleteCacheZone(config.cacheZones().stream()
                    .filter(z -> z.read().zoneName().equals("nrm_it_cache")).findFirst().orElseThrow());
            mt.su.nrm.nginx.GlobalSettings back = config.global().read();
            back.serverTokens = g.serverTokens.equals("off") ? "" : g.serverTokens;
            back.serverTokens = "";
            config.global().apply(back);
            config.deleteVirtualHost(config.virtualHosts().stream()
                    .filter(h -> h.displayName().equals(site)).findFirst().orElseThrow());

            var undo = config.pendingChanges();
            ApplyPipeline.Outcome undoTest = ApplyPipeline.test(s, paths, config, undo);
            assertTrue(undoTest.ok(), undoTest.output() + "\n" + undo.stream().map(mt.su.nrm.nginx.PendingChange::diff).toList());
            ApplyPipeline.Outcome undone = ApplyPipeline.apply(s, paths, config, undo, Clock.systemUTC());
            assertTrue(undone.ok(), undone.output());

            config = RemoteConfigService.load(s, paths);
            assertEquals(originalMain, config.mainFile().originalText(), "nginx.conf is back exactly as it was");
            assertTrue(config.upstreams().stream().noneMatch(u -> u.name().equals("nrm_it_app")));
        }
    }

    @Test
    void certificateAuthorityIssuingAndListingAgainstARealServer() throws Exception {
        ServerProfile p = profile();
        mt.su.nrm.model.ServerPaths paths = p.getPaths();
        // Use throwaway folders so a real CA on the box is never touched.
        String base = "/tmp/nrm-it-ca-" + (System.nanoTime() % 100000);
        paths.setCaStorageDir(base + "/ca");
        paths.setManualCertDir(base + "/certs");
        paths.setLetsEncryptDir(base + "/le");

        try (SshSession s = SshSession.connect(p, Credentials.stored(p), new CommandLog(), f -> { })) {
            try {
                assertTrue(CertificateService.list(s, paths, List.of()).isEmpty());

                var caSubject = new mt.su.nrm.ssl.SubjectInfo("NRM Test CA", "NRM Testing", "Security", "AU", "New South Wales",
                        "Sydney", "pki@nrm.example");
                CaService.Result ca = CaService.createCa(s, paths,
                        new CaService.CaRequest(caSubject, 30, CaService.KeyType.EC_P384));
                assertTrue(ca.ok(), ca.output());
                var caRef = CaService.authority(paths, ca.certPath());
                assertFalse(CaService.createCa(s, paths, new CaService.CaRequest(caSubject, 30, CaService.KeyType.EC_P384)).ok(),
                        "an existing CA is never overwritten");
                // A server can have many authorities, each in its own folder.
                CaService.Result second = CaService.createCa(s, paths, new CaService.CaRequest(
                        mt.su.nrm.ssl.SubjectInfo.ofCommonName("NRM Second CA"), 30, CaService.KeyType.RSA_2048));
                assertTrue(second.ok(), second.output());
                var secondRef = CaService.authority(paths, second.certPath());
                assertFalse(caRef.dir().equals(secondRef.dir()));
                assertEquals("600", s.execPrivileged("stat -c %a " + caRef.dir() + "/ca.key").stdout().strip());
                assertEquals("600", s.execPrivileged("stat -c %a " + secondRef.dir() + "/ca.key").stdout().strip());

                var names = mt.su.nrm.ssl.AltNames.parse(
                        "nrm-it.internal *.nrm-it.internal 10.9.8.7 email:ops@nrm.example uri:https://nrm-it.internal/api",
                        true, true).names();
                var subject = new mt.su.nrm.ssl.SubjectInfo("nrm-it.internal", "NRM Testing", "Web", "AU", "New South Wales",
                        "Sydney", "ops@nrm.example");
                var request = new CaService.CertRequest(subject, names, 60, CaService.KeyType.EC_P256,
                        CaService.Usage.SERVER_AND_CLIENT);
                CaService.Result cert = CaService.issue(s, paths, caRef, request);
                assertTrue(cert.ok(), cert.output());
                assertEquals("600", s.execPrivileged("stat -c %a " + cert.keyPath()).stdout().strip());
                assertFalse(CaService.issue(s, paths, caRef, request).ok(), "an existing certificate is not overwritten");

                // A client certificate for a person: no host names, e-mail SAN only.
                var clientSubject = new mt.su.nrm.ssl.SubjectInfo("Jane Tester", "NRM Testing", "", "AU", "", "", "jane@nrm.example");
                CaService.Result client = CaService.issue(s, paths, caRef, new CaService.CertRequest(clientSubject,
                        mt.su.nrm.ssl.AltNames.parse("email:jane@nrm.example", true, true).names(), 30,
                        CaService.KeyType.RSA_2048, CaService.Usage.CLIENT));
                assertTrue(client.ok(), client.output());

                var listed = CertificateService.list(s, paths, List.of());
                // Two authorities and two issued certificates (from the first authority).
                assertEquals(4, listed.size(), listed.toString());
                var issued = listed.stream().filter(c -> c.path().endsWith("nrm-it.internal.crt")).findFirst().orElseThrow();
                assertEquals("nrm-it.internal", issued.commonName());
                assertEquals(List.of("nrm-it.internal", "*.nrm-it.internal", "10.9.8.7", "email:ops@nrm.example",
                        "URI:https://nrm-it.internal/api"), issued.names());
                assertEquals("NRM Testing", issued.subjectField("O"));
                assertEquals("Web", issued.subjectField("OU"));
                assertEquals("AU", issued.subjectField("C"));
                assertEquals("New South Wales", issued.subjectField("ST"));
                assertEquals("Sydney", issued.subjectField("L"));
                assertEquals("ops@nrm.example", issued.subjectField("emailAddress"));
                assertFalse(issued.serial().isEmpty());
                assertTrue(issued.keyInfo().startsWith("EC "), issued.keyInfo());
                assertFalse(issued.authority());
                assertEquals(CertificateInfo.Status.OK, issued.status(Clock.systemUTC()));
                assertFalse(issued.selfSigned());
                assertEquals("NRM Test CA", issued.issuerField("CN"));

                var person = listed.stream().filter(c -> c.path().endsWith("Jane_Tester.crt")).findFirst().orElseThrow();
                assertEquals(List.of("email:jane@nrm.example"), person.names());
                assertEquals("RSA 2048-bit", person.keyInfo());

                var authority = listed.stream().filter(c -> c.path().equals(caRef.certPath())).findFirst().orElseThrow();
                assertTrue(authority.selfSigned());
                assertTrue(authority.authority());
                assertEquals("NRM Test CA", authority.commonName());
                assertEquals("NRM Testing", authority.subjectField("O"));
                assertEquals("pki@nrm.example", authority.subjectField("emailAddress"));
                assertTrue(authority.keyInfo().startsWith("EC "), authority.keyInfo());

                // The second authority is listed separately and has issued nothing.
                var secondAuthority = listed.stream().filter(c -> c.path().equals(secondRef.certPath())).findFirst().orElseThrow();
                assertEquals("NRM Second CA", secondAuthority.commonName());
                assertTrue(secondAuthority.keyInfo().startsWith("RSA"), secondAuthority.keyInfo());
                assertEquals(0, listed.stream().filter(c -> !c.authority() && c.issuer().equals(secondAuthority.subject())).count());

                // What the UI shows as "issued by the CA".
                assertEquals(2, listed.stream().filter(c -> !c.authority() && c.issuer().equals(authority.subject())).count());

                // The certificates really chain to the CA.
                String verify = s.execPrivileged("openssl verify -CAfile " + caRef.certPath() + " " + cert.certPath()).stdout();
                assertTrue(verify.contains("OK"), verify);

                String pem = CaService.fetchCaCertificate(s, paths, caRef);
                assertTrue(pem.contains("BEGIN CERTIFICATE") && !pem.contains("PRIVATE KEY"));

                // Deleting: a certificate and its key, then a CA with the certificate it issued.
                assertTrue(CaService.deleteCertificate(s, paths, client.certPath(), true).ok());
                assertFalse(s.execPrivileged("test -e " + client.certPath() + " -o -e " + client.keyPath()).ok(),
                        "the client certificate and its key are gone");
                assertTrue(s.execPrivileged("test -e " + cert.certPath()).ok(), "other certificates are untouched");
                assertTrue(CaService.deleteCa(s, paths, secondRef, List.of()).ok());
                assertFalse(s.execPrivileged("test -e " + secondRef.dir()).ok(), "the second CA's folder is gone");
                assertTrue(s.execPrivileged("test -e " + caRef.certPath()).ok(), "the first CA is untouched");
                assertTrue(CaService.deleteCa(s, paths, caRef, List.of(cert.certPath())).ok());
                assertFalse(s.execPrivileged("test -e " + caRef.dir() + " -o -e " + cert.certPath()).ok(),
                        "the CA and the certificate it issued are gone");
                assertTrue(CertificateService.list(s, paths, List.of()).isEmpty());
            } finally {
                s.execPrivileged("rm -rf " + base);
            }

            // certbot may not be installed on the box; if it isn't, the failure is clear and harmless.
            if (!s.exec("command -v certbot").ok()) {
                CertbotService.Result r = CertbotService.issue(s, paths, new CertbotService.IssueRequest(
                        List.of("example.com"), "", CertbotService.Method.WEBROOT, "/var/www/html", true, ""));
                assertFalse(r.ok());
                assertTrue(r.output().contains("certbot is not installed"), r.output());
            }
        }
    }
}
