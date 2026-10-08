package mt.su.nrm.config;

/** A problem reading or writing the profile store, with a kind the UI can act on. */
public final class ProfileStoreException extends Exception {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        /** Disk or permission problem. Retrying may help. */
        IO,
        /** The key could not be unlocked: wrong passphrase, or a different Windows user or machine. */
        CANNOT_UNLOCK,
        /** The file is corrupt, truncated, or was modified. A backup may be available. */
        DAMAGED,
        /** Written by a newer version of the app. */
        UNSUPPORTED_VERSION,
        /** Protected with a different key protector than the one this app is using. */
        PROTECTOR_MISMATCH
    }

    private final Kind kind;

    public ProfileStoreException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public ProfileStoreException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
