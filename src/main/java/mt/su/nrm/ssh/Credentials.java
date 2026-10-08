package mt.su.nrm.ssh;

import mt.su.nrm.model.AuthMethod;
import mt.su.nrm.model.PrivilegeMode;
import mt.su.nrm.model.ServerProfile;
import mt.su.nrm.util.Secrets;

import java.util.ArrayList;
import java.util.List;

/**
 * The secrets needed for one connection. Any stored on the profile are used as-is; the UI asks for
 * the rest ({@link #missing}) before connecting.
 */
public final class Credentials {

    public enum Missing { LOGIN_PASSWORD, KEY_PASSPHRASE, SUDO_PASSWORD, GATEWAY_PASSWORD, GATEWAY_KEY_PASSPHRASE }

    private final String password;
    private final String keyPassphrase;
    private final String sudoPassword;
    private final String gatewayPassword;
    private final String gatewayKeyPassphrase;

    public Credentials(String password, String keyPassphrase, String sudoPassword) {
        this(password, keyPassphrase, sudoPassword, null, null);
    }

    public Credentials(String password, String keyPassphrase, String sudoPassword,
                       String gatewayPassword, String gatewayKeyPassphrase) {
        this.password = password;
        this.keyPassphrase = keyPassphrase;
        this.sudoPassword = sudoPassword;
        this.gatewayPassword = gatewayPassword;
        this.gatewayKeyPassphrase = gatewayKeyPassphrase;
    }

    public static Credentials stored(ServerProfile p) {
        return new Credentials(p.getPassword(), p.getPrivateKeyPassphrase(), p.getSudoPassword(),
                p.getGatewayPassword(), p.getGatewayPrivateKeyPassphrase());
    }

    /** The gateway login password, or null. */
    public String gatewayPassword() {
        return gatewayPassword;
    }

    /** The gateway private key passphrase, or null for an unprotected key. */
    public String gatewayKeyPassphrase() {
        return gatewayKeyPassphrase;
    }

    /** The login password, or null. */
    public String password() {
        return password;
    }

    /** The private key passphrase, or null for an unprotected key. */
    public String keyPassphrase() {
        return keyPassphrase;
    }

    /** The sudo password exactly as given, without the fall-back to the login password. */
    public String rawSudoPassword() {
        return sudoPassword;
    }

    /** The sudo password, which defaults to the login password. */
    public String sudoPassword() {
        return sudoPassword != null ? sudoPassword : password;
    }

    /**
     * What still has to be asked for. A key passphrase can't be told apart from "no passphrase",
     * so it is reported as missing whenever none is stored; the prompt lets the user leave it empty.
     */
    public static List<Missing> missing(ServerProfile p, Credentials c) {
        List<Missing> result = new ArrayList<>();
        if (p.getAuthMethod() == AuthMethod.PASSWORD && isEmpty(c.password)) {
            result.add(Missing.LOGIN_PASSWORD);
        }
        if (p.getAuthMethod() == AuthMethod.PRIVATE_KEY && c.keyPassphrase == null) {
            result.add(Missing.KEY_PASSPHRASE);
        }
        if (p.isGatewayEnabled()) {
            if (p.getGatewayAuthMethod() == AuthMethod.PASSWORD && isEmpty(c.gatewayPassword)) {
                result.add(Missing.GATEWAY_PASSWORD);
            }
            if (p.getGatewayAuthMethod() == AuthMethod.PRIVATE_KEY && c.gatewayKeyPassphrase == null) {
                result.add(Missing.GATEWAY_KEY_PASSPHRASE);
            }
        }
        if (p.getPrivilegeMode() == PrivilegeMode.SUDO_PASSWORD && isEmpty(c.sudoPassword())) {
            result.add(Missing.SUDO_PASSWORD);
        }
        return result;
    }

    /** Registers every secret with the log so it is masked wherever it appears. */
    public void registerWith(CommandLog log) {
        log.addSecret(password);
        log.addSecret(keyPassphrase);
        log.addSecret(sudoPassword);
        log.addSecret(gatewayPassword);
        log.addSecret(gatewayKeyPassphrase);
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    @Override
    public String toString() {
        return "Credentials[password=" + Secrets.mask(password) + ", keyPassphrase=" + Secrets.mask(keyPassphrase)
                + ", sudoPassword=" + Secrets.mask(sudoPassword)
                + ", gatewayPassword=" + Secrets.mask(gatewayPassword)
                + ", gatewayKeyPassphrase=" + Secrets.mask(gatewayKeyPassphrase) + "]";
    }
}
