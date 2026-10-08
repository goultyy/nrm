package mt.su.nrm.ssh;

import mt.su.nrm.model.ServerPaths;
import mt.su.nrm.nginx.PendingChange;
import mt.su.nrm.nginx.RemoteConfig;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The two server-side steps of the validate/apply pipeline. Blocking: run through {@link SshExecutor}.
 * <ol>
 *   <li>{@link #test}: upload the new files to temporary files, build a scratch copy of the config
 *       folder with them in place and run {@code nginx -t} on that copy. The live config is not
 *       touched.</li>
 *   <li>{@link #apply}: after the user has confirmed the diff, back up the affected files, write
 *       the new ones, run {@code nginx -t} on the live config, and reload nginx. If the test fails,
 *       everything is put back before anything is reloaded.</li>
 * </ol>
 */
public final class ApplyPipeline {

    public enum Status {
        /** The config is valid (test) or was written and nginx reloaded (apply). */
        OK,
        /** {@code nginx -t} rejected the new configuration; nothing was changed on the server. */
        TEST_FAILED,
        /** The live test failed after writing, so the files were restored; nginx was not reloaded. */
        ROLLED_BACK,
        /** The files were written and the config is valid, but nginx would not reload. */
        RELOAD_FAILED
    }

    /** @param output what nginx (and the script) printed, with scratch paths mapped back to real ones */
    public record Outcome(Status status, String output, String backupDir) {
        public boolean ok() {
            return status == Status.OK;
        }
    }

    static final String BACKUP_ROOT = "/var/backups/nrm";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private ApplyPipeline() {
    }

    // ------------------------------------------------------------------------------- test

    public static Outcome test(SshSession session, ServerPaths paths, RemoteConfig config, List<PendingChange> changes)
            throws IOException {
        String id = newId();
        List<String> uploaded = new ArrayList<>();
        try {
            List<String> tempFiles = upload(session, paths, changes, id, uploaded);
            String script = testScript(paths, config, changes, tempFiles, id);
            CommandResult result = session.execPrivileged(script, SshSession.DEFAULT_TIMEOUT.multipliedBy(3));
            String output = (result.stdout() + result.stderr()).strip();
            return new Outcome(result.ok() ? Status.OK : Status.TEST_FAILED, output, null);
        } finally {
            cleanup(session, uploaded);
        }
    }

    /** The scratch-copy script; package-private so it can be checked without a server. */
    static String testScript(ServerPaths paths, RemoteConfig config, List<PendingChange> changes,
                             List<String> tempFiles, String id) throws IOException {
        String confDir = safeDir(config.confDir());
        String scratch = safeDir(paths.getRemoteTempDir()) + "/nrm-test-" + id;
        String nginx = paths.getNginxBinary();
        String mainRel = relativeTo(confDir, safeFile(paths.mainConfigFile(), confDir));

        StringBuilder s = new StringBuilder();
        s.append("umask 077\n");
        s.append("NGINX=").append(Shell.quote(nginx)).append('\n');
        s.append("C=").append(Shell.quote(confDir)).append('\n');
        s.append("T=").append(Shell.quote(scratch)).append('\n');
        s.append("rm -rf \"$T\"; mkdir -p \"$T\" || exit 40\n");
        s.append("cp -a \"$C\"/. \"$T\"/ || { rm -rf \"$T\"; exit 41; }\n");

        for (int i = 0; i < changes.size(); i++) {
            PendingChange c = changes.get(i);
            String rel = relativeTo(confDir, safeFile(c.path(), confDir));
            switch (c.kind()) {
                case DELETE:
                    s.append("rm -f \"$T/").append(rel).append("\"\n");
                    for (RemoteConfig.Link link : c.links()) {
                        if (link.path().startsWith(confDir + "/") && isSafe(link.path())) {
                            s.append("rm -f \"$T/").append(relativeTo(confDir, link.path())).append("\"\n");
                        }
                    }
                    break;
                default:
                    s.append("mkdir -p \"$(dirname \"$T/").append(rel).append("\")\" && cat ")
                            .append(Shell.quote(tempFiles.get(i))).append(" > \"$T/").append(rel)
                            .append("\" || { rm -rf \"$T\"; exit 42; }\n");
                    if (c.enableLink() != null) {
                        String link = safeFile(c.enableLink(), confDir);
                        s.append("mkdir -p \"$(dirname \"$T/").append(relativeTo(confDir, link)).append("\")\" && ln -sfn ")
                                .append(Shell.quote(relativeLink(link, c.path()))).append(" \"$T/")
                                .append(relativeTo(confDir, link)).append("\" || { rm -rf \"$T\"; exit 43; }\n");
                    }
            }
        }
        // Point the copy at itself, now that the new files are in it (they may name the live folder too):
        // symlinks and absolute paths into the live folder are redirected to the scratch folder.
        s.append("find \"$T\" -type l | while IFS= read -r l; do t=$(readlink \"$l\"); case \"$t\" in \"$C\"/*) ln -sfn \"$T/${t#\"$C\"/}\" \"$l\";; esac; done\n");
        s.append("grep -rIl -F -- \"$C/\" \"$T\" 2>/dev/null | while IFS= read -r f; do [ -L \"$f\" ] || sed -i \"s#$C/#$T/#g\" \"$f\"; done\n");
        s.append(prepareDirs(cacheParents(changes)));
        s.append("out=$(\"$NGINX\" -t -c \"$T/").append(mainRel).append("\" 2>&1); rc=$?\n");
        // nginx -t has just created any missing cache folder as root, mode 700: hand it to the workers.
        s.append(fixCacheDirs(cachePaths(changes), "\"$T/" + mainRel + "\""));
        s.append("rm -rf \"$T\"\n");
        s.append("printf '%s\\n' \"$out\" | sed \"s#$T/#$C/#g\"\n");
        s.append("exit $rc\n");
        return s.toString();
    }

    // ------------------------------------------------------------------------------- apply

    public static Outcome apply(SshSession session, ServerPaths paths, RemoteConfig config,
                                List<PendingChange> changes, Clock clock) throws IOException {
        String id = newId();
        List<String> uploaded = new ArrayList<>();
        try {
            List<String> tempFiles = upload(session, paths, changes, id, uploaded);
            String backupDir = BACKUP_ROOT + "/" + STAMP.format(clock.instant()) + "-" + id.substring(0, 4);
            String script = applyScript(paths, config, changes, tempFiles, backupDir, id);
            CommandResult result = session.execPrivileged(script, SshSession.DEFAULT_TIMEOUT.multipliedBy(3));
            String output = (result.stdout() + result.stderr()).strip();
            return new Outcome(statusOf(output, result.exitStatus(), id), stripMarkers(output, id), backupDir);
        } finally {
            cleanup(session, uploaded);
        }
    }

    static String applyScript(ServerPaths paths, RemoteConfig config, List<PendingChange> changes,
                              List<String> tempFiles, String backupDir, String id) throws IOException {
        String confDir = safeDir(config.confDir());
        String mainPath = safeFile(paths.mainConfigFile(), confDir);
        if (!isSafe(backupDir)) {
            throw new IOException("Unsafe backup path.");
        }

        List<String> forward = new ArrayList<>();
        List<String> rollback = new ArrayList<>();
        List<String> manifest = new ArrayList<>();
        for (int i = 0; i < changes.size(); i++) {
            PendingChange c = changes.get(i);
            String path = Shell.quote(safeFile(c.path(), confDir));
            String backup = Shell.quote(backupDir + "/" + i);
            manifest.add(BackupService.manifestLine(c, i));
            switch (c.kind()) {
                case MODIFY:
                    forward.add("cp -a " + path + " " + backup);
                    forward.add("cat " + Shell.quote(tempFiles.get(i)) + " > " + path);
                    rollback.add("cp -a " + backup + " " + path);
                    break;
                case CREATE:
                    forward.add("mkdir -p " + Shell.quote(dirOf(c.path())));
                    forward.add("install -m 644 " + Shell.quote(tempFiles.get(i)) + " " + path);
                    rollback.add("rm -f " + path);
                    if (c.enableLink() != null) {
                        String link = safeFile(c.enableLink(), confDir);
                        forward.add("mkdir -p " + Shell.quote(dirOf(link)));
                        forward.add("ln -sfn " + Shell.quote(relativeLink(link, c.path())) + " " + Shell.quote(link));
                        rollback.add("rm -f " + Shell.quote(link));
                    }
                    break;
                default:
                    forward.add("cp -a " + path + " " + backup);
                    List<String> restoreLinks = new ArrayList<>();
                    StringBuilder rm = new StringBuilder("rm -f");
                    for (RemoteConfig.Link link : c.links()) {
                        if (!isSafe(link.path()) || !isSafeLinkTarget(link.target())) {
                            throw new IOException("Unsafe symlink " + link.path() + ".");
                        }
                        rm.append(' ').append(Shell.quote(link.path()));
                        restoreLinks.add("ln -sfn " + Shell.quote(link.target()) + " " + Shell.quote(link.path()));
                    }
                    forward.add(rm + " " + path);
                    rollback.add("cp -a " + backup + " " + path);
                    rollback.addAll(restoreLinks);
            }
        }
        java.util.Collections.reverse(rollback);

        String nginx = Shell.quote(paths.getNginxBinary());
        String conf = Shell.quote(mainPath);
        String marker = "@@NRM-" + id;
        StringBuilder s = new StringBuilder();
        s.append("umask 022\n");
        s.append("mkdir -p ").append(Shell.quote(backupDir)).append(" || exit 50\n");
        // What this apply touches, so the backup can be listed and put back later (see BackupService).
        for (String line : manifest) {
            s.append("printf '%s\\n' ").append(Shell.quote(line)).append(" >> ")
                    .append(Shell.quote(backupDir + "/" + BackupService.MANIFEST)).append(" || exit 50\n");
        }
        s.append(prepareDirs(cacheParents(changes)));
        s.append(fixCacheDirs(cachePaths(changes), conf));
        s.append("rb() {\n");
        for (String cmd : rollback) {
            s.append("  ").append(cmd).append(" || echo 'rollback step failed: ' ").append(Shell.quote(cmd.length() > 60 ? cmd.substring(0, 60) : cmd)).append('\n');
        }
        s.append("  :\n}\n");
        for (String cmd : forward) {
            s.append(cmd).append(" || { rb; echo '").append(marker).append(" ROLLED-BACK'; exit 51; }\n");
        }
        s.append("out=$(").append(nginx).append(" -t -c ").append(conf).append(" 2>&1); rc=$?\n");
        s.append("printf '%s\\n' \"$out\"\n");
        s.append("if [ $rc -ne 0 ]; then rb; echo '").append(marker).append(" ROLLED-BACK'; exit 52; fi\n");
        // The new files are live and valid: from here on this backup is a version the user can go back to.
        s.append(": > ").append(Shell.quote(backupDir + "/" + BackupService.APPLIED)).append('\n');
        s.append("out=$(").append(nginx).append(" -c ").append(conf).append(" -s reload 2>&1); rc=$?\n");
        s.append("printf '%s\\n' \"$out\"\n");
        s.append("if [ $rc -ne 0 ]; then echo '").append(marker).append(" RELOAD-FAILED'; exit 53; fi\n");
        s.append("echo '").append(marker).append(" APPLIED'\n");
        return s.toString();
    }

    /**
     * nginx creates the last folder of a cache path itself but not the ones above it, so a cache
     * zone in a folder that doesn't exist yet would fail the config test. These are the parent
     * folders of every {@code *_cache_path} in the files being written.
     */
    static List<String> cacheParents(List<PendingChange> changes) {
        List<String> parents = new ArrayList<>();
        for (String path : cachePaths(changes)) {
            String parent = dirOf(path);
            if (!parent.equals("/") && !parents.contains(parent)) {
                parents.add(parent);
            }
        }
        return parents;
    }

    /** Every {@code *_cache_path} folder named in the files being written. */
    static List<String> cachePaths(List<PendingChange> changes) {
        List<String> paths = new ArrayList<>();
        for (PendingChange c : changes) {
            if (c.kind() == PendingChange.Kind.DELETE) {
                continue;
            }
            try {
                var file = mt.su.nrm.nginx.ConfigFile.parse(c.path(), c.newText());
                for (var d : file.findDirectives(List.of("proxy_cache_path", "fastcgi_cache_path", "uwsgi_cache_path",
                        "scgi_cache_path"))) {
                    String path = d.arg(0);
                    if (path != null && isSafe(path) && !paths.contains(path)) {
                        paths.add(path);
                    }
                }
            } catch (mt.su.nrm.nginx.NginxParseException e) {
                // A file that doesn't parse can't be applied anyway; the local checks report it.
            }
        }
        return paths;
    }

    /**
     * Makes each cache folder usable by the nginx workers: creates it if missing and hands it to the
     * user named on nginx.conf's {@code user} line. {@code nginx -t} run as root creates missing cache
     * folders itself, root-owned and mode 700, which the workers then can't open ("opendir() failed
     * (13: Permission denied)"), so this runs after it too. A folder owned by anyone but root is left
     * alone and reported, and a parent folder is only opened up for traversal if root owns it.
     *
     * @param confExpr a shell expression for the main config file the {@code user} line is read from
     */
    static String fixCacheDirs(List<String> dirs, String confExpr) {
        if (dirs.isEmpty()) {
            return "";
        }
        StringBuilder items = new StringBuilder();
        for (String d : dirs) {
            items.append(Shell.quote(d)).append(' ');
        }
        StringBuilder s = new StringBuilder();
        s.append("NU=$(awk '$1==\"user\"{sub(/;.*/,\"\",$2);print $2;exit}' ").append(confExpr).append(" 2>/dev/null)\n");
        s.append("if [ -z \"$NU\" ]; then for u in www-data nginx nobody; do id \"$u\" >/dev/null 2>&1 && { NU=$u; break; }; done; fi\n");
        s.append("for d in ").append(items).append("; do\n");
        s.append("  [ -d \"$d\" ] || mkdir \"$d\" || { echo \"Could not create $d\"; continue; }\n");
        s.append("  P=$(dirname \"$d\"); [ \"$(stat -c %U \"$P\" 2>/dev/null)\" = root ] && chmod o+x \"$P\"\n");
        s.append("  if [ -n \"$NU\" ] && id \"$NU\" >/dev/null 2>&1; then\n");
        s.append("    O=$(stat -c %U \"$d\" 2>/dev/null)\n");
        s.append("    if [ \"$O\" = root ]; then chown -R \"$NU\" \"$d\" || echo \"Could not give $d to $NU\";\n");
        s.append("    elif [ \"$O\" != \"$NU\" ]; then echo \"Note: $d is owned by $O, not nginx's user $NU; left unchanged\"; fi\n");
        s.append("  fi\n");
        s.append("  chmod u+rwx \"$d\"\n");
        s.append("done\n");
        return s.toString();
    }

    /** Creates each folder (one level only, never the folders above it) if it is missing. */
    static String prepareDirs(List<String> dirs) {
        if (dirs.isEmpty()) {
            return "";
        }
        StringBuilder items = new StringBuilder();
        for (String d : dirs) {
            items.append(Shell.quote(d)).append(' ');
        }
        return "for d in " + items + "; do [ -d \"$d\" ] || mkdir \"$d\" || echo \"Could not create $d\"; done\n";
    }

    private static Status statusOf(String output, int exit, String id) {
        String marker = "@@NRM-" + id;
        if (output.contains(marker + " APPLIED") && exit == 0) {
            return Status.OK;
        }
        if (output.contains(marker + " RELOAD-FAILED")) {
            return Status.RELOAD_FAILED;
        }
        return Status.ROLLED_BACK;
    }

    private static String stripMarkers(String output, String id) {
        StringBuilder sb = new StringBuilder();
        for (String line : output.split("\r?\n")) {
            if (!line.startsWith("@@NRM-" + id)) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString().strip();
    }

    // ------------------------------------------------------------------------------- shared

    private static List<String> upload(SshSession session, ServerPaths paths, List<PendingChange> changes, String id,
                                       List<String> uploaded) throws IOException {
        String tmp = safeDir(paths.getRemoteTempDir());
        List<String> files = new ArrayList<>();
        for (int i = 0; i < changes.size(); i++) {
            PendingChange c = changes.get(i);
            if (c.kind() == PendingChange.Kind.DELETE) {
                files.add(null);
                continue;
            }
            String path = tmp + "/nrm-" + id + "-" + i + ".new";
            session.upload(c.newText().getBytes(java.nio.charset.StandardCharsets.UTF_8), path);
            uploaded.add(path);
            files.add(path);
        }
        return files;
    }

    private static void cleanup(SshSession session, List<String> uploaded) {
        for (String path : uploaded) {
            try {
                session.delete(path);
            } catch (IOException | RuntimeException ignored) {
                // A leftover temp file is harmless; the failure is already in the command log.
            }
        }
    }

    static boolean isSafe(String path) {
        return RemoteConfigService.SAFE_PATH.matcher(path).matches() && !path.contains("//") && !path.contains("/../");
    }

    private static boolean isSafeLinkTarget(String target) {
        return target.matches("[A-Za-z0-9_./+@%=,:~-]+") && !target.contains("//");
    }

    private static String safeDir(String dir) throws IOException {
        String d = dir.length() > 1 && dir.endsWith("/") ? dir.substring(0, dir.length() - 1) : dir;
        if (!isSafe(d)) {
            throw new IOException("The folder \"" + dir + "\" has characters this app will not put in a remote command.");
        }
        return d;
    }

    /** A file path that is safe to use and lies inside the config folder. */
    private static String safeFile(String path, String confDir) throws IOException {
        if (!isSafe(path) || !path.startsWith(confDir + "/")) {
            throw new IOException("Refusing to touch " + path + ": it is not a plain path inside " + confDir + ".");
        }
        return path;
    }

    private static String relativeTo(String dir, String path) {
        return path.substring(dir.length() + 1);
    }

    private static String dirOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "/" : path.substring(0, slash);
    }

    /** The relative symlink target that leads from the link's folder to the file. */
    static String relativeLink(String linkPath, String targetPath) {
        String[] from = dirOf(linkPath).split("/");
        String[] to = targetPath.split("/");
        int common = 0;
        while (common < from.length - 0 && common < to.length - 1 && from[common].equals(to[common])) {
            common++;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = common; i < from.length; i++) {
            if (!from[i].isEmpty()) {
                sb.append("../");
            }
        }
        for (int i = common; i < to.length; i++) {
            sb.append(to[i]);
            if (i < to.length - 1) {
                sb.append('/');
            }
        }
        return sb.toString();
    }

    private static String newId() {
        byte[] bytes = new byte[6];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
