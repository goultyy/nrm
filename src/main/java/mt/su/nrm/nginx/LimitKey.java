package mt.su.nrm.nginx;

/** What a rate or connection limit counts per: the plain-language choices behind nginx's key variables. */
public enum LimitKey {

    CLIENT_IP("$binary_remote_addr", "Each visitor (by IP address)",
            "One counter per visitor address. The usual choice: it stops one visitor from hogging the server.", "perip"),
    PROXIED_IP("$http_x_forwarded_for", "Each visitor behind a proxy or CDN",
            "Uses the X-Forwarded-For header. Only choose this if a trusted proxy in front of nginx sets that header.", "perproxied"),
    SITE("$server_name", "The whole site (one shared counter)",
            "Everyone shares one limit. Good for protecting a small backend from total overload.", "persite"),
    URL("$request_uri", "Each web address (URL)",
            "One counter per URL, for example to protect one expensive page.", "perurl"),
    CUSTOM("", "Something else (an nginx variable)",
            "Type any nginx variable, such as $http_authorization or $cookie_session.", "custom");

    private final String variable;
    private final String label;
    private final String description;
    private final String zonePrefix;

    LimitKey(String variable, String label, String description, String zonePrefix) {
        this.variable = variable;
        this.label = label;
        this.description = description;
        this.zonePrefix = zonePrefix;
    }

    /** The nginx variable, or "" for {@link #CUSTOM}. */
    public String variable() {
        return variable;
    }

    public String description() {
        return description;
    }

    /** A zone name that says what it counts, e.g. {@code perip_req}. */
    public String suggestedZoneName(LimitZoneSettings.Kind kind) {
        return zonePrefix + (kind == LimitZoneSettings.Kind.REQUEST ? "_req" : "_conn");
    }

    /** The choice for an existing variable; anything unknown is {@link #CUSTOM}. */
    public static LimitKey of(String variable) {
        for (LimitKey k : values()) {
            if (k != CUSTOM && k.variable.equals(variable)) {
                return k;
            }
        }
        return CUSTOM;
    }

    /** Roughly how many distinct keys fit in a limit zone of this many megabytes (nginx: about 16,000 per MB). */
    public static long keysInLimitZone(long megabytes) {
        return megabytes * 16_000L;
    }

    /** Roughly how many cached items a cache key index of this many megabytes can track (nginx: about 8,000 per MB). */
    public static long itemsInCacheIndex(long megabytes) {
        return megabytes * 8_000L;
    }

    @Override
    public String toString() {
        return label;
    }
}
