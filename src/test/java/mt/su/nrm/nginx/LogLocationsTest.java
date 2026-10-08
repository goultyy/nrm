package mt.su.nrm.nginx;

import mt.su.nrm.model.ConfigLayout;
import mt.su.nrm.ssh.LogService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LogLocationsTest {

    private static RemoteConfig config(String main, String site) throws Exception {
        RemoteConfig c = new RemoteConfig("/etc/nginx", ConfigLayout.values()[0], ConfigFile.parse("/etc/nginx/nginx.conf", main));
        c.addFile(ConfigFile.parse("/etc/nginx/conf.d/a.conf", site), List.of(), null);
        return c;
    }

    @Test
    void findsGeneralAndSiteLogs() throws Exception {
        RemoteConfig c = config("error_log /var/log/nginx/main-error.log warn;\nhttp {\n access_log /var/log/nginx/all.log combined;\n}\n",
                "server {\n server_name a.example.com;\n access_log /var/log/nginx/a.access.log main;\n error_log /var/log/nginx/a.error.log;\n}\n"
                        + "server {\n server_name b.example.com;\n access_log off;\n}\n");
        List<LogLocations.LogSource> logs = LogLocations.discover(c);
        assertEquals(List.of("/var/log/nginx/main-error.log", "/var/log/nginx/all.log", "/var/log/nginx/a.access.log",
                "/var/log/nginx/a.error.log"), logs.stream().map(LogLocations.LogSource::path)
                .sorted(java.util.Comparator.comparing(p -> logs.stream().filter(l -> l.path().equals(p)).findFirst().map(logs::indexOf).get()))
                .toList());
        assertEquals("a.example.com", logs.get(2).group());
        assertTrue(logs.get(3).error());
    }

    @Test
    void fallsBackToDefaultsAndSkipsNonFiles() throws Exception {
        assertEquals(List.of(LogLocations.DEFAULT_ACCESS, LogLocations.DEFAULT_ERROR),
                LogLocations.discover(null).stream().map(LogLocations.LogSource::path).toList());
        RemoteConfig c = config("http {\n}\n", "server {\n server_name x;\n access_log syslog:server=1.2.3.4;\n error_log stderr;\n}\n");
        assertEquals(2, LogLocations.discover(c).size());
    }

    @Test
    void serviceOnlyReadsKnownOrVarLogFiles() {
        Set<String> known = Set.of("/srv/app/logs/x.log");
        assertTrue(LogService.problems("/var/log/nginx/access.log", known, 100, "").isEmpty());
        assertTrue(LogService.problems("/srv/app/logs/x.log", known, 100, "GET").isEmpty());
        for (String bad : List.of("/etc/shadow", "/var/log/../etc/shadow", "relative.log", "/var/log/a b", "/var/log/x;rm")) {
            assertFalse(LogService.problems(bad, known, 100, "").isEmpty(), bad);
        }
        assertFalse(LogService.problems("/var/log/nginx/a.log", known, 0, "").isEmpty());
        assertFalse(LogService.problems("/var/log/nginx/a.log", known, 100, "x\ny").isEmpty());
    }
}
