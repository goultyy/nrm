package mt.su.nrm.config;

import java.security.GeneralSecurityException;

/**
 * Wraps and unwraps the random data key that encrypts the profile store.
 * The store never writes the data key to disk unwrapped.
 */
public interface KeyProtector {

    /** Stable identifier written into the store file, e.g. "dpapi". */
    String id();

    byte[] protect(byte[] key) throws GeneralSecurityException;

    byte[] unprotect(byte[] wrappedKey) throws GeneralSecurityException;
}
