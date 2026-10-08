package mt.su.nrm.ui;

import mt.su.nrm.nginx.Arg;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes the one-line forms of rewrite rules, {@code limit_req}, {@code limit_conn}, access
 * logs and error pages, and describes each in plain words. The friendly editors build on this; a line
 * that {@code parse*} doesn't understand comes back as null and is shown (and edited) as raw text, so
 * nothing is ever lost or reinterpreted. Free of JavaFX so it can be unit tested.
 */
final class LineEntries {

    private LineEntries() {
    }

    private static final Map<String, String> STATUS = Map.ofEntries(
            Map.entry("200", "OK"), Map.entry("204", "No Content"), Map.entry("301", "Moved Permanently"),
            Map.entry("302", "Found"), Map.entry("303", "See Other"), Map.entry("307", "Temporary Redirect"),
            Map.entry("308", "Permanent Redirect"), Map.entry("400", "Bad Request"), Map.entry("401", "Unauthorized"),
            Map.entry("403", "Forbidden"), Map.entry("404", "Not Found"), Map.entry("410", "Gone"),
            Map.entry("429", "Too Many Requests"), Map.entry("444", "close the connection"),
            Map.entry("500", "Internal Server Error"), Map.entry("502", "Bad Gateway"),
            Map.entry("503", "Service Unavailable"), Map.entry("504", "Gateway Timeout"));

    /** "404 (Not Found)" for known codes, the bare code otherwise. */
    static String statusName(String code) {
        String name = STATUS.get(code);
        return name == null ? code : code + " (" + name + ")";
    }

    private static String raw(String value) {
        return Arg.of(value).raw();
    }

    // ------------------------------------------------------------------ rewrites

    enum RewriteKind { REWRITE, REDIRECT, STATUS }

    /**
     * One rule. REWRITE uses pattern/replacement/flag; REDIRECT uses code/target; STATUS uses code and an
     * optional response text.
     */
    record Rewrite(RewriteKind kind, String pattern, String replacement, String flag, String code, String target) {
        static Rewrite rewrite(String pattern, String replacement, String flag) {
            return new Rewrite(RewriteKind.REWRITE, pattern, replacement, flag, "", "");
        }

        static Rewrite redirect(String code, String target) {
            return new Rewrite(RewriteKind.REDIRECT, "", "", "", code, target);
        }

        static Rewrite status(String code, String text) {
            return new Rewrite(RewriteKind.STATUS, "", "", "", code, text);
        }
    }

    /** Reads a line such as {@code rewrite ^/old/(.*)$ /new/$1 permanent} or {@code return 301 https://x}. */
    static Rewrite parseRewrite(String line) {
        String text = line.strip();
        int space = text.indexOf(' ');
        if (space < 0) {
            return null;
        }
        List<String> v = Arg.parseValues(text.substring(space).strip());
        switch (text.substring(0, space)) {
            case "rewrite" -> {
                if (v.size() < 2 || v.size() > 3) {
                    return null;
                }
                String flag = v.size() == 3 ? v.get(2) : "";
                if (!flag.isEmpty() && !List.of("last", "break", "redirect", "permanent").contains(flag)) {
                    return null;
                }
                return Rewrite.rewrite(v.get(0), v.get(1), flag);
            }
            case "return" -> {
                if (v.isEmpty() || !v.get(0).matches("\\d{3}") || v.size() > 2) {
                    return null;
                }
                boolean redirect = v.get(0).startsWith("3") && v.size() == 2;
                return redirect ? Rewrite.redirect(v.get(0), v.get(1))
                        : Rewrite.status(v.get(0), v.size() == 2 ? v.get(1) : "");
            }
            default -> {
                return null;
            }
        }
    }

    static String formatRewrite(Rewrite r) {
        return switch (r.kind()) {
            case REWRITE -> "rewrite " + raw(r.pattern()) + " " + raw(r.replacement())
                    + (r.flag().isEmpty() ? "" : " " + r.flag());
            case REDIRECT -> "return " + r.code() + " " + raw(r.target());
            case STATUS -> "return " + r.code() + (r.target().isEmpty() ? "" : " " + raw(r.target()));
        };
    }

    static String describeRewrite(String line) {
        Rewrite r = parseRewrite(line);
        if (r == null) {
            return line;
        }
        return switch (r.kind()) {
            case REWRITE -> "Rewrite URLs matching " + r.pattern() + " to " + r.replacement()
                    + switch (r.flag()) {
                        case "last" -> " (then look for a matching location again)";
                        case "break" -> " (and stop processing rules)";
                        case "redirect" -> " (as a temporary redirect)";
                        case "permanent" -> " (as a permanent redirect)";
                        default -> "";
                    };
            case REDIRECT -> "Redirect visitors to " + r.target() + " (" + redirectName(r.code()) + ")";
            case STATUS -> "Answer every request with " + statusName(r.code())
                    + (r.target().isEmpty() ? "" : " and the text \"" + r.target() + "\"");
        };
    }

    static String redirectName(String code) {
        return switch (code) {
            case "301" -> "301, permanent";
            case "302" -> "302, temporary";
            case "303" -> "303, see other";
            case "307" -> "307, temporary, keeps the request method";
            case "308" -> "308, permanent, keeps the request method";
            default -> code;
        };
    }

    /** What is wrong with a rule, worded for the user; empty if fine. */
    static List<String> rewriteProblems(Rewrite r) {
        List<String> problems = new ArrayList<>();
        switch (r.kind()) {
            case REWRITE -> {
                if (r.pattern().isBlank()) {
                    problems.add("Enter the address pattern to match, e.g. ^/old/(.*)$");
                } else {
                    try {
                        java.util.regex.Pattern.compile(r.pattern());
                    } catch (java.util.regex.PatternSyntaxException e) {
                        problems.add("The pattern is not a valid regular expression: " + e.getDescription());
                    }
                }
                if (r.replacement().isBlank()) {
                    problems.add("Enter what to rewrite it to, e.g. /new/$1");
                }
            }
            case REDIRECT -> {
                if (!r.code().matches("30[12378]")) {
                    problems.add("Choose a redirect type.");
                }
                if (r.target().isBlank()) {
                    problems.add("Enter the address to send visitors to.");
                }
            }
            case STATUS -> {
                if (!r.code().matches("[1-5]\\d\\d")) {
                    problems.add("Enter a three-digit status code, e.g. 403.");
                }
            }
        }
        return problems;
    }

    // ------------------------------------------------------------------ limit_req

    /** {@code zone=NAME [burst=N] [nodelay | delay=N]}; anything else is kept in {@code extra}. */
    record LimitReq(String zone, String burst, boolean nodelay, String delay, List<String> extra) {
    }

    static LimitReq parseLimitReq(String line) {
        String zone = "";
        String burst = "";
        String delay = "";
        boolean nodelay = false;
        List<String> extra = new ArrayList<>();
        for (String token : Arg.parseValues(line)) {
            if (token.startsWith("zone=")) {
                zone = token.substring(5);
            } else if (token.startsWith("burst=")) {
                burst = token.substring(6);
            } else if (token.startsWith("delay=")) {
                delay = token.substring(6);
            } else if (token.equals("nodelay")) {
                nodelay = true;
            } else {
                extra.add(token);
            }
        }
        return zone.isEmpty() ? null : new LimitReq(zone, burst, nodelay, delay, extra);
    }

    static String formatLimitReq(LimitReq l) {
        StringBuilder sb = new StringBuilder("zone=").append(l.zone());
        if (!l.burst().isBlank() && !l.burst().equals("0")) {
            sb.append(" burst=").append(l.burst());
            if (l.nodelay()) {
                sb.append(" nodelay");
            } else if (!l.delay().isBlank()) {
                sb.append(" delay=").append(l.delay());
            }
        }
        for (String e : l.extra()) {
            sb.append(' ').append(raw(e));
        }
        return sb.toString();
    }

    static String describeLimitReq(String line) {
        LimitReq l = parseLimitReq(line);
        if (l == null) {
            return line;
        }
        String base = "Limit request rate using zone \"" + l.zone() + "\"";
        if (l.burst().isBlank() || l.burst().equals("0")) {
            return base + ": requests over the rate are rejected";
        }
        return base + ": allow bursts of " + l.burst() + " extra requests, "
                + (l.nodelay() ? "served immediately" : "queued and slowed to the rate");
    }

    static List<String> limitReqProblems(String zone, String burst) {
        List<String> problems = new ArrayList<>();
        if (zone == null || zone.isBlank()) {
            problems.add("Choose a zone (define zones under Rate Limits).");
        }
        if (!burst.isBlank() && !burst.matches("\\d+")) {
            problems.add("The burst must be a whole number.");
        }
        return problems;
    }

    // ------------------------------------------------------------------ limit_conn

    record LimitConn(String zone, String max) {
    }

    static LimitConn parseLimitConn(String line) {
        List<String> v = Arg.parseValues(line);
        return v.size() == 2 ? new LimitConn(v.get(0), v.get(1)) : null;
    }

    static String formatLimitConn(LimitConn l) {
        return raw(l.zone()) + " " + l.max();
    }

    static String describeLimitConn(String line) {
        LimitConn l = parseLimitConn(line);
        return l == null ? line : "At most " + l.max() + " simultaneous connections per visitor (zone \"" + l.zone() + "\")";
    }

    static List<String> limitConnProblems(String zone, String max) {
        List<String> problems = new ArrayList<>();
        if (zone == null || zone.isBlank()) {
            problems.add("Choose a zone (define zones under Rate Limits).");
        }
        if (!max.matches("[1-9]\\d*")) {
            problems.add("Enter how many connections are allowed, e.g. 10.");
        }
        return problems;
    }

    // ------------------------------------------------------------------ access_log

    /** {@code off}, or a destination with an optional format and further options ({@code buffer=32k}, {@code gzip}...). */
    record AccessLog(boolean off, String path, String format, List<String> extra) {
    }

    static AccessLog parseAccessLog(String line) {
        List<String> v = Arg.parseValues(line);
        if (v.isEmpty()) {
            return null;
        }
        if (v.get(0).equals("off")) {
            return v.size() == 1 ? new AccessLog(true, "", "", List.of()) : null;
        }
        return new AccessLog(false, v.get(0), v.size() > 1 ? v.get(1) : "",
                v.size() > 2 ? new ArrayList<>(v.subList(2, v.size())) : List.of());
    }

    static String formatAccessLog(AccessLog a) {
        if (a.off()) {
            return "off";
        }
        StringBuilder sb = new StringBuilder(raw(a.path()));
        if (!a.format().isBlank()) {
            sb.append(' ').append(raw(a.format()));
        }
        for (String e : a.extra()) {
            sb.append(' ').append(raw(e));
        }
        return sb.toString();
    }

    static String describeAccessLog(String line) {
        AccessLog a = parseAccessLog(line);
        if (a == null) {
            return line;
        }
        if (a.off()) {
            return "Do not keep an access log";
        }
        return "Record visits in " + a.path() + (a.format().isBlank() ? "" : " using the \"" + a.format() + "\" format")
                + (a.extra().isEmpty() ? "" : " (" + String.join(", ", a.extra()) + ")");
    }

    static List<String> accessLogProblems(AccessLog a) {
        List<String> problems = new ArrayList<>();
        if (!a.off() && !(a.path().startsWith("/") || a.path().startsWith("syslog:"))) {
            problems.add("The log file must be a full path such as /var/log/nginx/site.access.log.");
        }
        if (!a.off() && a.path().chars().anyMatch(c -> c < 0x21 || c == ';')) {
            problems.add("The log path can't contain spaces or semicolons.");
        }
        return problems;
    }

    // ------------------------------------------------------------------ error_page

    /** Status codes, an optional replacement response code ({@code =200}), and the page or address to show. */
    record ErrorPage(List<String> codes, String responseCode, String target) {
    }

    static ErrorPage parseErrorPage(String line) {
        List<String> v = Arg.parseValues(line);
        if (v.size() < 2) {
            return null;
        }
        List<String> codes = new ArrayList<>();
        String response = "";
        for (String token : v.subList(0, v.size() - 1)) {
            if (token.matches("\\d{3}")) {
                codes.add(token);
            } else if (token.matches("=\\d{3}") && response.isEmpty()) {
                response = token.substring(1);
            } else {
                return null;
            }
        }
        return codes.isEmpty() ? null : new ErrorPage(codes, response, v.get(v.size() - 1));
    }

    static String formatErrorPage(ErrorPage e) {
        StringBuilder sb = new StringBuilder(String.join(" ", e.codes()));
        if (!e.responseCode().isBlank()) {
            sb.append(" =").append(e.responseCode());
        }
        return sb.append(' ').append(raw(e.target())).toString();
    }

    static String describeErrorPage(String line) {
        ErrorPage e = parseErrorPage(line);
        if (e == null) {
            return line;
        }
        List<String> names = e.codes().stream().map(LineEntries::statusName).toList();
        String where = e.target().startsWith("@") ? "the named location " + e.target()
                : e.target().startsWith("/") ? "the page " + e.target() : "redirect to " + e.target();
        return "On " + String.join(", ", names) + " show " + where
                + (e.responseCode().isBlank() ? "" : " and answer with " + statusName(e.responseCode()));
    }

    static List<String> errorPageProblems(ErrorPage e) {
        List<String> problems = new ArrayList<>();
        if (e.codes().isEmpty()) {
            problems.add("Choose at least one error to handle.");
        }
        String t = e.target();
        if (!(t.startsWith("/") || t.startsWith("@") || t.startsWith("http://") || t.startsWith("https://"))) {
            problems.add("The page must start with / (a page on this site), @ (a named location) or http(s)://.");
        }
        if (t.chars().anyMatch(c -> c < 0x21 || c == ';')) {
            problems.add("The page can't contain spaces or semicolons.");
        }
        return problems;
    }

    // ------------------------------------------------------------------ listen

    /**
     * One {@code listen} line. {@code host} is "" for all IPv4 addresses, "[::]" for all IPv6 addresses, or
     * a specific address; other options ({@code reuseport}, {@code backlog=...}) are kept in {@code extra}.
     */
    record Listen(String host, String port, boolean ssl, boolean http2, boolean defaultServer, List<String> extra) {
    }

    static Listen parseListen(String line) {
        List<String> v = Arg.parseValues(line);
        if (v.isEmpty()) {
            return null;
        }
        String endpoint = v.get(0);
        String host;
        String port;
        java.util.regex.Matcher m;
        if (endpoint.matches("\\d{1,5}")) {
            host = "";
            port = endpoint;
        } else if ((m = java.util.regex.Pattern.compile("(\\[[0-9A-Fa-f:.]*\\]):(\\d{1,5})").matcher(endpoint)).matches()
                || (m = java.util.regex.Pattern.compile("([A-Za-z0-9.*_-]+):(\\d{1,5})").matcher(endpoint)).matches()) {
            host = m.group(1);
            port = m.group(2);
        } else {
            return null;
        }
        boolean ssl = false;
        boolean http2 = false;
        boolean def = false;
        List<String> extra = new ArrayList<>();
        for (String token : v.subList(1, v.size())) {
            switch (token) {
                case "ssl" -> ssl = true;
                case "http2" -> http2 = true;
                case "default_server" -> def = true;
                default -> extra.add(token);
            }
        }
        return new Listen(host.equals("*") ? "" : host, port, ssl, http2, def, extra);
    }

    static String formatListen(Listen l) {
        StringBuilder sb = new StringBuilder(l.host().isEmpty() ? l.port() : l.host() + ":" + l.port());
        if (l.ssl()) {
            sb.append(" ssl");
        }
        if (l.http2()) {
            sb.append(" http2");
        }
        if (l.defaultServer()) {
            sb.append(" default_server");
        }
        for (String e : l.extra()) {
            sb.append(' ').append(raw(e));
        }
        return sb.toString();
    }

    static String describeListen(String line) {
        Listen l = parseListen(line);
        if (l == null) {
            return line;
        }
        String where = l.host().isEmpty() ? "all IPv4 addresses" : l.host().equals("[::]") ? "all IPv6 addresses"
                : "address " + l.host();
        return (l.ssl() ? "Secure HTTPS" + (l.http2() ? " with HTTP/2" : "") : "Plain HTTP") + " on port " + l.port()
                + ", " + where + (l.defaultServer() ? " (answers requests that match no other site)" : "");
    }

    static List<String> listenProblems(Listen l) {
        List<String> problems = new ArrayList<>();
        if (!l.port().matches("\\d{1,5}") || Integer.parseInt(l.port()) < 1 || Integer.parseInt(l.port()) > 65535) {
            problems.add("The port must be a number from 1 to 65535 (80 for HTTP, 443 for HTTPS).");
        }
        if (!l.host().isEmpty() && !l.host().equals("[::]") && !l.host().matches("\\[[0-9A-Fa-f:.]+\\]")
                && !l.host().matches("[A-Za-z0-9.-]+")) {
            problems.add("Enter an IPv4 address (192.0.2.10) or an IPv6 address in brackets ([2001:db8::1]).");
        }
        if (l.http2() && !l.ssl()) {
            problems.add("HTTP/2 needs the secure (SSL) option.");
        }
        return problems;
    }
}
