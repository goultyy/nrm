package mt.su.nrm.logformat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The variables the builder offers, grouped. nginx has many more (and modules add their own); anything not listed can
 * still be used, as a custom variable or a header, and is checked only for being a well-formed variable name.
 */
public final class LogFields {

    public static final String CLIENT = "Visitor";
    public static final String TIME = "Time";
    public static final String REQUEST = "Request";
    public static final String RESPONSE = "Response";
    public static final String UPSTREAM = "Proxied server";
    public static final String CONNECTION = "Connection and TLS";

    /** A variable name as nginx reads it: {@code $name}. */
    public static final Pattern VARIABLE = Pattern.compile("\\$[A-Za-z_][A-Za-z0-9_]*");
    /** A variable in a format string, with or without braces: {@code $name} or {@code ${name}}. */
    public static final Pattern IN_TEXT = Pattern.compile("\\$\\{[A-Za-z_][A-Za-z0-9_]*\\}|\\$[A-Za-z_][A-Za-z0-9_]*");

    private static final List<LogField> ALL = List.of(
            f("$remote_addr", "Visitor address", "The address the request came from. Behind a proxy this is the proxy's "
                    + "address, unless real visitor IP is set up.", "203.0.113.7", false, CLIENT, ""),
            f("$remote_user", "Signed-in user", "The user name from basic authentication, or - when nobody signed in.",
                    "-", false, CLIENT, ""),
            f("$remote_port", "Visitor port", "The port the visitor connected from.", "51234", true, CLIENT, ""),
            f("$realip_remote_addr", "Connecting address", "The address that actually connected, before real visitor IP "
                    + "replaced it with the visitor's.", "172.68.10.4", false, CLIENT,
                    "Needs the real-IP module (see Real visitor IP)."),
            f("$http_x_forwarded_for", "Forwarded-for header", "The X-Forwarded-For header a proxy added: the visitor and "
                    + "any proxies on the way.", "198.51.100.4, 10.0.0.2", false, CLIENT, ""),
            f("$http_cf_connecting_ip", "Cloudflare visitor header", "The visitor's address as Cloudflare reports it.",
                    "198.51.100.4", false, CLIENT, "Only present behind Cloudflare."),
            f("$http_x_real_ip", "X-Real-IP header", "The X-Real-IP header some proxies add.", "198.51.100.4", false,
                    CLIENT, ""),

            f("$time_local", "Time (common format)", "When the request finished, as in the standard log: "
                    + "07/Oct/2026:22:15:01 +0100.", "07/Oct/2026:22:15:01 +0100", false, TIME, ""),
            f("$time_iso8601", "Time (ISO 8601)", "When the request finished, in a form other tools read easily.",
                    "2026-10-07T22:15:01+01:00", false, TIME, ""),
            f("$msec", "Time (seconds since 1970)", "When the request finished, in seconds with milliseconds.",
                    "1791407701.123", true, TIME, ""),
            f("$request_time", "Time taken", "How long the whole request took, in seconds.", "0.043", true, TIME, ""),

            f("$request", "Request line", "The method, address and protocol: GET /index.html HTTP/1.1.",
                    "GET /index.html?id=7 HTTP/1.1", false, REQUEST, ""),
            f("$request_method", "Method", "GET, POST and so on.", "GET", false, REQUEST, ""),
            f("$request_uri", "Address with query", "The address requested, including anything after the ?.",
                    "/index.html?id=7", false, REQUEST, ""),
            f("$uri", "Path", "The path, after nginx has tidied it (and after any internal redirect).",
                    "/index.html", false, REQUEST, ""),
            f("$args", "Query string", "What follows the ? in the address.", "id=7", false, REQUEST, ""),
            f("$scheme", "http or https", "Whether the visit used HTTPS.", "https", false, REQUEST, ""),
            f("$server_protocol", "Protocol", "HTTP/1.0, HTTP/1.1, HTTP/2.0 and so on.", "HTTP/1.1", false, REQUEST, ""),
            f("$host", "Host name", "The site name the visitor asked for.", "example.com", false, REQUEST, ""),
            f("$http_host", "Host header", "The Host header exactly as sent.", "example.com", false, REQUEST, ""),
            f("$server_name", "Server name (configured)", "The server_name of the site that answered.", "example.com",
                    false, REQUEST, ""),
            f("$server_port", "Server port", "The port the visitor connected to.", "443", true, REQUEST, ""),
            f("$request_length", "Request size", "How many bytes the visitor sent, headers included.", "512", true,
                    REQUEST, ""),
            f("$request_id", "Request id", "A random id for this request, to follow it through logs.",
                    "a1b2c3d4e5f60718293a4b5c6d7e8f90", false, REQUEST, "Needs nginx 1.11.0 or newer."),
            f("$http_referer", "Referrer", "The page the visitor came from, if their browser said.",
                    "https://example.org/start", false, REQUEST, ""),
            f("$http_user_agent", "Browser (User-Agent)", "What the visitor's browser or tool calls itself.",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Firefox/131.0", false, REQUEST, ""),

            f("$status", "Response status", "The status code: 200, 404, 502...", "200", true, RESPONSE, ""),
            f("$body_bytes_sent", "Bytes sent (body)", "How many bytes of content were sent, headers not counted.",
                    "1234", true, RESPONSE, ""),
            f("$bytes_sent", "Bytes sent (all)", "How many bytes were sent in total.", "1459", true, RESPONSE, ""),
            f("$sent_http_content_type", "Response content type", "The Content-Type of the reply.", "text/html",
                    false, RESPONSE, ""),
            f("$gzip_ratio", "Compression ratio", "How much smaller gzip made the reply, or - if it didn't.", "3.20",
                    false, RESPONSE, "Needs the gzip module."),

            f("$upstream_addr", "Proxied server address", "The address of the server the request was passed to.",
                    "10.0.0.5:8080", false, UPSTREAM, ""),
            f("$upstream_status", "Proxied server status", "The status code the proxied server answered with.", "200",
                    false, UPSTREAM, ""),
            f("$upstream_response_time", "Proxied server time", "How long the proxied server took to answer, in "
                    + "seconds. - when nothing was proxied.", "0.040", false, UPSTREAM, ""),
            f("$upstream_connect_time", "Time to connect", "How long connecting to the proxied server took.", "0.001",
                    false, UPSTREAM, ""),
            f("$upstream_header_time", "Time to first reply", "How long until the proxied server sent its headers.",
                    "0.038", false, UPSTREAM, ""),
            f("$upstream_cache_status", "Cache result", "HIT, MISS, EXPIRED, BYPASS and so on, for cached replies.",
                    "HIT", false, UPSTREAM, ""),

            f("$connection", "Connection number", "A serial number for the connection.", "1234", true, CONNECTION, ""),
            f("$connection_requests", "Requests on this connection", "How many requests this connection has carried.",
                    "3", true, CONNECTION, ""),
            f("$ssl_protocol", "TLS version", "TLSv1.2, TLSv1.3...", "TLSv1.3", false, CONNECTION, "Only on HTTPS."),
            f("$ssl_cipher", "TLS cipher", "The cipher suite in use.", "TLS_AES_256_GCM_SHA384", false, CONNECTION,
                    "Only on HTTPS."),
            f("$pipe", "Pipelined", "p if the request was pipelined, otherwise a dot.", ".", false, CONNECTION, ""),
            f("$hostname", "Server's own name", "The host name of the machine nginx runs on.", "web1", false,
                    CONNECTION, ""));

    private static final Map<String, LogField> BY_VARIABLE = new LinkedHashMap<>();

    static {
        for (LogField field : ALL) {
            BY_VARIABLE.put(field.variable(), field);
        }
    }

    private LogFields() {
    }

    private static LogField f(String variable, String title, String description, String sample, boolean numeric,
                              String category, String note) {
        return new LogField(variable, title, description, sample, numeric, category, note);
    }

    public static List<LogField> all() {
        return ALL;
    }

    /** The groups, in the order they are listed. */
    public static List<String> categories() {
        return List.of(CLIENT, TIME, REQUEST, RESPONSE, UPSTREAM, CONNECTION);
    }

    /** The field for a variable such as {@code $status} or {@code ${status}}, if it is one the builder knows. */
    public static Optional<LogField> find(String variable) {
        return Optional.ofNullable(BY_VARIABLE.get(plain(variable)));
    }

    /** {@code ${name}} written as {@code $name}. */
    public static String plain(String variable) {
        if (variable.startsWith("${") && variable.endsWith("}")) {
            return "$" + variable.substring(2, variable.length() - 1);
        }
        return variable;
    }

    /** A sample value for the preview: from the catalog, or a stand-in that shows what the variable is. */
    public static String sample(String variable) {
        String plain = plain(variable);
        LogField known = BY_VARIABLE.get(plain);
        if (known != null) {
            return known.sample();
        }
        if (plain.startsWith("$http_") || plain.startsWith("$sent_http_") || plain.startsWith("$upstream_http_")) {
            return "(header value)";
        }
        if (plain.startsWith("$cookie_") || plain.startsWith("$arg_")) {
            return "(value)";
        }
        return "(" + plain + ")";
    }

    /** True if the variable is well formed, whether or not it is one the catalog lists. */
    public static boolean isWellFormed(String variable) {
        return VARIABLE.matcher(plain(variable)).matches();
    }

    /** The variable for a request header: {@code X-Request-Id} becomes {@code $http_x_request_id}. */
    public static String requestHeader(String headerName) {
        return "$http_" + headerName.strip().toLowerCase(java.util.Locale.ROOT).replace('-', '_');
    }

    /** Splits a format string into its plain text and its variables, in order. */
    public static List<String[]> tokens(String text) {
        List<String[]> parts = new ArrayList<>();
        Matcher m = IN_TEXT.matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                parts.add(new String[] {"text", text.substring(last, m.start())});
            }
            parts.add(new String[] {"variable", m.group()});
            last = m.end();
        }
        if (last < text.length()) {
            parts.add(new String[] {"text", text.substring(last)});
        }
        return parts;
    }
}
