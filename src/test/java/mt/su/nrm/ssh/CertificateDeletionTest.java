package mt.su.nrm.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mt.su.nrm.model.PrivilegeMode;
import mt.su.nrm.model.ServerPaths;
import org.junit.jupiter.api.Test;

class CertificateDeletionTest {

    private final SshSessionTest.FakeTransport transport = new SshSessionTest.FakeTransport();
    private final ServerPaths paths = ServerPaths.defaults();

    private SshSession session() {
        return new SshSession(transport, new CommandLog(), PrivilegeMode.SUDO_PASSWORD, "pw", "u@h");
    }

    private static String nonceIn(String command) {
        Matcher m = Pattern.compile("@@NRM-([0-9a-f]+)").matcher(command);
        assertTrue(m.find(), command);
        return m.group(1);
    }

    // ------------------------------------------------------------------------------- what may be deleted

    @Test
    void onlyIssuedCertificatesDirectlyInTheManualFolderAreDeletable() {
        assertTrue(CaService.isDeletableCertificate(paths, "/etc/nginx/ssl/app.internal.crt"));
        assertTrue(CaService.isDeletableCertificate(paths, "/etc/nginx/ssl/site.pem"));
        for (String no : List.of("/etc/passwd", "/etc/nginx/nginx.conf", "/etc/nginx/ssl/sub/app.crt", "/etc/nginx/ssl/app.key",
                "/etc/nginx/ssl/../nginx.conf.crt", "/etc/nrm/ca/prod/ca.crt", "/etc/nginx/ssl/ca.crt",
                "/etc/letsencrypt/live/a/fullchain.pem", "/etc/nginx/ssl/x'; rm -rf /; '.crt", "/etc/nginx/ssl/a b.crt")) {
            assertFalse(CaService.isDeletableCertificate(paths, no), no);
        }
        assertEquals("/etc/nginx/ssl/app.internal.key", CaService.keyFor("/etc/nginx/ssl/app.internal.crt"));
        assertEquals("/etc/nginx/ssl/site.key", CaService.keyFor("/etc/nginx/ssl/site.pem"));
    }

    // ------------------------------------------------------------------------------- certificate scripts

    @Test
    void deletingACertificateRemovesExactlyItsFiles() throws Exception {
        String withKey = CaService.deleteCertificateScript(paths, "/etc/nginx/ssl/app.crt", true, "n1");
        assertEquals("rm -f -- '/etc/nginx/ssl/app.crt' '/etc/nginx/ssl/app.key' && echo '@@NRM-n1 DELETED'\n", withKey);
        String certOnly = CaService.deleteCertificateScript(paths, "/etc/nginx/ssl/app.crt", false, "n1");
        assertEquals("rm -f -- '/etc/nginx/ssl/app.crt' && echo '@@NRM-n1 DELETED'\n", certOnly);
        assertFalse(withKey.contains("-r"), "never recursive");
    }

    @Test
    void unsafeOrForeignPathsAreRefusedBeforeAnythingIsSent() {
        for (String bad : List.of("/etc/passwd", "/etc/nginx/ssl/a/../../../etc/x.crt", "/etc/nrm/ca/a/ca.crt")) {
            assertThrows(IOException.class, () -> CaService.deleteCertificate(session(), paths, bad, true), bad);
        }
        assertTrue(transport.commands.isEmpty());
    }

    @Test
    void deletionReportsSuccessAndFailure() throws Exception {
        transport.responder = c -> SshSessionTest.FakeTransport.raw(0, "@@NRM-" + nonceIn(c) + " DELETED\n", "");
        assertTrue(CaService.deleteCertificate(session(), paths, "/etc/nginx/ssl/app.crt", true).ok());
        assertTrue(transport.commands.get(0).startsWith("sudo -S"));

        transport.responder = c -> SshSessionTest.FakeTransport.raw(1, "", "rm: cannot remove: Permission denied\n");
        CaService.Result failed = CaService.deleteCertificate(session(), paths, "/etc/nginx/ssl/app.crt", true);
        assertFalse(failed.ok());
        assertTrue(failed.output().contains("Permission denied"));
    }

    // ------------------------------------------------------------------------------- CA scripts

    @Test
    void deletingACaRemovesOnlyItsOwnFilesAndThenItsEmptyFolder() throws Exception {
        String script = CaService.deleteCaScript(paths, new CaService.CaRef("/etc/nrm/ca/prod"), List.of(), "n1");
        assertEquals("rm -f -- '/etc/nrm/ca/prod/ca.crt' '/etc/nrm/ca/prod/ca.key' '/etc/nrm/ca/prod/ca.srl'\n"
                + "rmdir -- '/etc/nrm/ca/prod' 2>/dev/null\necho '@@NRM-n1 DELETED'\n", script);
        assertFalse(script.contains("rm -rf") || script.contains("-r "), "never recursive");
    }

    @Test
    void anOlderSingleCaIsDeletedWithoutTouchingTheOtherAuthoritiesInTheSameFolder() throws Exception {
        String script = CaService.deleteCaScript(paths, new CaService.CaRef("/etc/nrm/ca"), List.of(), "n1");
        assertTrue(script.contains("rm -f -- '/etc/nrm/ca/ca.crt' '/etc/nrm/ca/ca.key' '/etc/nrm/ca/ca.srl'"));
        assertFalse(script.contains("rmdir"), "the folder holds the other authorities");
    }

    @Test
    void issuedCertificatesAreDeletedFirstAndOnlyIfTheyAreIssuedCertificates() throws Exception {
        String script = CaService.deleteCaScript(paths, new CaService.CaRef("/etc/nrm/ca/prod"),
                List.of("/etc/nginx/ssl/a.crt", "/etc/nginx/ssl/b.crt"), "n1");
        int certs = script.indexOf("rm -f -- '/etc/nginx/ssl/a.crt' '/etc/nginx/ssl/a.key'");
        assertTrue(certs >= 0 && script.contains("'/etc/nginx/ssl/b.key'"));
        assertTrue(certs < script.indexOf("ca.crt"), "certificates first, then the CA");
        assertThrows(IOException.class, () -> CaService.deleteCaScript(paths, new CaService.CaRef("/etc/nrm/ca/prod"),
                List.of("/etc/passwd"), "n1"));
    }

    @Test
    void aCaOutsideTheCaFolderCannotBeDeleted() {
        for (String dir : List.of("/etc", "/etc/nrm", "/etc/nrm/ca/a/b", "/etc/nginx", "/etc/nrm/ca/x'y")) {
            assertThrows(IOException.class, () -> CaService.deleteCaScript(paths, new CaService.CaRef(dir), List.of(), "n1"), dir);
        }
    }

    @Test
    void deleteCaRunsThroughTheSession() throws Exception {
        transport.responder = c -> SshSessionTest.FakeTransport.raw(0, "@@NRM-" + nonceIn(c) + " DELETED\n", "");
        assertTrue(CaService.deleteCa(session(), paths, new CaService.CaRef("/etc/nrm/ca/prod"), List.of()).ok());
    }

    // ------------------------------------------------------------------------------- Let's Encrypt

    @Test
    void letsEncryptDeletionUsesCertbotWithAValidatedName() throws Exception {
        String script = CertbotService.deleteScript(paths, "app.example.com", "n1");
        assertTrue(script.contains("\"$C\" delete --non-interactive --cert-name 'app.example.com' 2>&1"), script);
        assertTrue(script.contains("command -v certbot"));

        transport.responder = c -> SshSessionTest.FakeTransport.raw(0, "Deleted all files\n@@NRM-" + nonceIn(c) + " DONE\n", "");
        CertbotService.Result ok = CertbotService.delete(session(), paths, "app.example.com");
        assertTrue(ok.ok());
        assertEquals("Deleted all files", ok.output());
        for (String bad : new String[] {"bad name", "a;b", "", "../x"}) {
            assertThrows(IOException.class, () -> CertbotService.delete(session(), paths, bad), bad);
        }
    }
}
