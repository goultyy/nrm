package mt.su.nrm.ui;

import mt.su.nrm.model.AuthMethod;
import mt.su.nrm.model.PrivilegeMode;
import mt.su.nrm.model.ServerPaths;
import mt.su.nrm.model.ServerProfile;
import mt.su.nrm.util.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The raw, unparsed contents of the server dialog. Kept free of JavaFX so the rules for turning
 * text fields into a {@link ServerProfile} (and the inline messages that go with them) can be
 * unit tested.
 * <p>
 * Blank secret fields mean "not stored, ask when connecting"; a blank sudo password means
 * "reuse the login password".
 */
public final class ProfileForm {

    public String name = "";
    public String host = "";
    public String port = String.valueOf(ServerProfile.DEFAULT_SSH_PORT);
    public String username = "";
    public AuthMethod authMethod = AuthMethod.PASSWORD;
    public String password = "";
    public String privateKeyPath = "";
    public String privateKeyPassphrase = "";
    public PrivilegeMode privilegeMode = PrivilegeMode.SUDO_PASSWORD;
    public String sudoPassword = "";
    public String notes = "";

    public boolean gatewayEnabled;
    public String gatewayHost = "";
    public String gatewayPort = String.valueOf(ServerProfile.DEFAULT_SSH_PORT);
    public String gatewayUsername = "";
    public AuthMethod gatewayAuthMethod = AuthMethod.PASSWORD;
    public String gatewayPassword = "";
    public String gatewayPrivateKeyPath = "";
    public String gatewayPrivateKeyPassphrase = "";

    public String cloudflareToken = "";

    public String nginxBinary = ServerPaths.DEFAULT_NGINX_BINARY;
    public String nginxConfDir = ServerPaths.DEFAULT_NGINX_CONF_DIR;
    public String certbotBinary = ServerPaths.DEFAULT_CERTBOT_BINARY;
    public String letsEncryptDir = ServerPaths.DEFAULT_LETSENCRYPT_DIR;
    public String caStorageDir = ServerPaths.DEFAULT_CA_STORAGE_DIR;
    public String manualCertDir = ServerPaths.DEFAULT_MANUAL_CERT_DIR;
    public String remoteTempDir = ServerPaths.DEFAULT_REMOTE_TEMP_DIR;

    /** Fills a form from an existing profile. */
    public static ProfileForm from(ServerProfile p) {
        ProfileForm f = new ProfileForm();
        f.name = p.getName();
        f.host = p.getHost();
        f.port = String.valueOf(p.getPort());
        f.username = p.getUsername();
        f.authMethod = p.getAuthMethod();
        f.password = orEmpty(p.getPassword());
        f.privateKeyPath = orEmpty(p.getPrivateKeyPath());
        f.privateKeyPassphrase = orEmpty(p.getPrivateKeyPassphrase());
        f.privilegeMode = p.getPrivilegeMode();
        f.sudoPassword = orEmpty(p.getSudoPassword());
        f.notes = orEmpty(p.getNotes());
        f.gatewayEnabled = p.isGatewayEnabled();
        f.gatewayHost = p.getGatewayHost();
        f.gatewayPort = String.valueOf(p.getGatewayPort());
        f.gatewayUsername = p.getGatewayUsername();
        f.gatewayAuthMethod = p.getGatewayAuthMethod();
        f.gatewayPassword = orEmpty(p.getGatewayPassword());
        f.gatewayPrivateKeyPath = orEmpty(p.getGatewayPrivateKeyPath());
        f.gatewayPrivateKeyPassphrase = orEmpty(p.getGatewayPrivateKeyPassphrase());
        f.cloudflareToken = orEmpty(p.getCloudflareToken());
        ServerPaths paths = p.getPaths();
        f.nginxBinary = paths.getNginxBinary();
        f.nginxConfDir = paths.getNginxConfDir();
        f.certbotBinary = paths.getCertbotBinary();
        f.letsEncryptDir = paths.getLetsEncryptDir();
        f.caStorageDir = paths.getCaStorageDir();
        f.manualCertDir = paths.getManualCertDir();
        f.remoteTempDir = paths.getRemoteTempDir();
        return f;
    }

    /**
     * Applies the form onto a copy of {@code base}, which carries the id, pinned host key and
     * cached server facts through unchanged. An unparseable port becomes 0, which
     * {@link ServerProfile#validate()} rejects; {@link #errors} words that case better.
     */
    public ServerProfile applyTo(ServerProfile base) {
        ServerProfile p = base.copy();
        p.setName(name.strip());
        p.setHost(host.strip());
        Integer parsedPort = parsePort();
        p.setPort(parsedPort == null ? 0 : parsedPort);
        p.setUsername(username.strip());
        p.setAuthMethod(authMethod);
        boolean useKey = authMethod == AuthMethod.PRIVATE_KEY;
        p.setPassword(useKey ? null : nullIfEmpty(password));
        p.setPrivateKeyPath(useKey ? nullIfEmpty(privateKeyPath.strip()) : null);
        p.setPrivateKeyPassphrase(useKey ? nullIfEmpty(privateKeyPassphrase) : null);
        p.setPrivilegeMode(privilegeMode);
        p.setSudoPassword(privilegeMode == PrivilegeMode.SUDO_PASSWORD ? nullIfEmpty(sudoPassword) : null);
        p.setNotes(notes);

        p.setGatewayEnabled(gatewayEnabled);
        String newGatewayHost = gatewayHost.strip();
        // A different gateway is a different server to trust, so its pinned key must not carry over.
        if (!newGatewayHost.equals(base.getGatewayHost()) || parseGatewayPortOrZero() != base.getGatewayPort()) {
            p.setGatewayHostKeyFingerprint(null);
        }
        p.setGatewayHost(newGatewayHost);
        p.setGatewayPort(parseGatewayPortOrZero());
        p.setGatewayUsername(gatewayUsername.strip());
        p.setGatewayAuthMethod(gatewayAuthMethod);
        boolean gatewayKey = gatewayAuthMethod == AuthMethod.PRIVATE_KEY;
        p.setGatewayPassword(gatewayKey ? null : nullIfEmpty(gatewayPassword));
        p.setGatewayPrivateKeyPath(gatewayKey ? nullIfEmpty(gatewayPrivateKeyPath.strip()) : null);
        p.setGatewayPrivateKeyPassphrase(gatewayKey ? nullIfEmpty(gatewayPrivateKeyPassphrase) : null);

        p.setCloudflareToken(nullIfEmpty(cloudflareToken.strip()));

        ServerPaths paths = p.getPaths();
        paths.setNginxBinary(nginxBinary.strip());
        paths.setNginxConfDir(nginxConfDir.strip());
        paths.setCertbotBinary(certbotBinary.strip());
        paths.setLetsEncryptDir(letsEncryptDir.strip());
        paths.setCaStorageDir(caStorageDir.strip());
        paths.setManualCertDir(manualCertDir.strip());
        paths.setRemoteTempDir(remoteTempDir.strip());
        return p;
    }

    /**
     * Everything wrong with the form right now, worded for display under the fields.
     *
     * @param takenNames names of the other profiles (a duplicate is rejected, ignoring case)
     */
    public List<String> errors(ServerProfile base, Set<String> takenNames) {
        List<String> errors = new ArrayList<>();
        boolean badPort = parsePort() == null;
        if (badPort) {
            errors.add("Port must be a number between 1 and 65535.");
        }
        boolean badGatewayPort = gatewayEnabled && parsePort(gatewayPort) == null;
        if (badGatewayPort) {
            errors.add("Gateway port must be a number between 1 and 65535.");
        }
        for (String problem : applyTo(base).validate()) {
            // An unparseable port already has a clearer message above.
            if (badPort && problem.startsWith("Port")) {
                continue;
            }
            if (badGatewayPort && problem.startsWith("Gateway port")) {
                continue;
            }
            errors.add(problem);
        }
        String wanted = name.strip().toLowerCase(Locale.ROOT);
        if (!wanted.isEmpty()
                && takenNames.stream().anyMatch(n -> n.strip().toLowerCase(Locale.ROOT).equals(wanted))) {
            errors.add("A server named \"" + name.strip() + "\" already exists.");
        }
        return errors;
    }

    private int parseGatewayPortOrZero() {
        Integer parsed = parsePort(gatewayPort);
        return parsed == null ? 0 : parsed;
    }

    private Integer parsePort() {
        return parsePort(port);
    }

    private static Integer parsePort(String text) {
        String s = text.strip();
        if (s.isEmpty() || s.length() > 5) {
            return null;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return null;
            }
        }
        return Integer.parseInt(s);
    }

    private static String nullIfEmpty(String s) {
        return Text.isBlank(s) ? null : s;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
