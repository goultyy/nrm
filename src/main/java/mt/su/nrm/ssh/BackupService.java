package mt.su.nrm.ssh;

import mt.su.nrm.nginx.ConfigFile;
import mt.su.nrm.nginx.NginxParseException;
import mt.su.nrm.nginx.PendingChange;
import mt.su.nrm.nginx.RemoteConfig;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The history of applied changes. Every apply leaves a folder under {@code /var/backups/nrm} holding the files it
 * replaced and a {@code manifest} saying what it touched ({@link ApplyPipeline}). This lists those folders and
 * turns one into the changes that would put the server back the way it was before that apply.
 * <p>
 * Restoring does not write anything itself: {@link #restorePlan} returns ordinary {@link PendingChange}s, which go
 * through the same local checks, {@code nginx -t}, diff review and rollback-on-failure as any other change. All
 * server access goes through the logged {@link SshSession}. Blocking: run through {@link SshExecutor}.
 */
public final class BackupService {

    static final String MANIFEST = "manifest";
    static final String APPLIED = "APPLIED";

    private static final Pattern NAME = Pattern.compile("\\d{8}-\\d{6}-[0-9a-f]{4}");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String LIST_MARK = "@@NRM-BACKUP ";

    /** One file an apply touched. {@code links} are the symlinks that enabled the file (new or removed files). */
    public record Entry(PendingChange.Kind kind, int index, String path, List<RemoteConfig.Link> links) {

        /** What undoing this does, in words. */
        public String undoes() {
            return switch (kind) {
                case MODIFY -> "was changed: the old version comes back";
                case CREATE -> "was created: it is removed";
                case DELETE -> "was deleted: it comes back";
            };
        }
    }

    /** One applied change set. */
    public record Backup(String name, String dir, Instant time, List<Entry> entries) {
    }

    private BackupService() {
    }

    // ---------------------------------------------------------------- the manifest

    /** The manifest line for one change: {@code M|C|D <index> <path> [link=<path>-><target>]...}. */
    static String manifestLine(PendingChange c, int index) {
        StringBuilder line = new StringBuilder();
        line.append(switch (c.kind()) {
            case MODIFY -> 'M';
            case CREATE -> 'C';
            case DELETE -> 'D';
        }).append(' ').append(index).append(' ').append(c.path());
        if (c.kind() == PendingChange.Kind.CREATE && c.enableLink() != null) {
            line.append(" link=").append(c.enableLink()).append("->")
                    .append(ApplyPipeline.relativeLink(c.enableLink(), c.path()));
        }
        if (c.kind() == PendingChange.Kind.DELETE) {
            for (RemoteConfig.Link link : c.links()) {
                line.append(" link=").append(link.path()).append("->").append(link.target());
            }
        }
        return line.toString();
    }

    /** Reads manifest lines back. Lines that make no sense, or name an unsafe path, are skipped. */
    static List<Entry> parseManifest(String text) {
        List<Entry> entries = new ArrayList<>();
        for (String line : text.split("\r?\n")) {
            String[] parts = line.strip().split(" ");
            if (parts.length < 3 || parts[0].length() != 1 || !parts[1].matches("\\d{1,4}")
                    || !ApplyPipeline.isSafe(parts[2])) {
                continue;
            }
            PendingChange.Kind kind = switch (parts[0].charAt(0)) {
                case 'M' -> PendingChange.Kind.MODIFY;
                case 'C' -> PendingChange.Kind.CREATE;
                case 'D' -> PendingChange.Kind.DELETE;
                default -> null;
            };
            if (kind == null) {
                continue;
            }
            List<RemoteConfig.Link> links = new ArrayList<>();
            boolean valid = true;
            for (int i = 3; i < parts.length; i++) {
                int arrow = parts[i].indexOf("->");
                if (!parts[i].startsWith("link=") || arrow < 0) {
                    valid = false;
                    break;
                }
                String path = parts[i].substring(5, arrow);
                String target = parts[i].substring(arrow + 2);
                if (!ApplyPipeline.isSafe(path) || !target.matches("[A-Za-z0-9_./+@%=,:~-]+")) {
                    valid = false;
                    break;
                }
                links.add(new RemoteConfig.Link(path, target));
            }
            if (valid) {
                entries.add(new Entry(kind, Integer.parseInt(parts[1]), parts[2], links));
            }
        }
        return entries;
    }

    /** The time an apply happened, from its folder name; null if the name isn't one of ours. */
    static Instant timeOf(String name) {
        if (!NAME.matcher(name).matches()) {
            return null;
        }
        try {
            return LocalDateTime.parse(name.substring(0, 15), STAMP).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- listing

    /** The server-side script that prints every usable backup with its manifest. */
    static String listScript() {
        return "cd " + Shell.quote(ApplyPipeline.BACKUP_ROOT) + " 2>/dev/null || exit 0\n"
                + "for d in */; do d=${d%/}; [ -f \"$d/" + APPLIED + "\" ] && [ -f \"$d/" + MANIFEST + "\" ] || continue\n"
                + "  echo '" + LIST_MARK + "'\"$d\"; cat \"$d/" + MANIFEST + "\"\n"
                + "done\n";
    }

    /** Newest first. Backups made before history existed have no manifest and are not listed. */
    public static List<Backup> list(SshSession session) throws IOException {
        CommandResult result = session.execPrivileged(listScript());
        if (!result.ok()) {
            throw new IOException("Could not read " + ApplyPipeline.BACKUP_ROOT + ": " + firstLine(result.stderr()));
        }
        return parseList(result.stdout());
    }

    static List<Backup> parseList(String output) {
        List<Backup> backups = new ArrayList<>();
        String name = null;
        StringBuilder manifest = new StringBuilder();
        for (String line : (output + "\n" + LIST_MARK + "\n").split("\r?\n")) {
            if (line.startsWith(LIST_MARK)) {
                if (name != null) {
                    Instant time = timeOf(name);
                    List<Entry> entries = parseManifest(manifest.toString());
                    if (time != null && !entries.isEmpty()) {
                        backups.add(new Backup(name, ApplyPipeline.BACKUP_ROOT + "/" + name, time, entries));
                    }
                }
                name = line.substring(LIST_MARK.length()).strip();
                manifest.setLength(0);
            } else {
                manifest.append(line).append('\n');
            }
        }
        backups.sort(Comparator.comparing(Backup::time).reversed());
        return backups;
    }

    // ---------------------------------------------------------------- restoring

    /**
     * The changes that put every file of the backup back as it was before that apply, judged against what is on the
     * server now (a file changed again since will show as a change back to the older text in the diff review).
     * Files that already match are left out.
     *
     * @throws IOException if the backup or a file can't be read, or a backed-up file isn't valid nginx syntax
     */
    public static List<PendingChange> restorePlan(SshSession session, Backup backup) throws IOException {
        if (!NAME.matcher(backup.name()).matches() || !backup.dir().equals(ApplyPipeline.BACKUP_ROOT + "/" + backup.name())) {
            throw new IOException("Refusing to read a backup folder with an unexpected name.");
        }
        List<PendingChange> plan = new ArrayList<>();
        for (Entry e : backup.entries()) {
            if (!ApplyPipeline.isSafe(e.path())) {
                throw new IOException("Refusing to touch " + e.path() + ".");
            }
            String current = readOrNull(session, e.path());
            switch (e.kind()) {
                case MODIFY, DELETE -> {
                    String old = read(session, backup.dir() + "/" + e.index());
                    if (current == null) {
                        String enable = e.links().isEmpty() ? null : e.links().get(0).path();
                        plan.add(new PendingChange(PendingChange.Kind.CREATE, parse(e.path(), old), e.path(), "", old,
                                enable, List.of()));
                    } else if (!current.equals(old)) {
                        plan.add(new PendingChange(PendingChange.Kind.MODIFY, parse(e.path(), old), e.path(), current,
                                old, null, List.of()));
                    }
                }
                case CREATE -> {
                    if (current != null) {
                        plan.add(new PendingChange(PendingChange.Kind.DELETE, parse(e.path(), current), e.path(), current,
                                "", null, e.links()));
                    }
                }
            }
        }
        return plan;
    }

    /** Removes a backup folder. Only a folder of this app's own naming, directly under the backup root. */
    public static void delete(SshSession session, Backup backup) throws IOException {
        if (!NAME.matcher(backup.name()).matches()) {
            throw new IOException("Refusing to delete a folder with an unexpected name.");
        }
        CommandResult result = session.execPrivileged("rm -rf -- " + Shell.quote(ApplyPipeline.BACKUP_ROOT + "/" + backup.name()));
        if (!result.ok()) {
            throw new IOException("Could not delete the backup: " + firstLine(result.stderr()));
        }
    }

    private static String readOrNull(SshSession session, String path) throws IOException {
        CommandResult r = session.execPrivileged("if [ -f " + Shell.quote(path) + " ]; then cat " + Shell.quote(path)
                + "; else exit 3; fi");
        if (r.exitStatus() == 3) {
            return null;
        }
        if (!r.ok()) {
            throw new IOException("Could not read " + path + ": " + firstLine(r.stderr()));
        }
        return r.stdout();
    }

    private static String read(SshSession session, String path) throws IOException {
        String text = readOrNull(session, path);
        if (text == null) {
            throw new IOException("The backup is incomplete: " + path + " is missing.");
        }
        return text;
    }

    private static ConfigFile parse(String path, String text) throws IOException {
        try {
            return ConfigFile.parse(path, text);
        } catch (NginxParseException e) {
            throw new IOException(path + " can't be read as nginx configuration: " + e.getMessage(), e);
        }
    }

    private static String firstLine(String text) {
        String t = text.strip();
        int nl = t.indexOf('\n');
        return nl < 0 ? t : t.substring(0, nl);
    }
}
