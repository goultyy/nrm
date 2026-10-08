package mt.su.nrm.ssh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * File and folder transfers built on the logged {@link SshSession} operations, so every file,
 * folder and listing involved shows up in the command log. Blocking: call off the FX thread.
 * Symbolic links on the local side are skipped rather than followed.
 */
public final class SftpService {

    /** Told what is happening; {@link #cancelled()} is polled between files and while copying. */
    public interface Listener {
        /** A new file or folder is starting. */
        void item(String description);

        /** Bytes copied since the last call. */
        void bytes(long delta);

        boolean cancelled();
    }

    public static final class CancelledException extends IOException {
        public CancelledException() {
            super("Cancelled");
        }
    }

    private SftpService() {
    }

    /** Joins a remote directory and a name with a single slash. */
    public static String join(String dir, String name) {
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    /**
     * What is wrong with a name typed for a new file or folder, or null if it is usable: it must be one plain name,
     * not a path, so it can't reach outside the folder it is created in.
     */
    public static String fileNameProblem(String name) {
        if (name == null || name.isBlank()) {
            return "Enter a name.";
        }
        if (name.equals(".") || name.equals("..")) {
            return "\"" + name + "\" is not a usable name.";
        }
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            return "A name can't contain / or \\. To create something in another folder, open that folder first.";
        }
        if (name.chars().anyMatch(Character::isISOControl)) {
            return "The name contains characters that can't be used.";
        }
        if (name.length() > 255) {
            return "The name is too long (255 characters at most).";
        }
        return null;
    }

    /** The parent of a remote path ("/" for top-level items and for "/"). */
    public static String parent(String path) {
        String trimmed = path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int slash = trimmed.lastIndexOf('/');
        return slash <= 0 ? "/" : trimmed.substring(0, slash);
    }

    /** Uploads a file, or a folder with everything in it, into {@code remoteDir}. */
    public static void upload(SshSession session, Path local, String remoteDir, Listener listener)
            throws IOException {
        String target = join(remoteDir, local.getFileName().toString());
        if (Files.isDirectory(local)) {
            uploadFolder(session, local, target, listener);
        } else if (Files.isRegularFile(local)) {
            uploadFile(session, local, target, listener);
        }
    }

    private static void uploadFolder(SshSession session, Path local, String remote, Listener listener)
            throws IOException {
        check(listener);
        listener.item(remote + "/");
        ensureRemoteDir(session, remote);
        List<Path> children;
        try (Stream<Path> stream = Files.list(local)) {
            children = stream.sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
        }
        for (Path child : children) {
            if (Files.isSymbolicLink(child)) {
                continue;
            }
            String childRemote = join(remote, child.getFileName().toString());
            if (Files.isDirectory(child)) {
                uploadFolder(session, child, childRemote, listener);
            } else if (Files.isRegularFile(child)) {
                uploadFile(session, child, childRemote, listener);
            }
        }
    }

    private static void uploadFile(SshSession session, Path local, String remote, Listener listener)
            throws IOException {
        check(listener);
        listener.item(remote);
        long[] last = {0};
        try {
            session.sftpUploadFile(local, remote, total -> {
                listener.bytes(total - last[0]);
                last[0] = total;
                checkUnchecked(listener);
            });
        } catch (UncheckedCancel e) {
            try {
                session.delete(remote); // don't leave a partial file that looks complete
            } catch (IOException | RuntimeException ignored) {
                // best effort
            }
            throw new CancelledException();
        }
    }

    /** Downloads a remote file or folder into the local directory {@code localDir}. */
    public static void download(SshSession session, String remotePath, String name, boolean directory,
                                Path localDir, Listener listener) throws IOException {
        Path target = safeChild(localDir, name);
        if (directory) {
            downloadFolder(session, remotePath, target, listener);
        } else {
            downloadFile(session, remotePath, target, listener);
        }
    }

    private static void downloadFolder(SshSession session, String remote, Path local, Listener listener)
            throws IOException {
        check(listener);
        listener.item(remote + "/");
        Files.createDirectories(local);
        for (SftpEntry entry : session.sftpList(remote)) {
            Path child = safeChild(local, entry.name());
            String childRemote = join(remote, entry.name());
            if (entry.directory()) {
                downloadFolder(session, childRemote, child, listener);
            } else {
                downloadFile(session, childRemote, child, listener);
            }
        }
    }

    private static void downloadFile(SshSession session, String remote, Path local, Listener listener)
            throws IOException {
        check(listener);
        listener.item(remote);
        long[] last = {0};
        try {
            session.sftpDownloadFile(remote, local, total -> {
                listener.bytes(total - last[0]);
                last[0] = total;
                checkUnchecked(listener);
            });
        } catch (UncheckedCancel e) {
            Files.deleteIfExists(local);
            throw new CancelledException();
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(local); // don't leave a truncated file that looks complete
            throw e;
        }
    }

    /** Deletes a remote file, or a folder with everything in it. */
    public static void delete(SshSession session, String remotePath, boolean directory, Listener listener)
            throws IOException {
        check(listener);
        listener.item(remotePath);
        if (!directory) {
            session.delete(remotePath);
            return;
        }
        for (SftpEntry entry : session.sftpList(remotePath)) {
            delete(session, join(remotePath, entry.name()), entry.directory(), listener);
        }
        session.sftpRmdir(remotePath);
    }

    private static void ensureRemoteDir(SshSession session, String remote) throws IOException {
        try {
            session.sftpMkdir(remote);
        } catch (IOException e) {
            // Most likely it exists already; if it can be listed, carry on and merge into it.
            try {
                session.sftpList(remote);
            } catch (IOException notThere) {
                throw e;
            }
        }
    }

    /**
     * A path for {@code name} directly inside {@code dir}. Names come from the server, so anything
     * that could land outside {@code dir} (separators, "..", drive letters) is refused.
     */
    static Path safeChild(Path dir, String name) throws IOException {
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0
                || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0 || name.indexOf('\0') >= 0) {
            throw new IOException("Refusing to write a file called \"" + name + "\" to this computer.");
        }
        Path child = dir.resolve(name).normalize();
        if (!child.startsWith(dir.normalize())) {
            throw new IOException("Refusing to write a file called \"" + name + "\" to this computer.");
        }
        return child;
    }

    private static void check(Listener listener) throws CancelledException {
        if (listener.cancelled()) {
            throw new CancelledException();
        }
    }

    /** Thrown from inside a progress callback, which can't throw a checked exception. */
    private static final class UncheckedCancel extends RuntimeException {
        UncheckedCancel() {
            super("Cancelled", null, false, false);
        }
    }

    private static void checkUnchecked(Listener listener) {
        if (listener.cancelled()) {
            throw new UncheckedCancel();
        }
    }
}
