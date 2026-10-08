package mt.su.nrm.config;

import mt.su.nrm.util.Secrets;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/**
 * Protects the data key with a key derived from a passphrase (PBKDF2-HMAC-SHA256, AES-256-GCM).
 * Used on non-Windows development machines, in tests, and for a future optional master password.
 * <p>
 * Wrapped layout: iterations (int) | salt (16) | nonce (12) | ciphertext+tag.
 */
public final class PassphraseKeyProtector implements KeyProtector {

    public static final String ID = "passphrase";
    public static final int DEFAULT_ITERATIONS = 600_000;

    private static final int SALT_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int MAX_ITERATIONS = 10_000_000;
    private static final byte[] AAD = "nrm-key-wrap-v1".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final char[] passphrase;
    private final int iterations;

    public PassphraseKeyProtector(char[] passphrase) {
        this(passphrase, DEFAULT_ITERATIONS);
    }

    /** Low iteration counts are only for tests; use {@link #DEFAULT_ITERATIONS} in the app. */
    public PassphraseKeyProtector(char[] passphrase, int iterations) {
        if (passphrase == null || passphrase.length == 0) {
            throw new IllegalArgumentException("Passphrase must not be empty.");
        }
        if (iterations < 1 || iterations > MAX_ITERATIONS) {
            throw new IllegalArgumentException("Iterations out of range: " + iterations);
        }
        this.passphrase = passphrase.clone();
        this.iterations = iterations;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public byte[] protect(byte[] key) throws GeneralSecurityException {
        byte[] salt = new byte[SALT_BYTES];
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(salt);
        RANDOM.nextBytes(nonce);

        Cipher cipher = cipher(Cipher.ENCRYPT_MODE, salt, iterations, nonce);
        byte[] ciphertext = cipher.doFinal(key);

        return ByteBuffer.allocate(4 + SALT_BYTES + NONCE_BYTES + ciphertext.length)
                .putInt(iterations).put(salt).put(nonce).put(ciphertext)
                .array();
    }

    @Override
    public byte[] unprotect(byte[] wrappedKey) throws GeneralSecurityException {
        if (wrappedKey == null || wrappedKey.length < 4 + SALT_BYTES + NONCE_BYTES + TAG_BITS / 8) {
            throw new GeneralSecurityException("Wrapped key is too short.");
        }
        ByteBuffer buf = ByteBuffer.wrap(wrappedKey);
        int storedIterations = buf.getInt();
        if (storedIterations < 1 || storedIterations > MAX_ITERATIONS) {
            throw new GeneralSecurityException("Wrapped key has an invalid iteration count.");
        }
        byte[] salt = new byte[SALT_BYTES];
        byte[] nonce = new byte[NONCE_BYTES];
        buf.get(salt).get(nonce);
        byte[] ciphertext = new byte[buf.remaining()];
        buf.get(ciphertext);

        return cipher(Cipher.DECRYPT_MODE, salt, storedIterations, nonce).doFinal(ciphertext);
    }

    /** Clears the passphrase from memory. The protector can't be used afterwards. */
    public void destroy() {
        Secrets.wipe(passphrase);
    }

    private Cipher cipher(int mode, byte[] salt, int rounds, byte[] nonce) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, rounds, 256);
        byte[] derived = null;
        try {
            derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(derived, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(AAD);
            return cipher;
        } finally {
            spec.clearPassword();
            Secrets.wipe(derived);
        }
    }
}
