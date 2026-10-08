package mt.su.nrm.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mt.su.nrm.model.AuthMethod;
import mt.su.nrm.model.PrivilegeMode;
import mt.su.nrm.model.ServerPaths;
import mt.su.nrm.model.ServerProfile;
import mt.su.nrm.model.ToolStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class SshHelpersTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void shellQuoteHandlesQuotesAndMetacharacters() {
        assertEquals("'plain'", Shell.quote("plain"));
        assertEquals("'it'\\''s'", Shell.quote("it's"));
        assertEquals("'a; rm -rf / $(x) `y`'", Shell.quote("a; rm -rf / $(x) `y`"));
    }

    @Test
    void privilegeWrappingForEachMode() {
        assertEquals("id", Privilege.wrap(PrivilegeMode.NONE, "id", null).commandLine());
        assertEquals("sudo -n sh -c 'id'", Privilege.wrap(PrivilegeMode.SUDO_NOPASSWD, "id", null).commandLine());
        Privilege.Invocation inv = Privilege.wrap(PrivilegeMode.SUDO_PASSWORD, "echo 'x'", "pw");
        assertEquals("sudo -S -p '' sh -c 'echo '\\''x'\\'''", inv.commandLine());
        assertEquals("pw\n", new String(inv.stdin(), StandardCharsets.UTF_8));
    }

    @Test
    void hostKeyPinningDecisions() {
        assertEquals(HostKeyPinning.Outcome.FIRST_USE, HostKeyPinning.evaluate(null, "SHA256:a"));
        assertEquals(HostKeyPinning.Outcome.FIRST_USE, HostKeyPinning.evaluate("  ", "SHA256:a"));
        assertEquals(HostKeyPinning.Outcome.MATCH, HostKeyPinning.evaluate("SHA256:a", "SHA256:a"));
        assertEquals(HostKeyPinning.Outcome.MISMATCH, HostKeyPinning.evaluate("SHA256:a", "SHA256:b"));
    }

    @Test
    void fingerprintMatchesOpenSshFormat() {
        // sha256 of the empty input, base64 without padding.
        assertEquals("SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU",
                HostKeyPinning.fingerprint(new byte[0]));
    }

    @Test
    void hostKeyChangeMessageNamesBothFingerprints() {
        var e = new HostKeyChangedException("h", "SHA256:old", "SHA256:new");
        assertTrue(e.getMessage().contains("SHA256:old") && e.getMessage().contains("SHA256:new"));
    }

    @Test
    void logMasksSecretsLongestFirst() {
        CommandLog log = new CommandLog();
        log.addSecret("pass");
        log.addSecret("password123");
        assertEquals(CommandLog.MASK + " and " + CommandLog.MASK, log.mask("pass and password123"));
    }

    @Test
    void logKeepsEachLineWholeAndCapsSize() {
        CommandLog log = new CommandLog(CLOCK, 3);
        String longLine = "x".repeat(5000);
        log.log(CommandLog.Kind.OUTPUT, longLine + "\nb\nc\nd");
        var lines = log.snapshot();
        assertEquals(3, lines.size());
        assertEquals("d", lines.get(2).text());
        log.clear();
        log.log(CommandLog.Kind.OUTPUT, longLine);
        assertEquals(5000, log.snapshot().get(0).text().length());
    }

    @Test
    void credentialsToStringAndResultToStringDoNotLeak() {
        Credentials c = new Credentials("pw-secret", "phrase-secret", "sudo-secret");
        String text = c.toString();
        assertFalse(text.contains("secret"));
        assertFalse(new CommandResult(0, "top secret output", "").toString().contains("secret"));
    }

    @Test
    void sudoPasswordFallsBackToLoginPassword() {
        assertEquals("login", new Credentials("login", null, null).sudoPassword());
        assertEquals("other", new Credentials("login", null, "other").sudoPassword());
        assertNull(new Credentials("login", null, null).rawSudoPassword());
    }

    @Test
    void missingCredentialsFollowTheAuthAndPrivilegeModes() {
        ServerProfile p = new ServerProfile();
        p.setAuthMethod(AuthMethod.PASSWORD);
        p.setPrivilegeMode(PrivilegeMode.SUDO_PASSWORD);
        assertEquals(List.of(Credentials.Missing.LOGIN_PASSWORD, Credentials.Missing.SUDO_PASSWORD),
                Credentials.missing(p, new Credentials(null, null, null)));
        // A stored login password also covers sudo.
        assertTrue(Credentials.missing(p, new Credentials("pw", null, null)).isEmpty());

        p.setAuthMethod(AuthMethod.PRIVATE_KEY);
        p.setPrivilegeMode(PrivilegeMode.SUDO_NOPASSWD);
        assertEquals(List.of(Credentials.Missing.KEY_PASSPHRASE),
                Credentials.missing(p, new Credentials(null, null, null)));
        assertTrue(Credentials.missing(p, new Credentials(null, "", null)).isEmpty());
    }

    @Test
    void parseToolReadsPathAndVersion() {
        ToolStatus ok = RequirementChecker.parseTool(
                new CommandResult(0, "/usr/bin/openssl\nOpenSSL 3.0.13 30 Jan 2024\n", ""), CLOCK);
        assertTrue(ok.available());
        assertEquals("/usr/bin/openssl", ok.path());
        assertEquals("OpenSSL 3.0.13 30 Jan 2024", ok.version());

        ToolStatus missing = RequirementChecker.parseTool(new CommandResult(1, "", ""), CLOCK);
        assertFalse(missing.available());
        assertFalse(RequirementChecker.parseTool(new CommandResult(0, "", ""), CLOCK).available());
    }

    @Test
    void requirementCheckReportsToolsAndRootAccess() throws IOException {
        SshSessionTest.FakeTransport transport = new SshSessionTest.FakeTransport();
        transport.responder = c -> {
            if (c.contains("openssl")) {
                return SshSessionTest.FakeTransport.raw(0, "/usr/bin/openssl\nOpenSSL 3.0.13\n", "");
            }
            if (c.contains("certbot")) {
                return SshSessionTest.FakeTransport.raw(1, "", "");
            }
            return SshSessionTest.FakeTransport.raw(0, "0\n", "");
        };
        SshSession session = new SshSession(transport, new CommandLog(), PrivilegeMode.SUDO_PASSWORD, "pw", "u@h");

        RequirementChecker.Requirements r = RequirementChecker.check(session, ServerPaths.defaults(), CLOCK);

        assertTrue(r.openssl().available());
        assertFalse(r.certbot().available());
        assertTrue(r.privilegeOk());
        assertTrue(transport.commands.stream().anyMatch(c -> c.startsWith("sudo -S")));
    }

    @Test
    void requirementCheckExplainsFailedRootAccess() throws IOException {
        SshSessionTest.FakeTransport transport = new SshSessionTest.FakeTransport();
        transport.responder = c -> c.startsWith("sudo")
                ? SshSessionTest.FakeTransport.raw(1, "", "Sorry, try again.\n")
                : SshSessionTest.FakeTransport.raw(1, "", "");
        SshSession session = new SshSession(transport, new CommandLog(), PrivilegeMode.SUDO_PASSWORD, "bad", "u@h");

        RequirementChecker.Requirements r = RequirementChecker.check(session, ServerPaths.defaults(), CLOCK);

        assertFalse(r.privilegeOk());
        assertTrue(r.privilegeMessage().contains("Sorry, try again."));
    }

    @Test
    void sudoPasswordModeNeedsAPasswordToWrap() {
        assertThrows(IllegalStateException.class, () -> Privilege.wrap(PrivilegeMode.SUDO_PASSWORD, "id", null));
    }

    @Test
    void gatewayCredentialsAreOnlyAskedForWhenTheGatewayIsEnabled() {
        ServerProfile p = new ServerProfile();
        p.setAuthMethod(AuthMethod.PASSWORD);
        p.setPrivilegeMode(PrivilegeMode.NONE);
        assertTrue(Credentials.missing(p, new Credentials("pw", null, null)).isEmpty());

        p.setGatewayEnabled(true);
        assertEquals(List.of(Credentials.Missing.GATEWAY_PASSWORD),
                Credentials.missing(p, new Credentials("pw", null, null)));
        p.setGatewayAuthMethod(AuthMethod.PRIVATE_KEY);
        assertEquals(List.of(Credentials.Missing.GATEWAY_KEY_PASSPHRASE),
                Credentials.missing(p, new Credentials("pw", null, null)));
        assertTrue(Credentials.missing(p, new Credentials("pw", null, null, null, "")).isEmpty());
    }
}
