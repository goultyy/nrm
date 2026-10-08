package mt.su.nrm.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mt.su.nrm.model.ConfigLayout;
import mt.su.nrm.model.ServerPaths;
import mt.su.nrm.nginx.ConfigFile;
import mt.su.nrm.nginx.PendingChange;
import mt.su.nrm.nginx.PendingChange.Kind;
import mt.su.nrm.nginx.RemoteConfig;
import mt.su.nrm.ssh.BackupService.Backup;
import mt.su.nrm.ssh.BackupService.Entry;
import mt.su.nrm.ssh.FakeSessions.Reply;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The change history: what an apply records, how it is read back, and the changes that undo it. */
class BackupServiceTest {

    private static final String DIR = "/var/backups/nrm/20260304-050607-abcd";
    private static final String SITE = "/etc/nginx/sites-available/a";
    private static final String OLD = "server {\n    listen 80;\n    server_name a.com;\n}\n";
    private static final String NEW = "server {\n    listen 80;\n    server_name a.com;\n    gzip on;\n}\n";

    private static ConfigFile file(String path, String text) throws Exception {
        return ConfigFile.parse(path, text);
    }

    private static RemoteConfig config() throws Exception {
        return new RemoteConfig("/etc/nginx", ConfigLayout.SITES_AVAILABLE,
                file("/etc/nginx/nginx.conf", "events {}\nhttp {\n}\n"));
    }

    private static PendingChange modify() throws Exception {
        return new PendingChange(Kind.MODIFY, file(SITE, NEW), SITE, OLD, NEW, null, List.of());
    }

    // ---------------------------------------------------------------- what an apply records

    @Test
    void applyWritesTheManifestFirstAndMarksTheBackupUsableOnlyOnceTheNewConfigPassed() throws Exception {
        String script = ApplyPipeline.applyScript(ServerPaths.defaults(), config(), List.of(modify()),
                List.of("/tmp/nrm-x-0.new"), DIR, "abc123");
        int manifest = script.indexOf("printf '%s\\n' 'M 0 " + SITE + "' >> '" + DIR + "/manifest'");
        int firstWrite = script.indexOf("cp -a '" + SITE + "'");
        int test = script.indexOf("-t -c");
        int applied = script.indexOf(": > '" + DIR + "/APPLIED'");
        int reload = script.indexOf("-s reload");
        int rollback = script.indexOf("if [ $rc -ne 0 ]; then rb;");
        assertTrue(manifest > 0 && manifest < firstWrite, "manifest comes before any file is touched: " + script);
        assertTrue(applied > rollback && applied < reload,
                "a backup that was rolled back must never be marked as a version to go back to: " + script);
        assertTrue(test < applied);
    }

    @Test
    void theManifestRoundTripsEveryKindIncludingLinks() throws Exception {
        PendingChange created = new PendingChange(Kind.CREATE, file("/etc/nginx/sites-available/n", NEW),
                "/etc/nginx/sites-available/n", "", NEW, "/etc/nginx/sites-enabled/n", List.of());
        PendingChange deleted = new PendingChange(Kind.DELETE, file(SITE, OLD), SITE, OLD, "", null,
                List.of(new RemoteConfig.Link("/etc/nginx/sites-enabled/a", "../sites-available/a")));
        List<String> lines = List.of(BackupService.manifestLine(modify(), 0), BackupService.manifestLine(created, 1),
                BackupService.manifestLine(deleted, 2));
        assertEquals("C 1 /etc/nginx/sites-available/n link=/etc/nginx/sites-enabled/n->../sites-available/n", lines.get(1));

        List<Entry> entries = BackupService.parseManifest(String.join("\n", lines));
        assertEquals(3, entries.size());
        assertEquals(Kind.MODIFY, entries.get(0).kind());
        assertEquals(Kind.CREATE, entries.get(1).kind());
        assertEquals("/etc/nginx/sites-enabled/n", entries.get(1).links().get(0).path());
        assertEquals("../sites-available/n", entries.get(1).links().get(0).target());
        assertEquals(new RemoteConfig.Link("/etc/nginx/sites-enabled/a", "../sites-available/a"),
                entries.get(2).links().get(0));
        assertEquals(2, entries.get(2).index());
    }

    @Test
    void manifestLinesThatMakeNoSenseOrNameUnsafePathsAreIgnored() {
        List<Entry> entries = BackupService.parseManifest("""
                M 0 /etc/nginx/ok
                X 1 /etc/nginx/unknown-kind
                M one /etc/nginx/bad-index
                M 2 /etc/nginx/a;rm -rf /
                M 3 relative/path
                M 4 /etc/nginx/../shadow
                D 5 /etc/nginx/b link=/etc/nginx/l->$(evil)
                D 6 /etc/nginx/c link=nonsense

                M 7 /etc/nginx/fine
                """);
        assertEquals(List.of("/etc/nginx/ok", "/etc/nginx/fine"), entries.stream().map(Entry::path).toList());
    }

    // ---------------------------------------------------------------- listing

    @Test
    void theListIsNewestFirstAndSkipsFoldersThatAreNotOurs() {
        String output = "@@NRM-BACKUP 20260101-010101-aaaa\nM 0 /etc/nginx/a\n"
                + "@@NRM-BACKUP 20260303-030303-bbbb\nM 0 /etc/nginx/b\nC 1 /etc/nginx/c\n"
                + "@@NRM-BACKUP not-ours\nM 0 /etc/nginx/x\n"
                + "@@NRM-BACKUP 20260202-020202-cccc\n";
        List<Backup> list = BackupService.parseList(output);
        assertEquals(List.of("20260303-030303-bbbb", "20260101-010101-aaaa"), list.stream().map(Backup::name).toList());
        assertEquals(Instant.parse("2026-03-03T03:03:03Z"), list.get(0).time());
        assertEquals(2, list.get(0).entries().size());
        assertEquals("/var/backups/nrm/20260303-030303-bbbb", list.get(0).dir());
    }

    @Test
    void listingRunsOnePrivilegedScriptThroughTheLoggedSession() throws Exception {
        List<String> ran = new ArrayList<>();
        SshSession session = FakeSessions.session(c -> Reply.ok("@@NRM-BACKUP 20260101-010101-aaaa\nM 0 /etc/nginx/a\n"),
                ran, new CommandLog());
        assertEquals(1, BackupService.list(session).size());
        assertEquals(1, ran.size());
        assertTrue(ran.get(0).contains("/var/backups/nrm"), ran.get(0));
        assertThrows(IOException.class, () -> BackupService.list(
                FakeSessions.session(c -> Reply.fail(1, "denied"), new ArrayList<>(), new CommandLog())));
    }

    // ---------------------------------------------------------------- restoring

    /** A fake server: the files that exist now, and what the backup folder holds. */
    private static SshSession server(Map<String, String> files, List<String> ran) {
        return FakeSessions.session(c -> {
            for (Map.Entry<String, String> f : files.entrySet()) {
                if (c.contains("'" + f.getKey() + "'")) {
                    return Reply.ok(f.getValue());
                }
            }
            return c.contains("if [ -f ") ? new Reply(3, "", "") : Reply.fail(1, "no such file");
        }, ran, new CommandLog());
    }

    @Test
    void aChangedFileComesBackAsAChangeFromWhatIsThereNow() throws Exception {
        Map<String, String> files = new HashMap<>();
        files.put(SITE, NEW);
        files.put(DIR + "/0", OLD);
        Backup backup = new Backup("20260304-050607-abcd", DIR, Instant.now(),
                List.of(new Entry(Kind.MODIFY, 0, SITE, List.of())));
        List<PendingChange> plan = BackupService.restorePlan(server(files, new ArrayList<>()), backup);
        assertEquals(1, plan.size());
        PendingChange c = plan.get(0);
        assertEquals(Kind.MODIFY, c.kind());
        assertEquals(NEW, c.oldText());
        assertEquals(OLD, c.newText());
        assertTrue(c.diff().contains("-    gzip on;"), c.diff());
    }

    @Test
    void aFileThatAlreadyMatchesTheBackupIsLeftOut() throws Exception {
        Map<String, String> files = new HashMap<>();
        files.put(SITE, OLD);
        files.put(DIR + "/0", OLD);
        Backup backup = new Backup("20260304-050607-abcd", DIR, Instant.now(),
                List.of(new Entry(Kind.MODIFY, 0, SITE, List.of())));
        assertTrue(BackupService.restorePlan(server(files, new ArrayList<>()), backup).isEmpty());
    }

    @Test
    void aCreatedFileIsRemovedWithItsLinkAndADeletedFileComesBackWithItsLink() throws Exception {
        String made = "/etc/nginx/sites-available/made";
        String gone = "/etc/nginx/sites-available/gone";
        Map<String, String> files = new HashMap<>();
        files.put(made, NEW);
        files.put(DIR + "/1", OLD);
        Backup backup = new Backup("20260304-050607-abcd", DIR, Instant.now(), List.of(
                new Entry(Kind.CREATE, 0, made,
                        List.of(new RemoteConfig.Link("/etc/nginx/sites-enabled/made", "../sites-available/made"))),
                new Entry(Kind.DELETE, 1, gone,
                        List.of(new RemoteConfig.Link("/etc/nginx/sites-enabled/gone", "../sites-available/gone")))));
        List<PendingChange> plan = BackupService.restorePlan(server(files, new ArrayList<>()), backup);

        assertEquals(2, plan.size());
        assertEquals(Kind.DELETE, plan.get(0).kind());
        assertEquals("/etc/nginx/sites-enabled/made", plan.get(0).links().get(0).path());
        assertEquals(Kind.CREATE, plan.get(1).kind());
        assertEquals("/etc/nginx/sites-enabled/gone", plan.get(1).enableLink());
        assertEquals(OLD, plan.get(1).newText());

        // The plan is something the normal apply script accepts, with matching rollbacks.
        String script = ApplyPipeline.applyScript(ServerPaths.defaults(), config(), plan,
                java.util.Arrays.asList(null, "/tmp/nrm-x-1.new"), "/var/backups/nrm/20260305-060708-ef01", "abc123");
        assertTrue(script.contains("rm -f '/etc/nginx/sites-enabled/made' '/etc/nginx/sites-available/made'"), script);
        assertTrue(script.contains("install -m 644 '/tmp/nrm-x-1.new' '" + gone + "'"), script);
        assertTrue(script.contains("ln -sfn '../sites-available/gone' '/etc/nginx/sites-enabled/gone'"), script);
    }

    @Test
    void aModifiedFileThatHasSinceBeenDeletedIsCreatedAgain() throws Exception {
        Map<String, String> files = new HashMap<>();
        files.put(DIR + "/0", OLD);
        Backup backup = new Backup("20260304-050607-abcd", DIR, Instant.now(),
                List.of(new Entry(Kind.MODIFY, 0, SITE, List.of())));
        List<PendingChange> plan = BackupService.restorePlan(server(files, new ArrayList<>()), backup);
        assertEquals(Kind.CREATE, plan.get(0).kind());
    }

    @Test
    void aMissingBackupFileOrAnUnreadableOneStopsTheRestore() {
        Backup backup = new Backup("20260304-050607-abcd", DIR, Instant.now(),
                List.of(new Entry(Kind.MODIFY, 0, SITE, List.of())));
        Map<String, String> files = new HashMap<>();
        files.put(SITE, NEW);
        assertTrue(assertThrows(IOException.class,
                () -> BackupService.restorePlan(server(files, new ArrayList<>()), backup)).getMessage().contains("incomplete"));

        files.put(DIR + "/0", "server {");
        assertTrue(assertThrows(IOException.class,
                () -> BackupService.restorePlan(server(files, new ArrayList<>()), backup)).getMessage()
                .contains("can't be read as nginx configuration"));
    }

    @Test
    void foldersWithOtherNamesAreNeverReadOrDeleted() {
        List<String> ran = new ArrayList<>();
        SshSession session = server(new HashMap<>(), ran);
        for (String name : List.of("../../etc", "x; rm -rf /", "20260304-050607-ABCD", "")) {
            Backup bad = new Backup(name, "/var/backups/nrm/" + name, Instant.now(),
                    List.of(new Entry(Kind.MODIFY, 0, SITE, List.of())));
            assertThrows(IOException.class, () -> BackupService.restorePlan(session, bad), name);
            assertThrows(IOException.class, () -> BackupService.delete(session, bad), name);
        }
        Backup lying = new Backup("20260304-050607-abcd", "/etc", Instant.now(), List.of());
        assertThrows(IOException.class, () -> BackupService.restorePlan(session, lying));
        assertTrue(ran.isEmpty(), "nothing may reach the server: " + ran);
    }

    @Test
    void deletingABackupRemovesOnlyThatFolder() throws Exception {
        List<String> ran = new ArrayList<>();
        SshSession session = FakeSessions.session(c -> Reply.ok(""), ran, new CommandLog());
        BackupService.delete(session, new Backup("20260304-050607-abcd", DIR, Instant.now(), List.of()));
        assertEquals(1, ran.size());
        assertTrue(ran.get(0).contains("rm -rf -- '" + DIR + "'"), ran.get(0));
        assertFalse(ran.get(0).contains("*"));
    }
}
