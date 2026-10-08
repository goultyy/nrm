package mt.su.nrm.ssh;

import mt.su.nrm.model.ServerPaths;
import mt.su.nrm.ssl.CertificateInfo;
import mt.su.nrm.ssl.SubjectInfo;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntermediateCaTest {

    private static SubjectInfo subject(String cn) {
        return new SubjectInfo(cn, "", "", "", "", "", "");
    }

    private final ServerPaths paths = ServerPaths.defaults();
    private final CaService.CaRef root = new CaService.CaRef("/etc/nrm/ca/root");

    @Test
    void aRequestWithoutAParentIsStillARootAndKeepsTheOldScript() throws Exception {
        CaService.CaRequest request = new CaService.CaRequest(subject("Root"), 3650, CaService.KeyType.RSA_4096, "root");
        assertNull(request.parent());
        String script = CaService.createCaScript(paths, request, "n1");
        assertTrue(script.contains("openssl req -x509"));
        assertFalse(script.contains("chain.crt"));
    }

    @Test
    void anIntermediateIsSignedByItsParentAndVerifiedBeforeItIsKept() throws Exception {
        CaService.CaRequest request = new CaService.CaRequest(subject("Team CA"), 365, CaService.KeyType.RSA_4096, "team", root);
        String script = CaService.createCaScript(paths, request, "n1");
        assertFalse(script.contains("-x509 -new"), "not self-signed");
        assertTrue(script.contains("openssl x509 -req -in \"$T/ca.csr\" -CA \"$P/ca.crt\" -CAkey \"$P/ca.key\""));
        assertTrue(script.contains("basicConstraints=critical,CA:TRUE"));
        assertTrue(script.contains("NO-PARENT"));
        assertTrue(script.indexOf("openssl verify") > script.indexOf("openssl x509 -req"));
        assertTrue(script.contains("chain.crt") && script.contains("bundle.crt"));
        assertTrue(script.contains("chmod 600 \"$D/ca.key\""));
    }

    @Test
    void anIntermediateParentMustBeInsideTheSafeFolder() {
        CaService.CaRequest request = new CaService.CaRequest(subject("Team CA"), 365, CaService.KeyType.RSA_4096, "team",
                new CaService.CaRef("/etc/nrm/ca/x'y"));
        assertThrows(IOException.class, () -> CaService.createCaScript(paths, request, "n1"));
    }

    @Test
    void issuingUnderAnIntermediateSendsItsChainAlong() throws Exception {
        var request = new CaService.CertRequest(subject("app"), List.of(), 30, CaService.KeyType.EC_P256,
                CaService.Usage.SERVER, "app");
        String script = CaService.issueScript(paths, new CaService.CaRef("/etc/nrm/ca/team"), request, "n1");
        assertTrue(script.contains("bundle.crt"));
        assertTrue(script.contains("cat \"$T/cert.pem\" \"$D/chain.crt\" > \"$T/full.pem\""));
        assertTrue(script.indexOf("openssl verify") > script.indexOf("openssl x509 -req"));
    }

    @Test
    void deletingAnIntermediateAlsoRemovesItsChainFiles() throws Exception {
        String plain = CaService.deleteCaScript(paths, new CaService.CaRef("/etc/nrm/ca/team"), List.of(), "n1", false);
        assertFalse(plain.contains("chain.crt"));
        String inter = CaService.deleteCaScript(paths, new CaService.CaRef("/etc/nrm/ca/team"), List.of(), "n1", true);
        assertTrue(inter.contains("rm -f -- '/etc/nrm/ca/team/chain.crt' '/etc/nrm/ca/team/bundle.crt'"));
        assertTrue(inter.indexOf("chain.crt") < inter.indexOf("rmdir"));
    }

    @Test
    void certificateInfoTellsRootsFromIntermediates() {
        CertificateInfo rootInfo = new CertificateInfo("/etc/nrm/ca/root/ca.crt", "CN=Root", "CN=Root", List.of(), null, null,
                "", "", "", "", true, null);
        CertificateInfo child = new CertificateInfo("/etc/nrm/ca/team/ca.crt", "CN=Team", "CN=Root", List.of(), null, null,
                "", "", "", "", true, null);
        assertTrue(rootInfo.selfSigned());
        assertFalse(child.selfSigned());
        assertTrue(child.issuer().equals(rootInfo.subject()));
    }
}
