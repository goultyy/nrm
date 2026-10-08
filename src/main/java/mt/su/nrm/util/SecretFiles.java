package mt.su.nrm.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;

/**
 * Writing a file that holds a secret (a private key) so that only the current user can read it. The file is created and
 * locked down <i>before</i> anything is written to it, so there is no moment when the key sits in a file others can open.
 */
public final class SecretFiles {

    private SecretFiles() {
    }

    /**
     * Replaces the file with one holding {@code content}, readable by its owner only where the file system can say so.
     *
     * @return true if access was restricted to the owner; false if the file system gave no way to do that (the content
     *         is still written, and the caller should tell the user)
     */
    public static boolean write(Path target, String content) throws IOException {
        Files.deleteIfExists(target);
        Files.createFile(target);
        boolean restricted = restrictToOwner(target);
        Files.writeString(target, content, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
        return restricted;
    }

    /** Owner-only access: a single ACL entry on Windows, mode 600 elsewhere. False if neither is possible. */
    static boolean restrictToOwner(Path file) {
        try {
            AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
            if (acl != null) {
                UserPrincipal owner = Files.getOwner(file);
                AclEntry only = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
                acl.setAcl(List.of(only));
                return true;
            }
            PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
            if (posix != null) {
                posix.setPermissions(PosixFilePermissions.fromString("rw-------"));
                return true;
            }
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            // Reported to the caller as "not restricted".
        }
        return false;
    }
}
