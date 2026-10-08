package mt.su.nrm.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mt.su.nrm.model.PrivilegeMode;
import mt.su.nrm.model.ServerPaths;
import mt.su.nrm.ssl.CertificateInfo;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CertificateExportTest {

    private static final String LEAF_PEM = "-----BEGIN CERTIFICATE-----\n"
            + "MIIBPDCB4qADAgECAgkAt2u4rHHXYJQwCgYIKoZIzj0EAwMwETEPMA0GA1UEAxMG\n"
            + "VGVzdCBhMCAXDTI2MTAwNzE4MzMwOFoYDzIxMjYwOTEzMTgzMzA4WjARMQ8wDQYD\n"
            + "VQQDEwZUZXN0IGEwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAASotSlVa5R/LJ3F\n"
            + "LmHrUTjtK8EU+i95qr9av6IFK6JZpyOgdM16yGu/DVSvf4BKOs8B1QhFwwQJ3/8S\n"
            + "hN1AxNrEoyEwHzAdBgNVHQ4EFgQU18HKfk56xt8KncnqJY8ZDA+sZJAwCgYIKoZI\n"
            + "zj0EAwMDSQAwRgIhAJrMGLZY6jI0w4msjFB6Qy9008y6+qeFErmYOg+6bx4TAiEA\n"
            + "nn6hmbNQCNUQvAt9dJlfa0dCDW++mUGORPuHipuF2wo=\n"
            + "-----END CERTIFICATE-----\n";
    private static final String CA_PEM = "-----BEGIN CERTIFICATE-----\n"
            + "MIIBOzCB4aADAgECAgg7hkWwQZhR+DAKBggqhkjOPQQDAzARMQ8wDQYDVQQDEwZU\n"
            + "ZXN0IGIwIBcNMjYxMDA3MTgzMzA5WhgPMjEyNjA5MTMxODMzMDlaMBExDzANBgNV\n"
            + "BAMTBlRlc3QgYjBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABDUkblWrTiYJcyU/\n"
            + "sGKc4sEze85HgWY9hTlKftqg4SBKeU2NqMwxNKLtnPsTYHkQgSRv6o3rKnsXtTp7\n"
            + "ifHilbqjITAfMB0GA1UdDgQWBBQWzw2y8Mholth5/wbXH2INsbH8ODAKBggqhkjO\n"
            + "PQQDAwNJADBGAiEA18RvlbXQM0fcMytzBVHUP9UNdWeKl15WceFOCA7+m1kCIQDv\n"
            + "d7Md+9IVihBhB0pKpUpSYrwPerqs036ymd/Urgk12g==\n"
            + "-----END CERTIFICATE-----\n";

    private static CertificateInfo info(String path, String subject, String issuer, boolean authority) {
        return new CertificateInfo(path, subject, issuer, List.of(), null, null, "", "", "", "", authority, null);
    }

    private static final CertificateInfo LEAF = info("/etc/nrm/certs/app.crt", "CN=app", "CN=Acme CA", false);
    private static final CertificateInfo ACME_CA = info("/etc/nrm/ca/acme_ca/ca.crt", "CN=Acme CA", "CN=Acme Root", true);
    private static final CertificateInfo ROOT = info("/etc/nrm/ca/root/ca.crt", "CN=Acme Root", "CN=Acme Root", true);

    private final SshSessionTest.FakeTransport transport = new SshSessionTest.FakeTransport();

    private SshSession session() {
        return new SshSession(transport, new CommandLog(), PrivilegeMode.SUDO_PASSWORD, "pw", "u@h");
    }

    @Test
    void theIssuerPathFollowsOurAuthoritiesUpToTheRoot() {
        List<CertificateInfo> known = List.of(LEAF, ROOT, ACME_CA);
        assertEquals(List.of(ACME_CA, ROOT), CertificateService.issuerPath(LEAF, known));
        // An issuer that isn't listed ends the path, and a self-signed certificate has none.
        assertEquals(List.of(), CertificateService.issuerPath(LEAF, List.of(LEAF)));
        assertEquals(List.of(), CertificateService.issuerPath(ROOT, known));
    }

    @Test
    void theExportKeepsOnlyCertificatesAndAddsTheIssuingAuthority() throws Exception {
        List<String> commands = new ArrayList<>();
        transport.responder = c -> {
            commands.add(c);
            if (c.contains("sed -n") && c.contains("/etc/nrm/certs/app.crt")) {
                // Even if the server sent a key, nothing but certificate blocks is kept.
                return SshSessionTest.FakeTransport.raw(0, LEAF_PEM + "-----BEGIN PRIVATE KEY-----\nsecret\n-----END PRIVATE KEY-----\n", "");
            }
            if (c.contains("/etc/nrm/ca/acme_ca/ca.crt")) {
                return SshSessionTest.FakeTransport.raw(0, CA_PEM, "");
            }
            return SshSessionTest.FakeTransport.raw(1, "", "unexpected: " + c);
        };
        CertificateService.Export export = CertificateService.fetchExport(session(), ServerPaths.defaults(), LEAF,
                List.of(LEAF, ACME_CA));
        assertEquals(LEAF_PEM, export.certificate());
        assertEquals(List.of(CA_PEM), export.chain());
        assertFalse(export.certificate().contains("PRIVATE"));
        assertTrue(commands.stream().noneMatch(c -> c.contains(".key")), commands.toString());
    }

    @Test
    void onlyListedCertificatesCanBeFetched() {
        transport.responder = c -> SshSessionTest.FakeTransport.raw(0, LEAF_PEM, "");
        assertThrows(IOException.class, () -> CertificateService.fetchExport(session(), ServerPaths.defaults(),
                info("/etc/shadow", "CN=x", "CN=y", false), List.of(LEAF)));
        assertThrows(IOException.class, () -> CertificateService.fetchExport(session(), ServerPaths.defaults(),
                info("/etc/nrm/certs/app.crt; rm -rf /", "CN=x", "CN=y", false),
                List.of(info("/etc/nrm/certs/app.crt; rm -rf /", "CN=x", "CN=y", false))));
    }

    @Test
    void aFileWithNoCertificateIsAnError() {
        transport.responder = c -> SshSessionTest.FakeTransport.raw(0, "-----BEGIN PRIVATE KEY-----\nx\n", "");
        assertThrows(IOException.class, () -> CertificateService.fetchExport(session(), ServerPaths.defaults(), LEAF,
                List.of(LEAF)));
    }
}
