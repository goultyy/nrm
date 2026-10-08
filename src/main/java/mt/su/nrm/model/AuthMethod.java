package mt.su.nrm.model;

/** How the app authenticates to the SSH server. */
public enum AuthMethod {
    PASSWORD("Password"),
    PRIVATE_KEY("Private key");

    private final String displayName;

    AuthMethod(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
