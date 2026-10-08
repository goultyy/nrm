package mt.su.nrm.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mt.su.nrm.model.AuthMethod;
import mt.su.nrm.model.PrivilegeMode;
import mt.su.nrm.model.ServerProfile;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProfileFormTest {

    private static ProfileForm valid() {
        ProfileForm f = new ProfileForm();
        f.name = "Prod";
        f.host = "example.com";
        f.username = "deploy";
        return f;
    }

    @Test
    void validFormHasNoErrors() {
        assertTrue(valid().errors(new ServerProfile(), Set.of()).isEmpty());
    }

    @Test
    void emptyFormReportsRequiredFields() {
        var errors = new ProfileForm().errors(new ServerProfile(), Set.of());
        assertTrue(errors.contains("Name is required."));
        assertTrue(errors.contains("Host is required."));
        assertTrue(errors.contains("Username is required."));
    }

    @Test
    void nonNumericPortGetsOneClearMessage() {
        ProfileForm f = valid();
        f.port = "22a";
        var errors = f.errors(new ServerProfile(), Set.of());
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).startsWith("Port must be a number"));
    }

    @Test
    void outOfRangePortIsRejected() {
        ProfileForm f = valid();
        f.port = "70000";
        assertFalse(f.errors(new ServerProfile(), Set.of()).isEmpty());
        f.port = "0";
        assertFalse(f.errors(new ServerProfile(), Set.of()).isEmpty());
    }

    @Test
    void duplicateNameIsRejectedIgnoringCase() {
        ProfileForm f = valid();
        f.name = " PROD ";
        var errors = f.errors(new ServerProfile(), Set.of("prod"));
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("already exists"));
    }

    @Test
    void relativePathIsRejected() {
        ProfileForm f = valid();
        f.nginxConfDir = "etc/nginx";
        assertFalse(f.errors(new ServerProfile(), Set.of()).isEmpty());
    }

    @Test
    void keyAuthRequiresKeyFile() {
        ProfileForm f = valid();
        f.authMethod = AuthMethod.PRIVATE_KEY;
        assertFalse(f.errors(new ServerProfile(), Set.of()).isEmpty());
        f.privateKeyPath = "C:/keys/id_ed25519";
        assertTrue(f.errors(new ServerProfile(), Set.of()).isEmpty());
    }

    @Test
    void blankSecretsBecomeNull() {
        ProfileForm f = valid();
        f.password = "";
        f.sudoPassword = "  ";
        ServerProfile p = f.applyTo(new ServerProfile());
        assertNull(p.getPassword());
        assertNull(p.getSudoPassword());
    }

    @Test
    void secretsThatDoNotApplyToTheChosenModesAreDropped() {
        ProfileForm f = valid();
        f.authMethod = AuthMethod.PASSWORD;
        f.password = "pw";
        f.privateKeyPath = "C:/stale";
        f.privateKeyPassphrase = "stale";
        f.privilegeMode = PrivilegeMode.SUDO_NOPASSWD;
        f.sudoPassword = "stale";
        ServerProfile p = f.applyTo(new ServerProfile());
        assertEquals("pw", p.getPassword());
        assertNull(p.getPrivateKeyPath());
        assertNull(p.getPrivateKeyPassphrase());
        assertNull(p.getSudoPassword());
    }

    @Test
    void applyKeepsIdAndPinnedHostKey() {
        ServerProfile base = new ServerProfile();
        UUID id = UUID.randomUUID();
        base.setId(id);
        base.setHostKeyFingerprint("SHA256:abc");
        ServerProfile p = valid().applyTo(base);
        assertEquals(id, p.getId());
        assertEquals("SHA256:abc", p.getHostKeyFingerprint());
    }

    @Test
    void roundTripsThroughFrom() {
        ServerProfile p = valid().applyTo(new ServerProfile());
        p.setPassword("pw");
        p.setNotes("hello");
        p.getPaths().setNginxBinary("/opt/nginx/sbin/nginx");
        assertEquals(p, ProfileForm.from(p).applyTo(p));
    }

    @Test
    void doesNotMutateTheBaseProfile() {
        ServerProfile base = new ServerProfile();
        valid().applyTo(base);
        assertEquals("", base.getName());
    }

    @Test
    void gatewayFieldsAreIgnoredUntilEnabled() {
        ProfileForm f = valid();
        f.gatewayHost = "";
        f.gatewayUsername = "";
        assertTrue(f.errors(new ServerProfile(), Set.of()).isEmpty());
    }

    @Test
    void enabledGatewayNeedsHostUserAndKeyFile() {
        ProfileForm f = valid();
        f.gatewayEnabled = true;
        f.gatewayAuthMethod = AuthMethod.PRIVATE_KEY;
        var errors = f.errors(new ServerProfile(), Set.of());
        assertTrue(errors.contains("Gateway host is required."));
        assertTrue(errors.contains("Gateway username is required."));
        assertTrue(errors.contains("Choose a private key file for the gateway."));

        f.gatewayHost = "bastion.example.com";
        f.gatewayUsername = "jump";
        f.gatewayPrivateKeyPath = "C:\\keys\\gw";
        assertTrue(f.errors(new ServerProfile(), Set.of()).isEmpty());
    }

    @Test
    void badGatewayPortGetsOneClearMessage() {
        ProfileForm f = valid();
        f.gatewayEnabled = true;
        f.gatewayHost = "bastion.example.com";
        f.gatewayUsername = "jump";
        f.gatewayPort = "abc";
        var errors = f.errors(new ServerProfile(), Set.of());
        assertEquals(1, errors.stream().filter(e -> e.startsWith("Gateway port")).count());
    }

    @Test
    void gatewayPasswordIsDroppedWhenKeyAuthIsChosen() {
        ProfileForm f = valid();
        f.gatewayEnabled = true;
        f.gatewayHost = "bastion.example.com";
        f.gatewayUsername = "jump";
        f.gatewayAuthMethod = AuthMethod.PRIVATE_KEY;
        f.gatewayPassword = "left-over";
        f.gatewayPrivateKeyPath = "C:\\keys\\gw";
        ServerProfile p = f.applyTo(new ServerProfile());
        assertNull(p.getGatewayPassword());
        assertEquals("C:\\keys\\gw", p.getGatewayPrivateKeyPath());
    }

    @Test
    void changingTheGatewayHostForgetsItsPinnedKey() {
        ServerProfile base = new ServerProfile();
        base.setGatewayHost("old.example.com");
        base.setGatewayHostKeyFingerprint("SHA256:old");
        ProfileForm f = ProfileForm.from(base);
        assertEquals("SHA256:old", f.applyTo(base).getGatewayHostKeyFingerprint());
        f.gatewayHost = "new.example.com";
        assertNull(f.applyTo(base).getGatewayHostKeyFingerprint());
    }
}
