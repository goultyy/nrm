package mt.su.nrm.nginx;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@code location} block as the editor sees it. Only the directives that belong to the location's
 * type are managed; anything else in the block is left alone.
 */
public final class LocationSettings {

    /** What the location does. */
    public enum Type {
        /** Serves files: root or alias, index, try_files. */
        STATIC("Static files"),
        /** Forwards to another server: proxy_pass and its headers. */
        PROXY("Reverse proxy"),
        /** Answers with a 3xx redirect. */
        REDIRECT("Redirect"),
        /** Hands requests to a FastCGI server such as PHP-FPM: fastcgi_pass and its parameters. */
        FASTCGI("PHP / FastCGI");

        private final String label;

        Type(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** The headers a new reverse proxy location starts with. */
    public static final List<String> DEFAULT_PROXY_HEADERS = List.of(
            "Host $host",
            "X-Real-IP $remote_addr",
            "X-Forwarded-For $proxy_add_x_forwarded_for",
            "X-Forwarded-Proto $scheme");

    /** The block this was read from; null for a location that doesn't exist yet. */
    Block source;

    /**
     * True if this location has {@code add_header} lines of its own. nginx then ignores the server's {@code add_header}
     * lines inside it, which matters for security headers.
     */
    public boolean definesOwnHeaders() {
        return source != null && !source.directives("add_header").isEmpty();
    }
    /** The type the block had when read, used to know which directives to clear if the type changes. */
    Type originalType = Type.STATIC;

    /** One of {@code ""}, {@code =}, {@code ~}, {@code ~*}, {@code ^~}. */
    public String modifier = "";
    public String path = "/";
    public Type type = Type.STATIC;

    public String root = "";
    public String alias = "";
    public String index = "";
    public String tryFiles = "";

    public String proxyPass = "";
    /** Each entry is {@code Name value}, the arguments of one proxy_set_header. */
    public List<String> proxySetHeaders = new ArrayList<>();

    public String redirectCode = "301";
    public String redirectTarget = "";

    /** Where the FastCGI server listens: {@code unix:/run/php/php8.2-fpm.sock}, {@code 127.0.0.1:9000} or an upstream name. */
    public String fastcgiPass = "";
    /** The file included for the usual FastCGI parameters, e.g. {@code snippets/fastcgi-php.conf} or {@code fastcgi_params}. */
    public String fastcgiInclude = "";
    /** Each entry is {@code NAME value}, the arguments of one fastcgi_param. */
    public List<String> fastcgiParams = new ArrayList<>();
    public String fastcgiReadTimeout = "";

    /** Basic authentication: the realm text, {@code off} to switch it off for this location, or empty for not set. */
    public String authBasic = "";
    public String authBasicUserFile = "";
    /** {@code any} (password or trusted network), {@code all} (password and trusted network), or empty for nginx's default (all). */
    public String satisfy = "";
    /** Access rules in order, each like {@code allow 10.0.0.0/8} or {@code deny all}. */
    public List<String> accessRules = new ArrayList<>();

    /** Arguments of each limit_req, e.g. {@code zone=perip burst=10 nodelay}. */
    public List<String> limitReq = new ArrayList<>();
    /** Arguments of each limit_conn, e.g. {@code perip 10}. */
    public List<String> limitConn = new ArrayList<>();

    /** {@code on}, {@code off}, or empty for not set. */
    public String gzip = "";
    public String gzipTypes = "";
    public String gzipMinLength = "";
    public String gzipCompLevel = "";

    /** The cache zone name, {@code off}, or empty for not set. */
    public String proxyCache = "";
    /** Arguments of each proxy_cache_valid, e.g. {@code 200 302 10m}. */
    public List<String> proxyCacheValid = new ArrayList<>();
    public String proxyReadTimeout = "";

    /** Arguments of each error_page in this location. */
    public List<String> errorPages = new ArrayList<>();

    /** True if this settings object came from an existing block. */
    public boolean isExisting() {
        return source != null;
    }

    /** A new reverse proxy location with the usual headers. */
    public static LocationSettings newProxy(String path, String target) {
        LocationSettings l = new LocationSettings();
        l.path = path;
        l.type = Type.PROXY;
        l.originalType = Type.PROXY;
        l.proxyPass = target;
        l.proxySetHeaders = new ArrayList<>(DEFAULT_PROXY_HEADERS);
        return l;
    }

    /** A new static-files location. */
    public static LocationSettings newStatic(String path) {
        LocationSettings l = new LocationSettings();
        l.path = path;
        return l;
    }

    /** A new redirect location answering 301. */
    public static LocationSettings newRedirect(String path, String target) {
        LocationSettings l = new LocationSettings();
        l.path = path;
        l.type = Type.REDIRECT;
        l.originalType = Type.REDIRECT;
        l.redirectTarget = target;
        return l;
    }

    /**
     * A new PHP location for {@code ~ \.php$}. With a snippet it includes that (the Debian/Ubuntu
     * {@code snippets/fastcgi-php.conf} sets SCRIPT_FILENAME and PATH_INFO itself); without one it
     * includes {@code fastcgi_params} and sets SCRIPT_FILENAME.
     */
    public static LocationSettings newPhp(String fastcgiPass, String snippet) {
        LocationSettings l = new LocationSettings();
        l.modifier = "~";
        l.path = "\\.php$";
        l.type = Type.FASTCGI;
        l.originalType = Type.FASTCGI;
        l.fastcgiPass = fastcgiPass;
        if (snippet != null && !snippet.isBlank()) {
            l.fastcgiInclude = snippet;
        } else {
            l.fastcgiInclude = "fastcgi_params";
            l.fastcgiParams.add("SCRIPT_FILENAME $document_root$fastcgi_script_name");
        }
        return l;
    }

    /** A short label for lists, e.g. {@code ~* \.php$}. */
    public String title() {
        return (modifier.isEmpty() ? "" : modifier + " ") + path;
    }

    public LocationSettings copy() {
        LocationSettings c = new LocationSettings();
        c.source = source;
        c.originalType = originalType;
        c.modifier = modifier;
        c.path = path;
        c.type = type;
        c.root = root;
        c.alias = alias;
        c.index = index;
        c.tryFiles = tryFiles;
        c.proxyPass = proxyPass;
        c.proxySetHeaders = new ArrayList<>(proxySetHeaders);
        c.fastcgiPass = fastcgiPass;
        c.fastcgiInclude = fastcgiInclude;
        c.fastcgiParams = new ArrayList<>(fastcgiParams);
        c.fastcgiReadTimeout = fastcgiReadTimeout;
        c.redirectCode = redirectCode;
        c.redirectTarget = redirectTarget;
        c.authBasic = authBasic;
        c.authBasicUserFile = authBasicUserFile;
        c.satisfy = satisfy;
        c.accessRules = new ArrayList<>(accessRules);
        c.limitReq = new ArrayList<>(limitReq);
        c.limitConn = new ArrayList<>(limitConn);
        c.gzip = gzip;
        c.gzipTypes = gzipTypes;
        c.gzipMinLength = gzipMinLength;
        c.gzipCompLevel = gzipCompLevel;
        c.proxyCache = proxyCache;
        c.proxyCacheValid = new ArrayList<>(proxyCacheValid);
        c.proxyReadTimeout = proxyReadTimeout;
        c.errorPages = new ArrayList<>(errorPages);
        return c;
    }
}
