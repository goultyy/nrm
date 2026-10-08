package mt.su.nrm.ssl;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validation and formatting of the names (subject alternative names) a certificate is issued for. */
public final class AltNames {

    public enum Type { DNS, IP, EMAIL, URI }

    private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?");
    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:]{2,39}");
    /** Characters allowed in a URI name: nothing that could end a config line or start a variable. */
    private static final Pattern URI = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*://[A-Za-z0-9._~:/?&=%@+-]+");

    private AltNames() {
    }

    /** A validated name. */
    public record Name(Type type, String value) {

        public static Name dns(String value) {
            return new Name(Type.DNS, value);
        }

        public static Name ip(String value) {
            return new Name(Type.IP, value);
        }

        public boolean isIp() {
            return type == Type.IP;
        }

        /** The subjectAltName entry, e.g. {@code DNS:example.com}, {@code IP:10.0.0.1}, {@code email:a@b.c}. */
        public String entry() {
            switch (type) {
                case IP:
                    return "IP:" + value;
                case EMAIL:
                    return "email:" + value;
                case URI:
                    return "URI:" + value;
                default:
                    return "DNS:" + value;
            }
        }
    }

    /** Result of parsing user input: the names, and what was wrong. */
    public record Parsed(List<Name> names, List<String> problems) {
    }

    /** DNS names and IP addresses only (wildcards optional). */
    public static Parsed parse(String text, boolean allowWildcard) {
        return parse(text, allowWildcard, false);
    }

    /**
     * Splits input on whitespace, commas and semicolons and classifies each item. E-mail addresses
     * and URIs are written {@code email:me@example.com} and {@code uri:https://example.com/x}.
     *
     * @param allowWildcard whether {@code *.example.com} is accepted (fine for a private CA, not for Let's Encrypt's webroot method)
     * @param allowEmailUri whether {@code email:} and {@code uri:} names are accepted
     */
    public static Parsed parse(String text, boolean allowWildcard, boolean allowEmailUri) {
        List<String> problems = new ArrayList<>();
        Set<Name> names = new LinkedHashSet<>();
        for (String item : text == null ? new String[0] : text.strip().split("[\\s,;]+")) {
            if (item.isEmpty()) {
                continue;
            }
            String lower = item.toLowerCase(Locale.ROOT);
            if (allowEmailUri && lower.startsWith("email:")) {
                String v = item.substring(6);
                if (isEmail(v)) {
                    names.add(new Name(Type.EMAIL, v));
                } else {
                    problems.add("\"" + v + "\" is not a valid e-mail address.");
                }
            } else if (allowEmailUri && lower.startsWith("uri:")) {
                String v = item.substring(4);
                if (URI.matcher(v).matches()) {
                    names.add(new Name(Type.URI, v));
                } else {
                    problems.add("\"" + v + "\" is not a valid URI (use something like https://example.com/path).");
                }
            } else if (isIp(item)) {
                names.add(Name.ip(item));
            } else if (isDnsName(item, allowWildcard)) {
                names.add(Name.dns(lower));
            } else {
                problems.add("\"" + item + "\" is not a valid " + (allowWildcard ? "host name or IP address"
                        + (allowEmailUri ? " (write e-mail names as email:me@example.com)" : "") : "host name") + ".");
            }
        }
        if (names.isEmpty() && problems.isEmpty()) {
            problems.add("Enter at least one name.");
        }
        return new Parsed(new ArrayList<>(names), problems);
    }

    public static boolean isDnsName(String name, boolean allowWildcard) {
        String n = name;
        if (n.startsWith("*.")) {
            if (!allowWildcard) {
                return false;
            }
            n = n.substring(2);
        }
        if (n.isEmpty() || n.length() > 253 || n.endsWith(".")) {
            return false;
        }
        String[] labels = n.split("\\.", -1);
        for (String label : labels) {
            if (!LABEL.matcher(label).matches()) {
                return false;
            }
        }
        // A top-level label is never all digits, so this also rejects malformed IP addresses like 999.1.1.1.
        return !labels[labels.length - 1].matches("\\d+");
    }

    public static boolean isIp(String s) {
        Matcher m = IPV4.matcher(s);
        if (m.matches()) {
            for (int i = 1; i <= 4; i++) {
                if (Integer.parseInt(m.group(i)) > 255) {
                    return false;
                }
            }
            return true;
        }
        return s.contains(":") && IPV6.matcher(s).matches();
    }

    public static String subjectAltName(List<Name> names) {
        List<String> entries = new ArrayList<>();
        for (Name n : names) {
            entries.add(n.entry());
        }
        return String.join(",", entries);
    }

    /** True for a plausible e-mail address (one @, no spaces, a dot in the domain). */
    public static boolean isEmail(String s) {
        return s != null && s.matches("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+");
    }
}
