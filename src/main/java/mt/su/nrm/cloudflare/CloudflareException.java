package mt.su.nrm.cloudflare;

/**
 * A Cloudflare call that failed: a network problem, an HTTP error, or {@code success: false} in
 * the API's reply. The message is safe to show to the user and never contains the token.
 */
public class CloudflareException extends Exception {

    private final int httpStatus;

    public CloudflareException(String message) {
        this(message, 0, null);
    }

    public CloudflareException(String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    /** The HTTP status, or 0 when no reply was received. */
    public int httpStatus() {
        return httpStatus;
    }
}
