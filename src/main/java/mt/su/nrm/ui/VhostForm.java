package mt.su.nrm.ui;

import mt.su.nrm.nginx.Arg;
import mt.su.nrm.nginx.VhostSettings;
import mt.su.nrm.nginx.VhostSettings.HeaderSpec;
import mt.su.nrm.nginx.VhostSettings.ListenSpec;
import mt.su.nrm.nginx.VhostSettings.RuleSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * The text of the virtual host editor's fields, and the rules for turning it into
 * {@link VhostSettings} and back. Kept free of JavaFX so it can be unit tested. List-like settings
 * are one entry per line, written the way nginx takes the directive's arguments, so what the user
 * sees matches what ends up in the file.
 * <p>
 * Locations are edited separately; {@link #applyTo} carries them over from the base settings.
 */
public final class VhostForm {

    public String serverNames = "";
    public String listens = "";
    public String root = "";
    public String index = "";
    public String sslCertificate = "";
    public String sslCertificateKey = "";
    public String sslProtocols = "";
    public String sslCiphers = "";
    public String headers = "";
    public String rewrites = "";
    public String clientMaxBodySize = "";
    public String limitRate = "";
    public String limitReq = "";
    public String limitConn = "";
    public String accessLogs = "";
    public String errorLog = "";
    public String errorPages = "";

    public static VhostForm from(VhostSettings s) {
        VhostForm f = new VhostForm();
        f.serverNames = joinArgs(s.serverNames);
        f.listens = lines(s.listens.stream().map(l -> {
            List<String> all = new ArrayList<>();
            all.add(l.endpoint);
            all.addAll(l.params);
            return joinArgs(all);
        }).toList());
        f.root = s.root;
        f.index = s.index;
        f.sslCertificate = s.sslCertificate;
        f.sslCertificateKey = s.sslCertificateKey;
        f.sslProtocols = s.sslProtocols;
        f.sslCiphers = s.sslCiphers;
        f.headers = lines(s.headers.stream().map(VhostForm::headerLine).toList());
        f.rewrites = lines(s.rewrites.stream().map(r -> r.directive + " " + r.arguments).toList());
        f.clientMaxBodySize = s.clientMaxBodySize;
        f.limitRate = s.limitRate;
        f.limitReq = lines(s.limitReq);
        f.limitConn = lines(s.limitConn);
        f.accessLogs = lines(s.accessLogs);
        f.errorLog = s.errorLog;
        f.errorPages = lines(s.errorPages);
        return f;
    }

    /** A copy of {@code base} with everything but the locations replaced by what the form says. */
    public VhostSettings applyTo(VhostSettings base) {
        VhostSettings s = base.copy();
        s.serverNames = Arg.parseValues(serverNames);
        s.listens = new ArrayList<>();
        for (String line : entries(listens)) {
            List<String> tokens = Arg.parseValues(line);
            if (!tokens.isEmpty()) {
                s.listens.add(new ListenSpec(tokens.get(0), tokens.subList(1, tokens.size()).toArray(new String[0])));
            }
        }
        s.root = root.strip();
        s.index = index.strip();
        s.sslCertificate = sslCertificate.strip();
        s.sslCertificateKey = sslCertificateKey.strip();
        s.sslProtocols = sslProtocols.strip();
        s.sslCiphers = sslCiphers.strip();
        s.headers = new ArrayList<>();
        for (String line : entries(headers)) {
            s.headers.add(HeaderSpec.fromValues(Arg.parseValues(line)));
        }
        s.rewrites = new ArrayList<>();
        for (String line : entries(rewrites)) {
            int space = indexOfWhitespace(line);
            s.rewrites.add(space < 0 ? new RuleSpec(line, "")
                    : new RuleSpec(line.substring(0, space), line.substring(space).strip()));
        }
        s.clientMaxBodySize = clientMaxBodySize.strip();
        s.limitRate = limitRate.strip();
        s.limitReq = entries(limitReq);
        s.limitConn = entries(limitConn);
        s.accessLogs = entries(accessLogs);
        s.errorLog = errorLog.strip();
        s.errorPages = entries(errorPages);
        return s;
    }

    private static String headerLine(HeaderSpec h) {
        List<String> parts = new ArrayList<>();
        parts.add(Arg.of(h.name).raw());
        if (!h.value.isEmpty() || h.always) {
            parts.add(Arg.of(h.value).raw());
        }
        if (h.always) {
            parts.add("always");
        }
        return String.join(" ", parts);
    }

    private static String joinArgs(List<String> values) {
        List<String> raw = new ArrayList<>();
        for (String v : values) {
            raw.add(Arg.of(v).raw());
        }
        return String.join(" ", raw);
    }

    private static String lines(List<String> entries) {
        return String.join("\n", entries);
    }

    /** Non-blank lines, stripped. */
    static List<String> entries(String text) {
        List<String> result = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String t = line.strip();
            if (!t.isEmpty()) {
                result.add(t);
            }
        }
        return result;
    }

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
