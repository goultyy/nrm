package mt.su.nrm.nginx;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheStatsTest {

    private static RemoteConfig config(String main, String site) throws Exception {
        RemoteConfig c = new RemoteConfig("/etc/nginx", mt.su.nrm.model.ConfigLayout.SITES_AVAILABLE,
                ConfigFile.parse("/etc/nginx/nginx.conf", main));
        c.addFile(ConfigFile.parse("/etc/nginx/conf.d/a.conf", site), List.of(), null);
        return c;
    }

    private static final String MAIN = "http {\n    access_log /var/log/nginx/access.log;\n    include /etc/nginx/conf.d/*.conf;\n}\n";
    private static final String SITE = "server {\n    server_name a.test;\n    access_log /var/log/nginx/a.log;\n"
            + "    location / {\n        proxy_cache zone1;\n    }\n}\n"
            + "server {\n    server_name b.test;\n    access_log off;\n}\n"
            + "server {\n    server_name c.test;\n}\n";

    @Test
    void enableAddsTheFormatMapAndLinesAndSurvivesAReparse() throws Exception {
        RemoteConfig c = config(MAIN, SITE);
        assertFalse(CacheStatsLogging.isEnabled(c));
        CacheStatsLogging.Result r = CacheStatsLogging.enable(c);
        assertTrue(r.ok(), r.problem());
        assertEquals(1, r.covered());
        assertEquals(1, r.loggingOff());
        assertTrue(CacheStatsLogging.isEnabled(c));
        assertEquals(0, CacheStatsLogging.uncovered(c));

        String main = c.mainFile().generate();
        assertTrue(main.indexOf("log_format nrm_cache") < main.indexOf("access_log /var/log/nginx/nrm-cache.log"), main);
        assertTrue(main.contains("map $upstream_cache_status $nrm_cache_logged {"), main);
        assertTrue(main.contains("        default 0;"), main);
        // What was written must parse again, with the same meaning.
        ConfigFile again = ConfigFile.parse("/etc/nginx/nginx.conf", main);
        assertEquals(main, again.generate());
        assertTrue(c.files().get(1).generate().contains("access_log /var/log/nginx/nrm-cache.log nrm_cache if=$nrm_cache_logged;"));

        // Running it again changes nothing.
        String before = main;
        assertTrue(CacheStatsLogging.enable(c).ok());
        assertEquals(before, c.mainFile().generate());
    }

    @Test
    void disableRestoresTheOriginalText() throws Exception {
        RemoteConfig c = config(MAIN, SITE);
        CacheStatsLogging.enable(c);
        assertTrue(CacheStatsLogging.disable(c) >= 4);
        assertEquals(MAIN, c.mainFile().generate());
        assertEquals(SITE, c.files().get(1).generate());
        assertFalse(CacheStatsLogging.isEnabled(c));
    }

    @Test
    void refusesWhenHttpHasNoAccessLogOfItsOwn() throws Exception {
        RemoteConfig c = config("http {\n    include /etc/nginx/conf.d/*.conf;\n}\n", "server {\n}\n");
        CacheStatsLogging.Result r = CacheStatsLogging.enable(c);
        assertFalse(r.ok());
        assertEquals("http {\n    include /etc/nginx/conf.d/*.conf;\n}\n", c.mainFile().generate());
    }

    @Test
    void countsStatusesPerHost() {
        String log = "2026-10-07T10:00:00+00:00 a.test HIT 200 0.001 \"/x\"\n"
                + "2026-10-07T10:00:01+00:00 a.test MISS 200 0.200 \"/y z\"\n"
                + "2026-10-07T10:00:02+00:00 A.test BYPASS 200 0.100 \"/\"\n"
                + "2026-10-07T10:00:03+00:00 b.test - 200 0.100 \"/\"\n"
                + "garbage\n"
                + "2026-10-07T10:00:04+00:00 b.test WEIRD 200 0.100 \"/\"\n";
        CacheStats s = CacheStats.parse(log);
        assertEquals(1, s.skipped());
        assertEquals(4, s.totals().total());
        CacheStats.HostStats a = s.hosts().iterator().next();
        assertEquals("a.test", a.host());
        assertEquals(3, a.total());
        assertEquals(1, a.count("HIT"));
        assertEquals(1.0 / 3, a.hitRatio(), 1e-9);
        assertEquals(1, s.totals().other());
        assertEquals("2026-10-07T10:00:00+00:00", s.firstTime());
    }
}
