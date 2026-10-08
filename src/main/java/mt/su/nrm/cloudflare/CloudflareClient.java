package mt.su.nrm.cloudflare;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import mt.su.nrm.ssh.CommandLog;

/**
 * The only class that talks to the Cloudflare API. Every request and reply is written to the
 * {@link CommandLog} first, with the API token masked, so the command log panel's promise that
 * nothing the app sends is hidden holds for Cloudflare as well as for SSH.
 * <p>
 * Calls block, so callers must run them off the JavaFX thread.
 */
public class CloudflareClient implements CloudflareApi {

    public static final URI DEFAULT_BASE = URI.create("https://api.cloudflare.com/client/v4");

    private static final int PAGE_SIZE = 50;

    private final URI base;
    private final String token;
    private final CommandLog log;
    private final HttpClient http;

    public CloudflareClient(String token, CommandLog log) {
        this(token, DEFAULT_BASE, log, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());
    }

    public CloudflareClient(String token, URI base, CommandLog log, HttpClient http) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("A Cloudflare API token is required");
        }
        this.base = base;
        this.token = token.trim();
        this.log = log;
        this.http = http;
        // Registered before any request, so the token can't reach the log even if echoed back.
        log.addSecret(this.token);
    }

    /**
     * Checks that the token works and can see at least one zone, and returns the zones. Listing
     * zones is used instead of Cloudflare's verify endpoint because that one only exists for
     * user-owned tokens, while this works for both kinds.
     */
    public List<Zone> verifyToken() throws CloudflareException {
        List<Zone> zones = listZones();
        if (zones.isEmpty()) {
            throw new CloudflareException("The API token works but cannot see any zones. "
                    + "Give it Zone: Read on the zones you want to manage.");
        }
        return zones;
    }

    public List<Zone> listZones() throws CloudflareException {
        List<Zone> zones = new ArrayList<>();
        for (Object item : getAll("/zones")) {
            Map<String, Object> zone = Json.asObject(item);
            Map<String, Object> account = Json.asObject(zone.get("account"));
            zones.add(new Zone(Json.asString(zone.get("id")), Json.asString(zone.get("name")),
                    Json.asString(account.get("id"))));
        }
        return zones;
    }

    public List<Tunnel> listTunnels(String accountId) throws CloudflareException {
        List<Tunnel> tunnels = new ArrayList<>();
        for (Object item : getAll("/accounts/" + accountId + "/cfd_tunnel?is_deleted=false")) {
            Map<String, Object> tunnel = Json.asObject(item);
            tunnels.add(new Tunnel(Json.asString(tunnel.get("id")), Json.asString(tunnel.get("name")),
                    Json.asString(tunnel.get("status"))));
        }
        return tunnels;
    }

    @Override
    public ZoneCreated createZone(String accountId, String name) throws CloudflareException {
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("id", accountId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("account", account);
        body.put("type", "full");
        Map<String, Object> zone = Json.asObject(send("POST", "/zones", body).result());
        List<String> nameServers = new ArrayList<>();
        for (Object ns : Json.asList(zone.get("name_servers"))) {
            nameServers.add(Json.asString(ns));
        }
        String owner = Json.asString(Json.asObject(zone.get("account")).get("id"));
        return new ZoneCreated(new Zone(Json.asString(zone.get("id")), Json.asString(zone.get("name")),
                owner.isEmpty() ? accountId : owner), List.copyOf(nameServers), Json.asString(zone.get("status")));
    }

    @Override
    public TunnelCreated createTunnel(String accountId, String name) throws CloudflareException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("config_src", "cloudflare");
        Map<String, Object> tunnel = Json.asObject(send("POST", "/accounts/" + accountId + "/cfd_tunnel", body).result());
        String id = Json.asString(tunnel.get("id"));
        String token = Json.asString(tunnel.get("token"));
        if (token.isEmpty()) {
            // Not every reply carries the token; it can always be asked for.
            token = Json.asString(send("GET", "/accounts/" + accountId + "/cfd_tunnel/" + id + "/token", null).result());
        }
        String status = Json.asString(tunnel.get("status"));
        return new TunnelCreated(new Tunnel(id, Json.asString(tunnel.get("name")), status.isEmpty() ? "inactive" : status),
                accountId, token);
    }

    @Override
    public void deleteZone(String zoneId) throws CloudflareException {
        if (zoneId == null || zoneId.isBlank()) {
            throw new IllegalArgumentException("A zone id is required");
        }
        send("DELETE", "/zones/" + zoneId, null);
    }

    @Override
    public void deleteTunnel(String accountId, String tunnelId) throws CloudflareException {
        if (accountId == null || accountId.isBlank() || tunnelId == null || tunnelId.isBlank()) {
            throw new IllegalArgumentException("An account id and a tunnel id are required");
        }
        send("DELETE", "/accounts/" + accountId + "/cfd_tunnel/" + tunnelId, null);
    }

    public TunnelConfig getTunnelConfig(String accountId, String tunnelId) throws CloudflareException {
        return TunnelConfig.fromReply(send("GET", tunnelConfigPath(accountId, tunnelId), null).result());
    }

    /**
     * Replaces the tunnel's whole configuration. Read it with {@link #getTunnelConfig} and edit that,
     * so nothing Cloudflare holds is dropped.
     */
    public void putTunnelConfig(String accountId, String tunnelId, TunnelConfig config)
            throws CloudflareException {
        if (!config.isRemotelyManaged()) {
            throw new CloudflareException("This tunnel is configured by a file on the server, so its routes "
                    + "cannot be changed from here.");
        }
        send("PUT", tunnelConfigPath(accountId, tunnelId), config.toRequestBody());
    }

    private static String tunnelConfigPath(String accountId, String tunnelId) {
        return "/accounts/" + accountId + "/cfd_tunnel/" + tunnelId + "/configurations";
    }

    /** Every DNS record in the zone. */
    public List<DnsRecord> listDnsRecords(String zoneId) throws CloudflareException {
        List<DnsRecord> records = new ArrayList<>();
        for (Object item : getAll("/zones/" + zoneId + "/dns_records")) {
            records.add(DnsRecord.fromJson(item));
        }
        return records;
    }

    /** Creates the record and returns it as Cloudflare stored it, with its id. */
    public DnsRecord createDnsRecord(String zoneId, DnsRecord record) throws CloudflareException {
        Object result = send("POST", "/zones/" + zoneId + "/dns_records", record.toJson()).result();
        return DnsRecord.fromJson(result);
    }

    /** Changes the record with {@code record.id()}; fields not modelled here are left as they are. */
    public DnsRecord updateDnsRecord(String zoneId, DnsRecord record) throws CloudflareException {
        if (record.id().isEmpty()) {
            throw new IllegalArgumentException("The record has no id; create it first");
        }
        Object result = send("PATCH", "/zones/" + zoneId + "/dns_records/" + record.id(), record.toJson())
                .result();
        return DnsRecord.fromJson(result);
    }

    public void deleteDnsRecord(String zoneId, String recordId) throws CloudflareException {
        if (recordId == null || recordId.isBlank()) {
            throw new IllegalArgumentException("A record id is required");
        }
        send("DELETE", "/zones/" + zoneId + "/dns_records/" + recordId, null);
    }

    /** Follows Cloudflare's pagination and returns every item of a list endpoint. */
    List<Object> getAll(String path) throws CloudflareException {
        List<Object> items = new ArrayList<>();
        String separator = path.contains("?") ? "&" : "?";
        int page = 1;
        while (true) {
            Reply reply = send("GET", path + separator + "per_page=" + PAGE_SIZE + "&page=" + page, null);
            items.addAll(Json.asList(reply.result()));
            Object totalPages = reply.resultInfo().get("total_pages");
            int pages = totalPages instanceof Number n ? n.intValue() : 1;
            if (page >= pages) {
                return items;
            }
            page++;
        }
    }

    Reply send(String method, String path, Object body) throws CloudflareException {
        URI uri = URI.create(base + path);
        String payload = body == null ? null : Json.write(body);

        log.log(CommandLog.Kind.COMMAND, "Cloudflare " + method + " " + uri);
        if (payload != null) {
            log.log(CommandLog.Kind.COMMAND, payload);
        }

        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
        if (payload != null) {
            request.header("Content-Type", "application/json");
            request.method(method, HttpRequest.BodyPublishers.ofString(payload));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }

        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            log.log(CommandLog.Kind.STATUS, "Cloudflare request failed: " + e.getMessage());
            throw new CloudflareException("Could not reach Cloudflare: " + log.mask(String.valueOf(e.getMessage())),
                    0, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CloudflareException("The Cloudflare request was interrupted.", 0, e);
        }

        int status = response.statusCode();
        // A tunnel's run token comes back in a reply; it must be masked before the reply is written to the log.
        hideTokens(path, response.body());
        log.log(CommandLog.Kind.STATUS, "Cloudflare replied HTTP " + status);
        log.log(CommandLog.Kind.OUTPUT, response.body());

        Map<String, Object> envelope;
        try {
            envelope = Json.asObject(Json.parse(response.body()));
        } catch (IllegalArgumentException e) {
            throw new CloudflareException("Cloudflare sent a reply that is not valid JSON (HTTP " + status + ").",
                    status, e);
        }

        boolean success = Boolean.TRUE.equals(envelope.get("success"));
        if (!success || status >= 400) {
            throw new CloudflareException(describeErrors(envelope, status), status, null);
        }
        return new Reply(envelope.get("result"), Json.asObject(envelope.get("result_info")));
    }

    /**
     * Registers any tunnel run token in the reply as a secret: a {@code token} field of the result (creating a
     * tunnel) or the result itself for a {@code .../token} call.
     */
    private void hideTokens(String path, String body) {
        try {
            Object result = Json.asObject(Json.parse(body)).get("result");
            if (result instanceof String s && path.endsWith("/token")) {
                log.addSecret(s);
            } else if (result instanceof Map<?, ?> m && m.get("token") instanceof String s) {
                log.addSecret(s);
            }
        } catch (IllegalArgumentException notJson) {
            // Reported properly by the caller.
        }
    }

    private String describeErrors(Map<String, Object> envelope, int status) {
        List<String> messages = new ArrayList<>();
        for (Object error : Json.asList(envelope.get("errors"))) {
            Map<String, Object> e = Json.asObject(error);
            String message = Json.asString(e.get("message"));
            if (!message.isEmpty()) {
                messages.add(message);
            }
        }
        String detail = messages.isEmpty() ? "no details given" : String.join("; ", messages);
        String hint = switch (status) {
            case 401 -> " Check that the API token is correct and has not expired.";
            case 403 -> " The token lacks a permission this action needs.";
            default -> "";
        };
        return log.mask("Cloudflare refused the request (HTTP " + status + "): " + detail + "." + hint);
    }

    record Reply(Object result, Map<String, Object> resultInfo) {
    }
}
