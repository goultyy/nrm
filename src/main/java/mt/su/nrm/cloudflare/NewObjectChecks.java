package mt.su.nrm.cloudflare;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** What is wrong with the name of a zone or tunnel about to be created, worded for the user. */
public final class NewObjectChecks {

    public static final int MAX_TUNNEL_NAME = 100;

    private static final Pattern DOMAIN =
            Pattern.compile("([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z][a-z0-9-]{1,62}");

    private NewObjectChecks() {
    }

    /** A zone is a registrable domain such as example.com, not a URL and not a subdomain's record. */
    public static List<String> zoneProblems(String domain, Collection<String> existingZones) {
        List<String> problems = new ArrayList<>();
        String name = domain.strip().toLowerCase(Locale.ROOT);
        if (name.isEmpty()) {
            problems.add("Enter the domain name.");
        } else if (name.contains("://") || name.contains("/") || name.chars().anyMatch(Character::isWhitespace)) {
            problems.add("Enter just the domain, such as example.com.");
        } else if (!DOMAIN.matcher(name).matches()) {
            problems.add("That doesn't look like a domain name. Use something like example.com.");
        } else if (existingZones.stream().anyMatch(z -> z.equalsIgnoreCase(name))) {
            problems.add(name + " is already in this account.");
        }
        return problems;
    }

    public static List<String> tunnelProblems(String name, Collection<String> existingTunnels) {
        List<String> problems = new ArrayList<>();
        String trimmed = name.strip();
        if (trimmed.isEmpty()) {
            problems.add("Enter a name for the tunnel.");
        } else if (trimmed.length() > MAX_TUNNEL_NAME) {
            problems.add("The name can be at most " + MAX_TUNNEL_NAME + " characters.");
        } else if (trimmed.chars().anyMatch(Character::isISOControl)) {
            problems.add("The name contains characters that can't be used.");
        } else if (existingTunnels.stream().anyMatch(t -> t.equalsIgnoreCase(trimmed))) {
            problems.add("A tunnel named \"" + trimmed + "\" already exists.");
        }
        return problems;
    }
}
