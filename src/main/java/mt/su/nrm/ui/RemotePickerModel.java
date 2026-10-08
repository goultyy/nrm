package mt.su.nrm.ui;

import mt.su.nrm.ssh.RemotePaths;
import mt.su.nrm.ssh.SftpEntry;
import mt.su.nrm.ssh.SftpService;

import java.util.Comparator;
import java.util.List;

/**
 * The rules of the server folder picker, apart from the window: what is listed, what can be chosen, what the choice
 * is, and where to open. No JavaFX and no server, so it can be tested directly.
 */
final class RemotePickerModel {

    /** What the field being filled holds. */
    enum Kind { FOLDER, FILE }

    private RemotePickerModel() {
    }

    /** Folders first, then files if they are shown, each group by name ignoring case. */
    static List<SftpEntry> visible(List<SftpEntry> entries, boolean showFiles) {
        return entries.stream()
                .filter(e -> !e.name().equals(".") && !e.name().equals(".."))
                .filter(e -> e.directory() || showFiles)
                .sorted(Comparator.comparing((SftpEntry e) -> !e.directory())
                        .thenComparing(e -> e.name().toLowerCase(java.util.Locale.ROOT)))
                .toList();
    }

    /** Whether a row may be chosen. In folder mode files are shown only for orientation. */
    static boolean selectable(Kind kind, SftpEntry entry) {
        return kind == Kind.FOLDER ? entry.directory() : !entry.directory();
    }

    /**
     * What Select would give back: in folder mode the selected folder, or the folder being viewed if none is selected;
     * in file mode the selected file. Null when there is nothing valid to choose.
     */
    static String chosen(Kind kind, String currentFolder, SftpEntry selected) {
        if (kind == Kind.FOLDER) {
            if (selected == null) {
                return RemotePaths.normalize(currentFolder);
            }
            return selected.directory() ? SftpService.join(RemotePaths.normalize(currentFolder), selected.name()) : null;
        }
        return selected != null && !selected.directory() ? SftpService.join(RemotePaths.normalize(currentFolder), selected.name()) : null;
    }

    /**
     * The folder to open for what is typed in the field: its folder, or for a file field the folder the file is in.
     * Falls back to the root when the value is empty, relative, or built from nginx variables from the start.
     */
    static String startFolder(String fieldValue, Kind kind) {
        String dir = FileTransferLinks.directoryOf(fieldValue, kind == Kind.FILE);
        return dir == null ? "/" : RemotePaths.normalize(dir);
    }

    /** The file to highlight when opening on a file field, or null. */
    static String preselect(String fieldValue, Kind kind) {
        if (kind != Kind.FILE || fieldValue == null) {
            return null;
        }
        String full = FileTransferLinks.directoryOf(fieldValue, false);
        if (full == null || fieldValue.contains("$")) {
            return null;
        }
        String name = RemotePaths.lastName(full);
        return name.isEmpty() ? null : name;
    }

    /** The folders to try, from the wanted one up to the root, when the wanted one can't be listed. */
    static List<String> fallbacks(String wanted) {
        java.util.ArrayList<String> chain = new java.util.ArrayList<>();
        String p = RemotePaths.normalize(wanted);
        chain.add(p);
        while (!p.equals("/")) {
            p = RemotePaths.parent(p);
            chain.add(p);
        }
        return chain;
    }

    /** The default for the "Show files" box: on when a file is wanted, off when only folders are. */
    static boolean showFilesByDefault(Kind kind) {
        return kind == Kind.FILE;
    }

    /** The wording for the button beside a field. */
    static String buttonLabel(Kind kind) {
        return kind == Kind.FILE ? "Choose file" : "Choose folder";
    }
}
