package mt.su.nrm.ssh;

import java.util.ArrayList;
import java.util.List;

/** Path handling for the server side of the folder picker. Pure text; nothing here touches a server. */
public final class RemotePaths {

    /** One step of the way to a folder: its name and its full path. */
    public record Crumb(String name, String path) {
    }

    private RemotePaths() {
    }

    /** True for a path the picker may list: absolute, and without control characters. */
    public static boolean usable(String path) {
        return path != null && path.startsWith("/") && path.chars().noneMatch(Character::isISOControl);
    }

    /**
     * The path with {@code .} and {@code ..} resolved and doubled or trailing slashes removed. A relative path is taken
     * from the root; {@code ..} above the root stays at the root. A blank path is the root.
     */
    public static String normalize(String path) {
        List<String> parts = new ArrayList<>();
        if (path != null) {
            for (String part : path.strip().split("/")) {
                if (part.isEmpty() || part.equals(".")) {
                    continue;
                }
                if (part.equals("..")) {
                    if (!parts.isEmpty()) {
                        parts.remove(parts.size() - 1);
                    }
                } else {
                    parts.add(part);
                }
            }
        }
        return parts.isEmpty() ? "/" : "/" + String.join("/", parts);
    }

    /** The root, then each folder down to this one ("/var/www" gives /, var, www). */
    public static List<Crumb> crumbs(String path) {
        String normalized = normalize(path);
        List<Crumb> crumbs = new ArrayList<>();
        crumbs.add(new Crumb("/", "/"));
        if (!normalized.equals("/")) {
            StringBuilder so = new StringBuilder();
            for (String part : normalized.substring(1).split("/")) {
                so.append('/').append(part);
                crumbs.add(new Crumb(part, so.toString()));
            }
        }
        return crumbs;
    }

    /** The folder above this one; the root's parent is the root. */
    public static String parent(String path) {
        return SftpService.parent(normalize(path));
    }

    /** The last part of a path, or "" for the root. */
    public static String lastName(String path) {
        String normalized = normalize(path);
        return normalized.equals("/") ? "" : normalized.substring(normalized.lastIndexOf('/') + 1);
    }
}
