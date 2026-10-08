package mt.su.nrm.nginx;

import java.util.List;

/**
 * One file that will be written, created or removed when the pending changes are applied.
 *
 * @param kind        what happens to the file
 * @param file        the file in the {@link RemoteConfig}
 * @param path        the real path on the server (symlinks already resolved)
 * @param oldText     the text on the server now; empty for a new file
 * @param newText     the text to write; empty for a file being removed
 * @param enableLink  for a new file in a sites-available layout: the symlink that enables it, else null
 * @param links       for a removed file: the symlinks that point at it and must go too
 */
public record PendingChange(Kind kind, ConfigFile file, String path, String oldText, String newText,
                            String enableLink, List<RemoteConfig.Link> links) {

    public enum Kind { MODIFY, CREATE, DELETE }

    /** A unified diff of this change. */
    public String diff() {
        switch (kind) {
            case CREATE:
                return UnifiedDiff.diff("/dev/null", path, "", newText);
            case DELETE:
                return UnifiedDiff.diff(path, "/dev/null", oldText, "");
            default:
                return UnifiedDiff.diff(path, path, oldText, newText);
        }
    }

    public String describe() {
        switch (kind) {
            case CREATE:
                return "New file " + path;
            case DELETE:
                return "Delete " + path;
            default:
                return "Change " + path;
        }
    }
}
