package mt.su.nrm.config;

import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.WinCrypt;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * Protects the data key with Windows DPAPI, tied to the current Windows user account.
 * No master password is needed; another user, or the same file copied to another machine,
 * cannot unlock it.
 */
public final class DpapiKeyProtector implements KeyProtector {

    public static final String ID = "dpapi";

    /** Extra entropy so other DPAPI consumers running as this user can't unwrap our blob by accident. */
    // A cryptographic label, not a display name: changing it would make every saved profile store
    // undecryptable, so it keeps the name the app had when the first store was written.
    private static final byte[] ENTROPY = "NginxRemoteManager/profile-key/v1".getBytes(StandardCharsets.UTF_8);
    private static final String DESCRIPTION = "NRM profile key";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public byte[] protect(byte[] key) throws GeneralSecurityException {
        try {
            return Crypt32Util.cryptProtectData(key, ENTROPY, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, DESCRIPTION, null);
        } catch (RuntimeException | LinkageError e) {
            throw new GeneralSecurityException("Windows could not protect the profile key: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] unprotect(byte[] wrappedKey) throws GeneralSecurityException {
        try {
            return Crypt32Util.cryptUnprotectData(wrappedKey, ENTROPY, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null);
        } catch (RuntimeException | LinkageError e) {
            throw new GeneralSecurityException("Windows could not unlock the profile key: " + e.getMessage(), e);
        }
    }
}
