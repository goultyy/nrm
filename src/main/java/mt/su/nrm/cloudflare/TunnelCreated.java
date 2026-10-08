package mt.su.nrm.cloudflare;

/**
 * A tunnel that was just created, with the token {@code cloudflared} needs to run it. The token is a secret: anyone
 * holding it can run the tunnel, so it is never part of {@link #toString()}.
 */
public record TunnelCreated(Tunnel tunnel, String accountId, String token) {

    /** The command that installs and starts the tunnel as a service on a Linux server. */
    public String installCommand() {
        return "sudo cloudflared service install " + token;
    }

    @Override
    public String toString() {
        return "TunnelCreated[tunnel=" + tunnel + ", token=(hidden)]";
    }
}
