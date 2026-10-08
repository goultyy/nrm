package mt.su.nrm.cloudflare;

import java.util.List;

/**
 * A zone that was just added to the account. Until the domain's nameservers are changed at its registrar to
 * {@code nameServers}, Cloudflare keeps the zone {@code pending} and visitors are not affected.
 */
public record ZoneCreated(Zone zone, List<String> nameServers, String status) {
}
