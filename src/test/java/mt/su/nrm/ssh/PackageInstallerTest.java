package mt.su.nrm.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mt.su.nrm.model.PrivilegeMode;
import mt.su.nrm.ssh.PackageInstaller.Manager;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class PackageInstallerTest {

    private final SshSessionTest.FakeTransport transport = new SshSessionTest.FakeTransport();
    private final SshSession session = new SshSession(transport, new CommandLog(), PrivilegeMode.NONE, null, "u@h");

    @Test
    void aptScriptUpdatesInstallsAndChecksTheProgram() throws IOException {
        String script = PackageInstaller.installScript(Manager.APT, PackageInstaller.CERTBOT, "abc");
        assertTrue(script.contains("DEBIAN_FRONTEND=noninteractive"));
        assertTrue(script.contains("apt-get install -y certbot || exit 71"));
        assertTrue(script.contains("command -v certbot"));
        assertTrue(script.endsWith("echo '@@NRM-abc DONE'\n"));
        assertFalse(script.contains("epel"));
    }

    @Test
    void redHatFamilyTriesEpelFirstAndCarriesOnWithoutIt() throws IOException {
        String dnf = PackageInstaller.installScript(Manager.DNF, PackageInstaller.CERTBOT, "abc");
        assertTrue(dnf.indexOf("dnf install -y epel-release || echo") < dnf.indexOf("dnf install -y certbot || exit 71"));
        assertTrue(PackageInstaller.installScript(Manager.YUM, PackageInstaller.CERTBOT, "abc")
                .contains("yum install -y certbot || exit 71"));
    }

    @Test
    void aPackageNeedsANameForTheManagerInUse() {
        PackageInstaller.Package aptOnly = new PackageInstaller.Package("thing", "thing",
                Map.of(Manager.APT, List.of("thing")));
        assertThrows(IOException.class, () -> PackageInstaller.installScript(Manager.DNF, aptOnly, "abc"));
    }

    @Test
    void namesThatCouldInjectCommandsAreRefused() {
        for (String bad : List.of("a; rm -rf /", "a b", "$(x)", "", "-y", "a`b`")) {
            assertThrows(IllegalArgumentException.class, () -> new PackageInstaller.Package("x", "x",
                    Map.of(Manager.APT, List.of(bad))), bad);
            assertThrows(IllegalArgumentException.class, () -> new PackageInstaller.Package("x", bad,
                    Map.of(Manager.APT, List.of("x"))), bad);
        }
    }

    @Test
    void installDetectsTheManagerThenRunsTheScriptAsRoot() throws IOException {
        transport.responder = c -> {
            if (c.contains("command -v apt-get")) {
                return SshSessionTest.FakeTransport.raw(0, "apt\n", "");
            }
            Matcher m = Pattern.compile("@@NRM-(\\w+) DONE").matcher(c);
            return SshSessionTest.FakeTransport.raw(0, "Setting up certbot\n" + (m.find() ? m.group(0) + "\n" : ""), "");
        };

        PackageInstaller.Result result = PackageInstaller.install(session, PackageInstaller.CERTBOT);

        assertTrue(result.ok());
        assertEquals("Setting up certbot", result.output());
        assertEquals(2, transport.commands.size());
        assertTrue(transport.commands.get(1).contains("apt-get install -y certbot"));
    }

    @Test
    void anInstallThatNeverReachesTheEndIsAFailure() throws IOException {
        transport.responder = c -> c.contains("command -v apt-get")
                ? SshSessionTest.FakeTransport.raw(0, "apt\n", "")
                : SshSessionTest.FakeTransport.raw(100, "", "E: Unable to locate package certbot\n");

        PackageInstaller.Result result = PackageInstaller.install(session, PackageInstaller.CERTBOT);

        assertFalse(result.ok());
        assertTrue(result.output().contains("Unable to locate package"));
    }

    @Test
    void anUnrecognisedPackageManagerInstallsNothing() throws IOException {
        transport.responder = c -> SshSessionTest.FakeTransport.raw(0, "", "");

        PackageInstaller.Result result = PackageInstaller.install(session, PackageInstaller.CERTBOT);

        assertFalse(result.ok());
        assertTrue(result.output().contains("not recognised"));
        assertEquals(1, transport.commands.size()); // only the detection ran
    }
}
