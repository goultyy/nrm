package mt.su.nrm.ssh;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** Trust-on-first-connect host key handling: pin the SHA-256 fingerprint, then insist on it. */
public final class HostKeyPinning {

    public enum Outcome {
        /** No fingerprint pinned yet: accept and pin this one. */
        FIRST_USE,
        /** Matches the pinned fingerprint. */
        MATCH,
        /** Differs from the pinned fingerprint: the connection must be refused. */
        MISMATCH
    }

    private HostKeyPinning() {
    }

    public static Outcome evaluate(String pinned, String actual) {
        if (pinned == null || pinned.isBlank()) {
            return Outcome.FIRST_USE;
        }
        return pinned.equals(actual) ? Outcome.MATCH : Outcome.MISMATCH;
    }

    /** OpenSSH-style fingerprint of a public key blob: {@code SHA256:<base64 without padding>}. */
    public static String fingerprint(byte[] publicKeyBlob) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBlob);
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
