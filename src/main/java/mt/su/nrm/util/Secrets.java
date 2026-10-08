package mt.su.nrm.util;

import java.util.Arrays;

/** Best-effort helpers for keeping secrets out of memory dumps and log output. */
public final class Secrets {

    private Secrets() {
    }

    public static void wipe(byte[] data) {
        if (data != null) {
            Arrays.fill(data, (byte) 0);
        }
    }

    public static void wipe(char[] data) {
        if (data != null) {
            Arrays.fill(data, '\0');
        }
    }

    /** Describes a secret without revealing it, for toString and log output. */
    public static String mask(String secret) {
        return secret == null || secret.isEmpty() ? "(none)" : "(set)";
    }
}
