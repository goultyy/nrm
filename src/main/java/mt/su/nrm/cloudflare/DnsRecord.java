package mt.su.nrm.cloudflare;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A DNS record in a Cloudflare zone. {@code id} is empty for a record not yet created.
 * {@code ttl} of {@value #TTL_AUTOMATIC} means automatic, which Cloudflare requires for proxied
 * records.
 */
public record DnsRecord(String id, String type, String name, String content, boolean proxied, int ttl,
        String comment) {

    public static final int TTL_AUTOMATIC = 1;

    /** A new record, not yet created, with automatic TTL and no comment. */
    public static DnsRecord of(String type, String name, String content, boolean proxied) {
        return new DnsRecord("", type, name, content, proxied, TTL_AUTOMATIC, "");
    }

    static DnsRecord fromJson(Object json) {
        Map<String, Object> o = Json.asObject(json);
        int ttl = o.get("ttl") instanceof Number n ? n.intValue() : TTL_AUTOMATIC;
        return new DnsRecord(Json.asString(o.get("id")), Json.asString(o.get("type")),
                Json.asString(o.get("name")), Json.asString(o.get("content")),
                Boolean.TRUE.equals(o.get("proxied")), ttl, Json.asString(o.get("comment")));
    }

    Map<String, Object> toJson() {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("type", type);
        o.put("name", name);
        o.put("content", content);
        o.put("ttl", ttl);
        o.put("proxied", proxied);
        o.put("comment", comment);
        return o;
    }
}
