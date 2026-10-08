package mt.su.nrm.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Cached result of checking for a remote tool such as openssl or certbot.
 * A profile holds null until the tool has been checked for the first time.
 *
 * @param available whether the tool was found
 * @param version   version string reported by the tool, or null
 * @param path      resolved path on the server, or null
 * @param checkedAt when the check ran
 */
public record ToolStatus(boolean available, String version, String path, Instant checkedAt) {

    public ToolStatus {
        Objects.requireNonNull(checkedAt, "checkedAt");
    }

    public static ToolStatus found(String version, String path, Instant checkedAt) {
        return new ToolStatus(true, version, path, checkedAt);
    }

    public static ToolStatus missing(Instant checkedAt) {
        return new ToolStatus(false, null, null, checkedAt);
    }
}
