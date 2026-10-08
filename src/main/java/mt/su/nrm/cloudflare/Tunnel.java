package mt.su.nrm.cloudflare;

/**
 * A Cloudflare Tunnel. {@code status} is Cloudflare's own word: healthy, degraded, down or
 * inactive.
 */
public record Tunnel(String id, String name, String status) {

    /** The hostname a proxied CNAME must point at to reach this tunnel. */
    public String cnameTarget() {
        return id + ".cfargotunnel.com";
    }
}
