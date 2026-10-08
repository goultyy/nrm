package mt.su.nrm.model;

import mt.su.nrm.util.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Remote paths for one server: the "Paths" section of a profile. All paths are absolute POSIX paths. */
public final class ServerPaths {

    public static final String DEFAULT_NGINX_BINARY = "/usr/sbin/nginx";
    public static final String DEFAULT_NGINX_CONF_DIR = "/etc/nginx";
    public static final String DEFAULT_CERTBOT_BINARY = "/usr/bin/certbot";
    public static final String DEFAULT_LETSENCRYPT_DIR = "/etc/letsencrypt";
    public static final String DEFAULT_CA_STORAGE_DIR = "/etc/nrm/ca";
    public static final String DEFAULT_MANUAL_CERT_DIR = "/etc/nginx/ssl";
    public static final String DEFAULT_REMOTE_TEMP_DIR = "/tmp";

    private String nginxBinary = DEFAULT_NGINX_BINARY;
    private String nginxConfDir = DEFAULT_NGINX_CONF_DIR;
    private String certbotBinary = DEFAULT_CERTBOT_BINARY;
    private String letsEncryptDir = DEFAULT_LETSENCRYPT_DIR;
    private String caStorageDir = DEFAULT_CA_STORAGE_DIR;
    private String manualCertDir = DEFAULT_MANUAL_CERT_DIR;
    private String remoteTempDir = DEFAULT_REMOTE_TEMP_DIR;

    public static ServerPaths defaults() {
        return new ServerPaths();
    }

    /** The main config file, e.g. /etc/nginx/nginx.conf. */
    public String mainConfigFile() {
        return join(nginxConfDir, "nginx.conf");
    }

    public static String join(String dir, String name) {
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    /** Returns human-readable problems, or an empty list if every path is usable. */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        checkPath(errors, "Nginx binary", nginxBinary);
        checkPath(errors, "Nginx config folder", nginxConfDir);
        checkPath(errors, "Certbot binary", certbotBinary);
        checkPath(errors, "Let's Encrypt folder", letsEncryptDir);
        checkPath(errors, "CA storage folder", caStorageDir);
        checkPath(errors, "Manual certificate folder", manualCertDir);
        checkPath(errors, "Remote temp folder", remoteTempDir);
        return errors;
    }

    private static void checkPath(List<String> errors, String label, String value) {
        if (Text.isBlank(value)) {
            errors.add(label + " is required.");
        } else if (Text.hasControlChars(value)) {
            errors.add(label + " contains invalid characters.");
        } else if (!value.startsWith("/")) {
            errors.add(label + " must be an absolute path (starting with /).");
        }
    }

    public ServerPaths copy() {
        ServerPaths c = new ServerPaths();
        c.nginxBinary = nginxBinary;
        c.nginxConfDir = nginxConfDir;
        c.certbotBinary = certbotBinary;
        c.letsEncryptDir = letsEncryptDir;
        c.caStorageDir = caStorageDir;
        c.manualCertDir = manualCertDir;
        c.remoteTempDir = remoteTempDir;
        return c;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    public String getNginxBinary() {
        return nginxBinary;
    }

    public void setNginxBinary(String nginxBinary) {
        this.nginxBinary = orEmpty(nginxBinary);
    }

    public String getNginxConfDir() {
        return nginxConfDir;
    }

    public void setNginxConfDir(String nginxConfDir) {
        this.nginxConfDir = orEmpty(nginxConfDir);
    }

    public String getCertbotBinary() {
        return certbotBinary;
    }

    public void setCertbotBinary(String certbotBinary) {
        this.certbotBinary = orEmpty(certbotBinary);
    }

    public String getLetsEncryptDir() {
        return letsEncryptDir;
    }

    public void setLetsEncryptDir(String letsEncryptDir) {
        this.letsEncryptDir = orEmpty(letsEncryptDir);
    }

    public String getCaStorageDir() {
        return caStorageDir;
    }

    public void setCaStorageDir(String caStorageDir) {
        this.caStorageDir = orEmpty(caStorageDir);
    }

    public String getManualCertDir() {
        return manualCertDir;
    }

    public void setManualCertDir(String manualCertDir) {
        this.manualCertDir = orEmpty(manualCertDir);
    }

    public String getRemoteTempDir() {
        return remoteTempDir;
    }

    public void setRemoteTempDir(String remoteTempDir) {
        this.remoteTempDir = orEmpty(remoteTempDir);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ServerPaths other)) {
            return false;
        }
        return nginxBinary.equals(other.nginxBinary)
                && nginxConfDir.equals(other.nginxConfDir)
                && certbotBinary.equals(other.certbotBinary)
                && letsEncryptDir.equals(other.letsEncryptDir)
                && caStorageDir.equals(other.caStorageDir)
                && manualCertDir.equals(other.manualCertDir)
                && remoteTempDir.equals(other.remoteTempDir);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nginxBinary, nginxConfDir, certbotBinary, letsEncryptDir,
                caStorageDir, manualCertDir, remoteTempDir);
    }

    @Override
    public String toString() {
        return "ServerPaths[nginx=" + nginxBinary + ", conf=" + nginxConfDir + ", certbot=" + certbotBinary
                + ", letsencrypt=" + letsEncryptDir + ", ca=" + caStorageDir + ", manualCerts=" + manualCertDir
                + ", temp=" + remoteTempDir + "]";
    }
}
