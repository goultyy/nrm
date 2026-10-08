package mt.su.nrm.logformat;

import mt.su.nrm.nginx.LogFormatSettings;

import java.util.List;
import java.util.function.Supplier;

/** Starting points for the builder. Each makes a fresh design, so editing one never changes the next. */
public final class LogFormatPresets {

    /** The text of nginx's built-in {@code combined} format. */
    public static final String COMBINED_TEXT = "$remote_addr - $remote_user [$time_local] \"$request\" $status "
            + "$body_bytes_sent \"$http_referer\" \"$http_user_agent\"";

    public record Preset(String id, String name, String description, Supplier<LogFormatDesign> design) {
        @Override
        public String toString() {
            return name;
        }
    }

    private LogFormatPresets() {
    }

    public static List<Preset> all() {
        return List.of(
                text("combined", "Standard (combined)", "nginx's own standard format, which most log tools understand.",
                        COMBINED_TEXT),
                text("timing", "Standard with timings", "The standard format plus how long the request and the proxied "
                        + "server took. Useful to find slow pages.", COMBINED_TEXT
                        + " rt=$request_time uct=\"$upstream_connect_time\" uht=\"$upstream_header_time\" "
                        + "urt=\"$upstream_response_time\""),
                text("proxy", "Behind a proxy", "The standard format plus the X-Forwarded-For header, to see the visitor "
                        + "when nginx sits behind another proxy.", "$remote_addr ($http_x_forwarded_for) - $remote_user "
                        + "[$time_local] \"$request\" $status $body_bytes_sent \"$http_referer\" \"$http_user_agent\""),
                text("cache", "With cache result", "The standard format plus whether the reply came from the cache.",
                        COMBINED_TEXT + " cache=$upstream_cache_status"),
                text("minimal", "Minimal", "Who, when, what and the result. Short lines for busy sites.",
                        "$remote_addr [$time_local] \"$request\" $status"),
                new Preset("json", "JSON (one object per line)", "Every line is a JSON object, for log systems that read "
                        + "JSON, such as Loki, Elasticsearch or jq.", LogFormatPresets::json),
                new Preset("blank", "Start empty", "Pick every field yourself.", LogFormatDesign::blank));
    }

    private static Preset text(String id, String name, String description, String text) {
        return new Preset(id, name, description, () -> {
            LogFormatSettings s = new LogFormatSettings();
            s.text = text;
            return LogFormatDesign.from(s);
        });
    }

    private static LogFormatDesign json() {
        LogFormatDesign d = LogFormatDesign.blank();
        d.style(LogFormatDesign.Style.JSON);
        String[][] rows = {{"$time_iso8601", "time"}, {"$remote_addr", "remote_addr"}, {"$host", "host"},
                {"$request_method", "method"}, {"$request_uri", "uri"}, {"$status", "status"},
                {"$body_bytes_sent", "bytes"}, {"$request_time", "request_time"}, {"$http_referer", "referrer"},
                {"$http_user_agent", "user_agent"}};
        for (String[] row : rows) {
            d.elements().add(LogFormatDesign.Element.field(row[0]).withKey(row[1]));
        }
        return d;
    }
}
