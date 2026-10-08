package mt.su.nrm.ssh;

import mt.su.nrm.model.ServerPaths;
import mt.su.nrm.ssl.CertificateInfo;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Lists the certificates on a server with their names and expiry: the ones nginx is configured to
 * use, plus those in the Let's Encrypt folder, the manual certificate folder, and the app's CA.
 * Only certificates are read ({@code openssl x509}); private keys are never opened.
 */
public final class CertificateService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private CertificateService() {
    }

    /**
     * @param inUse paths of certificates nginx is configured to use (ssl_certificate values)
     */
    public static List<CertificateInfo> list(SshSession session, ServerPaths paths, Collection<String> inUse)
            throws IOException {
        String nonce = nonce();
        String script = listScript(nonce, explicitPaths(inUse), globs(paths));
        CommandResult result = session.execPrivileged(script, SshSession.DEFAULT_TIMEOUT.multipliedBy(2));
        return parse(result.stdout(), nonce);
    }

    /**
     * A certificate and the certificates that lead to its root, as separate PEM blocks.
     *
     * @param certificate the certificate itself
     * @param chain       the issuing certificates in order, nearest first; empty if it is self-signed or its
     *                    issuer isn't on the server and the file holds nothing more
     */
    public record Export(String certificate, List<String> chain) {
    }

    /**
     * Fetches a listed certificate and its chain, public parts only. Only the certificate blocks of the file
     * leave the server (a file that also holds a key has the key cut out there), and only paths from
     * {@code known}, the certificates already listed, are accepted. Whatever else the file contains after the
     * certificate is kept, then the issuer is followed through the server's own authorities up to the root.
     *
     * @throws IOException if the file is not a listed certificate or could not be read
     */
    public static Export fetchExport(SshSession session, ServerPaths paths, CertificateInfo leaf,
                                     Collection<CertificateInfo> known) throws IOException {
        if (leaf.error() != null || known.stream().noneMatch(c -> c.path().equals(leaf.path()))
                || !ApplyPipeline.isSafe(leaf.path())) {
            throw new IOException("Only a certificate from the list can be downloaded.");
        }
        CommandResult r = session.execPrivileged("sed -n '/-----BEGIN CERTIFICATE-----/,/-----END CERTIFICATE-----/p' "
                + Shell.quote(leaf.path()));
        List<String> own = mt.su.nrm.ssl.CertificateChains.blocks(r.stdout());
        if (!r.ok() || own.isEmpty()) {
            throw new IOException("Could not read the certificate: " + r.stderr().strip());
        }
        List<String> chain = new ArrayList<>(own.subList(1, own.size()));
        for (CertificateInfo ca : issuerPath(leaf, known)) {
            CaService.CaRef ref;
            try {
                ref = CaService.authority(paths, ca.path());
            } catch (IOException e) {
                break; // not one of the app's authorities: the chain ends here
            }
            // A read failure from here on is a real error (for example a dropped connection), not the end of the chain.
            for (String block : mt.su.nrm.ssl.CertificateChains.blocks(CaService.fetchCaCertificate(session, paths, ref))) {
                if (!own.get(0).equals(block) && !chain.contains(block)) {
                    chain.add(block);
                }
            }
        }
        return new Export(own.get(0), List.copyOf(chain));
    }

    /**
     * The authorities above a certificate that are listed on the server, nearest first, found by matching each
     * issuer to an authority's subject. Stops at a self-signed certificate, at an issuer that isn't listed, and
     * after a safe number of steps.
     */
    public static List<CertificateInfo> issuerPath(CertificateInfo leaf, Collection<CertificateInfo> known) {
        List<CertificateInfo> path = new ArrayList<>();
        CertificateInfo current = leaf;
        for (int i = 0; i < 10 && !current.selfSigned(); i++) {
            CertificateInfo c = current;
            CertificateInfo issuer = known.stream().filter(k -> k.error() == null && k.authority()
                    && !k.path().equals(c.path()) && k.subject().equals(c.issuer())).findFirst().orElse(null);
            if (issuer == null || path.contains(issuer)) {
                break;
            }
            path.add(issuer);
            current = issuer;
        }
        return path;
    }

    static List<String> explicitPaths(Collection<String> inUse) {
        Set<String> safe = new LinkedHashSet<>();
        for (String p : inUse) {
            if (ApplyPipeline.isSafe(p)) {
                safe.add(p);
            }
        }
        return new ArrayList<>(safe);
    }

    /** The folders searched, as globs. Folders with unusual characters are skipped. */
    static List<String> globs(ServerPaths paths) {
        List<String> globs = new ArrayList<>();
        String le = trim(paths.getLetsEncryptDir());
        String manual = trim(paths.getManualCertDir());
        String ca = trim(paths.getCaStorageDir());
        if (ApplyPipeline.isSafe(le)) {
            globs.add(le + "/live/*/fullchain.pem");
        }
        if (ApplyPipeline.isSafe(manual)) {
            globs.add(manual + "/*.crt");
            globs.add(manual + "/*.pem");
            globs.add(manual + "/*.cer");
        }
        if (ApplyPipeline.isSafe(ca)) {
            globs.add(ca + "/ca.crt");     // a single CA created by an earlier version
            globs.add(ca + "/*/ca.crt");   // one folder per certificate authority
        }
        return globs;
    }

    static String listScript(String nonce, List<String> explicit, List<String> globs) {
        StringBuilder items = new StringBuilder();
        for (String p : explicit) {
            items.append(Shell.quote(p)).append(' ');
        }
        for (String g : globs) {
            items.append(g).append(' ');
        }
        StringBuilder s = new StringBuilder();
        s.append("for f in ").append(items).append("; do\n");
        s.append("  [ -f \"$f\" ] || continue\n");
        s.append("  printf '@@NRM-").append(nonce).append(" CERT\\t%s\\t%s\\n' \"$f\" \"$(readlink -f \"$f\")\"\n");
        s.append("  openssl x509 -in \"$f\" -noout -subject -issuer -serial -startdate -enddate -fingerprint -sha256 2>&1\n");
        s.append("  t=$(openssl x509 -in \"$f\" -noout -text 2>/dev/null)\n");
        s.append("  printf '%s\n' \"$t\" | grep -A1 'Subject Alternative Name' | tail -n +2\n");
        s.append("  printf '%s\n' \"$t\" | grep -E 'Public Key Algorithm:|Public-Key:|ASN1 OID:|NIST CURVE:|Signature Algorithm:|CA:(TRUE|FALSE)'\n");
        s.append("  printf '@@NRM-").append(nonce).append(" ENDCERT\\n'\n");
        s.append("done\n");
        return s.toString();
    }

    static List<CertificateInfo> parse(String output, String nonce) {
        String head = "@@NRM-" + nonce + " CERT\t";
        String end = "@@NRM-" + nonce + " ENDCERT";
        List<CertificateInfo> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int pos = 0;
        while (true) {
            int start = output.indexOf(head, pos);
            if (start < 0) {
                break;
            }
            int headerEnd = output.indexOf('\n', start);
            int blockEnd = output.indexOf(end, headerEnd < 0 ? start : headerEnd);
            if (headerEnd < 0 || blockEnd < 0) {
                break;
            }
            String[] fields = output.substring(start + head.length(), headerEnd).split("\t", -1);
            String path = fields[0];
            String real = fields.length > 1 && !fields[1].isEmpty() ? fields[1] : path;
            if (seen.add(real)) {
                result.add(CertificateInfo.parse(path, output.substring(headerEnd + 1, blockEnd)));
            }
            pos = blockEnd + end.length();
        }
        return result;
    }

    private static String trim(String dir) {
        return dir.length() > 1 && dir.endsWith("/") ? dir.substring(0, dir.length() - 1) : dir;
    }

    private static String nonce() {
        byte[] b = new byte[8];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }
}
