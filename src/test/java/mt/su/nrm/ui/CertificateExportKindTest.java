package mt.su.nrm.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mt.su.nrm.ui.CertificateExportKind.Output;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertPath;
import java.security.cert.CertificateFactory;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The four files a server certificate can be saved as, and exactly what goes into each. */
class CertificateExportKindTest {

    private static final String LEAF = "-----BEGIN CERTIFICATE-----\n"
            + "MIIBPDCB4qADAgECAgkAt2u4rHHXYJQwCgYIKoZIzj0EAwMwETEPMA0GA1UEAxMG\n"
            + "VGVzdCBhMCAXDTI2MTAwNzE4MzMwOFoYDzIxMjYwOTEzMTgzMzA4WjARMQ8wDQYD\n"
            + "VQQDEwZUZXN0IGEwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAASotSlVa5R/LJ3F\n"
            + "LmHrUTjtK8EU+i95qr9av6IFK6JZpyOgdM16yGu/DVSvf4BKOs8B1QhFwwQJ3/8S\n"
            + "hN1AxNrEoyEwHzAdBgNVHQ4EFgQU18HKfk56xt8KncnqJY8ZDA+sZJAwCgYIKoZI\n"
            + "zj0EAwMDSQAwRgIhAJrMGLZY6jI0w4msjFB6Qy9008y6+qeFErmYOg+6bx4TAiEA\n"
            + "nn6hmbNQCNUQvAt9dJlfa0dCDW++mUGORPuHipuF2wo=\n"
            + "-----END CERTIFICATE-----\n";
    private static final String ISSUER = "-----BEGIN CERTIFICATE-----\n"
            + "MIIBOzCB4aADAgECAgg7hkWwQZhR+DAKBggqhkjOPQQDAzARMQ8wDQYDVQQDEwZU\n"
            + "ZXN0IGIwIBcNMjYxMDA3MTgzMzA5WhgPMjEyNjA5MTMxODMzMDlaMBExDzANBgNV\n"
            + "BAMTBlRlc3QgYjBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABDUkblWrTiYJcyU/\n"
            + "sGKc4sEze85HgWY9hTlKftqg4SBKeU2NqMwxNKLtnPsTYHkQgSRv6o3rKnsXtTp7\n"
            + "ifHilbqjITAfMB0GA1UdDgQWBBQWzw2y8Mholth5/wbXH2INsbH8ODAKBggqhkjO\n"
            + "PQQDAwNJADBGAiEA18RvlbXQM0fcMytzBVHUP9UNdWeKl15WceFOCA7+m1kCIQDv\n"
            + "d7Md+9IVihBhB0pKpUpSYrwPerqs036ymd/Urgk12g==\n"
            + "-----END CERTIFICATE-----\n";

    private static String text(Output o) {
        return new String(o.bytes(), StandardCharsets.UTF_8);
    }

    @Test
    void everyKindIsOfferedWithItsOwnTitleAndExplanation() {
        Set<String> titles = new HashSet<>();
        for (CertificateExportKind kind : CertificateExportKind.values()) {
            assertTrue(titles.add(kind.title()), "titles must differ: " + kind);
            assertFalse(kind.description().isBlank(), kind.name());
        }
        assertEquals(5, CertificateExportKind.values().length);
        assertEquals(CertificateExportKind.PEM_FULL, CertificateFormatDialog.rememberedChoice(),
                "the plain full-chain PEM, not the Windows file, is what is ticked to begin with");
    }

    @Test
    void theFileNamesAndExtensionsTellTheKindsApart() {
        assertEquals("example.com.p7b", CertificateExportKind.WINDOWS.suggestedFileName("example.com"));
        assertEquals("example.com-fullchain.crt", CertificateExportKind.PEM_FULL.suggestedFileName("example.com"));
        assertEquals("example.com.crt", CertificateExportKind.PEM_CERT.suggestedFileName("example.com"));
        assertEquals("example.com-chain.crt", CertificateExportKind.PEM_CHAIN.suggestedFileName("example.com"));
        assertFalse(CertificateExportKind.PEM_FULL.suggestedFileName("*.example.com").contains("*"),
                "a wildcard name must be a usable file name");
        assertEquals("site.crt", CertificateExportKind.PEM_CERT.withExtension("site"));
        assertEquals("site.pem", CertificateExportKind.PEM_CERT.withExtension("site.pem"), "a name the user typed is kept");
        assertEquals("site.p7b", CertificateExportKind.WINDOWS.withExtension("site"));
    }

    @Test
    void theCertificateAloneIsExactlyTheCertificate() throws Exception {
        Output o = CertificateExportKind.PEM_CERT.render(LEAF, List.of(ISSUER));
        assertEquals(LEAF, text(o));
        assertFalse(text(o).contains("Zm9v") || text(o).contains(ISSUER));
    }

    @Test
    void theFullChainIsTheCertificateThenTheIssuersInOrder() throws Exception {
        Output o = CertificateExportKind.PEM_FULL.render(LEAF, List.of(ISSUER));
        assertEquals(LEAF + ISSUER, text(o));
        assertTrue(o.note().contains("1 issuing certificate"), o.note());
    }

    @Test
    void theChainAloneLeavesTheCertificateOutAndRefusesWhenThereIsNone() throws Exception {
        Output o = CertificateExportKind.PEM_CHAIN.render(LEAF, List.of(ISSUER));
        assertEquals(ISSUER, text(o));
        assertTrue(assertThrows(CertificateExportKind.NothingToSave.class,
                () -> CertificateExportKind.PEM_CHAIN.render(LEAF, List.of())).getMessage().contains("no issuing certificates"));
        // The other kinds still work for a certificate with no chain.
        assertEquals(LEAF, text(CertificateExportKind.PEM_FULL.render(LEAF, List.of())));
    }

    @Test
    void theWindowsFileHoldsEveryCertificate() throws Exception {
        Output o = CertificateExportKind.WINDOWS.render(LEAF, List.of(ISSUER));
        CertPath path = CertificateFactory.getInstance("X.509").generateCertPath(new ByteArrayInputStream(o.bytes()), "PKCS7");
        assertEquals(2, path.getCertificates().size());
        assertTrue(o.note().contains("Install Certificate"), o.note());
    }

    @Test
    void thePrivateKeyIsItsOwnChoiceNeverRenderedFromCertificatesAndNeverTheDefault() {
        assertTrue(CertificateExportKind.PRIVATE_KEY.isKey());
        assertEquals(1, java.util.Arrays.stream(CertificateExportKind.values()).filter(CertificateExportKind::isKey).count());
        assertEquals("example.com.key", CertificateExportKind.PRIVATE_KEY.suggestedFileName("example.com"));
        assertEquals("Private key (*.key)", CertificateExportKind.PRIVATE_KEY.filterName());
        assertTrue(CertificateExportKind.PRIVATE_KEY.description().contains("warned"));
        assertThrows(IllegalStateException.class, () -> CertificateExportKind.PRIVATE_KEY.render(LEAF, List.of(ISSUER)));
        CertificateFormatDialog.forget();
        assertFalse(CertificateFormatDialog.rememberedChoice().isKey());
    }

    @Test
    void theWarningNamesEveryDangerAndWontLetYouContinueUntilYouAgree() {
        String dangers = String.join("\n", KeyDownloadWarning.dangers()).toLowerCase();
        assertTrue(dangers.contains("pretend to be your website"), "impersonation");
        assertTrue(dangers.contains("read it"), "reading traffic");
        assertTrue(dangers.contains("replace the certificate and key") && dangers.contains("revoke"), "no taking it back");
        assertTrue(dangers.contains("email") && dangers.contains("git"), "where not to put it");
        assertTrue(dangers.contains("safer way"), "the alternative of a new key");
        String handling = String.join("\n", KeyDownloadWarning.handling());
        assertTrue(handling.contains("does not keep it or write it to its log"), handling);

        String path = "/etc/letsencrypt/live/a.com/privkey.pem";
        assertFalse(KeyDownloadWarning.canContinue(false, path), "the box must be ticked");
        assertTrue(KeyDownloadWarning.canContinue(true, path));
        assertFalse(KeyDownloadWarning.canContinue(true, ""), "and a usable path given");
        assertFalse(KeyDownloadWarning.canContinue(true, "/etc/a b;rm"), "a path that couldn't be sent safely");
    }

    @Test
    void noKindEverWritesAPrivateKeyBlock() throws Exception {
        // Only certificate blocks are passed in, but whatever a kind writes must never contain a key block.
        for (CertificateExportKind kind : CertificateExportKind.values()) {
            if (kind == CertificateExportKind.WINDOWS || kind.isKey()) {
                continue;
            }
            Output o = kind.render(LEAF, List.of(ISSUER));
            assertFalse(text(o).contains("PRIVATE KEY"), kind.name());
        }
        assertArrayEquals(LEAF.getBytes(StandardCharsets.UTF_8), CertificateExportKind.PEM_CERT.render(LEAF, List.of()).bytes());
    }
}
