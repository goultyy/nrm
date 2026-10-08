package mt.su.nrm.proxy;

import mt.su.nrm.cloudflare.Json;
import mt.su.nrm.ssh.CommandLog;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Cloudflare's published address ranges. The list is public: no account or token is involved. The request is written
 * to the command log like every other call. Every entry is checked before it is returned, because these values end
 * up in the nginx configuration.
 */
public final class CloudflareRanges {

    public static final URI DEFAULT_URL = URI.create("https://api.cloudflare.com/client/v4/ips");

    private CloudflareRanges() {
    }

    public static List<String> fetch(CommandLog log) throws IOException {
        return fetch(DEFAULT_URL, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(), log);
    }

    public static List<String> fetch(URI url, HttpClient http, CommandLog log) throws IOException {
        log.log(CommandLog.Kind.COMMAND, "Cloudflare GET " + url);
        HttpResponse<String> response;
        try {
            response = http.send(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Reading Cloudflare's address ranges was interrupted.", e);
        } catch (IOException e) {
            log.log(CommandLog.Kind.STATUS, "Cloudflare request failed: " + e.getMessage());
            throw new IOException("Could not reach Cloudflare to read its address ranges: " + e.getMessage(), e);
        }
        log.log(CommandLog.Kind.STATUS, "Cloudflare replied HTTP " + response.statusCode());
        log.log(CommandLog.Kind.OUTPUT, response.body());
        return parse(response.statusCode(), response.body());
    }

    /** Reads Cloudflare's reply; throws if it is not the list, or if anything in it is not an address or range. */
    static List<String> parse(int status, String body) throws IOException {
        Map<String, Object> envelope;
        try {
            envelope = Json.asObject(Json.parse(body));
        } catch (IllegalArgumentException e) {
            throw new IOException("Cloudflare's address list was not valid JSON (HTTP " + status + ").", e);
        }
        if (status != 200 || !Boolean.TRUE.equals(envelope.get("success"))) {
            throw new IOException("Cloudflare did not give its address list (HTTP " + status + ").");
        }
        Map<String, Object> result = Json.asObject(envelope.get("result"));
        List<String> ranges = new ArrayList<>();
        for (String key : List.of("ipv4_cidrs", "ipv6_cidrs")) {
            for (Object item : Json.asList(result.get(key))) {
                String range = Json.asString(item).strip();
                if (!mt.su.nrm.nginx.RealIp.validSource(range)) {
                    throw new IOException("Cloudflare's list contains something that is not an address or range: \""
                            + range + "\". Nothing was used.");
                }
                ranges.add(range);
            }
        }
        if (ranges.isEmpty()) {
            throw new IOException("Cloudflare's address list was empty.");
        }
        return List.copyOf(ranges);
    }
}
