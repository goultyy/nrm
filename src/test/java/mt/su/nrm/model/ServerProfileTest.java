package mt.su.nrm.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerProfileTest {

    private static ServerProfile valid() {
        ServerProfile p = new ServerProfile();
        p.setName("Web 1");
        p.setHost("web1.example.com");
        p.setUsername("deploy");
        return p;
    }

    @Test
    void validProfileHasNoErrors() {
        assertEquals(List.of(), valid().validate());
    }

    @Test
    void emptyProfileReportsRequiredFields() {
        List<String> errors = new ServerProfile().validate();
        assertTrue(errors.contains("Name is required."));
        assertTrue(errors.contains("Host is required."));
        assertTrue(errors.contains("Username is required."));
    }

    @Test
    void acceptsIpv4AndIpv6Hosts() {
        ServerProfile p = valid();
        p.setHost("192.168.1.10");
        assertEquals(List.of(), p.validate());
        p.setHost("fe80::1%eth0");
        assertEquals(List.of(), p.validate());
    }

    @Test
    void rejectsHostWithSpacesOrShellCharacters() {
        ServerProfile p = valid();
        p.setHost("web1; reboot");
        assertTrue(p.validate().contains("Host must be a hostname or IP address."));
    }

    @Test
    void portMustBeInRange() {
        ServerProfile p = valid();
        p.setPort(0);
        assertTrue(p.validate().contains("Port must be between 1 and 65535."));
        p.setPort(65536);
        assertTrue(p.validate().contains("Port must be between 1 and 65535."));
        p.setPort(2222);
        assertEquals(List.of(), p.validate());
    }

    @Test
    void keyAuthRequiresKeyPath() {
        ServerProfile p = valid();
        p.setAuthMethod(AuthMethod.PRIVATE_KEY);
        assertTrue(p.validate().contains("Choose a private key file for key authentication."));
        p.setPrivateKeyPath("C:\\Users\\me\\.ssh\\id_ed25519");
        assertEquals(List.of(), p.validate());
    }

    @Test
    void passwordMayBeLeftEmptyToPromptOnConnect() {
        ServerProfile p = valid();
        p.setPassword(null);
        assertEquals(List.of(), p.validate());
    }

    @Test
    void remotePathsMustBeAbsolute() {
        ServerProfile p = valid();
        p.getPaths().setNginxConfDir("etc/nginx");
        assertTrue(p.validate().contains("Nginx config folder must be an absolute path (starting with /)."));
    }

    @Test
    void remotePathsRejectControlCharacters() {
        ServerProfile p = valid();
        p.getPaths().setCaStorageDir("/etc/ca\nrm -rf /");
        assertTrue(p.validate().contains("CA storage folder contains invalid characters."));
    }

    @Test
    void mainConfigFileJoinsCleanly() {
        ServerPaths paths = ServerPaths.defaults();
        assertEquals("/etc/nginx/nginx.conf", paths.mainConfigFile());
        paths.setNginxConfDir("/usr/local/nginx/conf/");
        assertEquals("/usr/local/nginx/conf/nginx.conf", paths.mainConfigFile());
    }

    @Test
    void copyIsDeep() {
        ServerProfile original = valid();
        original.setId(UUID.randomUUID());
        original.setOpensslStatus(ToolStatus.found("OpenSSL 3.0.13", "/usr/bin/openssl", Instant.EPOCH));

        ServerProfile copy = original.copy();
        assertEquals(original, copy);
        assertNotSame(original.getPaths(), copy.getPaths());

        copy.getPaths().setNginxConfDir("/opt/nginx");
        assertEquals(ServerPaths.DEFAULT_NGINX_CONF_DIR, original.getPaths().getNginxConfDir());
    }

    @Test
    void toStringNeverContainsSecrets() {
        ServerProfile p = valid();
        p.setPassword("hunter2");
        p.setSudoPassword("sudo-secret");
        p.setPrivateKeyPassphrase("key-secret");
        p.setCloudflareToken("cf-secret");
        String text = p.toString();
        assertFalse(text.contains("cf-secret"));
        assertFalse(text.contains("hunter2"));
        assertFalse(text.contains("sudo-secret"));
        assertFalse(text.contains("key-secret"));
        assertTrue(text.contains("password=(set)"));
    }

    @Test
    void displayAddressHidesDefaultPort() {
        ServerProfile p = valid();
        assertEquals("deploy@web1.example.com", p.displayAddress());
        p.setPort(2222);
        assertEquals("deploy@web1.example.com:2222", p.displayAddress());
    }
}
