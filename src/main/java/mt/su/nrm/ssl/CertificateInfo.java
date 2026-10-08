package mt.su.nrm.ssl;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What {@code openssl x509} reports about one certificate file. Only public information: private
 * keys are never read by the app.
 *
 * @param path        the certificate file on the server
 * @param subject     the subject as openssl prints it, or "" if unreadable
 * @param issuer      the issuer as openssl prints it
 * @param names       subject alternative names: DNS names and IP addresses plain, e-mail names as
 *                    {@code email:...} and URIs as {@code URI:...} (empty if there are none)
 * @param notBefore   start of validity, or null if unreadable
 * @param notAfter    end of validity, or null if unreadable
 * @param fingerprint SHA-256 fingerprint, or ""
 * @param serial      serial number in hex, or ""
 * @param keyInfo     the public key, e.g. {@code RSA 2048-bit} or {@code EC prime256v1}, or ""
 * @param signature   the signature algorithm, e.g. {@code sha256WithRSAEncryption}, or ""
 * @param authority   true if the certificate is marked as a certificate authority (CA:TRUE)
 * @param error       why the file could not be read as a certificate, or null
 */
public record CertificateInfo(String path, String subject, String issuer, List<String> names, Instant notBefore,
                              Instant notAfter, String fingerprint, String serial, String keyInfo, String signature,
                              boolean authority, String error) {

    public enum Status { OK, EXPIRING, EXPIRED, UNKNOWN }

    /** Certificates with this many days or fewer left are flagged. */
    public static final int WARN_DAYS = 30;

    private static final Pattern CN = fieldPattern("CN");
    private static final DateTimeFormatter OPENSSL_DATE =
            DateTimeFormatter.ofPattern("MMM d HH:mm:ss yyyy z", Locale.ENGLISH);
    private static final Pattern BITS = Pattern.compile("\\((\\d+) bit\\)");

    private static Pattern fieldPattern(String key) {
        return Pattern.compile("(?:^|[,/\\s])" + key + "\\s*=\\s*([^,/]+)");
    }

    /** The certificate's main name: the subject's CN, or the first alternative name. */
    public String commonName() {
        String cn = subjectField("CN");
        if (!cn.isEmpty()) {
            return cn;
        }
        return names.isEmpty() ? subject : names.get(0);
    }

    /** One part of the subject (C, ST, L, O, OU, CN, emailAddress), or "". */
    public String subjectField(String key) {
        return field(subject, key);
    }

    /** One part of the issuer, or "". */
    public String issuerField(String key) {
        return field(issuer, key);
    }

    private static String field(String dn, String key) {
        Matcher m = (key.equals("CN") ? CN : fieldPattern(key)).matcher(dn);
        return m.find() ? m.group(1).strip() : "";
    }

    /** The names it is valid for, for display: the alternative names, or the common name. */
    public List<String> displayNames() {
        return names.isEmpty() ? List.of(commonName()) : names;
    }

    public boolean selfSigned() {
        return !subject.isEmpty() && subject.equals(issuer);
    }

    /** Whole days until expiry (negative once expired), or Long.MIN_VALUE if unknown. */
    public long daysLeft(Clock clock) {
        if (notAfter == null) {
            return Long.MIN_VALUE;
        }
        return Math.floorDiv(Duration.between(clock.instant(), notAfter).getSeconds(), 86_400L);
    }

    public Status status(Clock clock) {
        if (notAfter == null) {
            return Status.UNKNOWN;
        }
        if (!notAfter.isAfter(clock.instant())) {
            return Status.EXPIRED;
        }
        return daysLeft(clock) <= WARN_DAYS ? Status.EXPIRING : Status.OK;
    }

    /** A readable, multi-line description for a details pane. */
    public String details() {
        if (error != null) {
            return path + "\n\nCould not be read as a certificate:\n" + error;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(authority ? "Certificate authority" : "Certificate").append("   ").append(path).append("\n\n");
        line(sb, "Common name", subjectField("CN"));
        line(sb, "Organisation", subjectField("O"));
        line(sb, "Unit", subjectField("OU"));
        line(sb, "Country", subjectField("C"));
        line(sb, "State", subjectField("ST"));
        line(sb, "City", subjectField("L"));
        line(sb, "E-mail", subjectField("emailAddress"));
        line(sb, "Names (SAN)", String.join(", ", names));
        line(sb, "Issuer", selfSigned() ? "itself (self-signed)" : issuer);
        line(sb, "Valid from", notBefore == null ? "" : notBefore.toString());
        line(sb, "Valid until", notAfter == null ? "" : notAfter.toString());
        line(sb, "Serial", serial);
        line(sb, "Public key", keyInfo);
        line(sb, "Signature", signature);
        line(sb, "SHA-256", fingerprint);
        return sb.toString().stripTrailing();
    }

    private static void line(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(String.format("%-13s %s%n", label, value));
        }
    }

    /** Reads the output of the listing script for one file (see CertificateService). */
    public static CertificateInfo parse(String path, String output) {
        String subject = "";
        String issuer = "";
        Instant notBefore = null;
        Instant notAfter = null;
        String fingerprint = "";
        String serial = "";
        String signature = "";
        String algorithm = "";
        String bits = "";
        String curve = "";
        boolean authority = false;
        List<String> names = new ArrayList<>();
        List<String> unrecognised = new ArrayList<>();

        for (String raw : output.split("\r?\n")) {
            String line = raw.strip();
            if (line.startsWith("subject=")) {
                subject = line.substring("subject=".length()).strip();
            } else if (line.startsWith("issuer=")) {
                issuer = line.substring("issuer=".length()).strip();
            } else if (line.startsWith("notBefore=")) {
                notBefore = date(line.substring("notBefore=".length()));
            } else if (line.startsWith("notAfter=")) {
                notAfter = date(line.substring("notAfter=".length()));
            } else if (line.startsWith("serial=")) {
                serial = line.substring("serial=".length()).strip();
            } else if (line.toLowerCase(Locale.ROOT).contains("fingerprint=")) {
                fingerprint = line.substring(line.indexOf('=') + 1).strip();
            } else if (line.startsWith("Public Key Algorithm:")) {
                algorithm = line.substring("Public Key Algorithm:".length()).strip();
            } else if (line.startsWith("Public-Key:")) {
                Matcher m = BITS.matcher(line);
                bits = m.find() ? m.group(1) : "";
            } else if (line.startsWith("ASN1 OID:") || line.startsWith("NIST CURVE:")) {
                if (curve.isEmpty() || line.startsWith("ASN1 OID:")) {
                    curve = line.substring(line.indexOf(':') + 1).strip();
                }
            } else if (line.startsWith("Signature Algorithm:")) {
                if (signature.isEmpty()) {
                    signature = line.substring("Signature Algorithm:".length()).strip();
                }
            } else if (line.contains("CA:TRUE")) {
                authority = true;
            } else if (line.contains("CA:FALSE")) {
                // explicitly not a CA
            } else if (line.contains("DNS:") || line.contains("IP Address:") || line.contains("email:")
                    || line.contains("URI:")) {
                for (String part : line.split(",")) {
                    String p = part.strip();
                    if (p.startsWith("DNS:")) {
                        names.add(p.substring(4).strip());
                    } else if (p.startsWith("IP Address:")) {
                        names.add(p.substring(11).strip());
                    } else if (p.startsWith("email:") || p.startsWith("URI:")) {
                        names.add(p);
                    }
                }
            } else if (!line.isEmpty()) {
                unrecognised.add(line);
            }
        }
        if (subject.isEmpty() && notAfter == null) {
            String why = unrecognised.isEmpty() ? "Not a readable certificate." : unrecognised.get(0);
            return new CertificateInfo(path, "", "", List.of(), null, null, "", "", "", "", false, why);
        }
        return new CertificateInfo(path, subject, issuer, List.copyOf(names), notBefore, notAfter, fingerprint, serial,
                keyDescription(algorithm, bits, curve), signature, authority, null);
    }

    private static String keyDescription(String algorithm, String bits, String curve) {
        String a = algorithm.toLowerCase(Locale.ROOT);
        if (a.contains("rsa")) {
            return bits.isEmpty() ? "RSA" : "RSA " + bits + "-bit";
        }
        if (a.contains("ec") || !curve.isEmpty()) {
            return "EC " + (curve.isEmpty() ? bits + "-bit" : curve);
        }
        return algorithm.isEmpty() ? "" : algorithm + (bits.isEmpty() ? "" : " " + bits + "-bit");
    }

    private static Instant date(String text) {
        try {
            return ZonedDateTime.parse(text.strip().replaceAll("\\s+", " "), OPENSSL_DATE).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
