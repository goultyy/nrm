package mt.su.nrm.cloudflare;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import mt.su.nrm.ssh.CommandLog;

/**
 * Everything the app knows about one server's Cloudflare account: the zones and tunnels the token can see, and the
 * {@link CloudflareWorkspace} holding what was read and what is staged. It outlives the screen, so staged changes
 * survive clicking elsewhere in the tree.
 * <p>
 * Instances are immutable. Adding a zone or tunnel gives a new session that shares the same workspace, so what is
 * staged is kept.
 */
public final class CloudflareSession {

    private final String token;
    private final CloudflareApi api;
    private final CloudflareWorkspace workspace;
    private final List<Zone> zones;
    private final List<Tunnel> tunnels;
    private final Map<String, String> accountOfTunnel;

    private CloudflareSession(String token, CloudflareApi api, CloudflareWorkspace workspace, List<Zone> zones,
            List<Tunnel> tunnels, Map<String, String> accountOfTunnel) {
        this.token = token;
        this.api = api;
        this.workspace = workspace;
        this.zones = List.copyOf(zones);
        this.tunnels = List.copyOf(tunnels);
        this.accountOfTunnel = Map.copyOf(accountOfTunnel);
    }

    /** Checks the token and reads the zones and tunnels it can see. Blocks; call off the FX thread. */
    public static CloudflareSession open(String token, CommandLog log) throws CloudflareException {
        return open(token, new CloudflareClient(token, log));
    }

    /** As {@link #open(String, CommandLog)}, over any implementation of the API (tests use an in-memory one). */
    public static CloudflareSession open(String token, CloudflareApi api) throws CloudflareException {
        List<Zone> zones = new ArrayList<>(api.verifyToken());
        zones.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));

        // A token that can't list tunnels still works for DNS, so that failure is not fatal.
        Set<String> accounts = new LinkedHashSet<>();
        zones.forEach(z -> accounts.add(z.accountId()));
        List<Tunnel> tunnels = new ArrayList<>();
        Map<String, String> accountOf = new LinkedHashMap<>();
        for (String account : accounts) {
            try {
                for (Tunnel t : api.listTunnels(account)) {
                    tunnels.add(t);
                    accountOf.put(t.id(), account);
                }
            } catch (CloudflareException e) {
                if (e.httpStatus() != 403) {
                    throw e;
                }
            }
        }
        tunnels.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return new CloudflareSession(token, api, new CloudflareWorkspace(api), zones, tunnels, accountOf);
    }

    /** True if this session was opened with the given token, so a changed token starts afresh. */
    public boolean usesToken(String other) {
        return Objects.equals(token, other == null ? null : other.trim());
    }

    public CloudflareWorkspace workspace() {
        return workspace;
    }

    public List<Zone> zones() {
        return zones;
    }

    public List<Tunnel> tunnels() {
        return tunnels;
    }

    public String accountOf(Tunnel tunnel) {
        return accountOfTunnel.get(tunnel.id());
    }

    // ---------------------------------------------------------------- accounts

    /**
     * The accounts the token's zones belong to. A new zone or tunnel needs an account, and the only way this app
     * learns of one is through a zone it can already see.
     */
    public List<String> accountIds() {
        Set<String> ids = new LinkedHashSet<>();
        zones.forEach(z -> ids.add(z.accountId()));
        return List.copyOf(ids);
    }

    /** A recognisable name for an account: the zones in it. */
    public String accountLabel(String accountId) {
        List<String> names = zones.stream().filter(z -> z.accountId().equals(accountId)).map(Zone::name).toList();
        String shown = names.size() > 3 ? String.join(", ", names.subList(0, 3)) + ", ..." : String.join(", ", names);
        return shown.isEmpty() ? accountId : shown;
    }

    // ---------------------------------------------------------------- adding things

    /** Adds the domain to the account on Cloudflare, now. Blocks; call off the FX thread. */
    public ZoneCreated createZone(String accountId, String name) throws CloudflareException {
        return api.createZone(accountId, name);
    }

    /** Creates the tunnel on Cloudflare, now. Blocks; call off the FX thread. */
    public TunnelCreated createTunnel(String accountId, String name) throws CloudflareException {
        return api.createTunnel(accountId, name);
    }

    /** Deletes the zone on Cloudflare, now. Blocks; call off the FX thread. */
    public void deleteZone(Zone zone) throws CloudflareException {
        api.deleteZone(zone.id());
    }

    /** Deletes the tunnel on Cloudflare, now. Blocks; call off the FX thread. */
    public void deleteTunnel(Tunnel tunnel) throws CloudflareException {
        String account = accountOf(tunnel);
        if (account == null) {
            throw new CloudflareException("The account of tunnel " + tunnel.name() + " is not known.");
        }
        api.deleteTunnel(account, tunnel.id());
    }

    /** This session without the zone, which is also forgotten by the workspace (it no longer exists). */
    public CloudflareSession withoutZone(Zone zone) {
        workspace.forget(zone);
        return new CloudflareSession(token, api, workspace, zones.stream().filter(z -> !z.id().equals(zone.id())).toList(),
                tunnels, accountOfTunnel);
    }

    /** This session without the tunnel, which is also forgotten by the workspace. */
    public CloudflareSession withoutTunnel(Tunnel tunnel) {
        workspace.forget(tunnel);
        Map<String, String> accounts = new LinkedHashMap<>(accountOfTunnel);
        accounts.remove(tunnel.id());
        return new CloudflareSession(token, api, workspace, zones,
                tunnels.stream().filter(t -> !t.id().equals(tunnel.id())).toList(), accounts);
    }

    /** This session with one more zone (the staged changes are shared, not copied). */
    public CloudflareSession withZone(Zone zone) {
        List<Zone> more = new ArrayList<>(zones);
        more.add(zone);
        more.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return new CloudflareSession(token, api, workspace, more, tunnels, accountOfTunnel);
    }

    /** This session with one more tunnel. */
    public CloudflareSession withTunnel(Tunnel tunnel, String accountId) {
        List<Tunnel> more = new ArrayList<>(tunnels);
        more.add(tunnel);
        more.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        Map<String, String> accounts = new LinkedHashMap<>(accountOfTunnel);
        accounts.put(tunnel.id(), accountId);
        return new CloudflareSession(token, api, workspace, zones, more, accounts);
    }
}
