package mt.su.nrm.logs;

import mt.su.nrm.cloudflare.Json;
import mt.su.nrm.logformat.LogFields;
import mt.su.nrm.logformat.LogFormatPresets;
import mt.su.nrm.nginx.LogFormatSettings;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns access log lines into {@link LogEntry}s. A parser is built from a {@code log_format}: the format's text becomes
 * a pattern, so lines written by any of the server's own formats (including ones made in Log Formats) can be read.
 * {@link #auto()} copes with the usual cases without being told: JSON lines, and nginx's standard {@code combined}
 * format, optionally followed by extra {@code name=value} items.
 * <p>
 * Lines that don't fit return empty and are counted as unreadable by the analysis, never guessed at.
 */
public class LogParser {

    private static final DateTimeFormatter TIME_LOCAL =
            DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH);

    /** Tighter patterns for variables whose shape is known; anything else is matched loosely up to the next literal. */
    private static final Map<String, String> SHAPES = Map.of(
            "$remote_addr", "(\\S+)",
            "$status", "(\\d{3})",
            "$body_bytes_sent", "(\\d+|-)",
            "$bytes_sent", "(\\d+|-)",
            "$request_time", "([0-9.]+|-)",
            "$request_method", "(\\S+)",
            "$time_iso8601", "(\\S+)",
            "$time_local", "(\\d{2}/\\w{3}/\\d{4}:\\d{2}:\\d{2}:\\d{2} [+-]\\d{4})");

    private final List<String> variables;
    private final Pattern pattern;
    private final boolean jsonLines;

    private LogParser(List<String> variables, Pattern pattern, boolean jsonLines) {
        this.variables = variables;
        this.pattern = pattern;
        this.jsonLines = jsonLines;
    }

    // ---------------------------------------------------------------- building parsers

    /** A parser for lines written with this format. A JSON format is read by its keys, whatever they are called. */
    public static LogParser forFormat(LogFormatSettings format) {
        if (format.escape == LogFormatSettings.Escape.JSON && format.text.strip().startsWith("{")) {
            return new LogParser(List.of(), null, true);
        }
        return build(format.text, true);
    }

    /** nginx's standard format. Extra text after it (such as {@code rt=0.2}) is allowed. */
    public static LogParser combined() {
        return build(LogFormatPresets.COMBINED_TEXT, false);
    }

    /** JSON lines when a line starts with a brace, otherwise {@code combined}. */
    public static LogParser auto() {
        return new Auto();
    }

    private static LogParser build(String text, boolean strictEnd) {
        List<String> variables = new ArrayList<>();
        StringBuilder regex = new StringBuilder("^");
        for (String[] token : LogFields.tokens(text)) {
            if (token[0].equals("variable")) {
                String variable = LogFields.plain(token[1]);
                variables.add(variable);
                regex.append(SHAPES.getOrDefault(variable, "(.*?)"));
            } else {
                regex.append(Pattern.quote(token[1]));
            }
        }
        regex.append(strictEnd ? "$" : "(?:\\s.*)?$");
        return new LogParser(variables, Pattern.compile(regex.toString()), false);
    }

    /** Picks JSON or combined per line, so a log that changed format part way through still reads. */
    private static final class Auto extends LogParser {
        private final LogParser json = new LogParser(List.of(), null, true);
        private final LogParser combined = combined();

        Auto() {
            super(List.of(), null, false);
        }

        @Override
        public Optional<LogEntry> parse(String line) {
            return line.stripLeading().startsWith("{") ? json.parse(line) : combined.parse(line);
        }
    }

    // ---------------------------------------------------------------- parsing

    public Optional<LogEntry> parse(String line) {
        if (line == null || line.isBlank()) {
            return Optional.empty();
        }
        Map<String, String> values = new HashMap<>();
        if (jsonLines) {
            if (!readJson(line.strip(), values)) {
                return Optional.empty();
            }
        } else {
            Matcher m = pattern.matcher(line.stripTrailing());
            if (!m.matches()) {
                return Optional.empty();
            }
            for (int i = 0; i < variables.size(); i++) {
                values.putIfAbsent(variables.get(i), m.group(i + 1));
            }
            readTrailingItems(line, values);
        }
        return toEntry(values);
    }

    /** The {@code rt=0.123} style items our timing formats add after the standard fields. */
    private static void readTrailingItems(String line, Map<String, String> values) {
        Matcher m = Pattern.compile("\\brt=\"?([0-9.]+)\"?").matcher(line);
        if (m.find()) {
            values.putIfAbsent("$request_time", m.group(1));
        }
    }

    private static boolean readJson(String line, Map<String, String> values) {
        if (!line.startsWith("{")) {
            return false;
        }
        Map<String, Object> object;
        try {
            object = Json.asObject(Json.parse(line));
        } catch (RuntimeException e) {
            return false;
        }
        for (Map.Entry<String, Object> e : object.entrySet()) {
            if (e.getValue() != null && !(e.getValue() instanceof Map) && !(e.getValue() instanceof List)) {
                values.put("json:" + e.getKey().toLowerCase(Locale.ROOT), String.valueOf(e.getValue()));
            }
        }
        // Names people commonly give the same thing, mapped onto the nginx variable they come from.
        alias(values, "$remote_addr", "remote_addr", "ip", "client", "clientip", "remote_ip", "client_ip");
        alias(values, "$time_iso8601", "time", "time_iso8601", "timestamp", "@timestamp", "ts", "datetime");
        alias(values, "$time_local", "time_local");
        alias(values, "$request", "request");
        alias(values, "$request_method", "method", "request_method", "verb");
        alias(values, "$request_uri", "uri", "request_uri", "path", "url");
        alias(values, "$status", "status", "response_code", "code");
        alias(values, "$body_bytes_sent", "body_bytes_sent", "bytes", "bytes_sent", "size", "response_size");
        alias(values, "$request_time", "request_time", "duration", "response_time", "rt");
        alias(values, "$http_user_agent", "http_user_agent", "user_agent", "useragent", "ua", "agent");
        alias(values, "$http_referer", "http_referer", "referrer", "referer");
        alias(values, "$host", "host", "server_name", "domain");
        alias(values, "$http_cf_connecting_ip", "http_cf_connecting_ip", "cf_connecting_ip");
        alias(values, "$http_x_forwarded_for", "http_x_forwarded_for", "x_forwarded_for", "xff");
        return !values.isEmpty();
    }

    private static void alias(Map<String, String> values, String variable, String... names) {
        for (String name : names) {
            String v = values.get("json:" + name);
            if (v != null) {
                values.putIfAbsent(variable, v);
                return;
            }
        }
    }

    private static Optional<LogEntry> toEntry(Map<String, String> v) {
        String method = clean(v.get("$request_method"));
        String uri = clean(v.get("$request_uri"));
        if (uri == null) {
            uri = clean(v.get("$uri"));
        }
        String request = v.get("$request");
        if (request != null) {
            // "GET /a?b=c HTTP/1.1". A request that isn't in that shape (garbage, TLS on a plain port) keeps its text.
            String[] parts = request.split(" ");
            if (parts.length >= 2 && parts[0].matches("[A-Z]{3,10}")) {
                method = method == null ? parts[0] : method;
                uri = uri == null ? parts[1] : uri;
            } else if (uri == null) {
                uri = request;
            }
        }
        int status = (int) number(v.get("$status"), -1);
        if (status < 100 || status > 599) {
            // A line with no usable status isn't a request the analysis can say anything about.
            if (uri == null && method == null) {
                return Optional.empty();
            }
            status = -1;
        }
        long bytes = number(firstNonNull(v.get("$body_bytes_sent"), v.get("$bytes_sent")), -1);
        double requestTime = decimal(v.get("$request_time"));
        return Optional.of(new LogEntry(visitor(v), time(v), method, uri, status, bytes, requestTime,
                clean(v.get("$http_referer")), clean(v.get("$http_user_agent")), clean(v.get("$host"))));
    }

    /** Prefers the real visitor when the format logs a proxy's header as well as the connecting address. */
    private static String visitor(Map<String, String> v) {
        String cloudflare = clean(v.get("$http_cf_connecting_ip"));
        if (cloudflare != null) {
            return cloudflare;
        }
        String forwarded = clean(v.get("$http_x_forwarded_for"));
        if (forwarded != null) {
            String first = forwarded.split(",")[0].strip();
            if (!first.isEmpty()) {
                return first;
            }
        }
        return clean(v.get("$remote_addr"));
    }

    private static Instant time(Map<String, String> v) {
        String local = v.get("$time_local");
        if (local != null) {
            try {
                return OffsetDateTime.parse(local, TIME_LOCAL).toInstant();
            } catch (DateTimeParseException ignored) {
                // fall through to the other forms
            }
        }
        String iso = v.get("$time_iso8601");
        if (iso != null) {
            try {
                return OffsetDateTime.parse(iso).toInstant();
            } catch (DateTimeParseException ignored) {
                try {
                    return LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC);
                } catch (DateTimeParseException alsoIgnored) {
                    try {
                        return Instant.parse(iso);
                    } catch (DateTimeParseException lastTry) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private static String clean(String s) {
        return s == null || s.isEmpty() || s.equals("-") ? null : s;
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private static long number(String s, long otherwise) {
        if (s == null || s.equals("-")) {
            return otherwise;
        }
        try {
            return (long) Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return otherwise;
        }
    }

    private static double decimal(String s) {
        if (s == null || s.equals("-")) {
            return -1;
        }
        try {
            double d = Double.parseDouble(s);
            return d < 0 ? -1 : d;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
