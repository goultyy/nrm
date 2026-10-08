package mt.su.nrm.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mt.su.nrm.nginx.ConfigFile;
import mt.su.nrm.nginx.VhostSettings;
import mt.su.nrm.nginx.VirtualHost;
import mt.su.nrm.security.SecurityHeaders.Choice;
import mt.su.nrm.security.SecurityHeaders.Header;
import mt.su.nrm.security.SecurityHeaders.Level;
import mt.su.nrm.security.SecurityHeaders.State;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SecurityHeadersTest {

    private static List<Choice> all(Level level) {
        return SecurityHeaders.preset(level).stream().map(h -> new Choice(h.name(), h.valueAt(level))).toList();
    }

    @Test
    void theCatalogIsCompleteAndEveryValueIsSafeToWrite() {
        Set<String> names = new HashSet<>();
        for (Header h : SecurityHeaders.catalog()) {
            assertTrue(names.add(h.name().toLowerCase()), "duplicate " + h.name());
            assertNull(SecurityHeaders.problem(new Choice(h.name(), h.value())), h.name());
            if (h.strictValue() != null) {
                assertNull(SecurityHeaders.problem(new Choice(h.name(), h.strictValue())), h.name());
            }
            assertFalse(h.why().isBlank() || h.risk().isBlank(), h.name() + " must explain itself");
        }
    }

    @Test
    void presetsGrowAndTheOptionalHeaderBelongsToNone() {
        int basic = SecurityHeaders.preset(Level.BASIC).size();
        int recommended = SecurityHeaders.preset(Level.RECOMMENDED).size();
        int strict = SecurityHeaders.preset(Level.STRICT).size();
        assertTrue(basic == 3 && recommended > basic && strict > recommended);
        for (Level l : List.of(Level.BASIC, Level.RECOMMENDED, Level.STRICT)) {
            assertTrue(SecurityHeaders.preset(l).stream().noneMatch(h -> h.level() == Level.OPTIONAL));
            assertFalse(SecurityHeaders.describe(l).isBlank());
        }
    }

    @Test
    void thePolicyThatBreaksSitesIsNotInTheRecommendedSetAndHstsStaysOutOfPreload() {
        assertTrue(SecurityHeaders.preset(Level.RECOMMENDED).stream()
                .noneMatch(h -> h.name().startsWith("Content-Security-Policy")));
        for (Header h : SecurityHeaders.catalog()) {
            assertFalse(h.value().contains("preload"), h.name());
            assertTrue(h.strictValue() == null || !h.strictValue().contains("preload"), h.name());
        }
        Header hsts = SecurityHeaders.find("strict-transport-security");
        assertEquals("max-age=31536000", hsts.valueAt(Level.RECOMMENDED));
        assertEquals("max-age=31536000; includeSubDomains", hsts.valueAt(Level.STRICT));
    }

    @Test
    void headersAreAddedWithAlwaysAndQuotedSoNginxReadsThemBack() throws Exception {
        String text = SecurityHeaders.merge("", all(Level.STRICT));
        assertTrue(text.contains("X-Frame-Options SAMEORIGIN always"), text);
        assertTrue(text.contains("Strict-Transport-Security \"max-age=31536000; includeSubDomains\" always"), text);

        // Through the real model, the generated server block parses and reads back with the values intact.
        ConfigFile file = ConfigFile.parse("/etc/nginx/a", "server {\n    listen 443 ssl;\n    server_name a.com;\n}\n");
        VirtualHost host = new VirtualHost(file, file.serverBlocks().get(0));
        VhostSettings s = host.read();
        for (String line : text.split("\n")) {
            s.headers.add(VhostSettings.HeaderSpec.fromValues(mt.su.nrm.nginx.Arg.parseValues(line)));
        }
        host.apply(s);
        String out = file.generate();
        VhostSettings back = new VirtualHost(ConfigFile.parse("x", out), ConfigFile.parse("x", out).serverBlocks().get(0)).read();
        assertEquals(SecurityHeaders.preset(Level.STRICT).size(), back.headers.size(), out);
        VhostSettings.HeaderSpec csp = back.headers.stream().filter(h -> h.name.equals("Content-Security-Policy"))
                .findFirst().orElseThrow();
        assertEquals(SecurityHeaders.find("Content-Security-Policy").value(), csp.value, out);
        assertTrue(csp.always);
    }

    @Test
    void mergingReplacesSameNamedHeadersInPlaceAndKeepsEverythingElse() {
        String existing = "X-Frame-Options DENY\nX-Custom \"keep me\" always\nx-content-type-options nosniff";
        String merged = SecurityHeaders.merge(existing, List.of(new Choice("X-Frame-Options", "SAMEORIGIN"),
                new Choice("X-Content-Type-Options", "nosniff"), new Choice("Referrer-Policy", "no-referrer")));
        assertEquals("X-Frame-Options SAMEORIGIN always\nX-Custom \"keep me\" always\n"
                + "X-Content-Type-Options nosniff always\nReferrer-Policy no-referrer always", merged);
        assertEquals(merged, SecurityHeaders.merge(merged, List.of(new Choice("Referrer-Policy", "no-referrer"))),
                "doing it twice changes nothing");
    }

    @Test
    void mergingIntoAnEmptyOrBlankListStartsClean() {
        assertEquals("Referrer-Policy no-referrer always",
                SecurityHeaders.merge("", List.of(new Choice("Referrer-Policy", "no-referrer"))));
        assertEquals("Referrer-Policy no-referrer always",
                SecurityHeaders.merge("\n\n", List.of(new Choice("Referrer-Policy", "no-referrer"))));
        assertEquals("", SecurityHeaders.merge("", List.of()));
    }

    @Test
    void reviewSaysWhatIsMissingSetDifferentOrSentOnlyOnSuccess() {
        String lines = "X-Content-Type-Options nosniff always\nX-Frame-Options DENY always\n"
                + "Referrer-Policy strict-origin-when-cross-origin";
        List<SecurityHeaders.Row> rows = SecurityHeaders.review(lines);
        assertEquals(SecurityHeaders.catalog().size(), rows.size());
        SecurityHeaders.Row nosniff = rows.stream().filter(r -> r.header().name().equals("X-Content-Type-Options")).findFirst().get();
        SecurityHeaders.Row frame = rows.stream().filter(r -> r.header().name().equals("X-Frame-Options")).findFirst().get();
        SecurityHeaders.Row referrer = rows.stream().filter(r -> r.header().name().equals("Referrer-Policy")).findFirst().get();
        SecurityHeaders.Row csp = rows.stream().filter(r -> r.header().name().equals("Content-Security-Policy")).findFirst().get();
        assertEquals(State.SET, nosniff.state());
        assertFalse(nosniff.withoutAlways());
        assertEquals(State.DIFFERENT, frame.state());
        assertEquals("DENY", frame.current());
        assertEquals(State.SET, referrer.state());
        assertTrue(referrer.withoutAlways(), "without always, error pages don't get it");
        assertEquals(State.MISSING, csp.state());
        assertNull(csp.current());
    }

    @Test
    void httpsIsRecognisedFromTheCertificateOrAnSslListener() {
        VhostSettings plain = new VhostSettings();
        assertFalse(SecurityHeaders.usesHttps(plain));
        VhostSettings cert = new VhostSettings();
        cert.sslCertificate = "/etc/ssl/a.pem";
        assertTrue(SecurityHeaders.usesHttps(cert));
        VhostSettings listen = new VhostSettings();
        listen.listens.add(new VhostSettings.ListenSpec("443", "ssl", "http2"));
        assertTrue(SecurityHeaders.usesHttps(listen));
    }

    @Test
    void warningsCoverHstsWithoutHttpsPreloadAndLocationsThatHideTheServerHeaders() throws Exception {
        VhostSettings plain = new VhostSettings();
        List<String> w = SecurityHeaders.warnings(plain, all(Level.RECOMMENDED));
        assertTrue(w.stream().anyMatch(x -> x.startsWith("Strict-Transport-Security is only meaningful over HTTPS")), w.toString());
        assertTrue(SecurityHeaders.warnings(plain, all(Level.BASIC)).isEmpty());
        assertTrue(SecurityHeaders.warnings(plain, List.of(new Choice("Strict-Transport-Security", "max-age=1; preload")))
                .stream().anyMatch(x -> x.contains("preload")));

        // A location with its own add_header hides the server-level ones: the classic nginx surprise.
        ConfigFile file = ConfigFile.parse("/etc/nginx/a", """
                server {
                    listen 443 ssl;
                    server_name a.com;
                    location /api { add_header Cache-Control "no-store"; proxy_pass http://127.0.0.1:3000; }
                    location / { root /var/www; }
                }
                """);
        VhostSettings s = new VirtualHost(file, file.serverBlocks().get(0)).read();
        List<String> warnings = SecurityHeaders.warnings(s, all(Level.BASIC));
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("/api") && !warnings.get(0).contains(", /."), warnings.get(0));
        assertTrue(SecurityHeaders.warnings(s, List.of()).isEmpty(), "nothing chosen, nothing to warn about");
    }

    @Test
    void typedHeadersAreCheckedBeforeTheyAreWritten() {
        assertNotNull(SecurityHeaders.problem(new Choice("Bad Name", "v")));
        assertNotNull(SecurityHeaders.problem(new Choice("X-Ok", "")));
        assertNotNull(SecurityHeaders.problem(new Choice("X-Ok", "a\nb")));
        assertNull(SecurityHeaders.problem(new Choice("X-Ok", "a; b 'c'")));
    }
}
