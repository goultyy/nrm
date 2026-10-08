package mt.su.nrm.config;

import mt.su.nrm.model.AuthMethod;
import mt.su.nrm.model.ConfigLayout;
import mt.su.nrm.model.PrivilegeMode;
import mt.su.nrm.model.ServerPaths;
import mt.su.nrm.model.ServerProfile;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProfileCodecTest {

    @Test
    void fullProfilesRoundTrip() throws IOException {
        List<ServerProfile> profiles = List.of(TestSupport.fullProfile("Web 1"), TestSupport.fullProfile("Web 2"));
        assertEquals(profiles, ProfileCodec.decode(ProfileCodec.encode(profiles)));
    }

    @Test
    void emptyListRoundTrips() throws IOException {
        assertEquals(List.of(), ProfileCodec.decode(ProfileCodec.encode(List.of())));
    }

    @Test
    void nullSecretsStayNull() throws IOException {
        ServerProfile p = TestSupport.minimalProfile("Bare");
        ServerProfile decoded = ProfileCodec.decode(ProfileCodec.encode(List.of(p))).get(0);
        assertNull(decoded.getPassword());
        assertNull(decoded.getSudoPassword());
        assertNull(decoded.getOpensslStatus());
        assertFalse(decoded.isGatewayEnabled());
        assertNull(decoded.getGatewayPassword());
    }

    @Test
    void missingKeysGetDefaultsAndUnknownKeysAreIgnored() {
        Map<String, String> fields = new HashMap<>();
        fields.put("name", "Old");
        fields.put("host", "old.example.com");
        fields.put("someFutureField", "whatever");

        ServerProfile p = ProfileCodec.fromMap(fields);
        assertEquals("Old", p.getName());
        assertEquals(22, p.getPort());
        assertEquals(AuthMethod.PASSWORD, p.getAuthMethod());
        assertEquals(PrivilegeMode.SUDO_PASSWORD, p.getPrivilegeMode());
        assertEquals(ServerPaths.defaults(), p.getPaths());
        assertNotNull(p.getId());
    }

    @Test
    void unreadableValuesFallBackToDefaults() {
        Map<String, String> fields = new HashMap<>();
        fields.put("port", "not-a-number");
        fields.put("auth", "KERBEROS");
        fields.put("layout", "SOMETHING_NEW");
        fields.put("createdAt", "yesterday");
        fields.put("tool.openssl.checkedAt", "garbage");

        ServerProfile p = ProfileCodec.fromMap(fields);
        assertEquals(22, p.getPort());
        assertEquals(AuthMethod.PASSWORD, p.getAuthMethod());
        assertEquals(ConfigLayout.UNKNOWN, p.getDetectedLayout());
        assertNull(p.getCreatedAt());
        assertNull(p.getOpensslStatus());
    }

    @Test
    void rejectsUnknownPayloadVersion() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(99);
        out.writeInt(0);
        assertThrows(IOException.class, () -> ProfileCodec.decode(bytes.toByteArray()));
    }

    @Test
    void rejectsAbsurdLengths() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(ProfileCodec.PAYLOAD_VERSION);
        out.writeInt(1);
        out.writeInt(1);
        out.writeInt(Integer.MAX_VALUE); // key length far beyond the data
        assertThrows(IOException.class, () -> ProfileCodec.decode(bytes.toByteArray()));
    }
}
