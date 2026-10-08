package mt.su.nrm.ssh;

import mt.su.nrm.model.ServerPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Brings a certificate and private key you already own (bought from a CA, or issued elsewhere) onto a
 * server so nginx can use them. This is the one place a private key travels from this computer, so it
 * is handled carefully: the files go over SFTP into a private (mode 700) temporary folder and are never
 * written to the command log, the server checks that the key really belongs to the certificate before
 * anything is installed, and the key ends up readable by root only.
 */
public final class ImportService {

    /**
     * @param name     the name to store it under; becomes {@code NAME.crt} and {@code NAME.key}
     * @param cert     the server certificate, PEM
     * @param chain    intermediate certificates, PEM, or "" for none; appended after the certificate
     * @param key      the private key, PEM, not protected by a passphrase
     * @param replace  true to overwrite a certificate that already has this name
     */
    public record ImportRequest(String name, String cert, String chain, String key, boolean replace) {
        /** Never prints the key. */
        @Override
        public String toString() {
            return "ImportRequest[name=" + name + "]";
        }
    }

    /** @param certPath where the certificate is now; @param keyPath where its key is now */
    public record Result(boolean ok, String output, String certPath, String keyPath) {
    }

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern CERT_BLOCK = Pattern.compile(
            "-----BEGIN CERTIFICATE-----[A-Za-z0-9+/=\\s]+-----END CERTIFICATE-----");
    private static final SecureRandom RANDOM = new SecureRandom();

    private ImportService() {
    }

    /** What is wrong with a request, worded for the user; empty if it can be sent. */
    public static List<String> problems(ServerPaths paths, ImportRequest r) {
        return problems(paths.getManualCertDir(), r);
    }

    /** As {@link #problems(ServerPaths, ImportRequest)}, given the folder certificates are stored in. */
    public static List<String> problems(String manualCertDir, ImportRequest r) {
        List<String> problems = new ArrayList<>();
        if (!NAME.matcher(r.name()).matches()) {
            problems.add("The name may use letters, digits, dots, dashes and underscores, and must start with a letter or digit.");
        }
        if (!ApplyPipeline.isSafe(manualCertDir)) {
            problems.add("The server's certificate folder in the profile's Paths has unusual characters.");
        }
        if (!CERT_BLOCK.matcher(r.cert()).find()) {
            problems.add("The certificate file doesn't contain a PEM certificate (-----BEGIN CERTIFICATE-----). "
                    + "If it is a .pfx or .p12 file, convert it to PEM first.");
        }
        if (!r.chain().isBlank() && !CERT_BLOCK.matcher(r.chain()).find()) {
            problems.add("The chain file doesn't contain a PEM certificate.");
        }
        String key = r.key();
        if (key.contains("ENCRYPTED")) {
            problems.add("The private key is protected by a passphrase. Remove it first (openssl pkey -in key.pem -out plain.pem).");
        } else if (!Pattern.compile("-----BEGIN (RSA |EC |)PRIVATE KEY-----").matcher(key).find()) {
            problems.add("The key file doesn't contain a PEM private key (-----BEGIN PRIVATE KEY-----).");
        }
        return problems;
    }

    /** Installs the certificate and key. Nothing is installed if the request has problems or the key doesn't match. */
    public static Result importCertificate(SshSession session, ServerPaths paths, ImportRequest request)
            throws IOException {
        List<String> problems = problems(paths, request);
        if (!problems.isEmpty()) {
            throw new IOException(String.join(" ", problems));
        }
        String tempRoot = paths.getRemoteTempDir().replaceAll("/+$", "");
        if (!ApplyPipeline.isSafe(tempRoot + "/x")) {
            throw new IOException("The temporary folder in the profile's Paths has unusual characters.");
        }
        CommandResult made = session.exec("mktemp -d " + Shell.quote(tempRoot + "/nrm-import.XXXXXX"));
        String dir = made.stdout().strip();
        if (!made.ok() || !ApplyPipeline.isSafe(dir + "/x")) {
            throw new IOException("Could not create a private temporary folder on the server: " + made.stderr().strip());
        }
        try {
            session.upload(request.cert().getBytes(StandardCharsets.UTF_8), dir + "/cert.pem");
            session.upload(request.chain().getBytes(StandardCharsets.UTF_8), dir + "/chain.pem");
            session.upload(request.key().getBytes(StandardCharsets.UTF_8), dir + "/key.pem");
            String nonce = nonce();
            CommandResult r = session.execPrivileged(script(paths, request, dir, nonce));
            String all = r.stdout() + r.stderr();
            String output = all.replaceAll("(?m)^@@NRM-" + nonce + ".*\\R?", "").strip();
            String base = paths.getManualCertDir().replaceAll("/+$", "") + "/" + request.name();
            return new Result(r.ok() && all.contains("@@NRM-" + nonce + " DONE"), output, base + ".crt", base + ".key");
        } finally {
            try {
                session.exec("rm -rf " + Shell.quote(dir));
            } catch (IOException | RuntimeException ignored) {
                // The privileged script normally removes it already; the failure is in the command log.
            }
        }
    }

    static String script(ServerPaths paths, ImportRequest r, String dir, String nonce) {
        String d = Shell.quote(paths.getManualCertDir().replaceAll("/+$", ""));
        String base = paths.getManualCertDir().replaceAll("/+$", "") + "/" + r.name();
        String crt = Shell.quote(base + ".crt");
        String key = Shell.quote(base + ".key");
        String s = "umask 077\n"
                + "S=" + Shell.quote(dir) + "\n"
                + "trap 'rm -rf \"$S\"' EXIT\n"
                + "mkdir -p " + d + " || { echo 'Could not create the certificate folder.'; exit 81; }\n"
                + (r.replace() ? "" : "if [ -e " + crt + " ] || [ -e " + key + " ]; then echo 'A certificate with this name "
                + "already exists. Choose another name or tick Replace.'; exit 90; fi\n")
                + "A=$(openssl x509 -in \"$S/cert.pem\" -noout -pubkey 2>/dev/null | openssl sha256 2>/dev/null)\n"
                + "B=$(openssl pkey -in \"$S/key.pem\" -passin pass: -pubout 2>/dev/null | openssl sha256 2>/dev/null)\n"
                + "if [ -z \"$A\" ]; then echo 'The server could not read the certificate.'; exit 91; fi\n"
                + "if [ -z \"$B\" ]; then echo 'The server could not read the private key (is it protected by a passphrase?).'; exit 92; fi\n"
                + "if [ \"$A\" != \"$B\" ]; then echo 'The private key does not belong to this certificate. Nothing was installed.'; exit 93; fi\n"
                + "{ cat \"$S/cert.pem\"; echo; cat \"$S/chain.pem\"; } | sed '/^[[:space:]]*$/d' > \"$S/full.crt\" || exit 94\n"
                + "chmod 644 \"$S/full.crt\" && chmod 600 \"$S/key.pem\" || exit 95\n"
                + "mv \"$S/full.crt\" " + crt + " || exit 96\n"
                + "mv \"$S/key.pem\" " + key + " || exit 97\n"
                + "echo '@@NRM-" + nonce + " DONE'\n";
        return s;
    }

    private static String nonce() {
        byte[] b = new byte[6];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }
}
