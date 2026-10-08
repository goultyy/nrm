package mt.su.nrm.ssh;

import java.io.IOException;

/** The server was reached but did not accept the username, password or key. */
public final class AuthenticationFailedException extends IOException {

    private static final long serialVersionUID = 1L;

    public AuthenticationFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
