package mt.su.nrm.config;

import mt.su.nrm.model.AuthMethod;
import mt.su.nrm.model.ConfigLayout;
import mt.su.nrm.model.PrivilegeMode;
import mt.su.nrm.model.ServerPaths;
import mt.su.nrm.model.ServerProfile;
import mt.su.nrm.model.ToolStatus;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Converts profiles to and from the plaintext payload that gets encrypted.
 * <p>
 * Each profile is a flat key/value map. Unknown keys are ignored and missing keys fall back
 * to defaults, so fields can be added in later versions without a migration.
 */
final class ProfileCodec {

    static final int PAYLOAD_VERSION = 1;

    private static final int MAX_PROFILES = 10_000;
    private static final int MAX_ENTRIES = 1_000;

    private ProfileCodec() {
    }

    static byte[] encode(List<ServerProfile> profiles) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(PAYLOAD_VERSION);
            out.writeInt(profiles.size());
            for (ServerProfile profile : profiles) {
                Map<String, String> fields = toMap(profile);
                out.writeInt(fields.size());
                for (Map.Entry<String, String> e : fields.entrySet()) {
                    BinaryIO.writeString(out, e.getKey());
                    BinaryIO.writeString(out, e.getValue());
                }
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e); // in-memory stream; can't happen
        }
    }

    static List<ServerProfile> decode(byte[] payload) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        int version = in.readInt();
        if (version != PAYLOAD_VERSION) {
            throw new IOException("Unsupported profile data version " + version + ".");
        }
        int count = BinaryIO.readCount(in, MAX_PROFILES);
        List<ServerProfile> profiles = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int entries = BinaryIO.readCount(in, MAX_ENTRIES);
            Map<String, String> fields = new HashMap<>();
            for (int j = 0; j < entries; j++) {
                fields.put(BinaryIO.readString(in), BinaryIO.readString(in));
            }
            profiles.add(fromMap(fields));
        }
        if (in.available() > 0) {
            throw new IOException("Unexpected data after the last profile.");
        }
        return profiles;
    }

    static Map<String, String> toMap(ServerProfile p) {
        Map<String, String> m = new LinkedHashMap<>();
        put(m, "id", p.getId() == null ? null : p.getId().toString());
        put(m, "name", p.getName());
        put(m, "host", p.getHost());
        put(m, "port", Integer.toString(p.getPort()));
        put(m, "username", p.getUsername());
        put(m, "auth", p.getAuthMethod().name());
        put(m, "password", p.getPassword());
        put(m, "keyPath", p.getPrivateKeyPath());
        put(m, "keyPassphrase", p.getPrivateKeyPassphrase());
        put(m, "privilege", p.getPrivilegeMode().name());
        put(m, "sudoPassword", p.getSudoPassword());
        put(m, "hostKey", p.getHostKeyFingerprint());
        put(m, "notes", p.getNotes());
        put(m, "gateway.enabled", Boolean.toString(p.isGatewayEnabled()));
        put(m, "gateway.host", p.getGatewayHost());
        put(m, "gateway.port", Integer.toString(p.getGatewayPort()));
        put(m, "gateway.username", p.getGatewayUsername());
        put(m, "gateway.auth", p.getGatewayAuthMethod().name());
        put(m, "gateway.password", p.getGatewayPassword());
        put(m, "gateway.keyPath", p.getGatewayPrivateKeyPath());
        put(m, "gateway.keyPassphrase", p.getGatewayPrivateKeyPassphrase());
        put(m, "gateway.hostKey", p.getGatewayHostKeyFingerprint());
        put(m, "cloudflare.token", p.getCloudflareToken());
        put(m, "layout", p.getDetectedLayout().name());
        put(m, "createdAt", instant(p.getCreatedAt()));
        put(m, "updatedAt", instant(p.getUpdatedAt()));

        ServerPaths paths = p.getPaths();
        put(m, "paths.nginxBinary", paths.getNginxBinary());
        put(m, "paths.nginxConfDir", paths.getNginxConfDir());
        put(m, "paths.certbotBinary", paths.getCertbotBinary());
        put(m, "paths.letsEncryptDir", paths.getLetsEncryptDir());
        put(m, "paths.caStorageDir", paths.getCaStorageDir());
        put(m, "paths.manualCertDir", paths.getManualCertDir());
        put(m, "paths.remoteTempDir", paths.getRemoteTempDir());

        putTool(m, "tool.openssl.", p.getOpensslStatus());
        putTool(m, "tool.certbot.", p.getCertbotStatus());
        return m;
    }

    static ServerProfile fromMap(Map<String, String> m) {
        ServerProfile p = new ServerProfile();
        p.setId(parseUuid(m.get("id")));
        p.setName(m.get("name"));
        p.setHost(m.get("host"));
        p.setPort(parseInt(m.get("port"), ServerProfile.DEFAULT_SSH_PORT));
        p.setUsername(m.get("username"));
        p.setAuthMethod(parseEnum(AuthMethod.class, m.get("auth"), AuthMethod.PASSWORD));
        p.setPassword(m.get("password"));
        p.setPrivateKeyPath(m.get("keyPath"));
        p.setPrivateKeyPassphrase(m.get("keyPassphrase"));
        p.setPrivilegeMode(parseEnum(PrivilegeMode.class, m.get("privilege"), PrivilegeMode.SUDO_PASSWORD));
        p.setSudoPassword(m.get("sudoPassword"));
        p.setHostKeyFingerprint(m.get("hostKey"));
        p.setNotes(m.get("notes"));
        p.setGatewayEnabled(Boolean.parseBoolean(m.get("gateway.enabled")));
        p.setGatewayHost(m.get("gateway.host"));
        p.setGatewayPort(parseInt(m.get("gateway.port"), ServerProfile.DEFAULT_SSH_PORT));
        p.setGatewayUsername(m.get("gateway.username"));
        p.setGatewayAuthMethod(parseEnum(AuthMethod.class, m.get("gateway.auth"), AuthMethod.PASSWORD));
        p.setGatewayPassword(m.get("gateway.password"));
        p.setGatewayPrivateKeyPath(m.get("gateway.keyPath"));
        p.setGatewayPrivateKeyPassphrase(m.get("gateway.keyPassphrase"));
        p.setGatewayHostKeyFingerprint(m.get("gateway.hostKey"));
        p.setCloudflareToken(m.get("cloudflare.token"));
        p.setDetectedLayout(parseEnum(ConfigLayout.class, m.get("layout"), ConfigLayout.UNKNOWN));
        p.setCreatedAt(parseInstant(m.get("createdAt")));
        p.setUpdatedAt(parseInstant(m.get("updatedAt")));

        ServerPaths paths = ServerPaths.defaults();
        apply(m, "paths.nginxBinary", paths::setNginxBinary);
        apply(m, "paths.nginxConfDir", paths::setNginxConfDir);
        apply(m, "paths.certbotBinary", paths::setCertbotBinary);
        apply(m, "paths.letsEncryptDir", paths::setLetsEncryptDir);
        apply(m, "paths.caStorageDir", paths::setCaStorageDir);
        apply(m, "paths.manualCertDir", paths::setManualCertDir);
        apply(m, "paths.remoteTempDir", paths::setRemoteTempDir);
        p.setPaths(paths);

        p.setOpensslStatus(readTool(m, "tool.openssl."));
        p.setCertbotStatus(readTool(m, "tool.certbot."));
        return p;
    }

    private static void put(Map<String, String> m, String key, String value) {
        if (value != null) {
            m.put(key, value);
        }
    }

    private static void apply(Map<String, String> m, String key, Consumer<String> setter) {
        String value = m.get(key);
        if (value != null) {
            setter.accept(value);
        }
    }

    private static void putTool(Map<String, String> m, String prefix, ToolStatus status) {
        if (status == null) {
            return;
        }
        put(m, prefix + "available", Boolean.toString(status.available()));
        put(m, prefix + "version", status.version());
        put(m, prefix + "path", status.path());
        put(m, prefix + "checkedAt", instant(status.checkedAt()));
    }

    private static ToolStatus readTool(Map<String, String> m, String prefix) {
        Instant checkedAt = parseInstant(m.get(prefix + "checkedAt"));
        if (checkedAt == null) {
            return null; // never checked, or unreadable: check again next time
        }
        return new ToolStatus(Boolean.parseBoolean(m.get(prefix + "available")),
                m.get(prefix + "version"), m.get(prefix + "path"), checkedAt);
    }

    private static String instant(Instant value) {
        return value == null ? null : value.toString();
    }

    private static Instant parseInstant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static UUID parseUuid(String value) {
        if (value != null) {
            try {
                return UUID.fromString(value);
            } catch (IllegalArgumentException ignored) {
                // fall through: give the profile a fresh id rather than lose it
            }
        }
        return UUID.randomUUID();
    }

    private static int parseInt(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
