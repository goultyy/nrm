package mt.su.nrm.nginx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SiteSummaryTest {

    private static SiteSummary summary(String resource, int server) throws Exception {
        ConfigFile file = ConfigFile.parse("x", NginxFixtures.read(resource));
        return SiteSummary.of(new VirtualHost(file, file.serverBlocks().get(server)).read());
    }

    @Test
    void aProxySiteShowsItsNameBindingsAndTarget() throws Exception {
        SiteSummary s = summary("confd-app.conf", 0);
        assertEquals("app.example.com", s.name());
        assertEquals("http :80", s.bindings());
        assertEquals("/usr/share/nginx/html", s.content(), "the document root wins");
        assertEquals("", s.ssl());
        assertEquals(4, s.locations());
    }

    @Test
    void anSslSiteShowsHttpsBindingsAndCertificate() throws Exception {
        SiteSummary s = summary("complex.conf", 0);
        assertEquals("example.com", s.name());
        assertEquals("example.com www.example.com *.example.org", s.allNames());
        assertEquals("https :443, https :443", s.bindings(), "the IPv6 listener is shown too");
        assertEquals("/etc/ssl/certs/example.pem", s.ssl());
        assertEquals("proxy to http://app_backend", s.content());
    }

    @Test
    void aRedirectOnlySiteAndTheDefaultSite() throws Exception {
        SiteSummary redirect = summary("complex.conf", 1);
        assertEquals("http :80", redirect.bindings());
        assertEquals("returns 301 https://$host$request_uri", redirect.content());

        SiteSummary def = summary("ubuntu-default-site", 0);
        assertEquals("default", def.name(), "the catch-all name _ is shown as default");
        assertEquals("http :80 (default), http :80 (default)", def.bindings());
        assertEquals("/var/www/html", def.content());
    }

    @Test
    void addressesAndSocketsAreShownAsWritten() {
        VhostSettings s = new VhostSettings();
        s.listens.add(new VhostSettings.ListenSpec("127.0.0.1:8080"));
        s.listens.add(new VhostSettings.ListenSpec("[2001:db8::1]:443", "ssl", "default_server"));
        s.listens.add(new VhostSettings.ListenSpec("unix:/run/n.sock"));
        s.listens.add(new VhostSettings.ListenSpec("*:81"));
        assertEquals("http 127.0.0.1:8080, https [2001:db8::1]:443 (default), unix:/run/n.sock, http :81", SiteSummary.of(s).bindings());
        assertEquals("http :80", SiteSummary.of(new VhostSettings()).bindings(), "no listen means port 80");
        assertEquals("(no server_name)", SiteSummary.of(new VhostSettings()).name());
    }

    @Test
    void redirectAndStaticLocationsDescribeTheContent() {
        VhostSettings s = new VhostSettings();
        s.locations.add(LocationSettings.newRedirect("/", "https://new.example.com"));
        assertEquals("redirect to https://new.example.com", SiteSummary.of(s).content());
        VhostSettings t = new VhostSettings();
        LocationSettings l = LocationSettings.newStatic("/");
        l.alias = "/srv/files/";
        t.locations.add(l);
        assertEquals("/srv/files/", SiteSummary.of(t).content());
    }
}
