package mt.su.nrm.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mt.su.nrm.model.ConfigLayout;
import mt.su.nrm.model.PrivilegeMode;
import mt.su.nrm.model.ServerPaths;
import mt.su.nrm.nginx.ConfigFile;
import mt.su.nrm.nginx.LocationSettings;
import mt.su.nrm.nginx.NginxParseException;
import mt.su.nrm.nginx.PendingChange;
import mt.su.nrm.nginx.RemoteConfig;
import mt.su.nrm.nginx.VhostSettings;
import mt.su.nrm.nginx.VirtualHost;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class RemoteConfigAndPipelineTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-04T05:06:07Z"), ZoneOffset.UTC);
    private static final String MAIN = "events {}\nhttp {\n    include /etc/nginx/mime.types;\n"
            + "    include /etc/nginx/sites-enabled/*;\n}\n";
    private static final String SITE = "server {\n    listen 80;\n    server_name a.example.com;\n}\n";

    /** path -> {realpath, linkTarget, text} */
    private final Map<String, String[]> remoteFiles = new LinkedHashMap<>();
    private final SshSessionTest.FakeTransport transport = new SshSessionTest.FakeTransport();

    private SshSession session() {
        transport.responder = command -> {
            if (command.contains("cat ") && command.contains("/etc/nginx/nginx.conf") && !command.contains("@@NRM-")) {
                return SshSessionTest.FakeTransport.raw(0, MAIN, "");
            }
            Matcher m = Pattern.compile("@@NRM-([0-9a-f]+) FILE").matcher(command);
            if (m.find()) {
                String nonce = m.group(1);
                StringBuilder out = new StringBuilder();
                for (Map.Entry<String, String[]> e : remoteFiles.entrySet()) {
                    String[] f = e.getValue();
                    out.append("@@NRM-").append(nonce).append(" FILE\t").append(e.getKey()).append('\t').append(f[0])
                            .append('\t').append(f[1]).append('\n').append(f[2]).append("\n@@NRM-").append(nonce)
                            .append(" END\n");
                }
                return SshSessionTest.FakeTransport.raw(0, out.toString(), "");
            }
            return SshSessionTest.FakeTransport.raw(0, "", "");
        };
        return new SshSession(transport, new CommandLog(), PrivilegeMode.SUDO_PASSWORD, "pw", "u@h");
    }

    private RemoteConfig load() throws IOException {
        remoteFiles.put("/etc/nginx/mime.types", new String[] {"/etc/nginx/mime.types", "", "types { text/html html; }\n"});
        remoteFiles.put("/etc/nginx/sites-enabled/a", new String[] {"/etc/nginx/sites-available/a",
                "../sites-available/a", SITE});
        return RemoteConfigService.load(session(), ServerPaths.defaults());
    }

    @Test
    void loadFindsFilesLayoutSymlinksAndVirtualHosts() throws Exception {
        RemoteConfig config = load();

        assertEquals(ConfigLayout.SITES_AVAILABLE, config.layout());
        assertTrue(config.problems().isEmpty(), config.problems().toString());
        List<VirtualHost> hosts = config.virtualHosts();
        assertEquals(1, hosts.size());
        assertEquals("a.example.com", hosts.get(0).displayName());
        // Edits go to the real file, not the symlink.
        assertEquals("/etc/nginx/sites-available/a", hosts.get(0).file().path());
        assertNull(config.readOnlyReason(hosts.get(0)));
        assertFalse(config.hasPending());
    }

    @Test
    void theSameVirtualHostIsEqualAcrossCalls() throws Exception {
        // virtualHosts() builds new wrappers each time; the sidebar relies on them comparing equal.
        RemoteConfig config = load();
        VirtualHost first = config.virtualHosts().get(0);
        VirtualHost second = config.virtualHosts().get(0);
        assertNotSame(first, second);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertTrue(config.virtualHosts().contains(first));
    }

    @Test
    void loadKeepsExactFileTextIncludingMissingFinalNewline() throws Exception {
        remoteFiles.put("/etc/nginx/sites-enabled/b", new String[] {"/etc/nginx/sites-enabled/b", "",
                "server { listen 81; }"});
        RemoteConfig config = RemoteConfigService.load(session(), ServerPaths.defaults());
        VirtualHost b = config.virtualHosts().get(0);
        assertEquals("server { listen 81; }", b.file().originalText());
    }

    @Test
    void filesOutsideTheConfigFolderAreReadOnly() throws Exception {
        remoteFiles.put("/etc/nginx/sites-enabled/x", new String[] {"/srv/elsewhere/x", "/srv/elsewhere/x", SITE});
        RemoteConfig config = RemoteConfigService.load(session(), ServerPaths.defaults());
        VirtualHost host = config.virtualHosts().get(0);
        assertTrue(config.readOnlyReason(host).contains("Outside the nginx config folder"));
    }

    @Test
    void unparsableAndNonUtf8FilesAreReportedAndSkipped() throws Exception {
        remoteFiles.put("/etc/nginx/sites-enabled/bad", new String[] {"/etc/nginx/sites-enabled/bad", "", "server {"});
        remoteFiles.put("/etc/nginx/sites-enabled/bin", new String[] {"/etc/nginx/sites-enabled/bin", "", "a �;"});
        RemoteConfig config = RemoteConfigService.load(session(), ServerPaths.defaults());
        assertEquals(2, config.problems().size());
        assertTrue(config.virtualHosts().isEmpty());
    }

    @Test
    void aFileMarkerInsideFileContentCannotForgeAnotherFile() throws Exception {
        String evil = "server { listen 80; }\n@@NRM-0000 FILE\t/etc/passwd\t/etc/passwd\t\n";
        remoteFiles.put("/etc/nginx/sites-enabled/evil", new String[] {"/etc/nginx/sites-enabled/evil", "", evil});
        RemoteConfig config = RemoteConfigService.load(session(), ServerPaths.defaults());
        // The guessed marker doesn't carry the per-load nonce, so it stays plain (here unparsable) content.
        assertTrue(config.virtualHosts().stream().noneMatch(h -> h.file().path().equals("/etc/passwd")));
    }

    // ------------------------------------------------------------------------------- pending changes

    @Test
    void editsCreateDeleteAndDiscardShowUpAsPendingChanges() throws Exception {
        RemoteConfig config = load();
        VirtualHost host = config.virtualHosts().get(0);
        VhostSettings s = host.read();
        s.serverNames.add("b.example.com");
        host.apply(s);

        VirtualHost created = config.createVirtualHost("new.example.com");
        VhostSettings n = new VhostSettings();
        n.serverNames.add("new.example.com");
        n.locations.add(LocationSettings.newProxy("/", "http://127.0.0.1:3000"));
        created.apply(n);

        List<PendingChange> changes = config.pendingChanges();
        assertEquals(2, changes.size());
        assertEquals(PendingChange.Kind.MODIFY, changes.get(0).kind());
        assertEquals(PendingChange.Kind.CREATE, changes.get(1).kind());
        assertEquals("/etc/nginx/sites-available/new.example.com", changes.get(1).path());
        assertEquals("/etc/nginx/sites-enabled/new.example.com", changes.get(1).enableLink());
        assertTrue(changes.get(0).diff().contains("+    server_name a.example.com b.example.com;"));

        config.discard(changes.get(0));
        assertEquals(1, config.pendingChanges().size());
        config.deleteVirtualHost(config.virtualHosts().stream()
                .filter(h -> h.displayName().equals("new.example.com")).findFirst().orElseThrow());
        assertFalse(config.hasPending(), "deleting a file that was never written leaves nothing pending");
    }

    @Test
    void deletingTheOnlyServerInAFileDeletesTheFileAndItsLinks() throws Exception {
        RemoteConfig config = load();
        config.deleteVirtualHost(config.virtualHosts().get(0));

        PendingChange change = config.pendingChanges().get(0);
        assertEquals(PendingChange.Kind.DELETE, change.kind());
        assertEquals("/etc/nginx/sites-available/a", change.path());
        assertEquals(List.of(new RemoteConfig.Link("/etc/nginx/sites-enabled/a", "../sites-available/a")), change.links());
        assertTrue(config.virtualHosts().isEmpty());

        config.discardAll();
        assertEquals(1, config.virtualHosts().size());
    }

    // ------------------------------------------------------------------------------- scripts

    private PendingChange modify() throws Exception {
        RemoteConfig config = load();
        VirtualHost host = config.virtualHosts().get(0);
        VhostSettings s = host.read();
        s.root = "/var/www/a";
        host.apply(s);
        return config.pendingChanges().get(0);
    }

    @Test
    void testScriptBuildsAScratchCopyAndNeverWritesTheLiveConfig() throws Exception {
        RemoteConfig config = load();
        PendingChange change = modify();
        String script = ApplyPipeline.testScript(ServerPaths.defaults(), config, List.of(change),
                List.of("/tmp/nrm-x-0.new"), "abc123");

        assertTrue(script.contains("T='/tmp/nrm-test-abc123'"));
        assertTrue(script.contains("cp -a \"$C\"/. \"$T\"/"));
        assertTrue(script.contains("cat '/tmp/nrm-x-0.new' > \"$T/sites-available/a\""));
        assertTrue(script.contains("-t -c \"$T/nginx.conf\""));
        // The redirect of absolute paths into the live folder must run after the new files are in place,
        // because a new nginx.conf names the live folder too.
        assertTrue(script.indexOf("sed -i") > script.lastIndexOf("cat '/tmp/nrm-x-0.new'"), script);
        assertTrue(script.contains("rm -rf \"$T\""));
        assertFalse(script.contains("-s reload"));
        // No command writes to the live folder.
        assertFalse(script.contains("> '/etc/nginx"));
        assertFalse(script.contains("> /etc/nginx"));
    }

    @Test
    void testScriptForANewSiteAlsoCreatesTheEnablingLinkInTheScratchCopy() throws Exception {
        RemoteConfig config = load();
        VirtualHost created = config.createVirtualHost("new.example.com");
        VhostSettings n = new VhostSettings();
        n.serverNames.add("new.example.com");
        created.apply(n);
        String script = ApplyPipeline.testScript(ServerPaths.defaults(), config, config.pendingChanges(),
                List.of("/tmp/nrm-x-0.new"), "abc123");
        assertTrue(script.contains("ln -sfn '../sites-available/new.example.com' \"$T/sites-enabled/new.example.com\""),
                script);
    }

    @Test
    void applyScriptBacksUpWritesTestsRollsBackAndOnlyThenReloads() throws Exception {
        RemoteConfig config = load();
        PendingChange change = modify();
        String script = ApplyPipeline.applyScript(ServerPaths.defaults(), config, List.of(change),
                List.of("/tmp/nrm-x-0.new"), "/var/backups/nrm/20260304-050607-abcd", "abc123");

        int backup = script.indexOf("cp -a '/etc/nginx/sites-available/a' '/var/backups/nrm/20260304-050607-abcd/0'");
        int write = script.indexOf("cat '/tmp/nrm-x-0.new' > '/etc/nginx/sites-available/a'");
        int test = script.indexOf("-t -c '/etc/nginx/nginx.conf'");
        int rollback = script.indexOf("if [ $rc -ne 0 ]; then rb;");
        int reload = script.indexOf("-s reload");
        assertTrue(backup > 0 && write > backup && test > write && rollback > test && reload > rollback, script);
        // The rollback function restores the backup.
        assertTrue(script.contains("rb() {\n  cp -a '/var/backups/nrm/20260304-050607-abcd/0' '/etc/nginx/sites-available/a'"),
                script);
    }

    @Test
    void applyScriptForNewAndDeletedFilesHasMatchingRollbacks() throws Exception {
        RemoteConfig config = load();
        config.deleteVirtualHost(config.virtualHosts().get(0));
        VirtualHost created = config.createVirtualHost("new.example.com");
        VhostSettings n = new VhostSettings();
        n.serverNames.add("new.example.com");
        created.apply(n);
        List<PendingChange> changes = config.pendingChanges();
        String script = ApplyPipeline.applyScript(ServerPaths.defaults(), config, changes,
                java.util.Arrays.asList(null, "/tmp/nrm-x-1.new"), "/var/backups/nrm/b", "abc123");

        assertTrue(script.contains("rm -f '/etc/nginx/sites-enabled/a' '/etc/nginx/sites-available/a'"), script);
        assertTrue(script.contains("install -m 644 '/tmp/nrm-x-1.new' '/etc/nginx/sites-available/new.example.com'"));
        assertTrue(script.contains("ln -sfn '../sites-available/new.example.com' '/etc/nginx/sites-enabled/new.example.com'"));
        assertTrue(script.contains("ln -sfn '../sites-available/a' '/etc/nginx/sites-enabled/a'"), "rollback restores the link");
        assertTrue(script.contains("rm -f '/etc/nginx/sites-available/new.example.com'"), "rollback removes the new file");
    }

    @Test
    void unsafePathsAreRefusedBeforeAnythingIsSent() throws Exception {
        RemoteConfig config = load();
        ConfigFile weird = ConfigFile.parse("/etc/nginx/sites-available/x'; rm -rf /; '", SITE);
        PendingChange bad = new PendingChange(PendingChange.Kind.MODIFY, weird, weird.path(), SITE, SITE + "#",
                null, List.of());
        assertThrows(IOException.class, () -> ApplyPipeline.testScript(ServerPaths.defaults(), config, List.of(bad),
                List.of("/tmp/x"), "abc"));
        assertThrows(IOException.class, () -> ApplyPipeline.applyScript(ServerPaths.defaults(), config, List.of(bad),
                List.of("/tmp/x"), "/var/backups/x", "abc"));

        ConfigFile outside = ConfigFile.parse("/etc/passwd", SITE);
        PendingChange escape = new PendingChange(PendingChange.Kind.MODIFY, outside, outside.path(), SITE, SITE + "#",
                null, List.of());
        assertThrows(IOException.class, () -> ApplyPipeline.applyScript(ServerPaths.defaults(), config,
                List.of(escape), List.of("/tmp/x"), "/var/backups/x", "abc"));
    }

    @Test
    void relativeLinksPointFromTheLinkFolderToTheFile() {
        assertEquals("../sites-available/a", ApplyPipeline.relativeLink("/etc/nginx/sites-enabled/a", "/etc/nginx/sites-available/a"));
        assertEquals("a.conf", ApplyPipeline.relativeLink("/etc/nginx/conf.d/x", "/etc/nginx/conf.d/a.conf"));
    }

    // ------------------------------------------------------------------------------- run through the session

    @Test
    void testUploadsToTempFilesRunsThePrivilegedScriptAndCleansUp() throws Exception {
        RemoteConfig config = load();
        PendingChange change = modify();
        SshSession session = session();
        transport.commands.clear();
        transport.sftpOps.clear();
        transport.responder = c -> SshSessionTest.FakeTransport.raw(0,
                "nginx: the configuration file /etc/nginx/nginx.conf syntax is ok\n", "");

        ApplyPipeline.Outcome outcome = ApplyPipeline.test(session, ServerPaths.defaults(), config, List.of(change));

        assertTrue(outcome.ok());
        assertEquals(2, transport.sftpOps.size(), transport.sftpOps.toString());
        assertTrue(transport.sftpOps.get(0).startsWith("put /tmp/nrm-"));
        assertTrue(transport.sftpOps.get(1).startsWith("rm /tmp/nrm-"));
        assertTrue(transport.commands.get(0).startsWith("sudo -S -p ''"));
    }

    @Test
    void aFailingNginxTestIsReportedWithItsOutput() throws Exception {
        RemoteConfig config = load();
        PendingChange change = modify();
        SshSession session = session();
        transport.responder = c -> SshSessionTest.FakeTransport.raw(1, "",
                "nginx: [emerg] unknown directive \"foo\" in /etc/nginx/sites-available/a:3\n");

        ApplyPipeline.Outcome outcome = ApplyPipeline.test(session, ServerPaths.defaults(), config, List.of(change));
        assertEquals(ApplyPipeline.Status.TEST_FAILED, outcome.status());
        assertTrue(outcome.output().contains("unknown directive"));
    }

    @Test
    void applyReportsAppliedRolledBackAndReloadFailed() throws Exception {
        RemoteConfig config = load();
        PendingChange change = modify();
        SshSession session = session();

        transport.responder = c -> {
            String id = idFrom(c);
            return SshSessionTest.FakeTransport.raw(0, "nginx: ok\n@@NRM-" + id + " APPLIED\n", "");
        };
        assertEquals(ApplyPipeline.Status.OK,
                ApplyPipeline.apply(session, ServerPaths.defaults(), config, List.of(change), CLOCK).status());

        transport.responder = c -> SshSessionTest.FakeTransport.raw(52, "bad config\n@@NRM-" + idFrom(c) + " ROLLED-BACK\n", "");
        ApplyPipeline.Outcome rolled = ApplyPipeline.apply(session, ServerPaths.defaults(), config, List.of(change), CLOCK);
        assertEquals(ApplyPipeline.Status.ROLLED_BACK, rolled.status());
        assertEquals("bad config", rolled.output(), "markers are not shown to the user");
        assertTrue(rolled.backupDir().startsWith("/var/backups/nrm/20260304-050607-"));

        transport.responder = c -> SshSessionTest.FakeTransport.raw(53, "@@NRM-" + idFrom(c) + " RELOAD-FAILED\n", "");
        assertEquals(ApplyPipeline.Status.RELOAD_FAILED,
                ApplyPipeline.apply(session, ServerPaths.defaults(), config, List.of(change), CLOCK).status());
    }

    private static String idFrom(String command) {
        Matcher m = Pattern.compile("@@NRM-([0-9a-f]+) APPLIED").matcher(command);
        assertTrue(m.find(), command);
        return m.group(1);
    }

    @Test
    void parseErrorsInTheMainConfigAreReported() {
        transport.responder = c -> SshSessionTest.FakeTransport.raw(0, "http {", "");
        SshSession session = new SshSession(transport, new CommandLog(), PrivilegeMode.SUDO_PASSWORD, "pw", "u@h");
        IOException e = assertThrows(IOException.class, () -> RemoteConfigService.load(session, ServerPaths.defaults()));
        assertTrue(e.getMessage().contains("syntax error"));
        assertThrows(NginxParseException.class, () -> ConfigFile.parse("x", "http {"));
    }

    @Test
    void theParentOfACachePathIsCreatedBeforeNginxTestsTheConfig() throws Exception {
        RemoteConfig config = load();
        mt.su.nrm.nginx.CacheZone zone = config.createCacheZone("/var/cache/nginx/site");
        mt.su.nrm.nginx.CacheZoneSettings s = zone.read();
        s.keysZone = "site:10m";
        zone.apply(s);
        List<PendingChange> changes = config.pendingChanges();

        assertEquals(List.of("/var/cache/nginx"), ApplyPipeline.cacheParents(changes));
        String test = ApplyPipeline.testScript(ServerPaths.defaults(), config, changes, List.of("/tmp/x.new"), "abc");
        String apply = ApplyPipeline.applyScript(ServerPaths.defaults(), config, changes, List.of("/tmp/x.new"),
                "/var/backups/nrm/b", "abc");
        for (String script : List.of(test, apply)) {
            int prepare = script.indexOf("for d in '/var/cache/nginx' ; do [ -d \"$d\" ] || mkdir \"$d\"");
            assertTrue(prepare > 0, script);
            assertTrue(prepare < script.indexOf("-t -c"), "created before nginx tests");
        }
        assertEquals("", ApplyPipeline.prepareDirs(List.of()));
    }

    @Test
    void theCacheFolderIsHandedToTheNginxUserAfterNginxMayHaveCreatedItAsRoot() throws Exception {
        RemoteConfig config = load();
        mt.su.nrm.nginx.CacheZone zone = config.createCacheZone("/var/cache/nginx/site");
        mt.su.nrm.nginx.CacheZoneSettings s = zone.read();
        s.keysZone = "site:10m";
        zone.apply(s);
        List<PendingChange> changes = config.pendingChanges();

        assertEquals(List.of("/var/cache/nginx/site"), ApplyPipeline.cachePaths(changes));
        String test = ApplyPipeline.testScript(ServerPaths.defaults(), config, changes, List.of("/tmp/x.new"), "abc");
        String apply = ApplyPipeline.applyScript(ServerPaths.defaults(), config, changes, List.of("/tmp/x.new"),
                "/var/backups/nrm/b", "abc");
        // "nginx -t" creates the folder as root (mode 700), so the test script fixes it after the check...
        assertTrue(test.indexOf("chown -R \"$NU\"") > test.indexOf("-t -c"), test);
        assertTrue(test.indexOf("chown -R \"$NU\"") < test.indexOf("rm -rf \"$T\"\nprintf"), test);
        // ...and the apply script does it before nginx first touches the folder.
        assertTrue(apply.indexOf("chown -R \"$NU\"") > 0 && apply.indexOf("chown -R \"$NU\"") < apply.indexOf("-t -c"), apply);
        assertTrue(apply.contains("'/var/cache/nginx/site' ; do"), apply);
        assertEquals("", ApplyPipeline.fixCacheDirs(List.of(), "x"));
    }
}
