package mt.su.nrm.ssl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.List;

/**
 * Converts certificate chains between formats. Public certificates only; no private key is ever involved.
 */
public final class CertificateChains {

    private static final java.util.regex.Pattern BLOCK = java.util.regex.Pattern.compile(
            "-----BEGIN CERTIFICATE-----[\\s\\S]*?-----END CERTIFICATE-----");

    private CertificateChains() {
    }

    /**
     * The certificates in a PEM text, one string each (header, base64 lines, footer, trailing newline), in file order.
     * Everything between them is dropped, so a file that also holds a private key yields only its certificates.
     */
    public static List<String> blocks(String pem) {
        List<String> blocks = new java.util.ArrayList<>();
        java.util.regex.Matcher m = BLOCK.matcher(pem == null ? "" : pem);
        while (m.find()) {
            StringBuilder sb = new StringBuilder();
            for (String line : m.group().split("\\R")) {
                if (!line.isBlank()) {
                    sb.append(line.strip()).append('\n');
                }
            }
            blocks.add(sb.toString());
        }
        return blocks;
    }

    /**
     * Packs every certificate in a PEM bundle into one PKCS#7 ({@code .p7b}) file. Windows shows only the first
     * certificate of a PEM file with several in it, but opens a .p7b with all of them, and installing it puts each
     * certificate in the right store (the root as trusted, the intermediates as intermediate authorities).
     *
     * @throws IOException if the text holds no readable certificate
     */
    public static byte[] toPkcs7(String pem) throws IOException {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            List<Certificate> certs = List.copyOf(factory.generateCertificates(
                    new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8))));
            if (certs.isEmpty()) {
                throw new IOException("There is no certificate in the chain.");
            }
            return factory.generateCertPath(certs).getEncoded("PKCS7");
        } catch (CertificateException e) {
            throw new IOException("The chain could not be read as certificates: " + e.getMessage(), e);
        }
    }
}
