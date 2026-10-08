package mt.su.nrm.ssh;

import mt.su.nrm.ssl.CertificateInfo;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloads the private key that belongs to a server certificate, because the user asked to. This is the one place a key
 * leaves the server, so it is deliberately narrow:
 * <ul>
 *   <li>only a certificate from the listed ones, and a key file the server itself proves belongs to it: it compares the
 *       public key in the certificate with the one derived from the key file, and refuses on any mismatch, so this can't
 *       be used to read some other file;</li>
 *   <li>an encrypted key is refused (it can't be checked, and the passphrase must not be handled here);</li>
 *   <li>only the private key block comes back, never other blocks of a combined file;</li>
 *   <li>the command runs through {@link SshSession#execPrivilegedSecret}: the command and its exit status are logged,
 *       the key is not, and a line records that a key was downloaded.</li>
 * </ul>
 * Blocking: run through {@link SshExecutor}.
 */
public final class KeyExportService {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_CHARS = 64 * 1024;
    private static final Pattern KEY_BLOCK = Pattern.compile(
            "-----BEGIN ((?:RSA |EC |DSA )?PRIVATE KEY)-----[A-Za-z0-9+/=\\r\\n]+-----END \\1-----\\r?\\n?");

    /** A private key file's contents, as the blocks of the key only. */
    public record Key(String keyPath, String pem) {
    }

    private KeyExportService() {
    }

    /**
     * The key files to offer for a certificate, most likely first: the {@code ssl_certificate_key} of every site that
     * uses this certificate, then the usual name next to the certificate.
     */
    public static List<String> candidates(String certificatePath, Collection<String> configuredKeys, String guessed) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String k : configuredKeys) {
            if (k != null && ApplyPipeline.isSafe(k.strip())) {
                out.add(k.strip());
            }
        }
        if (guessed != null && ApplyPipeline.isSafe(guessed)) {
            out.add(guessed);
        }
        out.remove(certificatePath);
        return new ArrayList<>(out);
    }

    /** What is wrong with a key path before anything is sent, or null. */
    public static String pathProblem(String keyPath) {
        if (keyPath == null || keyPath.isBlank()) {
            return "Enter the key file's path on the server.";
        }
        if (!keyPath.equals(keyPath.strip()) || !ApplyPipeline.isSafe(keyPath)) {
            return "That isn't a plain full path on the server (no spaces, quotes or special characters).";
        }
        return null;
    }

    /**
     * The server-side check and read. Exit codes: 40 openssl missing, 41 the certificate can't be read, 42 the key file
     * can't be read as an unencrypted key, 43 the key does not belong to the certificate.
     */
    static String script(String certificatePath, String keyPath) {
        String c = Shell.quote(certificatePath);
        String k = Shell.quote(keyPath);
        return String.join("\n",
                "command -v openssl >/dev/null 2>&1 || exit 40",
                "P1=$(openssl x509 -noout -pubkey -in " + c + " 2>/dev/null)",
                "case \"$P1\" in *'BEGIN PUBLIC KEY'*) ;; *) exit 41;; esac",
                "P2=$(openssl pkey -pubout -in " + k + " </dev/null 2>/dev/null)",
                "case \"$P2\" in *'BEGIN PUBLIC KEY'*) ;; *) exit 42;; esac",
                "[ \"$P1\" = \"$P2\" ] || exit 43",
                "sed -n '/-----BEGIN [A-Z ]*PRIVATE KEY-----/,/-----END [A-Z ]*PRIVATE KEY-----/p' " + k,
                "");
    }

    /**
     * Fetches the key at {@code keyPath} for a listed certificate.
     *
     * @throws IOException with a message for the user if the key doesn't belong to the certificate, can't be read, is
     *                     encrypted, or the certificate isn't a listed one
     */
    public static Key fetch(SshSession session, CertificateInfo certificate, Collection<CertificateInfo> known,
                            String keyPath) throws IOException {
        if (certificate.error() != null || certificate.authority()
                || known.stream().noneMatch(c -> c.path().equals(certificate.path()))
                || !ApplyPipeline.isSafe(certificate.path())) {
            throw new IOException("Only the key of a server certificate from the list can be downloaded.");
        }
        String problem = pathProblem(keyPath);
        if (problem != null) {
            throw new IOException(problem);
        }
        CommandResult r = session.execPrivilegedSecret("sh -c " + Shell.quote(script(certificate.path(), keyPath)), TIMEOUT);
        switch (r.exitStatus()) {
            case 0:
                break;
            case 40:
                throw new IOException("openssl is not installed on the server, so the key can't be checked against the "
                        + "certificate, and it will not be downloaded unchecked.");
            case 41:
                throw new IOException("The certificate could not be read on the server.");
            case 42:
                throw new IOException("The file " + keyPath + " could not be read as an unencrypted private key (it is "
                        + "missing, not a key, or protected by a passphrase).");
            case 43:
                throw new IOException("The key in " + keyPath + " does not belong to this certificate, so it was not "
                        + "downloaded.");
            default:
                throw new IOException("Could not read the key (exit " + r.exitStatus() + "): " + r.stderr().strip());
        }
        String pem = keyBlocksOnly(r.stdout());
        if (pem.isEmpty()) {
            throw new IOException("The server returned no private key block.");
        }
        session.log().log(CommandLog.Kind.INFO, "A private key was downloaded to this computer: " + keyPath
                + " (the key itself is not shown here).");
        return new Key(keyPath, pem);
    }

    /** Only well-formed private key blocks, nothing else the output may have held; empty if there is none. */
    static String keyBlocksOnly(String output) {
        if (output == null || output.length() > MAX_CHARS) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        Matcher m = KEY_BLOCK.matcher(output);
        while (m.find()) {
            out.append(m.group().stripTrailing()).append('\n');
        }
        return out.toString();
    }
}
