package mt.su.nrm.nginx;

import mt.su.nrm.nginx.VhostSettings.HeaderSpec;
import mt.su.nrm.nginx.VhostSettings.ListenSpec;
import mt.su.nrm.nginx.VhostSettings.RuleSpec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Checks virtual host settings before they are written. Errors would produce a broken or
 * dangerous config and block the apply; warnings are worth a look but nginx accepts them. This is
 * a first line of defence: the real judge is {@code nginx -t} on the server.
 */
public final class VhostValidator {

    public enum Severity { ERROR, WARNING }

    /** @param where the field or area the problem is in, e.g. "Locations" */
    public record Issue(Severity severity, String where, String message) {
        @Override
        public String toString() {
            return (severity == Severity.ERROR ? "Error" : "Warning") + " (" + where + "): " + message;
        }
    }

    private static final Pattern SERVER_NAME = Pattern.compile("[A-Za-z0-9._*\\-]+|~.+");
    private static final Pattern BODY_SIZE = Pattern.compile("\\d+[kKmMgG]?");
    private static final Pattern HEADER_NAME = Pattern.compile("[A-Za-z0-9\\-_]+");
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9._\\-]+|\\*|\\[[0-9A-Fa-f:.]+\\]|[0-9]{1,3}(\\.[0-9]{1,3}){3}");
    private static final Set<String> PROTOCOLS = Set.of("SSLv2", "SSLv3", "TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3");
    private static final Set<String> REWRITE_FLAGS = Set.of("last", "break", "redirect", "permanent");
    private static final Set<String> LOG_LEVELS = Set.of("debug", "info", "notice", "warn", "error", "crit", "alert", "emerg");
    private static final Set<String> MODIFIERS = Set.of("", "=", "~", "~*", "^~");

    private VhostValidator() {
    }

    public static List<Issue> validate(VhostSettings s) {
        List<Issue> issues = new ArrayList<>();
        general(s, issues);
        ssl(s, issues);
        headers(s, issues);
        realIp(s, issues);
        rewrites(s, issues);
        limits(s, issues);
        logging(s, issues);
        errorPages(s.errorPages, "Error pages", issues);
        locations(s, issues);
        return issues;
    }

    /** Adds warnings for server names and ports that another virtual host on the same server also claims. */
    public static List<Issue> checkConflicts(VhostSettings s, List<VhostSettings> others) {
        List<Issue> issues = new ArrayList<>();
        Set<String> reported = new HashSet<>();
        for (VhostSettings other : others) {
            for (ListenSpec mine : s.listens) {
                for (ListenSpec theirs : other.listens) {
                    if (mine.port() != theirs.port() || mine.port() < 0) {
                        continue;
                    }
                    for (String name : s.serverNames) {
                        if (other.serverNames.contains(name) && reported.add(name + ":" + mine.port())) {
                            issues.add(new Issue(Severity.WARNING, "General", "Another virtual host already answers to \""
                                    + name + "\" on port " + mine.port() + "; nginx will ignore one of them."));
                        }
                    }
                }
            }
        }
        return issues;
    }

    public static boolean hasErrors(List<Issue> issues) {
        return issues.stream().anyMatch(i -> i.severity() == Severity.ERROR);
    }

    // ------------------------------------------------------------------------------- areas

    private static void general(VhostSettings s, List<Issue> issues) {
        if (s.serverNames.isEmpty() && s.listens.stream().noneMatch(ListenSpec::defaultServer)) {
            issues.add(warn("General", "No server name is set, so this only matches requests nobody else claims."));
        }
        for (String name : s.serverNames) {
            if (!SERVER_NAME.matcher(name).matches()) {
                issues.add(error("General", "\"" + name + "\" is not a valid server name."));
            }
        }
        if (s.listens.isEmpty()) {
            issues.add(warn("General", "No listen entry: nginx will listen on port 80."));
        }
        Set<String> seen = new HashSet<>();
        for (ListenSpec l : s.listens) {
            checkListen(l, issues);
            if (!seen.add(l.endpoint)) {
                issues.add(error("General", "Listen " + l.endpoint + " appears more than once."));
            }
        }
        checkPath("General", "Document root", s.root, issues);
        if (hasBadChars(s.index)) {
            issues.add(error("General", "Index files contain invalid characters."));
        }
    }

    private static void checkListen(ListenSpec l, List<Issue> issues) {
        String e = l.endpoint;
        if (e.isBlank() || hasBadChars(e)) {
            issues.add(error("General", "A listen entry is empty or contains invalid characters."));
            return;
        }
        if (e.startsWith("unix:")) {
            if (e.length() <= 6 || e.charAt(5) != '/') {
                issues.add(error("General", "Listen \"" + e + "\": a unix socket needs an absolute path."));
            }
            return;
        }
        int port = l.port();
        if (port < 1 || port > 65535) {
            issues.add(error("General", "Listen \"" + e + "\": the port must be between 1 and 65535."));
        }
        String host = hostPart(e);
        if (!host.isEmpty() && !HOST.matcher(host).matches()) {
            issues.add(error("General", "Listen \"" + e + "\": the address is not valid."));
        }
        for (String p : l.params) {
            if (hasBadChars(p) || p.isBlank()) {
                issues.add(error("General", "Listen \"" + e + "\": a parameter contains invalid characters."));
            }
        }
    }

    private static String hostPart(String endpoint) {
        if (endpoint.startsWith("[")) {
            int close = endpoint.indexOf(']');
            return close < 0 ? endpoint : endpoint.substring(0, close + 1);
        }
        int colon = endpoint.indexOf(':');
        if (colon < 0) {
            return endpoint.chars().allMatch(Character::isDigit) ? "" : endpoint;
        }
        return endpoint.substring(0, colon);
    }

    private static void ssl(VhostSettings s, List<Issue> issues) {
        boolean cert = !s.sslCertificate.isBlank();
        boolean key = !s.sslCertificateKey.isBlank();
        if (s.usesSsl() && !(cert && key)) {
            issues.add(error("SSL", "A listen entry uses ssl, so both a certificate and its key are required."));
        } else if (cert != key) {
            issues.add(error("SSL", "The certificate and its key must be set together."));
        }
        checkPath("SSL", "Certificate", s.sslCertificate, issues);
        checkPath("SSL", "Certificate key", s.sslCertificateKey, issues);
        for (String p : mt.su.nrm.nginx.Arg.parseValues(s.sslProtocols)) {
            if (!PROTOCOLS.contains(p)) {
                issues.add(error("SSL", "\"" + p + "\" is not a known protocol (use TLSv1.2, TLSv1.3, ...)."));
            } else if (p.equals("SSLv2") || p.equals("SSLv3") || p.equals("TLSv1") || p.equals("TLSv1.1")) {
                issues.add(warn("SSL", p + " is obsolete and insecure."));
            }
        }
        if (hasBadChars(s.sslCiphers) || s.sslCiphers.contains(" ")) {
            issues.add(error("SSL", "The cipher list must be one colon-separated string without spaces."));
        }
        if (!cert && !s.usesSsl() && !s.sslProtocols.isBlank()) {
            issues.add(warn("SSL", "SSL protocols are set but no listen entry uses ssl."));
        }
    }

    private static void headers(VhostSettings s, List<Issue> issues) {
        for (HeaderSpec h : s.headers) {
            if (!HEADER_NAME.matcher(h.name).matches()) {
                issues.add(error("Headers", "\"" + h.name + "\" is not a valid header name."));
            }
            if (hasBadChars(h.value) || h.value.isEmpty()) {
                issues.add(error("Headers", "The value of header \"" + h.name + "\" is empty or has invalid characters."));
            }
        }
    }

    private static void rewrites(VhostSettings s, List<Issue> issues) {
        for (RuleSpec r : s.rewrites) {
            List<String> v = Arg.parseValues(r.arguments);
            if (hasBadChars(r.arguments)) {
                issues.add(error("Rewrites", "A rule contains invalid characters."));
            } else if (r.directive.equals("rewrite")) {
                if (v.size() < 2 || v.size() > 3) {
                    issues.add(error("Rewrites", "rewrite needs a pattern, a replacement and optionally a flag: \""
                            + r.arguments + "\"."));
                    continue;
                }
                checkRegex("Rewrites", v.get(0), issues);
                if (v.size() == 3 && !REWRITE_FLAGS.contains(v.get(2))) {
                    issues.add(error("Rewrites", "\"" + v.get(2) + "\" is not a rewrite flag (last, break, redirect, permanent)."));
                }
            } else if (r.directive.equals("return")) {
                checkReturn("Rewrites", v, issues);
            } else {
                issues.add(error("Rewrites", "Unknown rule type \"" + r.directive + "\"."));
            }
        }
    }

    private static void checkReturn(String where, List<String> v, List<Issue> issues) {
        if (v.isEmpty()) {
            issues.add(error(where, "return needs a status code."));
            return;
        }
        String code = v.get(0);
        if (code.matches("\\d{3}")) {
            int c = Integer.parseInt(code);
            if (c < 100 || c > 599) {
                issues.add(error(where, "\"" + code + "\" is not a valid HTTP status code."));
            } else if (c >= 300 && c < 400 && v.size() < 2) {
                issues.add(error(where, "A " + code + " redirect needs a target URL."));
            }
        } else if (!(v.size() == 1 && (code.startsWith("http://") || code.startsWith("https://")))) {
            issues.add(error(where, "return must start with a status code, e.g. 301 https://example.com."));
        }
    }

    private static void limits(VhostSettings s, List<Issue> issues) {
        if (!s.clientMaxBodySize.isBlank() && !BODY_SIZE.matcher(s.clientMaxBodySize.strip()).matches()) {
            issues.add(error("Limits", "Maximum upload size must look like 10m, 512k or 0 (unlimited)."));
        }
        if (!s.limitRate.isBlank() && !BODY_SIZE.matcher(s.limitRate.strip()).matches()) {
            issues.add(error("Limits", "Rate limit must look like 500k or 2m."));
        }
        for (String line : s.limitReq) {
            if (!line.contains("zone=") || hasBadChars(line)) {
                issues.add(error("Limits", "limit_req \"" + line + "\" needs a zone, e.g. zone=perip burst=10."));
            }
        }
        for (String line : s.limitConn) {
            if (Arg.parseValues(line).size() != 2 || hasBadChars(line)) {
                issues.add(error("Limits", "limit_conn \"" + line + "\" needs a zone name and a number, e.g. perip 10."));
            }
        }
    }

    private static void logging(VhostSettings s, List<Issue> issues) {
        for (String line : s.accessLogs) {
            List<String> v = Arg.parseValues(line);
            if (hasBadChars(line) || v.isEmpty()) {
                issues.add(error("Logging", "An access log entry is empty or has invalid characters."));
            } else if (!v.get(0).equals("off") && !v.get(0).startsWith("/") && !v.get(0).startsWith("syslog:")) {
                issues.add(error("Logging", "Access log \"" + v.get(0) + "\" must be an absolute path, syslog:, or off."));
            }
        }
        if (!s.errorLog.isBlank()) {
            List<String> v = Arg.parseValues(s.errorLog);
            if (hasBadChars(s.errorLog)) {
                issues.add(error("Logging", "The error log contains invalid characters."));
            } else if (!v.get(0).startsWith("/") && !v.get(0).startsWith("syslog:") && !v.get(0).equals("stderr")) {
                issues.add(error("Logging", "Error log \"" + v.get(0) + "\" must be an absolute path, syslog: or stderr."));
            } else if (v.size() > 2 || (v.size() == 2 && !LOG_LEVELS.contains(v.get(1)))) {
                issues.add(error("Logging", "The error log level must be one of " + String.join(", ", LOG_LEVELS) + "."));
            }
        }
    }

    private static void locations(VhostSettings s, List<Issue> issues) {
        Set<String> seen = new HashSet<>();
        for (LocationSettings l : s.locations) {
            String where = "Locations";
            if (!MODIFIERS.contains(l.modifier)) {
                issues.add(error(where, "\"" + l.modifier + "\" is not a location modifier."));
            }
            if (l.path.isBlank() || hasBadChars(l.path) || l.path.chars().anyMatch(Character::isWhitespace)
                    && l.modifier.isEmpty()) {
                issues.add(error(where, "Location path \"" + l.path + "\" is empty or invalid."));
                continue;
            }
            boolean regex = l.modifier.equals("~") || l.modifier.equals("~*");
            if (regex) {
                checkRegex(where, l.path, issues);
            } else if (!l.path.startsWith("/") && !l.path.startsWith("@")) {
                issues.add(error(where, "Location path \"" + l.path + "\" must start with / (or @ for a named location)."));
            }
            if (!seen.add(l.modifier + " " + l.path)) {
                issues.add(error(where, "Location " + l.title() + " is defined more than once."));
            }
            switch (l.type) {
                case STATIC:
                    checkPath(where, "Root of " + l.title(), l.root, issues);
                    checkPath(where, "Alias of " + l.title(), l.alias, issues);
                    if (!l.root.isBlank() && !l.alias.isBlank()) {
                        issues.add(warn(where, l.title() + " sets both root and alias; alias wins."));
                    }
                    break;
                case PROXY:
                    checkProxyPass(l, issues);
                    for (String h : l.proxySetHeaders) {
                        List<String> v = Arg.parseValues(h);
                        if (v.size() < 2 || !HEADER_NAME.matcher(v.get(0)).matches() || hasBadChars(h)) {
                            issues.add(error(where, "Proxy header \"" + h + "\" must be a name followed by a value."));
                        }
                    }
                    break;
                case FASTCGI:
                    checkFastcgi(l, issues);
                    break;
                default:
                    List<String> ret = new ArrayList<>();
                    ret.add(l.redirectCode);
                    if (!l.redirectTarget.isBlank()) {
                        ret.add(l.redirectTarget);
                    }
                    if (!List.of("301", "302", "303", "307", "308").contains(l.redirectCode)) {
                        issues.add(error(where, "A redirect status must be 301, 302, 303, 307 or 308."));
                    }
                    if (hasBadChars(l.redirectTarget)) {
                        issues.add(error(where, "The redirect target of " + l.title() + " has invalid characters."));
                    }
                    checkReturn(where, ret, issues);
            }
            locationExtras(l, issues);
        }
    }

    /** Access control, limits, compression and caching of one location. */
    private static void locationExtras(LocationSettings l, List<Issue> issues) {
        String where = "Locations";
        String title = l.title();
        if (!l.authBasic.isBlank() && !l.authBasic.equals("off")) {
            if (hasBadChars(l.authBasic)) {
                issues.add(error(where, "The login realm of " + title + " has invalid characters."));
            }
            if (l.authBasicUserFile.isBlank()) {
                issues.add(error(where, title + " asks for a login but has no password file."));
            }
        }
        checkPath(where, "Password file of " + title, l.authBasicUserFile, issues);
        if (!l.satisfy.isBlank() && !l.satisfy.equals("any") && !l.satisfy.equals("all")) {
            issues.add(error(where, "\"satisfy\" in " + title + " must be any or all."));
        }
        for (String rule : l.accessRules) {
            List<String> v = Arg.parseValues(rule);
            boolean ok = v.size() == 2 && (v.get(0).equals("allow") || v.get(0).equals("deny"))
                    && (v.get(1).equals("all") || v.get(1).startsWith("unix:")
                    || v.get(1).matches("[0-9A-Fa-f:.]+(/\\d{1,3})?"));
            if (!ok || hasBadChars(rule)) {
                issues.add(error(where, "Access rule \"" + rule + "\" must be allow or deny followed by an address, network or all."));
            }
        }
        for (String line : l.limitReq) {
            if (!line.contains("zone=") || hasBadChars(line)) {
                issues.add(error(where, "limit_req \"" + line + "\" in " + title + " needs a zone, e.g. zone=perip burst=10."));
            }
        }
        for (String line : l.limitConn) {
            if (Arg.parseValues(line).size() != 2 || hasBadChars(line)) {
                issues.add(error(where, "limit_conn \"" + line + "\" in " + title + " needs a zone name and a number."));
            }
        }
        if (!l.gzip.isBlank() && !l.gzip.equals("on") && !l.gzip.equals("off")) {
            issues.add(error(where, "Compression in " + title + " must be on or off."));
        }
        if (!l.gzipCompLevel.isBlank() && !l.gzipCompLevel.matches("[1-9]")) {
            issues.add(error(where, "Compression level in " + title + " must be 1 to 9."));
        }
        if (!l.gzipMinLength.isBlank() && !l.gzipMinLength.matches("\\d+[kKmM]?")) {
            issues.add(error(where, "Compression minimum length in " + title + " must be a size such as 256."));
        }
        if (hasBadChars(l.gzipTypes)) {
            issues.add(error(where, "The compressed types of " + title + " have invalid characters."));
        }
        if (!l.proxyCache.isBlank() && (l.proxyCache.chars().anyMatch(Character::isWhitespace) || hasBadChars(l.proxyCache))) {
            issues.add(error(where, "The cache zone of " + title + " must be a single name or off."));
        }
        for (String line : l.proxyCacheValid) {
            List<String> v = Arg.parseValues(line);
            if (v.size() < 1 || !v.get(v.size() - 1).matches("\\d+(ms|s|m|h|d|w|M|y)?") || hasBadChars(line)) {
                issues.add(error(where, "Cache time \"" + line + "\" must end with a time such as 10m (optionally after status codes)."));
            }
        }
        if (!l.proxyReadTimeout.isBlank() && !l.proxyReadTimeout.matches("\\d+(ms|s|m|h|d)?")) {
            issues.add(error(where, "The read timeout of " + title + " must look like 60s."));
        }
        errorPages(l.errorPages, where, issues);
    }

    private static void errorPages(List<String> pages, String where, List<Issue> issues) {
        for (String line : pages) {
            List<String> v = Arg.parseValues(line);
            boolean ok = v.size() >= 2 && !hasBadChars(line);
            if (ok) {
                String target = v.get(v.size() - 1);
                ok = target.startsWith("/") || target.startsWith("@") || target.startsWith("http://")
                        || target.startsWith("https://") || target.startsWith("$");
                for (String code : v.subList(0, v.size() - 1)) {
                    ok &= code.matches("\\d{3}") || code.matches("=\\d{0,3}");
                }
            }
            if (!ok) {
                issues.add(error(where, "Error page \"" + line + "\" must be status codes followed by a page, e.g. 404 /404.html."));
            }
        }
    }

    /**
     * Warns about zones and upstreams a host refers to that are not defined anywhere. The names come
     * from the http-level objects found in the configuration.
     */
    public static List<Issue> checkReferences(VhostSettings s, java.util.Collection<String> requestZones,
                                              java.util.Collection<String> connectionZones,
                                              java.util.Collection<String> cacheZones) {
        List<Issue> issues = new ArrayList<>();
        List<String> reqLines = new ArrayList<>(s.limitReq);
        List<String> connLines = new ArrayList<>(s.limitConn);
        for (LocationSettings l : s.locations) {
            reqLines.addAll(l.limitReq);
            connLines.addAll(l.limitConn);
            if (!l.proxyCache.isBlank() && !l.proxyCache.equals("off") && l.proxyPass.isBlank()) {
                issues.add(warn("Locations", "Location " + l.path + " uses cache zone \"" + l.proxyCache + "\" but doesn't "
                        + "proxy to a server, so nothing is ever cached there (nginx serves it directly). Remove the cache "
                        + "zone, or make this a reverse proxy location."));
            }
            if (!l.proxyCache.isBlank() && !l.proxyCache.equals("off") && !cacheZones.contains(l.proxyCache)) {
                issues.add(warn("Locations", "Cache zone \"" + l.proxyCache + "\" is not defined (see Cache Zones)."));
            }
        }
        for (String line : reqLines) {
            String zone = zoneOf(Arg.parseValues(line).stream().filter(t -> t.startsWith("zone=")).findFirst().orElse(""));
            if (!zone.isEmpty() && !requestZones.contains(zone)) {
                issues.add(warn("Limits", "Request limit zone \"" + zone + "\" is not defined (see Rate Limits)."));
            }
        }
        for (String line : connLines) {
            List<String> v = Arg.parseValues(line);
            if (!v.isEmpty() && !connectionZones.contains(v.get(0))) {
                issues.add(warn("Limits", "Connection limit zone \"" + v.get(0) + "\" is not defined (see Rate Limits)."));
            }
        }
        return issues;
    }

    private static String zoneOf(String token) {
        String v = token.startsWith("zone=") ? token.substring(5) : "";
        int colon = v.indexOf(':');
        return colon < 0 ? v : v.substring(0, colon);
    }

    private static void checkProxyPass(LocationSettings l, List<Issue> issues) {
        String target = l.proxyPass.strip();
        if (target.isEmpty()) {
            issues.add(error("Locations", "Reverse proxy " + l.title() + " needs a target, e.g. http://127.0.0.1:3000."));
        } else if (hasBadChars(target) || target.chars().anyMatch(Character::isWhitespace)) {
            issues.add(error("Locations", "The proxy target of " + l.title() + " has invalid characters."));
        } else if (!target.startsWith("http://") && !target.startsWith("https://")) {
            issues.add(error("Locations", "The proxy target of " + l.title() + " must start with http:// or https://."));
        } else if (target.replaceFirst("^https?://", "").isEmpty()) {
            issues.add(error("Locations", "The proxy target of " + l.title() + " has no host."));
        }
    }

    private static final Pattern FASTCGI_TARGET = Pattern.compile(
            "unix:/\\S+|(\\d{1,3}(\\.\\d{1,3}){3}|\\[[0-9A-Fa-f:.]+\\]|[A-Za-z0-9._\\-]+):\\d{1,5}|[A-Za-z0-9._\\-]+");
    private static final Pattern FASTCGI_PARAM_NAME = Pattern.compile("[A-Za-z0-9_]+");

    /** A PHP / FastCGI location: where the server listens, the parameters it is sent, and the two usual PHP pitfalls. */
    private static void checkFastcgi(LocationSettings l, List<Issue> issues) {
        String where = "Locations";
        String title = l.title();
        String target = l.fastcgiPass.strip();
        if (target.isEmpty()) {
            issues.add(error(where, "The PHP location " + title + " needs a FastCGI address, e.g. unix:/run/php/php-fpm.sock or 127.0.0.1:9000."));
        } else if (hasBadChars(target) || !FASTCGI_TARGET.matcher(target).matches()) {
            issues.add(error(where, "The FastCGI address of " + title + " must be unix:/path/to.sock, host:port, or an upstream name."));
        }
        String include = l.fastcgiInclude.strip();
        if (hasBadChars(include) || include.chars().anyMatch(Character::isWhitespace) || include.contains("..")) {
            issues.add(error(where, "The FastCGI include of " + title + " must be a single file name without \"..\"."));
        }
        boolean scriptFilename = false;
        for (String p : l.fastcgiParams) {
            List<String> v = Arg.parseValues(p);
            if (v.size() < 2 || !FASTCGI_PARAM_NAME.matcher(v.get(0)).matches() || hasBadChars(p)) {
                issues.add(error(where, "FastCGI parameter \"" + p + "\" must be a NAME followed by a value."));
            } else if (v.get(0).equals("SCRIPT_FILENAME")) {
                scriptFilename = true;
            }
        }
        if (!l.fastcgiReadTimeout.isBlank() && !l.fastcgiReadTimeout.matches("\\d+(ms|s|m|h|d|w|M|y)?")) {
            issues.add(error(where, "The FastCGI read timeout of " + title + " must be a time such as 60s."));
        }
        boolean snippet = include.contains("fastcgi-php");
        if (!snippet && !scriptFilename && !include.isEmpty()) {
            issues.add(warn(where, title + " sets no SCRIPT_FILENAME, so PHP will answer \"File not found\". "
                    + "Add SCRIPT_FILENAME $document_root$fastcgi_script_name, or include snippets/fastcgi-php.conf."));
        }
        if (!snippet && l.tryFiles.isBlank() && l.modifier.startsWith("~")) {
            issues.add(warn(where, title + " passes every matching URL to PHP, even for files that don't exist. "
                    + "Set try_files to $uri =404 so only real scripts run."));
        }
    }

    // ------------------------------------------------------------------------------- helpers

    private static void checkPath(String where, String label, String value, List<Issue> issues) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (hasBadChars(value)) {
            issues.add(error(where, label + " contains invalid characters."));
        } else if (!value.startsWith("/") && !value.startsWith("$")) {
            issues.add(error(where, label + " must be an absolute path (starting with /)."));
        }
    }

    private static void checkRegex(String where, String regex, List<Issue> issues) {
        try {
            Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            issues.add(warn(where, "\"" + regex + "\" may not be a valid regular expression: " + e.getDescription() + "."));
        }
    }

    /** Line breaks and other control characters can't appear in any value and would corrupt the file. */
    private static boolean hasBadChars(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return true;
            }
        }
        return false;
    }

    private static Issue error(String where, String message) {
        return new Issue(Severity.ERROR, where, message);
    }

    private static void realIp(VhostSettings s, List<Issue> issues) {
        if (s.realIp == null || s.realIp.isEmpty()) {
            return;
        }
        for (String problem : RealIp.problems(s.realIp)) {
            issues.add(new Issue(Severity.ERROR, "Real IP", problem));
        }
        for (String warning : RealIp.warnings(s.realIp)) {
            issues.add(warn("Real IP", warning));
        }
    }

    private static Issue warn(String where, String message) {
        return new Issue(Severity.WARNING, where, message);
    }
}
