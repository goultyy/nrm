package mt.su.nrm.ssl;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.cert.CertPath;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CertificateChainsTest {

    private static final String A = "-----BEGIN CERTIFICATE-----\n"
            + "MIIBPDCB4qADAgECAgkAt2u4rHHXYJQwCgYIKoZIzj0EAwMwETEPMA0GA1UEAxMG\n"
            + "VGVzdCBhMCAXDTI2MTAwNzE4MzMwOFoYDzIxMjYwOTEzMTgzMzA4WjARMQ8wDQYD\n"
            + "VQQDEwZUZXN0IGEwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAASotSlVa5R/LJ3F\n"
            + "LmHrUTjtK8EU+i95qr9av6IFK6JZpyOgdM16yGu/DVSvf4BKOs8B1QhFwwQJ3/8S\n"
            + "hN1AxNrEoyEwHzAdBgNVHQ4EFgQU18HKfk56xt8KncnqJY8ZDA+sZJAwCgYIKoZI\n"
            + "zj0EAwMDSQAwRgIhAJrMGLZY6jI0w4msjFB6Qy9008y6+qeFErmYOg+6bx4TAiEA\n"
            + "nn6hmbNQCNUQvAt9dJlfa0dCDW++mUGORPuHipuF2wo=\n"
            + "-----END CERTIFICATE-----\n";
    private static final String B = "-----BEGIN CERTIFICATE-----\n"
            + "MIIBOzCB4aADAgECAgg7hkWwQZhR+DAKBggqhkjOPQQDAzARMQ8wDQYDVQQDEwZU\n"
            + "ZXN0IGIwIBcNMjYxMDA3MTgzMzA5WhgPMjEyNjA5MTMxODMzMDlaMBExDzANBgNV\n"
            + "BAMTBlRlc3QgYjBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABDUkblWrTiYJcyU/\n"
            + "sGKc4sEze85HgWY9hTlKftqg4SBKeU2NqMwxNKLtnPsTYHkQgSRv6o3rKnsXtTp7\n"
            + "ifHilbqjITAfMB0GA1UdDgQWBBQWzw2y8Mholth5/wbXH2INsbH8ODAKBggqhkjO\n"
            + "PQQDAwNJADBGAiEA18RvlbXQM0fcMytzBVHUP9UNdWeKl15WceFOCA7+m1kCIQDv\n"
            + "d7Md+9IVihBhB0pKpUpSYrwPerqs036ymd/Urgk12g==\n"
            + "-----END CERTIFICATE-----\n";

    @Test
    void everyCertificateOfABundleEndsUpInThePkcs7File() throws Exception {
        byte[] p7b = CertificateChains.toPkcs7(A + B);
        CertPath path = CertificateFactory.getInstance("X.509")
                .generateCertPath(new ByteArrayInputStream(p7b), "PKCS7");
        assertEquals(2, path.getCertificates().size());
        // Order inside a .p7b doesn't matter to Windows (and Java writes it reversed), so compare as a set.
        assertEquals(java.util.Set.of("CN=Test a", "CN=Test b"), path.getCertificates().stream()
                .map(c -> ((X509Certificate) c).getSubjectX500Principal().getName())
                .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void textWithoutCertificatesIsRefused() {
        assertThrows(IOException.class, () -> CertificateChains.toPkcs7("nothing here"));
        assertThrows(IOException.class, () -> CertificateChains.toPkcs7(""));
    }
}
