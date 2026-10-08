package mt.su.nrm.ssh;

import java.io.IOException;

/** The server presented a different host key than the one pinned on the profile. */
public final class HostKeyChangedException extends IOException {

    private static final long serialVersionUID = 1L;

    private final String pinned;
    private final String presented;
    private final String keyType;
    private final boolean gateway;
    private final String host;

    public HostKeyChangedException(String host, String pinned, String presented) {
        this(host, pinned, presented, "");
    }

    public HostKeyChangedException(String host, String pinned, String presented, String keyType) {
        this(host, pinned, presented, keyType, false);
    }

    /** @param gateway true if the key that changed belongs to the SSH gateway rather than the server itself */
    public HostKeyChangedException(String host, String pinned, String presented, String keyType, boolean gateway) {
        super("WARNING: the host key of " + (gateway ? "the gateway " : "") + host + " has changed.\n"
                + "Pinned:    " + pinned + "\n"
                + "Presented: " + presented + "\n"
                + "This could mean the server was reinstalled, or that someone is intercepting the connection. "
                + "The connection was refused. If you are sure the change is legitimate, clear the pinned key "
                + "for this server.");
        this.pinned = pinned;
        this.presented = presented;
        this.keyType = keyType == null ? "" : keyType;
        this.gateway = gateway;
        this.host = host;
    }

    /** True if the changed key is the gateway's, so the fix is to re-pin the gateway, not the server. */
    public boolean isGateway() {
        return gateway;
    }

    /** The host whose key changed. */
    public String host() {
        return host;
    }

    /** The kind of key the server now presents, e.g. {@code ssh-ed25519}, or "" if unknown. */
    public String keyType() {
        return keyType;
    }

    public String pinned() {
        return pinned;
    }

    public String presented() {
        return presented;
    }
}
