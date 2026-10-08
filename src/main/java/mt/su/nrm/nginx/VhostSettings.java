package mt.su.nrm.nginx;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The parts of a virtual host that the editor manages, as plain data. {@link VirtualHost#read}
 * fills one from a {@code server} block and {@link VirtualHost#apply} writes it back, touching
 * only what actually changed; everything else in the block is left exactly as it was.
 */
public final class VhostSettings {

    /** One {@code listen} directive. The parameters (ssl, http2, default_server, ...) keep their order. */
    public static final class ListenSpec {
        /** The address and/or port as written: {@code 80}, {@code 127.0.0.1:8080}, {@code [::]:443}, {@code unix:/x}. */
        public String endpoint = "80";
        public final List<String> params = new ArrayList<>();

        public ListenSpec() {
        }

        public ListenSpec(String endpoint, String... params) {
            this.endpoint = endpoint;
            this.params.addAll(Arrays.asList(params));
        }

        public boolean has(String param) {
            return params.contains(param);
        }

        public void set(String param, boolean on) {
            if (on && !params.contains(param)) {
                params.add(param);
            } else if (!on) {
                params.removeIf(param::equals);
            }
        }

        public boolean ssl() {
            return has("ssl");
        }

        public boolean defaultServer() {
            return has("default_server");
        }

        /**
         * The port (80 when only an address is given), or -1 for a unix socket or an endpoint that
         * isn't valid.
         */
        public int port() {
            String e = endpoint;
            if (e.startsWith("unix:")) {
                return -1;
            }
            String portPart;
            if (e.startsWith("[")) {
                int close = e.indexOf(']');
                if (close < 0) {
                    return -1;
                }
                String rest = e.substring(close + 1);
                if (rest.isEmpty()) {
                    return 80;
                }
                if (!rest.startsWith(":")) {
                    return -1;
                }
                portPart = rest.substring(1);
            } else {
                int colon = e.indexOf(':');
                if (colon != e.lastIndexOf(':')) {
                    return -1;
                }
                portPart = colon < 0 ? e : e.substring(colon + 1);
                if (colon < 0 && !isNumber(portPart)) {
                    return 80; // an address without a port
                }
            }
            return isNumber(portPart) && portPart.length() <= 6 ? Integer.parseInt(portPart) : -1;
        }

        private static boolean isNumber(String s) {
            if (s.isEmpty()) {
                return false;
            }
            for (int i = 0; i < s.length(); i++) {
                if (!Character.isDigit(s.charAt(i))) {
                    return false;
                }
            }
            return true;
        }

        List<String> toValues() {
            List<String> v = new ArrayList<>();
            v.add(endpoint);
            v.addAll(params);
            return v;
        }

        public ListenSpec copy() {
            return new ListenSpec(endpoint, params.toArray(new String[0]));
        }
    }

    /** One {@code add_header}. */
    public static final class HeaderSpec {
        public String name = "";
        public String value = "";
        public boolean always;

        /** The arguments this header was read from, so an unedited header is written back untouched. */
        private List<String> original;
        private String originalName;
        private String originalValue;
        private boolean originalAlways;

        public HeaderSpec() {
        }

        public HeaderSpec(String name, String value, boolean always) {
            this.name = name;
            this.value = value;
            this.always = always;
        }

        /** Reads a header from the arguments of an add_header directive. */
        public static HeaderSpec fromValues(List<String> values) {
            HeaderSpec h = new HeaderSpec(values.size() > 0 ? values.get(0) : "", values.size() > 1 ? values.get(1) : "",
                    values.size() > 2 && values.get(2).equals("always"));
            h.original = List.copyOf(values);
            h.originalName = h.name;
            h.originalValue = h.value;
            h.originalAlways = h.always;
            return h;
        }

        List<String> toValues() {
            if (original != null && name.equals(originalName) && value.equals(originalValue)
                    && always == originalAlways) {
                return original;
            }
            List<String> v = new ArrayList<>(List.of(name, value));
            if (always) {
                v.add("always");
            }
            return v;
        }

        public HeaderSpec copy() {
            HeaderSpec c = new HeaderSpec(name, value, always);
            c.original = original;
            c.originalName = originalName;
            c.originalValue = originalValue;
            c.originalAlways = originalAlways;
            return c;
        }
    }

    /**
     * How this site finds the real visitor behind a proxy: {@code set_real_ip_from} (the proxy addresses to believe),
     * {@code real_ip_header} (where the proxy puts the visitor's address) and {@code real_ip_recursive}. Set in the
     * server block, these replace whatever the http level says for this site only.
     */
    public static final class RealIpSpec {
        public List<String> sources = new ArrayList<>();
        /** A header name, or {@code proxy_protocol}; empty if the site doesn't set one (the http level's applies). */
        public String header = "";
        public boolean recursive;
        /**
         * Whether {@code real_ip_recursive off} is written when {@link #recursive} is false. Needed when the http level
         * turns it on, since a server inherits it otherwise; left off so that a site that never had the line doesn't gain one.
         */
        public boolean recursiveExplicit;

        public RealIpSpec() {
        }

        public RealIpSpec(List<String> sources, String header, boolean recursive) {
            this.sources = new ArrayList<>(sources);
            this.header = header;
            this.recursive = recursive;
        }

        public boolean isEmpty() {
            return sources.isEmpty() && header.isBlank();
        }

        public RealIpSpec copy() {
            RealIpSpec c = new RealIpSpec(sources, header, recursive);
            c.recursiveExplicit = recursiveExplicit;
            return c;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof RealIpSpec r && r.sources.equals(sources) && r.header.equals(header)
                    && r.recursive == recursive && r.recursiveExplicit == recursiveExplicit;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(sources, header, recursive, recursiveExplicit);
        }
    }

    /** A {@code rewrite} or {@code return} at the top level of the server block. */
    public static final class RuleSpec {
        /** Either {@code rewrite} or {@code return}. */
        public String directive = "rewrite";
        /** The arguments after the directive name, joined by single spaces. */
        public String arguments = "";

        public RuleSpec() {
        }

        public RuleSpec(String directive, String arguments) {
            this.directive = directive;
            this.arguments = arguments;
        }

        public RuleSpec copy() {
            return new RuleSpec(directive, arguments);
        }
    }

    public List<String> serverNames = new ArrayList<>();
    public List<ListenSpec> listens = new ArrayList<>();
    public String root = "";
    public String index = "";

    public String sslCertificate = "";
    public String sslCertificateKey = "";
    public String sslProtocols = "";
    public String sslCiphers = "";

    public List<HeaderSpec> headers = new ArrayList<>();
    public List<RuleSpec> rewrites = new ArrayList<>();

    /** This site's own real-visitor-IP settings, or null if it sets none (it then uses the http level's, if any). */
    public RealIpSpec realIp;

    public String clientMaxBodySize = "";
    public String limitRate = "";
    /** Arguments of each {@code limit_req}, e.g. {@code zone=perip burst=10 nodelay}. */
    public List<String> limitReq = new ArrayList<>();
    /** Arguments of each {@code limit_conn}, e.g. {@code perip 10}. */
    public List<String> limitConn = new ArrayList<>();

    /** Arguments of each {@code access_log}, e.g. {@code /var/log/nginx/a.log main} or {@code off}. */
    public List<String> accessLogs = new ArrayList<>();
    /** Arguments of {@code error_log}, e.g. {@code /var/log/nginx/e.log warn}. */
    public String errorLog = "";

    /** Arguments of each {@code error_page}, e.g. {@code 404 /404.html} or {@code 500 502 503 504 /50x.html}. */
    public List<String> errorPages = new ArrayList<>();

    public List<LocationSettings> locations = new ArrayList<>();

    public VhostSettings copy() {
        VhostSettings c = new VhostSettings();
        c.serverNames = new ArrayList<>(serverNames);
        listens.forEach(l -> c.listens.add(l.copy()));
        c.root = root;
        c.index = index;
        c.sslCertificate = sslCertificate;
        c.sslCertificateKey = sslCertificateKey;
        c.sslProtocols = sslProtocols;
        c.sslCiphers = sslCiphers;
        headers.forEach(h -> c.headers.add(h.copy()));
        rewrites.forEach(r -> c.rewrites.add(r.copy()));
        c.realIp = realIp == null ? null : realIp.copy();
        c.clientMaxBodySize = clientMaxBodySize;
        c.limitRate = limitRate;
        c.limitReq = new ArrayList<>(limitReq);
        c.limitConn = new ArrayList<>(limitConn);
        c.accessLogs = new ArrayList<>(accessLogs);
        c.errorLog = errorLog;
        c.errorPages = new ArrayList<>(errorPages);
        locations.forEach(l -> c.locations.add(l.copy()));
        return c;
    }

    /** True if any listen directive uses ssl. */
    public boolean usesSsl() {
        return listens.stream().anyMatch(ListenSpec::ssl);
    }

}
