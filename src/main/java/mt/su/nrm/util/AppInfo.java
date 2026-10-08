package mt.su.nrm.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** The application's own facts, filled in at build time. */
public final class AppInfo {

    public static final String NAME = "NRM";

    private AppInfo() {
    }

    /** The version from the build, or "development" when running from unpackaged classes. */
    public static String version() {
        try (InputStream in = AppInfo.class.getResourceAsStream("/app.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                String v = p.getProperty("version", "");
                if (!v.isBlank() && !v.startsWith("${")) {
                    return v;
                }
            }
        } catch (IOException e) {
            // Fall through to the default.
        }
        return "development";
    }
}
