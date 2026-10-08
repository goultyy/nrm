package mt.su.nrm.model;

import mt.su.nrm.util.Secrets;
import mt.su.nrm.util.Text;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One remote Nginx server: connection details, remote paths and cached facts about the server.
 * <p>
 * Instances are mutable so editors can bind to them; the repository hands out copies, so edits
 * only take effect when passed back to {@code ProfileRepository.update}.
 * <p>
 * Secret fields may be null, meaning "not stored, ask when connecting".
 */
public final class ServerProfile {

    public static final int DEFAULT_SSH_PORT = 22;
    public static final int MAX_NAME_LENGTH = 100;

    /** Hostnames, IPv4, and IPv6 (optionally bracketed, with zone id). */
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9._\\-:\\[\\]%]{1,253}");

    private UUID id;
    private String name = "";
    private String host = "";
    private int port = DEFAULT_SSH_PORT;
    private String username = "";

    private AuthMethod authMethod = AuthMethod.PASSWORD;
    private String password;
    private String privateKeyPath;
    private String privateKeyPassphrase;

    private PrivilegeMode privilegeMode = PrivilegeMode.SUDO_PASSWORD;
    /** Null means "use the login password for sudo", which is the usual case. */
    private String sudoPassword;

    /** SHA-256 fingerprint of the server's host key, pinned on first connect. */
    private String hostKeyFingerprint;

    /** Optional SSH gateway (jump host): when enabled, the server is reached through it. */
    private boolean gatewayEnabled;
    private String gatewayHost = "";
    private int gatewayPort = DEFAULT_SSH_PORT;
    private String gatewayUsername = "";
    private AuthMethod gatewayAuthMethod = AuthMethod.PASSWORD;
    private String gatewayPassword;
    private String gatewayPrivateKeyPath;
    private String gatewayPrivateKeyPassphrase;
    /** SHA-256 fingerprint of the gateway's host key, pinned on first connect. */
    private String gatewayHostKeyFingerprint;

    /** Optional Cloudflare API token for this server's DNS records and tunnel routes. */
    private String cloudflareToken;

    private ServerPaths paths = ServerPaths.defaults();

    private ConfigLayout detectedLayout = ConfigLayout.UNKNOWN;
    private ToolStatus opensslStatus;
    private ToolStatus certbotStatus;

    private String notes = "";
    private Instant createdAt;
    private Instant updatedAt;

    /** Returns human-readable problems, or an empty list if the profile can be saved. */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();

        String trimmedName = name.strip();
        if (trimmedName.isEmpty()) {
            errors.add("Name is required.");
        } else if (trimmedName.length() > MAX_NAME_LENGTH) {
            errors.add("Name must be " + MAX_NAME_LENGTH + " characters or fewer.");
        } else if (Text.hasControlChars(trimmedName)) {
            errors.add("Name contains invalid characters.");
        }

        String trimmedHost = host.strip();
        if (trimmedHost.isEmpty()) {
            errors.add("Host is required.");
        } else if (!HOST.matcher(trimmedHost).matches()) {
            errors.add("Host must be a hostname or IP address.");
        }

        if (port < 1 || port > 65535) {
            errors.add("Port must be between 1 and 65535.");
        }

        String trimmedUser = username.strip();
        if (trimmedUser.isEmpty()) {
            errors.add("Username is required.");
        } else if (Text.hasWhitespace(trimmedUser) || Text.hasControlChars(trimmedUser)) {
            errors.add("Username must not contain spaces or control characters.");
        }

        if (authMethod == AuthMethod.PRIVATE_KEY && Text.isBlank(privateKeyPath)) {
            errors.add("Choose a private key file for key authentication.");
        }

        if (gatewayEnabled) {
            String gh = gatewayHost.strip();
            if (gh.isEmpty()) {
                errors.add("Gateway host is required.");
            } else if (!HOST.matcher(gh).matches()) {
                errors.add("Gateway host must be a hostname or IP address.");
            }
            if (gatewayPort < 1 || gatewayPort > 65535) {
                errors.add("Gateway port must be between 1 and 65535.");
            }
            String gu = gatewayUsername.strip();
            if (gu.isEmpty()) {
                errors.add("Gateway username is required.");
            } else if (Text.hasWhitespace(gu) || Text.hasControlChars(gu)) {
                errors.add("Gateway username must not contain spaces or control characters.");
            }
            if (gatewayAuthMethod == AuthMethod.PRIVATE_KEY && Text.isBlank(gatewayPrivateKeyPath)) {
                errors.add("Choose a private key file for the gateway.");
            }
        }

        errors.addAll(paths.validate());
        return errors;
    }

    /** Deep copy. */
    public ServerProfile copy() {
        ServerProfile c = new ServerProfile();
        c.id = id;
        c.name = name;
        c.host = host;
        c.port = port;
        c.username = username;
        c.authMethod = authMethod;
        c.password = password;
        c.privateKeyPath = privateKeyPath;
        c.privateKeyPassphrase = privateKeyPassphrase;
        c.privilegeMode = privilegeMode;
        c.sudoPassword = sudoPassword;
        c.hostKeyFingerprint = hostKeyFingerprint;
        c.gatewayEnabled = gatewayEnabled;
        c.gatewayHost = gatewayHost;
        c.gatewayPort = gatewayPort;
        c.gatewayUsername = gatewayUsername;
        c.gatewayAuthMethod = gatewayAuthMethod;
        c.gatewayPassword = gatewayPassword;
        c.gatewayPrivateKeyPath = gatewayPrivateKeyPath;
        c.gatewayPrivateKeyPassphrase = gatewayPrivateKeyPassphrase;
        c.gatewayHostKeyFingerprint = gatewayHostKeyFingerprint;
        c.cloudflareToken = cloudflareToken;
        c.paths = paths.copy();
        c.detectedLayout = detectedLayout;
        c.opensslStatus = opensslStatus;
        c.certbotStatus = certbotStatus;
        c.notes = notes;
        c.createdAt = createdAt;
        c.updatedAt = updatedAt;
        return c;
    }

    /** "user@host" or "user@host:port" for display. */
    public String displayAddress() {
        return username + "@" + host + (port == DEFAULT_SSH_PORT ? "" : ":" + port);
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = orEmpty(name);
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = orEmpty(host);
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = orEmpty(username);
    }

    public AuthMethod getAuthMethod() {
        return authMethod;
    }

    public void setAuthMethod(AuthMethod authMethod) {
        this.authMethod = Objects.requireNonNull(authMethod, "authMethod");
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getPrivateKeyPath() {
        return privateKeyPath;
    }

    public void setPrivateKeyPath(String privateKeyPath) {
        this.privateKeyPath = privateKeyPath;
    }

    public String getPrivateKeyPassphrase() {
        return privateKeyPassphrase;
    }

    public void setPrivateKeyPassphrase(String privateKeyPassphrase) {
        this.privateKeyPassphrase = privateKeyPassphrase;
    }

    public PrivilegeMode getPrivilegeMode() {
        return privilegeMode;
    }

    public void setPrivilegeMode(PrivilegeMode privilegeMode) {
        this.privilegeMode = Objects.requireNonNull(privilegeMode, "privilegeMode");
    }

    public String getSudoPassword() {
        return sudoPassword;
    }

    public void setSudoPassword(String sudoPassword) {
        this.sudoPassword = sudoPassword;
    }

    public String getHostKeyFingerprint() {
        return hostKeyFingerprint;
    }

    public void setHostKeyFingerprint(String hostKeyFingerprint) {
        this.hostKeyFingerprint = hostKeyFingerprint;
    }

    public boolean isGatewayEnabled() {
        return gatewayEnabled;
    }

    public void setGatewayEnabled(boolean gatewayEnabled) {
        this.gatewayEnabled = gatewayEnabled;
    }

    public String getGatewayHost() {
        return gatewayHost;
    }

    public void setGatewayHost(String gatewayHost) {
        this.gatewayHost = orEmpty(gatewayHost);
    }

    public int getGatewayPort() {
        return gatewayPort;
    }

    public void setGatewayPort(int gatewayPort) {
        this.gatewayPort = gatewayPort;
    }

    public String getGatewayUsername() {
        return gatewayUsername;
    }

    public void setGatewayUsername(String gatewayUsername) {
        this.gatewayUsername = orEmpty(gatewayUsername);
    }

    public AuthMethod getGatewayAuthMethod() {
        return gatewayAuthMethod;
    }

    public void setGatewayAuthMethod(AuthMethod gatewayAuthMethod) {
        this.gatewayAuthMethod = Objects.requireNonNull(gatewayAuthMethod, "gatewayAuthMethod");
    }

    public String getGatewayPassword() {
        return gatewayPassword;
    }

    public void setGatewayPassword(String gatewayPassword) {
        this.gatewayPassword = gatewayPassword;
    }

    public String getGatewayPrivateKeyPath() {
        return gatewayPrivateKeyPath;
    }

    public void setGatewayPrivateKeyPath(String gatewayPrivateKeyPath) {
        this.gatewayPrivateKeyPath = gatewayPrivateKeyPath;
    }

    public String getGatewayPrivateKeyPassphrase() {
        return gatewayPrivateKeyPassphrase;
    }

    public void setGatewayPrivateKeyPassphrase(String gatewayPrivateKeyPassphrase) {
        this.gatewayPrivateKeyPassphrase = gatewayPrivateKeyPassphrase;
    }

    public String getGatewayHostKeyFingerprint() {
        return gatewayHostKeyFingerprint;
    }

    public void setGatewayHostKeyFingerprint(String gatewayHostKeyFingerprint) {
        this.gatewayHostKeyFingerprint = gatewayHostKeyFingerprint;
    }

    /** "user@host" or "user@host:port" of the gateway, for display. */
    public String gatewayDisplayAddress() {
        return gatewayUsername + "@" + gatewayHost + (gatewayPort == DEFAULT_SSH_PORT ? "" : ":" + gatewayPort);
    }

    public String getCloudflareToken() {
        return cloudflareToken;
    }

    public void setCloudflareToken(String cloudflareToken) {
        this.cloudflareToken = cloudflareToken;
    }

    public ServerPaths getPaths() {
        return paths;
    }

    public void setPaths(ServerPaths paths) {
        this.paths = Objects.requireNonNull(paths, "paths");
    }

    public ConfigLayout getDetectedLayout() {
        return detectedLayout;
    }

    public void setDetectedLayout(ConfigLayout detectedLayout) {
        this.detectedLayout = Objects.requireNonNull(detectedLayout, "detectedLayout");
    }

    public ToolStatus getOpensslStatus() {
        return opensslStatus;
    }

    public void setOpensslStatus(ToolStatus opensslStatus) {
        this.opensslStatus = opensslStatus;
    }

    public ToolStatus getCertbotStatus() {
        return certbotStatus;
    }

    public void setCertbotStatus(ToolStatus certbotStatus) {
        this.certbotStatus = certbotStatus;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = orEmpty(notes);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ServerProfile other)) {
            return false;
        }
        return port == other.port
                && Objects.equals(id, other.id)
                && name.equals(other.name)
                && host.equals(other.host)
                && username.equals(other.username)
                && authMethod == other.authMethod
                && Objects.equals(password, other.password)
                && Objects.equals(privateKeyPath, other.privateKeyPath)
                && Objects.equals(privateKeyPassphrase, other.privateKeyPassphrase)
                && privilegeMode == other.privilegeMode
                && Objects.equals(sudoPassword, other.sudoPassword)
                && Objects.equals(hostKeyFingerprint, other.hostKeyFingerprint)
                && gatewayEnabled == other.gatewayEnabled
                && gatewayPort == other.gatewayPort
                && gatewayHost.equals(other.gatewayHost)
                && gatewayUsername.equals(other.gatewayUsername)
                && gatewayAuthMethod == other.gatewayAuthMethod
                && Objects.equals(gatewayPassword, other.gatewayPassword)
                && Objects.equals(gatewayPrivateKeyPath, other.gatewayPrivateKeyPath)
                && Objects.equals(gatewayPrivateKeyPassphrase, other.gatewayPrivateKeyPassphrase)
                && Objects.equals(gatewayHostKeyFingerprint, other.gatewayHostKeyFingerprint)
                && Objects.equals(cloudflareToken, other.cloudflareToken)
                && paths.equals(other.paths)
                && detectedLayout == other.detectedLayout
                && Objects.equals(opensslStatus, other.opensslStatus)
                && Objects.equals(certbotStatus, other.certbotStatus)
                && notes.equals(other.notes)
                && Objects.equals(createdAt, other.createdAt)
                && Objects.equals(updatedAt, other.updatedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, host, port, username);
    }

    /** Never includes secrets. */
    @Override
    public String toString() {
        return "ServerProfile[id=" + id + ", name=" + name + ", " + displayAddress()
                + ", auth=" + authMethod.name()
                + ", password=" + Secrets.mask(password)
                + ", keyPassphrase=" + Secrets.mask(privateKeyPassphrase)
                + ", privilege=" + privilegeMode.name()
                + ", sudoPassword=" + Secrets.mask(sudoPassword)
                + (gatewayEnabled ? ", gateway=" + gatewayDisplayAddress()
                + ", gatewayPassword=" + Secrets.mask(gatewayPassword)
                + ", gatewayKeyPassphrase=" + Secrets.mask(gatewayPrivateKeyPassphrase) : "")
                + ", cloudflareToken=" + Secrets.mask(cloudflareToken)
                + ", layout=" + detectedLayout.name() + "]";
    }
}
