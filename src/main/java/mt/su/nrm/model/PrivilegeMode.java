package mt.su.nrm.model;

/** How the app gains the root rights needed to write nginx config and reload nginx. */
public enum PrivilegeMode {
    /** The SSH user is root; commands run as-is. */
    NONE("Connect as root (no sudo)"),
    /** Commands run through sudo, which asks for a password. */
    SUDO_PASSWORD("sudo with password"),
    /** Commands run through sudo configured with NOPASSWD. */
    SUDO_NOPASSWD("sudo without password");

    private final String displayName;

    PrivilegeMode(String displayName) {
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
