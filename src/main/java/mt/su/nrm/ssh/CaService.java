package mt.su.nrm.ssh;

import mt.su.nrm.model.ServerPaths;
import mt.su.nrm.nginx.LayoutDetector;
import mt.su.nrm.ssl.AltNames;
import mt.su.nrm.ssl.SubjectInfo;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A private certificate authority on the server, for self-signed setups: one root CA per server
 * (no intermediates), issuing certificates with full subject details and subject alternative names.
 * Everything happens on the server; the CA key and the issued keys are created there and never leave
 * it. Only the CA's public certificate can be fetched, so clients can be told to trust it.
 */
public final class CaService {

    /** The kind of key to generate. */
    public enum KeyType {
        RSA_2048("RSA 2048-bit", "openssl genrsa -out %s 2048", "digitalSignature,keyEncipherment"),
        RSA_4096("RSA 4096-bit", "openssl genrsa -out %s 4096", "digitalSignature,keyEncipherment"),
        EC_P256("ECDSA P-256", "openssl ecparam -name prime256v1 -genkey -noout -out %s", "digitalSignature"),
        EC_P384("ECDSA P-384", "openssl ecparam -name secp384r1 -genkey -noout -out %s", "digitalSignature");

        private final String label;
        private final String command;
        private final String keyUsage;

        KeyType(String label, String command, String keyUsage) {
            this.label = label;
            this.command = command;
            this.keyUsage = keyUsage;
        }

        String generate(String outputPath) {
            return String.format(command, outputPath);
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** What a certificate may be used for (extended key usage). */
    public enum Usage {
        SERVER("Server (websites and services)", "serverAuth"),
        CLIENT("Client (people and devices logging in)", "clientAuth"),
        SERVER_AND_CLIENT("Server and client", "serverAuth,clientAuth");

        private final String label;
        private final String eku;

        Usage(String label, String eku) {
            this.label = label;
            this.eku = eku;
        }

        public boolean includesServer() {
            return this != CLIENT;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * @param subject the CA's identity (a common name is required)
     * @param parent  the authority that signs this one, making it an intermediate CA; null for a self-signed root CA
     */
    public record CaRequest(SubjectInfo subject, int days, KeyType keyType, String folder, CaRef parent) {

        /** A root CA request whose folder name is made from the CA name. */
        public CaRequest(SubjectInfo subject, int days, KeyType keyType) {
            this(subject, days, keyType, defaultFolder(subject.commonName()), null);
        }

        /** A root CA request with its own folder name. */
        public CaRequest(SubjectInfo subject, int days, KeyType keyType, String folder) {
            this(subject, days, keyType, folder, null);
        }
    }

    /**
     * A certificate authority on the server, identified by its folder (which holds ca.crt, ca.key and
     * ca.srl). A server can have any number of them, each in its own folder under the CA storage
     * folder; an older single CA lives directly in the storage folder.
     */
    public record CaRef(String dir) {
        public String certPath() {
            return dir + "/ca.crt";
        }

        /** The folder name under the CA storage folder, or "" for a CA directly in it. */
        public String folder(ServerPaths paths) {
            String base = paths.getCaStorageDir().replaceAll("/+$", "");
            return dir.equals(base) ? "" : dir.substring(base.length() + 1);
        }
    }

    /** The name to use for a CA's folder: lowercase letters, digits, dots, dashes and underscores. */
    public static String defaultFolder(String commonName) {
        String f = LayoutDetector.safeFileName(commonName).toLowerCase(java.util.Locale.ROOT);
        return f.length() > 40 ? f.substring(0, 40) : f;
    }

    /**
     * The CA whose certificate is at this path; refused unless it is {@code ca.crt} directly in the
     * CA storage folder or in a folder one level below it.
     */
    public static CaRef authority(ServerPaths paths, String certPath) throws IOException {
        String base = caDir(paths);
        if (!certPath.endsWith("/ca.crt") || !ApplyPipeline.isSafe(certPath)) {
            throw new IOException("That is not a certificate authority file.");
        }
        String dir = certPath.substring(0, certPath.length() - "/ca.crt".length());
        boolean oneLevel = dir.startsWith(base + "/") && dir.indexOf('/', base.length() + 1) < 0;
        if (!dir.equals(base) && !oneLevel) {
            throw new IOException("That certificate authority is outside " + base + ".");
        }
        return new CaRef(dir);
    }

    /**
     * @param subject the certificate's identity; the common name may be blank, in which case the first
     *                DNS name or IP address is used
     * @param names   subject alternative names (DNS, IP, e-mail, URI)
     */
    public record CertRequest(SubjectInfo subject, List<AltNames.Name> names, int days, KeyType keyType, Usage usage,
                              String fileName) {

        /** A request whose file name comes from the common name. */
        public CertRequest(SubjectInfo subject, List<AltNames.Name> names, int days, KeyType keyType, Usage usage) {
            this(subject, names, days, keyType, usage, "");
        }

        /** The subject's common name, or the first DNS name or IP address. */
        public String commonName() {
            if (!subject.commonName().isEmpty()) {
                return subject.commonName();
            }
            for (AltNames.Name n : names) {
                if (n.type() == AltNames.Type.DNS || n.type() == AltNames.Type.IP) {
                    return n.value();
                }
            }
            return "";
        }

        /** The names on the certificate: the ones given, plus the common name if it is a host name or address. */
        public List<AltNames.Name> effectiveNames() {
            List<AltNames.Name> all = new ArrayList<>(names);
            String cn = commonName();
            AltNames.Name asName = AltNames.isIp(cn) ? AltNames.Name.ip(cn)
                    : AltNames.isDnsName(cn, true) ? AltNames.Name.dns(cn.toLowerCase(java.util.Locale.ROOT)) : null;
            if (asName != null && !all.contains(asName) && usage.includesServer()) {
                all.add(0, asName);
            }
            return all;
        }
    }

    /** @param certPath where the certificate was written (null if not issued); keyPath is its private key */
    public record Result(boolean ok, String output, String certPath, String keyPath) {
    }

    private static final Duration TIMEOUT = Duration.ofMinutes(4);
    private static final SecureRandom RANDOM = new SecureRandom();

    private CaService() {
    }

    public static List<String> problems(CaRequest r) {
        List<String> problems = new ArrayList<>(r.subject().problems(true));
        if (!r.folder().matches("[a-z0-9][a-z0-9._-]{0,39}")) {
            problems.add("The CA folder name may use lowercase letters, digits, dots, dashes and underscores.");
        }
        if (r.days() < 30 || r.days() > 36500) {
            problems.add("The CA validity must be between 30 and 36500 days.");
        }
        return problems;
    }

    public static List<String> problems(CertRequest r) {
        List<String> problems = new ArrayList<>(r.subject().problems(false));
        if (r.commonName().isEmpty()) {
            problems.add("Enter a common name (or a DNS name or IP address to use as one).");
        }
        if (r.usage().includesServer()
                && r.effectiveNames().stream().noneMatch(n -> n.type() == AltNames.Type.DNS || n.type() == AltNames.Type.IP)) {
            problems.add("A server certificate needs at least one DNS name or IP address.");
        }
        if (r.days() < 1 || r.days() > 3650) {
            problems.add("The validity must be between 1 and 3650 days.");
        }
        return problems;
    }

    /**
     * Creates a CA in its own folder: a self-signed root, or, when the request names a parent, an
     * intermediate signed by that parent (which may itself be an intermediate). Fails if a CA already
     * lives in that folder.
     */
    public static Result createCa(SshSession session, ServerPaths paths, CaRequest request) throws IOException {
        List<String> problems = problems(request);
        if (!problems.isEmpty()) {
            throw new IOException(String.join(" ", problems));
        }
        String nonce = nonce();
        CommandResult r = session.execPrivileged(createCaScript(paths, request, nonce), TIMEOUT);
        String all = r.stdout() + r.stderr();
        boolean ok = r.ok() && all.contains("@@NRM-" + nonce + " CA-CREATED");
        String message = all.contains("@@NRM-" + nonce + " EXISTS")
                ? "A certificate authority already exists in " + caFolder(paths, request.folder()) + "."
                : all.contains("@@NRM-" + nonce + " NO-PARENT")
                ? "The certificate authority that should sign this one was not found on the server." : clean(all, nonce);
        return new Result(ok, message, ok ? caFolder(paths, request.folder()) + "/ca.crt" : null, null);
    }

    /** Issues a certificate signed by the given CA into the manual certificate folder. */
    public static Result issue(SshSession session, ServerPaths paths, CaRef ca, CertRequest request) throws IOException {
        List<String> problems = problems(request);
        if (!problems.isEmpty()) {
            throw new IOException(String.join(" ", problems));
        }
        String nonce = nonce();
        CommandResult r = session.execPrivileged(issueScript(paths, ca, request, nonce), TIMEOUT);
        String all = r.stdout() + r.stderr();
        String base = fileBase(request);
        String dir = manualDir(paths);
        boolean ok = r.ok() && all.contains("@@NRM-" + nonce + " ISSUED");
        String message;
        if (all.contains("@@NRM-" + nonce + " NO-CA")) {
            message = "This server has no certificate authority yet. Create one first.";
        } else if (all.contains("@@NRM-" + nonce + " FILE-EXISTS")) {
            message = "A certificate named " + base + " already exists in " + dir + ".";
        } else {
            message = clean(all, nonce);
        }
        return new Result(ok, message, ok ? dir + "/" + base + ".crt" : null, ok ? dir + "/" + base + ".key" : null);
    }

    /** The CA's public certificate (PEM), for installing on clients. The CA key is not read. */
    public static String fetchCaCertificate(SshSession session, ServerPaths paths, CaRef ca) throws IOException {
        CommandResult r = session.execPrivileged("cat " + Shell.quote(ca.certPath()));
        if (!r.ok() || !r.stdout().contains("BEGIN CERTIFICATE")) {
            throw new IOException("Could not read the CA certificate: " + r.stderr().strip());
        }
        return r.stdout();
    }

    /** The public chain of an intermediate CA: its certificate, the intermediates above it and the root. */
    public static String fetchChain(SshSession session, ServerPaths paths, CaRef ca) throws IOException {
        CommandResult r = session.execPrivileged("cat " + Shell.quote(ca.dir() + "/bundle.crt"));
        if (!r.ok() || !r.stdout().contains("BEGIN CERTIFICATE")) {
            throw new IOException("Could not read the certificate chain: " + r.stderr().strip());
        }
        return r.stdout();
    }

    // ------------------------------------------------------------------------------- deleting

    /**
     * True for a certificate this app can delete: a file directly in the manual certificate folder
     * (where issued certificates are written) with a certificate extension.
     */
    public static boolean isDeletableCertificate(ServerPaths paths, String certPath) {
        try {
            String dir = manualDir(paths);
            return ApplyPipeline.isSafe(certPath) && certPath.startsWith(dir + "/")
                    && certPath.indexOf('/', dir.length() + 1) < 0 && certPath.matches(".*\\.(crt|pem|cer)$")
                    && !certPath.endsWith("/ca.crt");
        } catch (IOException e) {
            return false;
        }
    }

    /** The private key that goes with a certificate file: the same name ending in .key. */
    public static String keyFor(String certPath) {
        return certPath.substring(0, certPath.lastIndexOf('.')) + ".key";
    }

    /** Deletes an issued certificate, and its private key if asked. Files only ever leave the manual certificate folder. */
    public static Result deleteCertificate(SshSession session, ServerPaths paths, String certPath, boolean deleteKey)
            throws IOException {
        String nonce = nonce();
        CommandResult r = session.execPrivileged(deleteCertificateScript(paths, certPath, deleteKey, nonce));
        String all = r.stdout() + r.stderr();
        return new Result(r.ok() && all.contains("@@NRM-" + nonce + " DELETED"), clean(all, nonce), null, null);
    }

    static String deleteCertificateScript(ServerPaths paths, String certPath, boolean deleteKey, String nonce)
            throws IOException {
        if (!isDeletableCertificate(paths, certPath)) {
            throw new IOException("Only certificates in " + manualDir(paths) + " can be deleted here.");
        }
        StringBuilder s = new StringBuilder("rm -f -- ").append(Shell.quote(certPath));
        if (deleteKey) {
            s.append(' ').append(Shell.quote(keyFor(certPath)));
        }
        return s + " && echo '@@NRM-" + nonce + " DELETED'\n";
    }

    /**
     * Deletes a certificate authority: its certificate, key and serial file, and its folder if that
     * is then empty. Certificates it issued are removed too when listed (they can no longer be
     * checked against it, and a new CA of the same name would not match them).
     *
     * @param issuedCertPaths issued certificates to delete together with the CA (each must be deletable)
     */
    public static Result deleteCa(SshSession session, ServerPaths paths, CaRef ca, List<String> issuedCertPaths)
            throws IOException {
        return deleteCa(session, paths, ca, issuedCertPaths, false);
    }

    /** @param intermediate true if the CA was signed by another CA, so its chain files are removed too */
    public static Result deleteCa(SshSession session, ServerPaths paths, CaRef ca, List<String> issuedCertPaths,
                                  boolean intermediate) throws IOException {
        String nonce = nonce();
        CommandResult r = session.execPrivileged(deleteCaScript(paths, ca, issuedCertPaths, nonce, intermediate));
        String all = r.stdout() + r.stderr();
        return new Result(r.ok() && all.contains("@@NRM-" + nonce + " DELETED"), clean(all, nonce), null, null);
    }

    static String deleteCaScript(ServerPaths paths, CaRef ca, List<String> issuedCertPaths, String nonce)
            throws IOException {
        return deleteCaScript(paths, ca, issuedCertPaths, nonce, false);
    }

    static String deleteCaScript(ServerPaths paths, CaRef ca, List<String> issuedCertPaths, String nonce,
                                 boolean intermediate) throws IOException {
        String base = caDir(paths);
        String dir = requireSafe(ca.dir());
        boolean legacy = dir.equals(base);
        if (!legacy && !(dir.startsWith(base + "/") && dir.indexOf('/', base.length() + 1) < 0)) {
            throw new IOException("That certificate authority is outside " + base + ".");
        }
        StringBuilder s = new StringBuilder();
        for (String cert : issuedCertPaths) {
            if (!isDeletableCertificate(paths, cert)) {
                throw new IOException("Refusing to delete " + cert + ": it is not an issued certificate in " + manualDir(paths) + ".");
            }
            s.append("rm -f -- ").append(Shell.quote(cert)).append(' ').append(Shell.quote(keyFor(cert))).append('\n');
        }
        // Only the CA's own files are removed, never the folder's other contents: for an older single CA
        // the folder also holds the other authorities.
        s.append("rm -f -- ").append(Shell.quote(dir + "/ca.crt")).append(' ').append(Shell.quote(dir + "/ca.key")).append(' ')
                .append(Shell.quote(dir + "/ca.srl")).append('\n');
        if (intermediate) {
            s.append("rm -f -- ").append(Shell.quote(dir + "/chain.crt")).append(' ')
                    .append(Shell.quote(dir + "/bundle.crt")).append('\n');
        }
        if (!legacy) {
            s.append("rmdir -- ").append(Shell.quote(dir)).append(" 2>/dev/null\n");
        }
        s.append("echo '@@NRM-").append(nonce).append(" DELETED'\n");
        return s.toString();
    }

    // ------------------------------------------------------------------------------- scripts

    /**
     * The script for an intermediate CA. The parent signs it, and two files are written next to it:
     * {@code chain.crt} (this CA and the intermediates above it, which a server must send along with its
     * certificate) and {@code bundle.crt} (the same plus the root, for verifying and for clients to trust).
     */
    static String createIntermediateScript(ServerPaths paths, CaRequest r, String nonce) throws IOException {
        String dir = caFolder(paths, r.folder());
        String parent = requireSafe(r.parent().dir());
        String tmp = requireSafe(paths.getRemoteTempDir().replaceAll("(?<=.)/+$", ""));
        List<String> cnf = new ArrayList<>(List.of("[req]", "distinguished_name=dn", "prompt=no", "[dn]"));
        cnf.addAll(r.subject().configLines());
        List<String> ext = List.of("basicConstraints=critical,CA:TRUE", "keyUsage=critical,keyCertSign,cRLSign",
                "subjectKeyIdentifier=hash", "authorityKeyIdentifier=keyid");
        String m = "@@NRM-" + nonce;
        StringBuilder s = new StringBuilder();
        s.append("umask 077\n");
        s.append("D=").append(Shell.quote(dir)).append('\n');
        s.append("P=").append(Shell.quote(parent)).append('\n');
        s.append("if [ ! -f \"$P/ca.key\" ] || [ ! -f \"$P/ca.crt\" ]; then echo '").append(m).append(" NO-PARENT'; exit 65; fi\n");
        s.append("if [ -e \"$D/ca.key\" ] || [ -e \"$D/ca.crt\" ]; then echo '").append(m).append(" EXISTS'; exit 60; fi\n");
        s.append("mkdir -p \"$D\" && chmod 700 \"$D\" || { echo '").append(m).append(" ERROR'; exit 61; }\n");
        s.append("T=$(mktemp -d ").append(Shell.quote(tmp + "/nrm-ca-XXXXXX")).append(") || { echo '").append(m).append(" ERROR'; exit 62; }\n");
        s.append("trap 'rm -rf \"$T\"' EXIT\n");
        s.append("printf '%s\\n' ").append(quoteAll(cnf)).append(" > \"$T/ca.cnf\"\n");
        s.append("printf '%s\\n' ").append(quoteAll(ext)).append(" > \"$T/ext.cnf\"\n");
        s.append("F=\"$D/ca.key $D/ca.crt $D/chain.crt $D/bundle.crt\"\n");
        s.append(r.keyType().generate("\"$D/ca.key\"")).append(" 2>&1 || { rm -f $F; echo '").append(m).append(" ERROR'; exit 63; }\n");
        s.append("chmod 600 \"$D/ca.key\"\n");
        s.append("openssl req -new -key \"$D/ca.key\" -config \"$T/ca.cnf\" -out \"$T/ca.csr\" 2>&1 || { rm -f $F; echo '")
                .append(m).append(" ERROR'; exit 64; }\n");
        s.append("openssl x509 -req -in \"$T/ca.csr\" -CA \"$P/ca.crt\" -CAkey \"$P/ca.key\" -CAcreateserial -CAserial \"$P/ca.srl\" ")
                .append("-out \"$D/ca.crt\" -days ").append(r.days()).append(" -sha256 -extfile \"$T/ext.cnf\" 2>&1 || { rm -f $F; echo '")
                .append(m).append(" ERROR'; exit 66; }\n");
        // What the parent's own chain of trust is: its bundle if it is an intermediate itself, else just its certificate.
        s.append("if [ -f \"$P/bundle.crt\" ]; then B=\"$P/bundle.crt\"; else B=\"$P/ca.crt\"; fi\n");
        s.append("openssl verify -CAfile \"$B\" \"$D/ca.crt\" 2>&1 || { rm -f $F; echo '").append(m).append(" ERROR'; exit 67; }\n");
        s.append("if [ -f \"$P/chain.crt\" ]; then cat \"$D/ca.crt\" \"$P/chain.crt\" > \"$D/chain.crt\"; else cp \"$D/ca.crt\" \"$D/chain.crt\"; fi\n");
        s.append("cat \"$D/ca.crt\" \"$B\" > \"$D/bundle.crt\"\n");
        s.append("chmod 644 \"$D/ca.crt\" \"$D/chain.crt\" \"$D/bundle.crt\"\n");
        s.append("echo '").append(m).append(" CA-CREATED'\n");
        return s.toString();
    }

    static String createCaScript(ServerPaths paths, CaRequest r, String nonce) throws IOException {
        if (r.parent() != null) {
            return createIntermediateScript(paths, r, nonce);
        }
        String dir = caFolder(paths, r.folder());
        String tmp = requireSafe(paths.getRemoteTempDir().replaceAll("(?<=.)/+$", ""));
        List<String> cnf = new ArrayList<>(List.of("[req]", "distinguished_name=dn", "x509_extensions=v3_ca", "prompt=no",
                "[dn]"));
        cnf.addAll(r.subject().configLines());
        cnf.addAll(List.of("[v3_ca]", "basicConstraints=critical,CA:TRUE", "keyUsage=critical,keyCertSign,cRLSign",
                "subjectKeyIdentifier=hash"));
        String m = "@@NRM-" + nonce;
        StringBuilder s = new StringBuilder();
        s.append("umask 077\n");
        s.append("D=").append(Shell.quote(dir)).append('\n');
        s.append("if [ -e \"$D/ca.key\" ] || [ -e \"$D/ca.crt\" ]; then echo '").append(m).append(" EXISTS'; exit 60; fi\n");
        s.append("mkdir -p \"$D\" && chmod 700 \"$D\" || { echo '").append(m).append(" ERROR'; exit 61; }\n");
        s.append("T=$(mktemp -d ").append(Shell.quote(tmp + "/nrm-ca-XXXXXX")).append(") || { echo '").append(m).append(" ERROR'; exit 62; }\n");
        s.append("trap 'rm -rf \"$T\"' EXIT\n");
        s.append("printf '%s\\n' ").append(quoteAll(cnf)).append(" > \"$T/ca.cnf\"\n");
        s.append(r.keyType().generate("\"$D/ca.key\"")).append(" 2>&1 || { rm -f \"$D/ca.key\"; echo '").append(m).append(" ERROR'; exit 63; }\n");
        s.append("chmod 600 \"$D/ca.key\"\n");
        s.append("openssl req -x509 -new -nodes -key \"$D/ca.key\" -sha256 -days ").append(r.days())
                .append(" -config \"$T/ca.cnf\" -out \"$D/ca.crt\" 2>&1 || { rm -f \"$D/ca.key\" \"$D/ca.crt\"; echo '")
                .append(m).append(" ERROR'; exit 64; }\n");
        s.append("chmod 644 \"$D/ca.crt\"\n");
        s.append("echo '").append(m).append(" CA-CREATED'\n");
        return s.toString();
    }

    static String issueScript(ServerPaths paths, CaRef ca, CertRequest r, String nonce) throws IOException {
        String dir = requireSafe(ca.dir());
        String out = manualDir(paths);
        String tmp = requireSafe(paths.getRemoteTempDir().replaceAll("(?<=.)/+$", ""));
        String base = fileBase(r);
        List<AltNames.Name> names = r.effectiveNames();
        String san = AltNames.subjectAltName(names);
        String m = "@@NRM-" + nonce;

        List<String> req = new ArrayList<>(List.of("[req]", "distinguished_name=dn", "prompt=no"));
        if (!names.isEmpty()) {
            req.add("req_extensions=v3_req");
        }
        req.add("[dn]");
        req.addAll(r.subject().withCommonName(r.commonName()).configLines());
        if (!names.isEmpty()) {
            req.addAll(List.of("[v3_req]", "subjectAltName=" + san));
        }
        List<String> ext = new ArrayList<>();
        if (!names.isEmpty()) {
            ext.add("subjectAltName=" + san);
        }
        ext.addAll(List.of("basicConstraints=CA:FALSE", "keyUsage=" + r.keyType().keyUsage,
                "extendedKeyUsage=" + r.usage().eku, "subjectKeyIdentifier=hash", "authorityKeyIdentifier=keyid"));

        StringBuilder s = new StringBuilder();
        s.append("umask 077\n");
        s.append("D=").append(Shell.quote(dir)).append('\n');
        s.append("O=").append(Shell.quote(out)).append('\n');
        s.append("N=").append(Shell.quote(base)).append('\n');
        s.append("if [ ! -f \"$D/ca.key\" ] || [ ! -f \"$D/ca.crt\" ]; then echo '").append(m).append(" NO-CA'; exit 70; fi\n");
        s.append("if [ -e \"$O/$N.crt\" ] || [ -e \"$O/$N.key\" ]; then echo '").append(m).append(" FILE-EXISTS'; exit 71; fi\n");
        s.append("mkdir -p \"$O\" || { echo '").append(m).append(" ERROR'; exit 72; }\n");
        s.append("T=$(mktemp -d ").append(Shell.quote(tmp + "/nrm-cert-XXXXXX")).append(") || { echo '").append(m).append(" ERROR'; exit 73; }\n");
        s.append("trap 'rm -rf \"$T\"' EXIT\n");
        s.append("printf '%s\\n' ").append(quoteAll(req)).append(" > \"$T/req.cnf\"\n");
        s.append("printf '%s\\n' ").append(quoteAll(ext)).append(" > \"$T/ext.cnf\"\n");
        s.append(r.keyType().generate("\"$T/key.pem\"")).append(" 2>&1 || { echo '").append(m).append(" ERROR'; exit 74; }\n");
        s.append("openssl req -new -key \"$T/key.pem\" -config \"$T/req.cnf\" -out \"$T/req.csr\" 2>&1 || { echo '").append(m).append(" ERROR'; exit 75; }\n");
        s.append("openssl x509 -req -in \"$T/req.csr\" -CA \"$D/ca.crt\" -CAkey \"$D/ca.key\" -CAcreateserial -CAserial \"$D/ca.srl\" ")
                .append("-out \"$T/cert.pem\" -days ").append(r.days()).append(" -sha256 -extfile \"$T/ext.cnf\" 2>&1 || { echo '")
                .append(m).append(" ERROR'; exit 76; }\n");
        // Under an intermediate CA, verify against its whole chain and send the intermediates along with the certificate.
        s.append("if [ -f \"$D/bundle.crt\" ]; then V=\"$D/bundle.crt\"; else V=\"$D/ca.crt\"; fi\n");
        s.append("openssl verify -CAfile \"$V\" \"$T/cert.pem\" 2>&1 || { echo '").append(m).append(" ERROR'; exit 77; }\n");
        s.append("if [ -f \"$D/chain.crt\" ]; then cat \"$T/cert.pem\" \"$D/chain.crt\" > \"$T/full.pem\"; else cp \"$T/cert.pem\" \"$T/full.pem\"; fi\n");
        s.append("cp \"$T/full.pem\" \"$O/$N.crt\" && cp \"$T/key.pem\" \"$O/$N.key\" || { rm -f \"$O/$N.crt\" \"$O/$N.key\"; echo '")
                .append(m).append(" ERROR'; exit 78; }\n");
        s.append("chmod 644 \"$O/$N.crt\"; chmod 600 \"$O/$N.key\"\n");
        s.append("echo '").append(m).append(" ISSUED'\n");
        return s.toString();
    }

    // ------------------------------------------------------------------------------- helpers

    /** The base name of the issued files: the common name with anything unsafe replaced. */
    static String fileBase(CertRequest r) {
        if (!r.fileName().isBlank()) {
            return LayoutDetector.safeFileName(r.fileName().strip());
        }
        String cn = r.commonName();
        return LayoutDetector.safeFileName(cn.startsWith("*.") ? "wildcard." + cn.substring(2) : cn);
    }

    /** The folder for a CA with this folder name under the storage folder. */
    static String caFolder(ServerPaths paths, String folder) throws IOException {
        return caDir(paths) + "/" + folder;
    }

    static String caDir(ServerPaths paths) throws IOException {
        return requireSafe(paths.getCaStorageDir().replaceAll("(?<=.)/+$", ""));
    }

    static String manualDir(ServerPaths paths) throws IOException {
        return requireSafe(paths.getManualCertDir().replaceAll("(?<=.)/+$", ""));
    }

    private static String requireSafe(String dir) throws IOException {
        if (!ApplyPipeline.isSafe(dir)) {
            throw new IOException("The folder \"" + dir + "\" has characters this app will not put in a remote command.");
        }
        return dir;
    }

    private static String quoteAll(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Shell.quote(l));
        }
        return sb.toString();
    }

    private static String clean(String output, String nonce) {
        StringBuilder sb = new StringBuilder();
        for (String line : output.split("\r?\n")) {
            if (!line.startsWith("@@NRM-" + nonce)) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString().strip();
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
