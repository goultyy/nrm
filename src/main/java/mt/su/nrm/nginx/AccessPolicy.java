package mt.su.nrm.nginx;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Who may open a location, in plain terms: anyone, people with a password, people on certain
 * networks, either of those, or both. The wizard edits one of these; {@link #applyTo} turns it into
 * the nginx directives ({@code auth_basic}, {@code allow}/{@code deny}, {@code satisfy}) and
 * {@link #from} reads it back from a location.
 */
public final class AccessPolicy {

    public enum Mode {
        EVERYONE("Anyone can open it", "No restriction."),
        PASSWORD("Only people with a username and password",
                "Visitors are asked to log in with a name and password."),
        NETWORK("Only people on certain networks", "Only visitors from the IP addresses or networks you list."),
        EITHER("A password OR a trusted network",
                "People on a trusted network get straight in; everyone else must log in. Handy for an office network."),
        BOTH("A password AND a trusted network",
                "Visitors must be on a trusted network and also log in.");

        private final String label;
        private final String description;

        Mode(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public String description() {
            return description;
        }

        public boolean usesPassword() {
            return this == PASSWORD || this == EITHER || this == BOTH;
        }

        public boolean usesNetworks() {
            return this == NETWORK || this == EITHER || this == BOTH;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** The networks people may come from when a wizard first asks. */
    public static final List<String> PRIVATE_NETWORKS = List.of("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16");

    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})(\\.\\d{1,3}){3}(/(\\d{1,2}))?");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]{2,45}(/(\\d{1,3}))?");

    public Mode mode = Mode.EVERYONE;
    /** The text of the login box. */
    public String realm = "Restricted area";
    /** The password file on the server. */
    public String passwordFile = "";
    /** Addresses and networks that may come in, e.g. {@code 10.0.0.0/8}. */
    public List<String> networks = new ArrayList<>();

    /** Reads the current access setup of a location. */
    public static AccessPolicy from(LocationSettings l) {
        AccessPolicy p = new AccessPolicy();
        boolean password = !l.authBasic.isBlank() && !l.authBasic.equals("off");
        List<String> allowed = new ArrayList<>();
        for (String rule : l.accessRules) {
            List<String> v = Arg.parseValues(rule);
            if (v.size() == 2 && v.get(0).equals("allow") && !v.get(1).equals("all")) {
                allowed.add(v.get(1));
            }
        }
        boolean network = !l.accessRules.isEmpty();
        if (password && network) {
            p.mode = l.satisfy.equals("any") ? Mode.EITHER : Mode.BOTH;
        } else if (password) {
            p.mode = Mode.PASSWORD;
        } else if (network) {
            p.mode = Mode.NETWORK;
        }
        if (password) {
            p.realm = l.authBasic;
        }
        p.passwordFile = l.authBasicUserFile;
        p.networks = allowed;
        return p;
    }

    /** Writes this policy into a location's access settings; other settings are left alone. */
    public void applyTo(LocationSettings l) {
        if (mode.usesPassword()) {
            l.authBasic = realm.strip();
            l.authBasicUserFile = passwordFile.strip();
        } else {
            // An explicit "off" (which switches off a login inherited from higher up) stays as it was.
            l.authBasic = l.authBasic.equals("off") ? "off" : "";
            l.authBasicUserFile = "";
        }
        if (mode.usesNetworks()) {
            List<String> rules = new ArrayList<>();
            for (String n : networks) {
                rules.add("allow " + n.strip());
            }
            rules.add("deny all");
            l.accessRules = rules;
        } else {
            l.accessRules = new ArrayList<>();
        }
        l.satisfy = mode == Mode.EITHER ? "any" : "";
    }

    /** What is wrong with the choices, worded for the user; empty if fine. */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (mode.usesPassword()) {
            if (realm.isBlank() || realm.chars().anyMatch(c -> c < 0x20 || c == '"')) {
                problems.add("Enter the text for the login box (without quotes).");
            }
            if (passwordFile.isBlank() || !passwordFile.strip().startsWith("/")
                    || passwordFile.chars().anyMatch(c -> c < 0x21)) {
                problems.add("The password file must be an absolute path without spaces.");
            }
        }
        if (mode.usesNetworks()) {
            if (networks.isEmpty()) {
                problems.add("List at least one address or network that may come in.");
            }
            for (String n : networks) {
                if (!isNetwork(n.strip())) {
                    problems.add("\"" + n + "\" is not a valid IP address or network (for example 203.0.113.7 or 10.0.0.0/8).");
                }
            }
        }
        return problems;
    }

    /** True for an IPv4 or IPv6 address, optionally with a network size such as /24. */
    public static boolean isNetwork(String text) {
        java.util.regex.Matcher v4 = IPV4.matcher(text);
        if (v4.matches()) {
            String[] parts = text.split("[/]")[0].split("\\.");
            for (String part : parts) {
                if (Integer.parseInt(part) > 255) {
                    return false;
                }
            }
            return v4.group(4) == null || Integer.parseInt(v4.group(4)) <= 32;
        }
        java.util.regex.Matcher v6 = IPV6.matcher(text);
        if (v6.matches() && text.contains(":")) {
            return v6.group(2) == null || Integer.parseInt(v6.group(2)) <= 128;
        }
        return false;
    }

    /** The directives this policy produces, as they would appear in the location. */
    public String preview() {
        LocationSettings l = new LocationSettings();
        applyTo(l);
        StringBuilder sb = new StringBuilder();
        if (mode == Mode.EVERYONE) {
            return "(no access restrictions)";
        }
        if (!l.authBasic.isBlank()) {
            sb.append("auth_basic \"").append(l.authBasic).append("\";\n");
            sb.append("auth_basic_user_file ").append(l.authBasicUserFile).append(";\n");
        }
        if (!l.satisfy.isBlank()) {
            sb.append("satisfy ").append(l.satisfy).append(";\n");
        }
        for (String rule : l.accessRules) {
            sb.append(rule).append(";\n");
        }
        return sb.toString().stripTrailing();
    }
}
