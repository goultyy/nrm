package mt.su.nrm.security;

import mt.su.nrm.nginx.Arg;
import mt.su.nrm.nginx.LocationSettings;
import mt.su.nrm.nginx.VhostSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * Browser security headers for a site: what each one does, what it can break, the sets that make sense together, and
 * how they are merged into a site's {@code add_header} lines without disturbing the other headers. No JavaFX.
 * <p>
 * Every header is added with {@code always}, so it is also sent with error pages. Headers that already exist under
 * the same name are replaced, never duplicated.
 */
public final class SecurityHeaders {

    /** How far a preset goes. {@code OPTIONAL} headers belong to no preset and are only ticked by hand. */
    public enum Level { BASIC, RECOMMENDED, STRICT, OPTIONAL }

    /**
     * @param strictValue the value used instead of {@code value} in the Strict preset, or null
     * @param why         what it protects against
     * @param risk        what it can break
     * @param needsHttps  only has any effect (or is only safe) on a site served over HTTPS
     */
    public record Header(String name, String value, String strictValue, Level level, String why, String risk,
                         boolean needsHttps) {

        public String valueAt(Level preset) {
            return preset == Level.STRICT && strictValue != null ? strictValue : value;
        }
    }

    public enum State { MISSING, SET, DIFFERENT }

    /** One header of the catalog as it stands on a site. {@code current} is the value now, or null if missing. */
    public record Row(Header header, State state, String current, boolean withoutAlways) {
    }

    /** A header the user chose to add, with its value (which they may have edited). */
    public record Choice(String name, String value) {
    }

    private static final String CSP_STARTER = "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
            + "script-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'self'";

    private static final List<Header> CATALOG = List.of(
            new Header("X-Content-Type-Options", "nosniff", null, Level.BASIC,
                    "Stops browsers guessing a file's type, which attackers use to run an uploaded file as a script.",
                    "Almost none. A file served with the wrong type (a script as text/plain) stops working, which "
                            + "is the point.", false),
            new Header("X-Frame-Options", "SAMEORIGIN", null, Level.BASIC,
                    "Stops other websites from showing your pages inside a frame (clickjacking).",
                    "Pages can't be embedded by other sites any more. If something legitimate embeds this site, it "
                            + "breaks.", false),
            new Header("Referrer-Policy", "strict-origin-when-cross-origin", null, Level.BASIC,
                    "Sends other sites only your domain, not the full address with its path and query, when a "
                            + "visitor follows a link away.",
                    "Analytics on other sites see less of where visitors came from.", false),
            new Header("Strict-Transport-Security", "max-age=31536000", "max-age=31536000; includeSubDomains",
                    Level.RECOMMENDED,
                    "Tells browsers to use HTTPS only for this site for a year, so they refuse to fall back to HTTP.",
                    "Hard to undo: for a year, browsers that saw it will refuse the site over plain HTTP, even if you "
                            + "fix or move it. Only use it when HTTPS works properly. The strict version also covers "
                            + "every subdomain, so they all need HTTPS.", true),
            new Header("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()", null,
                    Level.RECOMMENDED,
                    "Switches off browser features the site doesn't use, so injected code can't use them either.",
                    "A page that does use the camera, microphone, location or payments stops being able to. Remove "
                            + "the ones you need.", false),
            new Header("Content-Security-Policy", CSP_STARTER, null, Level.STRICT,
                    "Lists where scripts, styles and images may come from. The strongest protection against injected "
                            + "scripts.",
                    "The most likely to break a site: anything loaded from another domain (analytics, fonts, "
                            + "CDNs, videos) or written inline is blocked until you list it. Try the report-only "
                            + "version first.", false),
            new Header("Cross-Origin-Opener-Policy", "same-origin", null, Level.STRICT,
                    "Keeps pages opened from other sites in a separate process, closing a class of side-channel attacks.",
                    "Pop-up windows that talk to the opener (some payment and login pop-ups) stop working.", false),
            new Header("Cross-Origin-Resource-Policy", "same-origin", null, Level.STRICT,
                    "Stops other sites from loading this site's images, scripts and files.",
                    "Other sites can no longer embed your images or files, including your own other domains.", false),
            new Header("Content-Security-Policy-Report-Only", CSP_STARTER, null, Level.OPTIONAL,
                    "The Content-Security-Policy starter, but only reporting in the browser console what it would "
                            + "block. Use it to find out what a real policy would break.",
                    "Nothing is blocked; the browser console fills with warnings.", false));

    private SecurityHeaders() {
    }

    public static List<Header> catalog() {
        return CATALOG;
    }

    public static Header find(String name) {
        return CATALOG.stream().filter(h -> h.name().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    /** What a preset includes: its own level and every level below it. */
    public static List<Header> preset(Level level) {
        return CATALOG.stream().filter(h -> h.level() != Level.OPTIONAL && h.level().ordinal() <= level.ordinal()).toList();
    }

    public static String describe(Level level) {
        return switch (level) {
            case BASIC -> "Basic: three headers that are safe for nearly every site.";
            case RECOMMENDED -> "Recommended: Basic, plus HTTPS-only (HSTS) and switching off browser features the site "
                    + "doesn't use.";
            case STRICT -> "Strict: Recommended, plus a Content-Security-Policy and cross-origin isolation. Likely to "
                    + "need adjusting for sites that load things from other domains.";
            case OPTIONAL -> "";
        };
    }

    // ---------------------------------------------------------------- reading a site

    /** True if the site is served over HTTPS (it has a certificate or listens with ssl). */
    public static boolean usesHttps(VhostSettings s) {
        if (!s.sslCertificate.isBlank()) {
            return true;
        }
        return s.listens.stream().anyMatch(l -> l.params.stream().anyMatch(p -> p.equalsIgnoreCase("ssl")));
    }

    /** Each catalog header against the site's current {@code add_header} lines (one per line, as in the Headers tab). */
    public static List<Row> review(String headerLines) {
        List<List<String>> existing = parse(headerLines);
        List<Row> rows = new ArrayList<>();
        for (Header h : CATALOG) {
            List<String> found = null;
            for (List<String> values : existing) {
                if (!values.isEmpty() && values.get(0).equalsIgnoreCase(h.name())) {
                    found = values;
                }
            }
            if (found == null) {
                rows.add(new Row(h, State.MISSING, null, false));
            } else {
                String value = found.size() > 1 ? found.get(1) : "";
                boolean always = found.size() > 2 && found.get(2).equals("always");
                rows.add(new Row(h, value.equals(h.value()) || value.equals(h.strictValue()) ? State.SET : State.DIFFERENT,
                        value, !always));
            }
        }
        return rows;
    }

    /**
     * Things to know before adding headers to this site, worded for the user:
     * nginx drops a server's {@code add_header} lines inside any location that has an {@code add_header} of its own.
     */
    public static List<String> warnings(VhostSettings s, List<Choice> chosen) {
        List<String> warnings = new ArrayList<>();
        List<String> shadowed = new ArrayList<>();
        for (LocationSettings l : s.locations) {
            if (l.definesOwnHeaders()) {
                shadowed.add(l.path);
            }
        }
        if (!shadowed.isEmpty() && !chosen.isEmpty()) {
            warnings.add("nginx does not apply a site's headers inside a location that has add_header lines of its own. "
                    + "These locations do, so the headers below won't reach them unless they are repeated there: "
                    + String.join(", ", shadowed) + ".");
        }
        if (!usesHttps(s)) {
            for (Choice c : chosen) {
                Header h = find(c.name());
                if (h != null && h.needsHttps()) {
                    warnings.add(h.name() + " is only meaningful over HTTPS and this site has no SSL certificate. "
                            + "Browsers ignore it on plain HTTP; add SSL first.");
                }
            }
        }
        for (Choice c : chosen) {
            if (c.value().contains("preload")) {
                warnings.add("\"preload\" asks browsers to hard-code this site as HTTPS-only. That is very hard to "
                        + "reverse; don't add it unless you mean it.");
            }
        }
        return warnings;
    }

    // ---------------------------------------------------------------- changing a site

    /**
     * The header lines with the chosen headers added. A line for a header of the same name (any case) is replaced in
     * place; the rest are appended. Every other line is kept exactly as it was.
     */
    public static String merge(String headerLines, List<Choice> chosen) {
        // The Headers tab ignores blank lines, so they are not worth keeping (and an empty text has one "line").
        List<String> lines = new ArrayList<>();
        for (String line : headerLines.split("\r?\n")) {
            if (!line.isBlank()) {
                lines.add(line);
            }
        }
        List<Choice> remaining = new ArrayList<>(chosen);
        for (int i = 0; i < lines.size(); i++) {
            List<String> values = Arg.parseValues(lines.get(i));
            if (values.isEmpty()) {
                continue;
            }
            for (Choice c : remaining) {
                if (values.get(0).equalsIgnoreCase(c.name())) {
                    lines.set(i, line(c));
                    remaining.remove(c);
                    break;
                }
            }
        }
        for (Choice c : remaining) {
            lines.add(line(c));
        }
        return String.join("\n", lines);
    }

    /** The line for one header: {@code Name "value" always}. */
    static String line(Choice c) {
        return Arg.of(c.name()).raw() + " " + Arg.of(c.value()).raw() + " always";
    }

    /** Problems with a header the user typed or edited, or null if it is fine. */
    public static String problem(Choice c) {
        if (!c.name().matches("[A-Za-z0-9-]+")) {
            return c.name() + " is not a valid header name.";
        }
        if (c.value().isBlank()) {
            return c.name() + " needs a value.";
        }
        if (c.value().chars().anyMatch(ch -> ch < 0x20 && ch != '\t')) {
            return c.name() + " has a line break or control character in its value.";
        }
        return null;
    }

    private static List<List<String>> parse(String lines) {
        List<List<String>> parsed = new ArrayList<>();
        for (String line : lines.split("\r?\n")) {
            if (!line.isBlank()) {
                parsed.add(Arg.parseValues(line));
            }
        }
        return parsed;
    }
}
