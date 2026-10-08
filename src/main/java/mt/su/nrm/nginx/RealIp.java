package mt.su.nrm.nginx;

import mt.su.nrm.nginx.VhostSettings.RealIpSpec;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks for a site's real-visitor-IP settings (see {@link RealIpSpec}), and reading the ones at the http level that a
 * site without its own inherits. Nothing here is specific to one proxy; see {@code mt.su.nrm.proxy.ProxyPreset}.
 * <p>
 * Trusting an address means believing the header it sends. A trusted range that is too wide lets anyone claim to be
 * anybody, so the whole internet ({@code /0}) is refused and very wide ranges are warned about.
 */
public final class RealIp {

    private static final List<String> NAMES = List.of("set_real_ip_from", "real_ip_header", "real_ip_recursive");
    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
    private static final Pattern HEADER_NAME = Pattern.compile("[A-Za-z0-9-]+");

    private RealIp() {
    }

    // ---------------------------------------------------------------- the http level

    /**
     * The settings outside any server or location (the http block, or the top of a file included into it), which
     * every site without its own inherits; empty if none are set.
     */
    public static Optional<RealIpSpec> inherited(RemoteConfig config) {
        RealIpSpec spec = new RealIpSpec();
        boolean found = false;
        for (ConfigFile file : config.files()) {
            found |= collect(file.root(), spec);
        }
        return found ? Optional.of(spec) : Optional.empty();
    }

    private static boolean collect(Block block, RealIpSpec into) {
        boolean found = false;
        for (Node n : block.children()) {
            if (n instanceof Directive d && NAMES.contains(d.name())) {
                found = true;
                String value = d.arg(0) == null ? "" : d.arg(0);
                switch (d.name()) {
                    case "set_real_ip_from" -> {
                        if (!value.isEmpty() && !into.sources.contains(value)) {
                            into.sources.add(value);
                        }
                    }
                    case "real_ip_header" -> into.header = value;
                    default -> {
                        into.recursive = value.equals("on");
                        into.recursiveExplicit = true;
                    }
                }
            } else if (n instanceof Block child && !child.isOpaque() && !child.name().equals("server")
                    && !child.name().equals("location")) {
                found |= collect(child, into);
            }
        }
        return found;
    }

    /** A one-line description for the user, e.g. "believes 15 addresses and reads the visitor from CF-Connecting-IP". */
    public static String describe(RealIpSpec spec) {
        int n = spec.sources.size();
        return "believes " + n + " address" + (n == 1 ? "" : "es") + " and reads the visitor from "
                + (spec.header.isEmpty() ? "(no header chosen)" : spec.header);
    }

    // ---------------------------------------------------------------- checking

    /** True for an IPv4 or IPv6 address, or such an address with a prefix length (a CIDR range). Host names are not accepted. */
    public static boolean validSource(String source) {
        if (source == null || source.isBlank() || !source.equals(source.strip())) {
            return false;
        }
        int slash = source.indexOf('/');
        String address = slash < 0 ? source : source.substring(0, slash);
        boolean v6 = address.contains(":");
        if (slash >= 0) {
            String prefix = source.substring(slash + 1);
            if (!prefix.matches("\\d{1,3}") || Integer.parseInt(prefix) > (v6 ? 128 : 32)) {
                return false;
            }
        }
        if (v6) {
            if (!address.matches("[0-9A-Fa-f:.]+")) {
                return false;
            }
            try {
                // A literal containing ':' is parsed, never looked up, so this does no network access.
                InetAddress.getByName(address);
                return true;
            } catch (UnknownHostException e) {
                return false;
            }
        }
        Matcher m = IPV4.matcher(address);
        if (!m.matches()) {
            return false;
        }
        for (int i = 1; i <= 4; i++) {
            if (Integer.parseInt(m.group(i)) > 255) {
                return false;
            }
        }
        return true;
    }

    /** True for private and loopback space, which no outsider can send from, so a wide range there is no risk. */
    private static boolean isPrivate(String source) {
        String s = source.toLowerCase(java.util.Locale.ROOT);
        int prefix = prefixOf(source);
        // The range must lie wholly inside the private block, so 10.0.0.0/4 (which reaches public space) doesn't count.
        return ((s.startsWith("fc") || s.startsWith("fd")) && prefix >= 7)
                || (s.startsWith("::1") && prefix == 128)
                || ((s.startsWith("10.") || s.startsWith("127.")) && prefix >= 8)
                || (s.startsWith("192.168.") && prefix >= 16);
    }

    /** The prefix length of a range, or the full length for a single address. */
    static int prefixOf(String source) {
        int slash = source.indexOf('/');
        if (slash >= 0) {
            return Integer.parseInt(source.substring(slash + 1));
        }
        return source.contains(":") ? 128 : 32;
    }

    /** What stops these settings from being saved, worded for the user; empty if they can be. */
    public static List<String> problems(RealIpSpec spec) {
        List<String> problems = new ArrayList<>();
        for (String source : spec.sources) {
            if (!validSource(source)) {
                problems.add("\"" + source + "\" is not an IP address or a range like 10.0.0.0/8.");
            } else if (source.contains("/") && prefixOf(source) == 0) {
                problems.add(source + " would trust every address on the internet, so anyone could fake theirs. "
                        + "Trust only the proxy's own addresses.");
            }
        }
        String header = spec.header;
        if (!header.isBlank() && !HEADER_NAME.matcher(header).matches() && !header.equals("proxy_protocol")) {
            problems.add("\"" + header + "\" is not a usable header name.");
        }
        return problems;
    }

    /** Things worth knowing that don't stop the settings from being saved. */
    public static List<String> warnings(RealIpSpec spec) {
        List<String> warnings = new ArrayList<>();
        for (String source : spec.sources) {
            if (validSource(source) && source.contains("/")) {
                int prefix = prefixOf(source);
                boolean v6 = source.contains(":");
                if (prefix > 0 && prefix < (v6 ? 16 : 8) && !isPrivate(source)) {
                    warnings.add(source + " is a very wide range. Everything in it can set the visitor's address.");
                }
            }
        }
        if (!spec.header.isBlank() && spec.sources.isEmpty()) {
            warnings.add("No proxy addresses are listed here, so the ones set at the http level are used. If there are "
                    + "none, nginx ignores the header.");
        }
        if (spec.header.isBlank() && !spec.sources.isEmpty()) {
            warnings.add("No header is chosen here, so the one set at the http level is used (if any).");
        }
        if (spec.header.equalsIgnoreCase("X-Forwarded-For") && !spec.recursive) {
            warnings.add("X-Forwarded-For can list several addresses. Without searching past trusted ones, nginx "
                    + "uses the last address in the list, which may be another proxy.");
        }
        if (spec.header.equals("proxy_protocol")) {
            warnings.add("proxy_protocol also needs \"proxy_protocol\" on the listen line of each site behind the "
                    + "proxy; otherwise nginx cannot read the connection.");
        }
        return warnings;
    }
}
