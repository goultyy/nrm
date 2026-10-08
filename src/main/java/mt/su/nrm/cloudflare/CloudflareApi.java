package mt.su.nrm.cloudflare;

import java.util.List;

/**
 * What the app needs from Cloudflare. {@link CloudflareClient} is the real implementation; tests
 * use an in-memory one. Calls block, so callers must run them off the JavaFX thread.
 */
public interface CloudflareApi {

    List<Zone> verifyToken() throws CloudflareException;

    List<Zone> listZones() throws CloudflareException;

    List<Tunnel> listTunnels(String accountId) throws CloudflareException;

    TunnelConfig getTunnelConfig(String accountId, String tunnelId) throws CloudflareException;

    void putTunnelConfig(String accountId, String tunnelId, TunnelConfig config) throws CloudflareException;

    /** Adds a domain to the account. Takes effect on Cloudflare at once; there is nothing to stage. */
    ZoneCreated createZone(String accountId, String name) throws CloudflareException;

    /** Creates a tunnel whose routes are managed through Cloudflare (so they can be edited here). */
    TunnelCreated createTunnel(String accountId, String name) throws CloudflareException;

    /** Removes the zone and every DNS record in it from the account. Takes effect at once. */
    void deleteZone(String zoneId) throws CloudflareException;

    /** Deletes a tunnel. Cloudflare refuses while the tunnel still has connections (cloudflared running). */
    void deleteTunnel(String accountId, String tunnelId) throws CloudflareException;

    List<DnsRecord> listDnsRecords(String zoneId) throws CloudflareException;

    DnsRecord createDnsRecord(String zoneId, DnsRecord record) throws CloudflareException;

    DnsRecord updateDnsRecord(String zoneId, DnsRecord record) throws CloudflareException;

    void deleteDnsRecord(String zoneId, String recordId) throws CloudflareException;
}
