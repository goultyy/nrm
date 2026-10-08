package mt.su.nrm.nginx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class FastcgiTest {

    private static ConfigFile load() throws Exception {
        return ConfigFile.parse("/etc/nginx/sites-available/blog", NginxRoundTripTest.resource("php-site.conf"));
    }

    private static boolean hasIssue(VhostSettings s, String fragment) {
        return VhostValidator.validate(s).stream().anyMatch(i -> i.message().contains(fragment));
    }

    @Test
    void aPhpLocationIsReadAndUnchangedApplyChangesNothing() throws Exception {
        ConfigFile file = load();
        VirtualHost host = new VirtualHost(file, file.serverBlocks().get(0));
        VhostSettings s = host.read();

        LocationSettings php = s.locations.get(1);
        assertEquals(LocationSettings.Type.FASTCGI, php.type);
        assertEquals("unix:/run/php/php8.2-fpm.sock", php.fastcgiPass);
        assertEquals("snippets/fastcgi-php.conf", php.fastcgiInclude);
        assertEquals("120s", php.fastcgiReadTimeout);
        // An include that is not a fastcgi file belongs to the user and is not managed.
        assertEquals("", s.locations.get(2).fastcgiInclude);

        host.apply(s);
        assertEquals(file.originalText(), file.generate());
    }

    @Test
    void newPhpLocationWithParamsInsteadOfASnippet() throws Exception {
        ConfigFile file = load();
        VirtualHost host = new VirtualHost(file, file.serverBlocks().get(0));
        VhostSettings s = host.read();
        s.locations.remove(1);
        s.locations.add(LocationSettings.newPhp("127.0.0.1:9000", ""));
        host.apply(s);

        String text = file.generate();
        assertTrue(text.contains("include fastcgi_params;"), text);
        assertTrue(text.contains("fastcgi_param SCRIPT_FILENAME $document_root$fastcgi_script_name;"), text);
        assertTrue(text.contains("fastcgi_pass 127.0.0.1:9000;"), text);
        assertFalse(text.contains("fastcgi-php.conf"));
        assertTrue(text.contains("include snippets/cache-headers.conf;"), "other includes stay");
        ConfigFile.parse("x", text);
    }

    @Test
    void changingTheTypeAwayFromPhpRemovesOnlyItsOwnDirectives() throws Exception {
        ConfigFile file = load();
        VirtualHost host = new VirtualHost(file, file.serverBlocks().get(0));
        VhostSettings s = host.read();
        s.locations.remove(1); // the real PHP location, so only the switched one could still hold fastcgi_pass
        LocationSettings loc = s.locations.get(1);
        loc.type = LocationSettings.Type.FASTCGI;
        loc.fastcgiPass = "127.0.0.1:9000";
        host.apply(s);
        loc.type = LocationSettings.Type.STATIC;
        host.apply(s);

        String text = file.generate();
        assertFalse(text.contains("fastcgi_pass"), text);
        assertTrue(text.contains("include snippets/cache-headers.conf;"), text);
    }

    @Test
    void validatorChecksTheAddressAndWarnsAboutThePhpPitfalls() throws Exception {
        ConfigFile file = load();
        VhostSettings s = new VirtualHost(file, file.serverBlocks().get(0)).read();
        LocationSettings php = s.locations.get(1);

        assertFalse(VhostValidator.validate(s).stream().anyMatch(i -> i.severity() == VhostValidator.Severity.ERROR));

        php.fastcgiPass = "";
        assertTrue(hasIssue(s, "needs a FastCGI address"));
        php.fastcgiPass = "not an address!";
        assertTrue(hasIssue(s, "must be unix:/path/to.sock"));
        php.fastcgiPass = "unix:/run/php/x.sock";

        php.fastcgiInclude = "fastcgi_params";
        assertTrue(hasIssue(s, "SCRIPT_FILENAME"), "no SCRIPT_FILENAME");
        assertTrue(hasIssue(s, "try_files"), "no try_files guard");
        php.fastcgiParams = List.of("SCRIPT_FILENAME $document_root$fastcgi_script_name");
        php.tryFiles = "$uri =404";
        assertFalse(hasIssue(s, "SCRIPT_FILENAME"));
        assertFalse(hasIssue(s, "try_files"));

        php.fastcgiParams = List.of("bad-name x");
        assertTrue(hasIssue(s, "must be a NAME followed by a value"));
        php.fastcgiParams = List.of();
        php.fastcgiInclude = "../etc/passwd";
        assertTrue(hasIssue(s, "without \"..\""));
        php.fastcgiInclude = "snippets/fastcgi-php.conf";
        php.fastcgiReadTimeout = "soon";
        assertTrue(hasIssue(s, "read timeout"));
    }

    @Test
    void siteSummaryShowsPhp() {
        VhostSettings s = new VhostSettings();
        s.locations.add(LocationSettings.newPhp("unix:/run/php/x.sock", "snippets/fastcgi-php.conf"));
        assertEquals("PHP via unix:/run/php/x.sock", SiteSummary.of(s).content());
    }
}
