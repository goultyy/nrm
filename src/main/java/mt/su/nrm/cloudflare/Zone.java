package mt.su.nrm.cloudflare;

/** A DNS zone (a domain) in a Cloudflare account. */
public record Zone(String id, String name, String accountId) {
}
