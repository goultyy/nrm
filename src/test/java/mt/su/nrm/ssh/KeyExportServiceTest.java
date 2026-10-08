package mt.su.nrm.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mt.su.nrm.ssh.FakeSessions.Reply;
import mt.su.nrm.ssl.CertificateInfo;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The one place a private key leaves a server: narrow, checked on the server, and never written to the log. */
class KeyExportServiceTest {

    private static final String KEY = "-----BEGIN PRIVATE KEY-----\nTOPSECRETKEYMATERIAL0123456789+/=\n-----END PRIVATE KEY-----\n";
    private static final String CERT = "-----BEGIN CERTIFICATE-----\nPUBLICCERTIFICATEBLOCK0123456789\n-----END CERTIFICATE-----\n";

    private static CertificateInfo info(String path, boolean authority, String error) {
        return new CertificateInfo(path, "CN=app", "CN=Acme CA", List.of(), null, null, "", "", "", "", authority, error);
    }

    private static final CertificateInfo LEAF = info("/etc/letsencrypt/live/app.example.com/fullchain.pem", false, null);
    private static final String KEY_PATH = "/etc/letsencrypt/live/app.example.com/privkey.pem";

    private static SshSession session(java.util.function.Function<String, Reply> script, List<String> ran, CommandLog log) {
        return FakeSessions.session(script, ran, log);
    }

    // ---------------------------------------------------------------- the server-side check

    @Test
    void theScriptComparesPublicKeysRefusesIfAnythingIsMissingAndPrintsOnlyKeyBlocks() {
        String script = KeyExportService.script(LEAF.path(), KEY_PATH);
        assertTrue(script.contains("openssl x509 -noout -pubkey -in '" + LEAF.path() + "'"), script);
        assertTrue(script.contains("openssl pkey -pubout -in '" + KEY_PATH + "' </dev/null"), "never waits for a passphrase");
        assertTrue(script.indexOf("exit 43") < script.indexOf("sed -n"), "the check comes before anything is printed");
        assertTrue(script.indexOf("exit 41") < script.indexOf("exit 42") && script.indexOf("exit 42") < script.indexOf("exit 43"));
        assertTrue(script.contains("*'BEGIN PUBLIC KEY'*"), "two failed derivations must not compare equal as empty strings");
        assertTrue(script.contains("PRIVATE KEY-----' '" + KEY_PATH + "'") || script.contains("p' '" + KEY_PATH + "'"), script);
        assertFalse(script.contains("cat "), "the file is never printed whole");
    }

    // ---------------------------------------------------------------- fetching

    @Test
    void aMatchingKeyIsReturnedAloneAndTheLogNeverHoldsIt() throws IOException {
        List<String> ran = new ArrayList<>();
        CommandLog log = new CommandLog();
        // A combined file: the server prints only key blocks, but even if it printed more, only the key is kept.
        SshSession s = session(c -> Reply.ok(CERT + KEY + CERT), ran, log);
        KeyExportService.Key key = KeyExportService.fetch(s, LEAF, List.of(LEAF), KEY_PATH);

        assertEquals(KEY, key.pem());
        assertEquals(KEY_PATH, key.keyPath());
        assertEquals(1, ran.size());
        assertTrue(ran.get(0).contains("openssl pkey"), ran.get(0));
        for (CommandLog.Line line : log.snapshot()) {
            assertFalse(line.text().contains("TOPSECRET") || line.text().contains("PUBLICCERTIFICATEBLOCK"), line.kind() + ": " + line.text());
        }
        assertTrue(log.snapshot().stream().anyMatch(l -> l.text().contains("withheld")), "the log says the output was held back");
        assertTrue(log.snapshot().stream().anyMatch(l -> l.text().contains("A private key was downloaded")
                && l.text().contains(KEY_PATH)), "and records that a key was taken, and which file");
    }

    @Test
    void everyFailureHasAMessageThatSaysWhatHappenedAndNothingIsReturned() {
        record Case(int exit, String expected) {
        }
        for (Case c : List.of(new Case(40, "openssl is not installed"), new Case(41, "certificate could not be read"),
                new Case(42, "could not be read as an unencrypted private key"), new Case(43, "does not belong to this certificate"),
                new Case(1, "Could not read the key (exit 1)"))) {
            SshSession s = session(x -> new Reply(c.exit(), "", "boom"), new ArrayList<>(), new CommandLog());
            IOException e = assertThrows(IOException.class, () -> KeyExportService.fetch(s, LEAF, List.of(LEAF), KEY_PATH));
            assertTrue(e.getMessage().contains(c.expected()), c.exit() + ": " + e.getMessage());
        }
    }

    @Test
    void aSuccessfulExitWithNoKeyBlockIsAnError() {
        for (String output : List.of("", CERT, "no key here", "-----BEGIN ENCRYPTED PRIVATE KEY-----\nAAAA\n-----END ENCRYPTED PRIVATE KEY-----\n")) {
            SshSession s = session(x -> Reply.ok(output), new ArrayList<>(), new CommandLog());
            assertThrows(IOException.class, () -> KeyExportService.fetch(s, LEAF, List.of(LEAF), KEY_PATH), output);
        }
    }

    @Test
    void nothingIsSentForAnUnlistedCertificateAnAuthorityABrokenOneOrAnUnsafePath() {
        List<String> ran = new ArrayList<>();
        SshSession s = session(x -> Reply.ok(KEY), ran, new CommandLog());
        assertThrows(IOException.class, () -> KeyExportService.fetch(s, LEAF, List.of(), KEY_PATH), "not in the list");
        CertificateInfo ca = info("/etc/nrm/ca/acme/ca.crt", true, null);
        assertThrows(IOException.class, () -> KeyExportService.fetch(s, ca, List.of(ca), "/etc/nrm/ca/acme/ca.key"), "a CA's key never leaves");
        CertificateInfo broken = info("/etc/x/broken.crt", false, "not a certificate");
        assertThrows(IOException.class, () -> KeyExportService.fetch(s, broken, List.of(broken), "/etc/x/broken.key"));
        CertificateInfo weird = info("/etc/x/a b;rm.crt", false, null);
        assertThrows(IOException.class, () -> KeyExportService.fetch(s, weird, List.of(weird), KEY_PATH));
        for (String bad : List.of("", "  ", "relative.key", "/etc/x/a b.key", "/etc/x/$(id).key", "/etc/x/a;b.key", "/etc/x/'.key",
                "/etc/x/../shadow", "/etc//x.key", KEY_PATH + " ")) {
            assertThrows(IOException.class, () -> KeyExportService.fetch(s, LEAF, List.of(LEAF), bad), "'" + bad + "'");
        }
        assertTrue(ran.isEmpty(), "none of that may reach the server: " + ran);
    }

    // ---------------------------------------------------------------- picking the key file

    @Test
    void theSitesOwnKeyComesFirstThenTheUsualName() {
        List<String> found = KeyExportService.candidates(LEAF.path(),
                List.of("/etc/ssl/private/site.key", "/etc/ssl/private/site.key", "bad path", ""), KEY_PATH);
        assertEquals(List.of("/etc/ssl/private/site.key", KEY_PATH), found);
        assertEquals(List.of(KEY_PATH), KeyExportService.candidates(LEAF.path(), List.of(), KEY_PATH));
        assertTrue(KeyExportService.candidates(LEAF.path(), List.of(), "a b").isEmpty());
        assertEquals(List.of("/etc/ssl/other.key"), KeyExportService.candidates("/etc/ssl/a.crt", List.of("/etc/ssl/a.crt", "/etc/ssl/other.key"), null),
                "the certificate itself is never offered as its key");
    }

    @Test
    void pathProblemsAreWordedForTheUser() {
        assertNull(KeyExportService.pathProblem(KEY_PATH));
        assertTrue(KeyExportService.pathProblem("").contains("Enter"));
        assertTrue(KeyExportService.pathProblem("/etc/a b").contains("plain full path"));
        assertTrue(KeyExportService.pathProblem(null).contains("Enter"));
    }

    // ---------------------------------------------------------------- what is kept from the output

    @Test
    void onlyWellFormedUnencryptedPrivateKeyBlocksSurvive() {
        String rsa = "-----BEGIN RSA PRIVATE KEY-----\nMIIEow+/=\n-----END RSA PRIVATE KEY-----\n";
        String ec = "-----BEGIN EC PRIVATE KEY-----\nMHcCAQ==\n-----END EC PRIVATE KEY-----\n";
        assertEquals(rsa + ec, KeyExportService.keyBlocksOnly("junk\n" + CERT + rsa + "more junk\n" + ec));
        assertEquals("", KeyExportService.keyBlocksOnly(CERT));
        assertEquals("", KeyExportService.keyBlocksOnly(null));
        assertEquals("", KeyExportService.keyBlocksOnly("-----BEGIN PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----\n"),
                "mismatched markers");
        assertEquals("", KeyExportService.keyBlocksOnly("-----BEGIN PRIVATE KEY-----\n" + "A".repeat(70_000) + "\n-----END PRIVATE KEY-----\n"),
                "an absurdly large reply is refused");
        assertEquals("", KeyExportService.keyBlocksOnly("-----BEGIN PRIVATE KEY-----\n<script>\n-----END PRIVATE KEY-----\n"),
                "anything that isn't base64 inside a block");
    }
}
