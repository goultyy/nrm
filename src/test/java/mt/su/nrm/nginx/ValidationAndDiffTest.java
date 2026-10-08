package mt.su.nrm.nginx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mt.su.nrm.model.ConfigLayout;
import mt.su.nrm.nginx.VhostSettings.HeaderSpec;
import mt.su.nrm.nginx.VhostSettings.ListenSpec;
import mt.su.nrm.nginx.VhostSettings.RuleSpec;
import java.util.List;
import org.junit.jupiter.api.Test;

class ValidationAndDiffTest {

    private static VhostSettings good() {
        VhostSettings s = new VhostSettings();
        s.serverNames.add("example.com");
        s.listens.add(new ListenSpec("80"));
        s.root = "/var/www";
        return s;
    }

    private static boolean hasError(VhostSettings s, String fragment) {
        return VhostValidator.validate(s).stream().anyMatch(i -> i.severity() == VhostValidator.Severity.ERROR
                && i.message().contains(fragment));
    }

    @Test
    void aSensibleHostHasNoIssues() {
        assertTrue(VhostValidator.validate(good()).isEmpty());
    }

    @Test
    void serverNamesAndListenEntriesAreChecked() {
        VhostSettings s = good();
        s.serverNames.add("bad name;");
        assertTrue(hasError(s, "not a valid server name"));

        s = good();
        s.listens.add(new ListenSpec("70000"));
        assertTrue(hasError(s, "port must be between"));

        s = good();
        s.listens.add(new ListenSpec("80"));
        assertTrue(hasError(s, "more than once"));

        s = good();
        s.listens.add(new ListenSpec("unix:relative"));
        assertTrue(hasError(s, "absolute path"));
    }

    @Test
    void sslListenersNeedACertificateAndKey() {
        VhostSettings s = good();
        s.listens.set(0, new ListenSpec("443", "ssl"));
        assertTrue(hasError(s, "both a certificate and its key"));
        s.sslCertificate = "/etc/ssl/a.pem";
        s.sslCertificateKey = "/etc/ssl/a.key";
        assertTrue(VhostValidator.validate(s).isEmpty());
        s.sslProtocols = "TLSv1 SSLv9";
        assertTrue(hasError(s, "SSLv9"));
        assertTrue(VhostValidator.validate(s).stream().anyMatch(i -> i.severity() == VhostValidator.Severity.WARNING
                && i.message().contains("TLSv1 is obsolete")));
    }

    @Test
    void locationsAreChecked() {
        VhostSettings s = good();
        s.locations.add(LocationSettings.newProxy("/api", "ftp://x"));
        assertTrue(hasError(s, "must start with http://"));

        s = good();
        s.locations.add(LocationSettings.newProxy("api", "http://x"));
        assertTrue(hasError(s, "must start with /"));

        s = good();
        s.locations.add(LocationSettings.newProxy("/a", "http://x"));
        s.locations.add(LocationSettings.newProxy("/a", "http://y"));
        assertTrue(hasError(s, "more than once"));

        s = good();
        LocationSettings redirect = new LocationSettings();
        redirect.type = LocationSettings.Type.REDIRECT;
        redirect.redirectCode = "301";
        s.locations.add(redirect);
        assertTrue(hasError(s, "needs a target URL"));
    }

    @Test
    void headersRulesAndLimitsAreChecked() {
        VhostSettings s = good();
        s.headers.add(new HeaderSpec("Bad Name", "v", false));
        assertTrue(hasError(s, "not a valid header name"));

        s = good();
        s.rewrites.add(new RuleSpec("rewrite", "only-one"));
        assertTrue(hasError(s, "rewrite needs"));
        s.rewrites.clear();
        s.rewrites.add(new RuleSpec("rewrite", "^/a /b sideways"));
        assertTrue(hasError(s, "not a rewrite flag"));

        s = good();
        s.clientMaxBodySize = "lots";
        assertTrue(hasError(s, "upload size"));
        s.clientMaxBodySize = "10m";
        assertFalse(hasError(s, "upload size"));
    }

    @Test
    void controlCharactersAreRejectedEverywhere() {
        VhostSettings s = good();
        s.root = "/var/www\n}";
        assertTrue(hasError(s, "invalid characters"));
        s = good();
        s.headers.add(new HeaderSpec("X", "a\nb", false));
        assertTrue(hasError(s, "invalid characters"));
    }

    @Test
    void sameNameOnTheSamePortAsAnotherHostIsFlagged() {
        VhostSettings other = good();
        assertEquals(1, VhostValidator.checkConflicts(good(), List.of(other)).size());
        VhostSettings differentPort = good();
        differentPort.listens.set(0, new ListenSpec("8080"));
        assertTrue(VhostValidator.checkConflicts(good(), List.of(differentPort)).isEmpty());
    }

    // ------------------------------------------------------------------------------- layout

    @Test
    void layoutIsDetectedFromTheMainConfig() throws Exception {
        ConfigFile ubuntu = ConfigFile.parse("/etc/nginx/nginx.conf", NginxRoundTripTest.resource("ubuntu-nginx.conf"));
        assertEquals(ConfigLayout.SITES_AVAILABLE, LayoutDetector.detect(ubuntu));
        assertEquals(ConfigLayout.CONF_D, LayoutDetector.detect(List.of("/etc/nginx/conf.d/*.conf")));
        assertEquals(ConfigLayout.UNKNOWN, LayoutDetector.detect(List.of("/etc/nginx/mime.types")));
    }

    @Test
    void newFilesGoWhereTheLayoutExpectsThem() {
        assertEquals("/etc/nginx/sites-available/example.com",
                LayoutDetector.newFilePath("/etc/nginx", ConfigLayout.SITES_AVAILABLE, "example.com"));
        assertEquals("/etc/nginx/sites-enabled/example.com",
                LayoutDetector.enabledLinkPath("/etc/nginx", ConfigLayout.SITES_AVAILABLE, "example.com"));
        assertEquals("/etc/nginx/conf.d/example.com.conf",
                LayoutDetector.newFilePath("/etc/nginx/", ConfigLayout.CONF_D, "example.com"));
        assertEquals(null, LayoutDetector.enabledLinkPath("/etc/nginx", ConfigLayout.CONF_D, "example.com"));
        assertEquals("evil_name", LayoutDetector.safeFileName("../evil name"));
        assertEquals("site", LayoutDetector.safeFileName("..."));
    }

    // ------------------------------------------------------------------------------- diff

    @Test
    void equalTextsHaveNoDiff() {
        assertEquals("", UnifiedDiff.diff("a", "b", "x\ny\n", "x\ny\n"));
    }

    @Test
    void aChangedLineShowsAsRemovedAndAddedWithContext() {
        String diff = UnifiedDiff.diff("a/x", "b/x", "1\n2\n3\n4\n5\n6\n7\n", "1\n2\n3\nfour\n5\n6\n7\n");
        assertEquals("--- a/x\n+++ b/x\n@@ -1,7 +1,7 @@\n 1\n 2\n 3\n-4\n+four\n 5\n 6\n 7\n", diff);
    }

    @Test
    void distantChangesGetSeparateHunks() {
        StringBuilder a = new StringBuilder();
        StringBuilder b = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            a.append("line").append(i).append('\n');
            b.append(i == 3 ? "changed3" : i == 28 ? "changed28" : "line" + i).append('\n');
        }
        String diff = UnifiedDiff.diff("a", "b", a.toString(), b.toString());
        assertEquals(2, diff.lines().filter(l -> l.startsWith("@@")).count(), diff);
        assertTrue(diff.contains("-line3\n+changed3\n"));
        assertTrue(diff.contains("-line28\n+changed28\n"));
        assertFalse(diff.contains(" line15\n"));
    }

    @Test
    void aNewFileIsAllAdditions() {
        String diff = UnifiedDiff.diff("/dev/null", "b/new", "", "a\nb\n");
        assertEquals("--- /dev/null\n+++ b/new\n@@ -0,0 +1,2 @@\n+a\n+b\n", diff);
    }

    @Test
    void additionsAndDeletionsAtTheEnds() {
        String diff = UnifiedDiff.diff("a", "b", "keep\nold\n", "keep\n");
        assertEquals("--- a\n+++ b\n@@ -1,2 +1,1 @@\n keep\n-old\n", diff);
    }
}
