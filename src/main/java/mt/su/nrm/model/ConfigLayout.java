package mt.su.nrm.model;

/** Where a server keeps its virtual host files. Detected on connect and cached on the profile. */
public enum ConfigLayout {
    /** Debian/Ubuntu style: files in sites-available, enabled via symlinks in sites-enabled. */
    SITES_AVAILABLE("sites-available / sites-enabled"),
    /** RHEL/Alpine/upstream style: *.conf files in conf.d, enabled by existing. */
    CONF_D("conf.d"),
    /** Not detected yet, or neither layout was found. */
    UNKNOWN("Not yet detected");

    private final String displayName;

    ConfigLayout(String displayName) {
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
