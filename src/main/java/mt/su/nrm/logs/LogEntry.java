package mt.su.nrm.logs;

import java.time.Instant;

/**
 * One access log line, reduced to what the analysis uses. Fields a log format doesn't have are null, or -1 for numbers.
 *
 * @param ip          the visitor's address (the real one if the format logs it, see {@link LogParser})
 * @param time        when the request was handled
 * @param method      GET, POST, ...
 * @param uri         the requested address with its query string, as logged
 * @param status      the HTTP status, or -1
 * @param bytes       bytes sent in the body, or -1
 * @param requestTime seconds nginx took, or -1
 */
public record LogEntry(String ip, Instant time, String method, String uri, int status, long bytes, double requestTime,
                       String referrer, String userAgent, String host) {

    /** The address without its query string, so /search?q=a and /search?q=b count as one page. */
    public String path() {
        if (uri == null || uri.isEmpty()) {
            return "";
        }
        int q = uri.indexOf('?');
        return q < 0 ? uri : uri.substring(0, q);
    }

    public boolean isError() {
        return status >= 400;
    }

    /** True if the user agent looks like a crawler or script rather than a person's browser. */
    public boolean looksAutomated() {
        if (userAgent == null || userAgent.isEmpty() || userAgent.equals("-")) {
            return false;
        }
        String ua = userAgent.toLowerCase(java.util.Locale.ROOT);
        return ua.contains("bot") || ua.contains("crawl") || ua.contains("spider") || ua.contains("curl/")
                || ua.contains("wget/") || ua.contains("python-requests") || ua.contains("go-http-client")
                || ua.contains("scrapy") || ua.contains("headlesschrome");
    }
}
