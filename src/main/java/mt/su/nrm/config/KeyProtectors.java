package mt.su.nrm.config;

import mt.su.nrm.util.AppDirs;

/** Picks the key protector for the current platform. */
public final class KeyProtectors {

    private KeyProtectors() {
    }

    /**
     * DPAPI on Windows. Other platforms have no equivalent wired in, so callers there must
     * supply a {@link PassphraseKeyProtector} instead.
     */
    public static KeyProtector platformDefault() {
        if (AppDirs.isWindows()) {
            return new DpapiKeyProtector();
        }
        throw new IllegalStateException(
                "The profile store uses Windows DPAPI. On other platforms, supply a PassphraseKeyProtector.");
    }
}
