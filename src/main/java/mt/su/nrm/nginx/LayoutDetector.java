package mt.su.nrm.nginx;

import mt.su.nrm.model.ConfigLayout;

import java.util.List;

/**
 * Works out where a server keeps its virtual host files, from the {@code include} lines of its main
 * configuration, and where new files should go.
 */
public final class LayoutDetector {

    private LayoutDetector() {
    }

    /** Debian style if the main config includes sites-enabled, conf.d style if it includes conf.d, else unknown. */
    public static ConfigLayout detect(ConfigFile mainConfig) {
        return detect(mainConfig.includePatterns());
    }

    public static ConfigLayout detect(List<String> includePatterns) {
        for (String pattern : includePatterns) {
            if (pattern.contains("sites-enabled")) {
                return ConfigLayout.SITES_AVAILABLE;
            }
        }
        for (String pattern : includePatterns) {
            if (pattern.contains("conf.d")) {
                return ConfigLayout.CONF_D;
            }
        }
        return ConfigLayout.UNKNOWN;
    }

    /** The folder nginx reads virtual host files from. */
    public static String enabledDir(String confDir, ConfigLayout layout) {
        return layout == ConfigLayout.SITES_AVAILABLE ? join(confDir, "sites-enabled") : join(confDir, "conf.d");
    }

    /** The folder new files are written to (for conf.d style this is the same as the enabled folder). */
    public static String availableDir(String confDir, ConfigLayout layout) {
        return layout == ConfigLayout.SITES_AVAILABLE ? join(confDir, "sites-available") : join(confDir, "conf.d");
    }

    /** The path a new virtual host file should be written to. */
    public static String newFilePath(String confDir, ConfigLayout layout, String serverName) {
        String base = safeFileName(serverName);
        return join(availableDir(confDir, layout), layout == ConfigLayout.CONF_D ? base + ".conf" : base);
    }

    /** For sites-available style: the symlink that turns the file on, otherwise null. */
    public static String enabledLinkPath(String confDir, ConfigLayout layout, String serverName) {
        return layout == ConfigLayout.SITES_AVAILABLE
                ? join(enabledDir(confDir, layout), safeFileName(serverName)) : null;
    }

    /** A file name made only of letters, digits, dots, dashes and underscores. */
    public static String safeFileName(String serverName) {
        String cleaned = serverName == null ? "" : serverName.replaceAll("[^A-Za-z0-9._-]", "_");
        cleaned = cleaned.replaceAll("^[._-]+", "");
        return cleaned.isEmpty() ? "site" : cleaned;
    }

    private static String join(String dir, String name) {
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }
}
