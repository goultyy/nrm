package mt.su.nrm.ui;

import mt.su.nrm.ssh.AuthService;
import mt.su.nrm.ssh.ImportService;
import mt.su.nrm.ssh.PhpService;
import mt.su.nrm.ssl.CertificateInfo;
import javafx.stage.Window;

import java.util.List;
import java.util.function.Consumer;

/**
 * What a dialog opened from a virtual host may ask of the server it belongs to. Most of the editor
 * only changes the in-memory configuration; a few things (a password file for basic authentication)
 * must exist on the server, so the editor asks through this instead of holding a connection.
 */
interface ServerAccess {

    /** For editors opened with no server behind them (or when not connected). */
    ServerAccess NONE = new ServerAccess() {
        @Override
        public boolean connected() {
            return false;
        }

        @Override
        public String confDir() {
            return "/etc/nginx";
        }

        @Override
        public String workerUser() {
            return "";
        }

        @Override
        public void listUsers(Window owner, String path, Consumer<List<String>> onDone) {
            onDone.accept(List.of());
        }

        @Override
        public void writePasswordFile(Window owner, AuthService.PasswordFileRequest request, Consumer<Boolean> onDone) {
            onDone.accept(false);
        }

        @Override
        public void listCertificates(Window owner, Consumer<List<CertificateInfo>> onDone) {
            onDone.accept(List.of());
        }

        @Override
        public void importCertificate(Window owner, ImportService.ImportRequest request,
                                      Consumer<ImportService.Result> onDone) {
            onDone.accept(null);
        }

        @Override
        public String certificateDir() {
            return "/etc/nginx/ssl";
        }

        @Override
        public void phpStatus(Window owner, Consumer<PhpService.Status> onDone) {
            onDone.accept(null);
        }

        @Override
        public void installPhp(Window owner, String packageManager, Consumer<Boolean> onDone) {
            onDone.accept(false);
        }
    };

    boolean connected();

    /**
     * Shows a folder on the server in a File Explorer window (the nearest folder that exists, if this one
     * doesn't). Does nothing when there is no server behind the editor.
     */
    default void openInFileTransfer(String remoteDirectory) {
    }

    /**
     * Lets the user pick a folder (or a file) on the server in a dialog that lists only the server. {@code current} is
     * what the field holds now, to open near it; {@code onChosen} gets the path if the user picks one. Does nothing when
     * there is no server behind the editor.
     */
    default void chooseRemote(Window owner, String current, boolean file, Consumer<String> onChosen) {
    }

    /**
     * The names an {@code access_log} can use as its format: nginx's built-in {@code combined}, then every format
     * defined on the server. Without a server only the built-in one is known.
     */
    default List<String> logFormatNames() {
        return List.of("combined");
    }

    /**
     * The real-visitor-IP settings at the http level, which a site without its own inherits; empty if none or if there
     * is no server behind the editor.
     */
    default java.util.Optional<mt.su.nrm.nginx.VhostSettings.RealIpSpec> inheritedRealIp() {
        return java.util.Optional.empty();
    }

    /** Where outgoing requests the app makes for this server are recorded (a throwaway log when there is none). */
    default mt.su.nrm.ssh.CommandLog commandLog() {
        return new mt.su.nrm.ssh.CommandLog();
    }

    /** The nginx configuration folder, where password files belong. */
    String confDir();

    /** The user nginx workers run as, or "" if unknown. */
    String workerUser();

    /** The users already in a password file (empty if it doesn't exist); the callback runs on the FX thread. */
    void listUsers(Window owner, String path, Consumer<List<String>> onDone);

    /**
     * Writes the password file behind a progress window and reports back on the FX thread whether it
     * worked; failures have already been shown to the user.
     */
    void writePasswordFile(Window owner, AuthService.PasswordFileRequest request, Consumer<Boolean> onDone);

    /** The folder imported certificates are stored in. */
    String certificateDir();

    /** The certificates found on the server (empty if not connected); the callback runs on the FX thread. */
    void listCertificates(Window owner, Consumer<List<CertificateInfo>> onDone);

    /**
     * Installs a certificate and key you already own behind a progress window. The callback runs on the FX
     * thread with the result, or null if it failed (the failure has already been shown to the user).
     */
    void importCertificate(Window owner, ImportService.ImportRequest request, Consumer<ImportService.Result> onDone);

    /**
     * Checks the server for PHP-FPM behind a progress window. The callback runs on the FX thread with what
     * was found, or null if not connected or the check failed (the failure has already been shown).
     */
    void phpStatus(Window owner, Consumer<PhpService.Status> onDone);

    /**
     * Installs and starts PHP-FPM with the given package manager (from {@link PhpService.Status#packageManager()})
     * behind a progress window. The callback runs on the FX thread with whether it worked; failures have
     * already been shown to the user.
     */
    void installPhp(Window owner, String packageManager, Consumer<Boolean> onDone);
}
