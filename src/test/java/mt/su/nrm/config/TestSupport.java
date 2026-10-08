package mt.su.nrm.config;

import mt.su.nrm.model.AuthMethod;
import mt.su.nrm.model.ConfigLayout;
import mt.su.nrm.model.PrivilegeMode;
import mt.su.nrm.model.ServerProfile;
import mt.su.nrm.model.ToolStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

final class TestSupport {

    /** Low iteration count keeps tests fast; the app uses the default. */
    static final int TEST_ITERATIONS = 1_000;

    private TestSupport() {
    }

    static KeyProtector passphrase(String passphrase) {
        return new PassphraseKeyProtector(passphrase.toCharArray(), TEST_ITERATIONS);
    }

    /** A protector that always fails, for testing that failed saves change nothing. */
    static KeyProtector failing() {
        return new KeyProtector() {
            @Override
            public String id() {
                return PassphraseKeyProtector.ID;
            }

            @Override
            public byte[] protect(byte[] key) throws GeneralSecurityException {
                throw new GeneralSecurityException("simulated failure");
            }

            @Override
            public byte[] unprotect(byte[] wrappedKey) throws GeneralSecurityException {
                throw new GeneralSecurityException("simulated failure");
            }
        };
    }

    /** A profile with every field set, including secrets and cached tool checks. */
    static ServerProfile fullProfile(String name) {
        ServerProfile p = new ServerProfile();
        p.setId(UUID.randomUUID());
        p.setName(name);
        p.setHost("web1.example.internal");
        p.setPort(2222);
        p.setUsername("deploy");
        p.setAuthMethod(AuthMethod.PRIVATE_KEY);
        p.setPassword("hunter2-s3cret");
        p.setPrivateKeyPath("C:\\Users\\me\\.ssh\\id_ed25519");
        p.setPrivateKeyPassphrase("key-passphrase-s3cret");
        p.setPrivilegeMode(PrivilegeMode.SUDO_PASSWORD);
        p.setSudoPassword("sudo-s3cret");
        p.setHostKeyFingerprint("SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU");
        p.setGatewayEnabled(true);
        p.setGatewayHost("bastion.example.com");
        p.setGatewayPort(2200);
        p.setGatewayUsername("jump");
        p.setGatewayAuthMethod(AuthMethod.PRIVATE_KEY);
        p.setGatewayPassword("gw-pass-s3cret");
        p.setGatewayPrivateKeyPath("C:\\Users\\me\\.ssh\\gateway_ed25519");
        p.setGatewayPrivateKeyPassphrase("gw-phrase-s3cret");
        p.setCloudflareToken("cf-token-s3cret");
        p.setGatewayHostKeyFingerprint("SHA256:gatewayFingerprintForTestsOnly000000000000000");
        p.getPaths().setNginxConfDir("/usr/local/nginx/conf");
        p.getPaths().setCaStorageDir("/srv/ca");
        p.setDetectedLayout(ConfigLayout.CONF_D);
        p.setOpensslStatus(ToolStatus.found("OpenSSL 3.0.13 30 Jan 2024", "/usr/bin/openssl",
                Instant.parse("2026-09-01T10:15:30Z")));
        p.setCertbotStatus(ToolStatus.missing(Instant.parse("2026-09-01T10:15:31Z")));
        p.setNotes("Primary web node\nsecond line, unicode: ÄÖÜ ✓");
        p.setCreatedAt(Instant.parse("2026-08-01T00:00:00Z"));
        p.setUpdatedAt(Instant.parse("2026-09-01T00:00:00Z"));
        return p;
    }

    static ServerProfile minimalProfile(String name) {
        ServerProfile p = new ServerProfile();
        p.setId(UUID.randomUUID());
        p.setName(name);
        p.setHost(name.toLowerCase().replace(' ', '-') + ".example.com");
        p.setUsername("admin");
        return p;
    }

    static void deleteRecursively(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /** A clock tests can move forward. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
