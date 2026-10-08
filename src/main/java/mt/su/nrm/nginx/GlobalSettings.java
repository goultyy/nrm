package mt.su.nrm.nginx;

import java.util.ArrayList;
import java.util.List;

/**
 * The server-wide settings of the main nginx.conf that the editor manages. An empty value means
 * the directive is not set (nginx uses its default).
 */
public final class GlobalSettings {

    // main context
    public String workerProcesses = "";
    public String errorLog = "";
    // events
    public String workerConnections = "";
    // http
    public String sendfile = "";
    public String tcpNopush = "";
    public String keepaliveTimeout = "";
    public String clientMaxBodySize = "";
    public String serverTokens = "";
    public String sslProtocols = "";
    public String sslPreferServerCiphers = "";
    public String accessLog = "";
    public String gzip = "";
    public String gzipCompLevel = "";
    public String gzipMinLength = "";
    public String gzipTypes = "";

    public GlobalSettings copy() {
        GlobalSettings c = new GlobalSettings();
        c.workerProcesses = workerProcesses;
        c.errorLog = errorLog;
        c.workerConnections = workerConnections;
        c.sendfile = sendfile;
        c.tcpNopush = tcpNopush;
        c.keepaliveTimeout = keepaliveTimeout;
        c.clientMaxBodySize = clientMaxBodySize;
        c.serverTokens = serverTokens;
        c.sslProtocols = sslProtocols;
        c.sslPreferServerCiphers = sslPreferServerCiphers;
        c.accessLog = accessLog;
        c.gzip = gzip;
        c.gzipCompLevel = gzipCompLevel;
        c.gzipMinLength = gzipMinLength;
        c.gzipTypes = gzipTypes;
        return c;
    }

    public List<String> problems() {
        List<String> p = new ArrayList<>();
        if (!workerProcesses.isBlank() && !workerProcesses.equals("auto") && !workerProcesses.matches("[1-9]\\d{0,3}")) {
            p.add("Worker processes must be auto or a number.");
        }
        if (!workerConnections.isBlank() && !workerConnections.matches("[1-9]\\d{0,6}")) {
            p.add("Worker connections must be a number.");
        }
        onOff(p, "sendfile", sendfile);
        onOff(p, "tcp_nopush", tcpNopush);
        onOff(p, "ssl_prefer_server_ciphers", sslPreferServerCiphers);
        onOff(p, "gzip", gzip);
        if (!keepaliveTimeout.isBlank() && !keepaliveTimeout.matches("\\d+(ms|s|m|h|d)?")) {
            p.add("Keepalive timeout must look like 65 or 65s.");
        }
        if (!clientMaxBodySize.isBlank() && !clientMaxBodySize.matches("\\d+[kKmMgG]?")) {
            p.add("Maximum upload size must look like 10m or 0.");
        }
        if (!serverTokens.isBlank() && !List.of("on", "off", "build").contains(serverTokens)) {
            p.add("server_tokens must be on, off or build.");
        }
        for (String proto : Arg.parseValues(sslProtocols)) {
            if (!List.of("SSLv2", "SSLv3", "TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3").contains(proto)) {
                p.add("\"" + proto + "\" is not a known SSL protocol.");
            }
        }
        if (!gzipCompLevel.isBlank() && !gzipCompLevel.matches("[1-9]")) {
            p.add("Gzip level must be 1 to 9.");
        }
        if (!gzipMinLength.isBlank() && !gzipMinLength.matches("\\d+[kKmM]?")) {
            p.add("Gzip minimum length must be a size such as 256.");
        }
        checkLog(p, "Error log", errorLog);
        checkLog(p, "Access log", accessLog);
        for (String v : List.of(workerProcesses, errorLog, workerConnections, sendfile, tcpNopush, keepaliveTimeout,
                clientMaxBodySize, serverTokens, sslProtocols, sslPreferServerCiphers, accessLog, gzip, gzipCompLevel,
                gzipMinLength, gzipTypes)) {
            if (v.chars().anyMatch(c -> c < 0x20)) {
                p.add("A value contains invalid characters.");
                break;
            }
        }
        return p;
    }

    private static void onOff(List<String> p, String name, String value) {
        if (!value.isBlank() && !value.equals("on") && !value.equals("off")) {
            p.add(name + " must be on or off.");
        }
    }

    private static void checkLog(List<String> p, String label, String value) {
        List<String> v = Arg.parseValues(value);
        if (!v.isEmpty() && !v.get(0).startsWith("/") && !v.get(0).startsWith("syslog:") && !v.get(0).equals("off")
                && !v.get(0).equals("stderr")) {
            p.add(label + " must be an absolute path, syslog:, stderr or off.");
        }
    }
}
