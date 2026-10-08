package mt.su.nrm.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mt.su.nrm.nginx.ConfigFile;
import mt.su.nrm.nginx.VhostSettings;
import mt.su.nrm.nginx.VirtualHost;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class VhostFormTest {

    private static ConfigFile load(String resource) throws Exception {
        return ConfigFile.parse("/etc/nginx/" + resource, mt.su.nrm.nginx.NginxFixtures.read(resource));
    }

    @Test
    void anUneditedFormChangesNothingInAnyCorpusFile() throws Exception {
        for (String name : List.of("ubuntu-default-site", "complex.conf", "confd-app.conf")) {
            ConfigFile file = load(name);
            for (var server : file.serverBlocks()) {
                VirtualHost host = new VirtualHost(file, server);
                VhostSettings before = host.read();
                host.apply(VhostForm.from(before).applyTo(before));
            }
            assertEquals(file.originalText(), file.generate(), name);
        }
    }

    @Test
    void editedTextBecomesSettings() {
        VhostForm f = new VhostForm();
        f.serverNames = "a.example.com \"b c.example.com\"";
        f.listens = "443 ssl http2\n[::]:443 ssl http2\n\n80";
        f.headers = "X-Frame-Options DENY always\nCache-Control \"no-store, max-age=0\"";
        f.rewrites = "rewrite ^/old/(.*)$ /new/$1 permanent\nreturn 301 https://x.example.com";
        f.limitReq = "zone=perip burst=10 nodelay";
        f.accessLogs = "/var/log/a.log main\noff";
        f.root = "  /var/www  ";

        VhostSettings s = f.applyTo(new VhostSettings());

        assertEquals(List.of("a.example.com", "b c.example.com"), s.serverNames);
        assertEquals(3, s.listens.size());
        assertEquals("443", s.listens.get(0).endpoint);
        assertEquals(List.of("ssl", "http2"), s.listens.get(0).params);
        assertEquals("[::]:443", s.listens.get(1).endpoint);
        assertEquals(2, s.headers.size());
        assertEquals("X-Frame-Options", s.headers.get(0).name);
        assertTrue(s.headers.get(0).always);
        assertEquals("no-store, max-age=0", s.headers.get(1).value);
        assertFalse(s.headers.get(1).always);
        assertEquals("rewrite", s.rewrites.get(0).directive);
        assertEquals("^/old/(.*)$ /new/$1 permanent", s.rewrites.get(0).arguments);
        assertEquals("return", s.rewrites.get(1).directive);
        assertEquals(List.of("zone=perip burst=10 nodelay"), s.limitReq);
        assertEquals(List.of("/var/log/a.log main", "off"), s.accessLogs);
        assertEquals("/var/www", s.root);
    }

    @Test
    void valuesWithSpacesAreShownQuotedSoTheyReadBackTheSame() {
        VhostSettings s = new VhostSettings();
        s.serverNames.add("with space.example.com");
        s.headers.add(new VhostSettings.HeaderSpec("Content-Security-Policy", "default-src 'self'; img-src *", true));
        VhostForm f = VhostForm.from(s);
        assertTrue(f.serverNames.startsWith("\""), f.serverNames);

        VhostSettings back = f.applyTo(new VhostSettings());
        assertEquals(s.serverNames, back.serverNames);
        assertEquals("default-src 'self'; img-src *", back.headers.get(0).value);
        assertTrue(back.headers.get(0).always);
    }

    @Test
    void aHeaderLineWithoutAValueIsKeptSoTheValidatorCanFlagIt() {
        VhostForm f = new VhostForm();
        f.headers = "X-Only-Name";
        VhostSettings s = f.applyTo(new VhostSettings());
        assertEquals("X-Only-Name", s.headers.get(0).name);
        assertEquals("", s.headers.get(0).value);
    }

    @Test
    void locationsAreCarriedOverFromTheBaseSettings() throws Exception {
        ConfigFile file = load("confd-app.conf");
        VhostSettings base = new VirtualHost(file, file.serverBlocks().get(0)).read();
        VhostSettings s = new VhostForm().applyTo(base);
        assertEquals(base.locations.size(), s.locations.size());
    }
}
