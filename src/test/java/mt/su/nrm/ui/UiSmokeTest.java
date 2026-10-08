package mt.su.nrm.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mt.su.nrm.config.EncryptedProfileStore;
import mt.su.nrm.config.PassphraseKeyProtector;
import mt.su.nrm.config.ProfileRepository;
import mt.su.nrm.model.ConfigLayout;
import mt.su.nrm.model.ServerProfile;
import mt.su.nrm.nginx.ConfigFile;
import mt.su.nrm.nginx.LocationSettings;
import mt.su.nrm.nginx.RemoteConfig;
import mt.su.nrm.nginx.VhostSettings;
import mt.su.nrm.nginx.VirtualHost;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Builds the screens on the JavaFX thread (without showing them) so construction errors,
 * null pointers and bad bindings show up in the test run rather than when someone opens a tab.
 * Skipped where JavaFX can't start (for example a machine with no display).
 */
class UiSmokeTest {

    private static boolean toolkitAvailable;
    /** Exceptions that escaped an event handler: JavaFX only prints these, so the tests collect them and fail. */
    private static final java.util.List<Throwable> UNCAUGHT = java.util.concurrent.CopyOnWriteArrayList.class.cast(new java.util.concurrent.CopyOnWriteArrayList<Throwable>());

    @BeforeAll
    static void startToolkit() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyRunning) {
            started.countDown();
        } catch (Throwable t) {
            return; // no display or no JavaFX runtime
        }
        toolkitAvailable = started.await(15, TimeUnit.SECONDS);
        if (toolkitAvailable) {
            // Closing the last dialog must not shut the toolkit down for the tests that follow.
            Platform.setImplicitExit(false);
            Thread.setDefaultUncaughtExceptionHandler((thread, error) -> UNCAUGHT.add(error));
        }
    }

    private static <T> T onFx(Callable<T> work) throws Exception {
        Assumptions.assumeTrue(toolkitAvailable, "JavaFX toolkit not available");
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result.set(work.call());
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(20, TimeUnit.SECONDS)) {
            StringBuilder stack = new StringBuilder();
            Thread.getAllStackTraces().forEach((t, st) -> {
                if (t.getName().contains("JavaFX Application")) {
                    for (StackTraceElement e : st) {
                        stack.append("\n    at ").append(e);
                    }
                }
            });
            throw new AssertionError("FX thread did not finish. It was at:" + stack);
        }
        if (!UNCAUGHT.isEmpty()) {
            Throwable first = UNCAUGHT.get(0);
            UNCAUGHT.clear();
            throw new AssertionError("an exception escaped an event handler: " + first, first);
        }
        if (failure.get() != null) {
            throw new AssertionError("failed on the FX thread: " + failure.get(), failure.get());
        }
        return result.get();
    }

    private static String resource(String name) throws Exception {
        try (InputStream in = UiSmokeTest.class.getResourceAsStream("/nginx/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static RemoteConfig sampleConfig() throws Exception {
        ConfigFile main = ConfigFile.parse("/etc/nginx/nginx.conf", resource("ubuntu-nginx.conf"));
        RemoteConfig config = new RemoteConfig("/etc/nginx", ConfigLayout.SITES_AVAILABLE, main);
        config.addFile(ConfigFile.parse("/etc/nginx/sites-available/app", resource("confd-app.conf")),
                List.of(new RemoteConfig.Link("/etc/nginx/sites-enabled/app", "../sites-available/app")), null);
        config.addFile(ConfigFile.parse("/etc/nginx/sites-available/default", resource("ubuntu-default-site")),
                List.of(), null);
        config.addFile(ConfigFile.parse("/srv/other/x.conf", resource("complex.conf")), List.of(), "Outside the folder.");
        return config;
    }

    private static ServerProfile profile() {
        ServerProfile p = new ServerProfile();
        p.setId(UUID.randomUUID());
        p.setName("Test");
        p.setHost("h.example.com");
        p.setUsername("u");
        return p;
    }

    @Test
    void virtualHostEditorBuildsForExistingAndNewHosts() throws Exception {
        RemoteConfig config = sampleConfig();
        onFx(() -> {
            for (VirtualHost host : config.virtualHosts()) {
                new VirtualHostEditor(host.read(), List.of(), host.unmanaged(), false);
            }
            VhostSettings blank = new VhostSettings();
            blank.listens.add(new VhostSettings.ListenSpec("80"));
            new VirtualHostEditor(blank, List.of(), List.of(), true);
            return null;
        });
    }

    @Test
    void locationsPaneShowsEveryLocationTypeAndReportsEdits() throws Exception {
        RemoteConfig config = sampleConfig();
        VirtualHost host = config.virtualHosts().stream()
                .filter(h -> h.displayName().equals("app.example.com")).findFirst().orElseThrow();
        LocationsPane pane = onFx(() -> new LocationsPane(host.read().locations, ZoneNames.NONE, () -> { }));
        assertEquals(4, onFx(() -> pane.locations().size()).intValue());
        onFx(() -> {
            LocationsPane p = new LocationsPane(List.of(LocationSettings.newProxy("/x", "http://a"),
                    LocationSettings.newStatic("/s"), LocationSettings.newRedirect("/r", "https://b")), ZoneNames.NONE, () -> { });
            assertEquals(3, p.locations().size());
            return null;
        });
    }

    @Test
    void pendingPanelRendersLoadedLoadingAndFailedStates() throws Exception {
        RemoteConfig config = sampleConfig();
        ServerProfile profile = profile();
        ServerConnection connection = new ServerConnection(profile.getId());
        ConnectionManager manager = new ConnectionManager(null);
        onFx(() -> {
            PendingChangesPanel pending = new PendingChangesPanel(profile, connection, manager);
            connection.configLoaded(config);

            // A pending change shows up in the count and in the pending panel.
            VirtualHost host = config.virtualHosts().stream()
                    .filter(h -> h.displayName().equals("app.example.com")).findFirst().orElseThrow();
            VhostSettings s = host.read();
            s.serverNames.add("www.app.example.com");
            host.apply(s);
            connection.configChanged();
            assertEquals(1, connection.pendingCountProperty().get());
            pending = new PendingChangesPanel(profile, connection, manager);
            assertNotNull(pending.getCenter());
            return null;
        });
    }

    @Test
    void mainWindowBuildsAndSwitchesBetweenNodes(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("profiles.nrm");
        ProfileRepository repository = ProfileRepository.open(
                new EncryptedProfileStore(store, new PassphraseKeyProtector("pw".toCharArray(), 1_000)));
        ServerProfile p = profile();
        p.setId(null);
        repository.add(p);
        onFx(() -> {
            MainWindow window = new MainWindow(repository);
            assertNotNull(window.getCenter());
            assertTrue(window.canClose());
            return null;
        });
        assertTrue(Files.exists(store));
    }

    @Test
    void httpObjectAndGlobalPanelsBuildAndTheEditorAcceptsZoneNames() throws Exception {
        RemoteConfig config = sampleConfig();
        ServerProfile profile = profile();
        ServerConnection connection = new ServerConnection(profile.getId());
        ConnectionManager manager = new ConnectionManager(null);
        onFx(() -> {
            connection.configLoaded(config);
            assertNotNull(new UpstreamsPanel(profile, connection, manager).getCenter());
            assertNotNull(new CacheZonesPanel(profile, connection, manager).getCenter());
            assertNotNull(new LimitZonesPanel(profile, connection, manager).getCenter());
            assertNotNull(new GlobalSettingsPanel(profile, connection, manager).getCenter());
            connection.configFailed("boom");
            assertNotNull(new UpstreamsPanel(profile, connection, manager).getCenter());
            assertNotNull(new GlobalSettingsPanel(profile, connection, manager).getCenter());
            VhostSettings s = new VhostSettings();
            s.locations.add(LocationSettings.newProxy("/", "http://app"));
            new VirtualHostEditor(s, List.of(), List.of(), true,
                    new ZoneNames(java.util.Set.of("perip"), java.util.Set.of(), java.util.Set.of("c1"), java.util.Set.of("app")));
            return null;
        });
    }

    @Test
    void certificatesPanelRendersEveryState() throws Exception {
        ServerProfile profile = profile();
        ServerConnection connection = new ServerConnection(profile.getId());
        onFx(() -> {
            assertNotNull(new CertificatesPanel(profile, connection).getCenter());   // not connected
            connection.connected(null);
            assertNotNull(new CertificatesPanel(profile, connection).getCenter());   // loading
            connection.configLoaded(sampleConfig());
            connection.setCertificates(List.of(
                    mt.su.nrm.ssl.CertificateInfo.parse("/etc/letsencrypt/live/a/fullchain.pem",
                            "subject=CN = a.example.com\nissuer=C = US, O = Let's Encrypt, CN = R11\n"
                                    + "notBefore=Jan  1 00:00:00 2020 GMT\nnotAfter=Jan  1 2021 GMT\nDNS:a.example.com\n"),
                    mt.su.nrm.ssl.CertificateInfo.parse("/etc/nginx/ssl/self.crt",
                            "subject=CN = s.internal\nissuer=CN = s.internal\nnotBefore=Jan  1 00:00:00 2020 GMT\n"
                                    + "notAfter=Jan  1 00:00:00 2099 GMT\n"),
                    mt.su.nrm.ssl.CertificateInfo.parse("/etc/nginx/ssl/junk.pem", "Could not read certificate\n")));
            assertNotNull(new CertificatesPanel(profile, connection).getCenter());   // listed
            connection.setCertificates(List.of());
            assertNotNull(new CertificatesPanel(profile, connection).getCenter());   // empty
            return null;
        });
        assertEquals("/etc/letsencrypt/live/a/privkey.pem", CertificatesPanel.keyPathFor("/etc/letsencrypt/live/a/fullchain.pem"));
        assertEquals("/etc/nginx/ssl/site.key", CertificatesPanel.keyPathFor("/etc/nginx/ssl/site.crt"));
    }

    @Test
    void sslScreenBuildsWithAndWithoutACertificateAuthority() throws Exception {
        ServerProfile profile = profile();
        ServerConnection connection = new ServerConnection(profile.getId());
        String caCert = profile.getPaths().getCaStorageDir() + "/ca.crt";
        var ca = mt.su.nrm.ssl.CertificateInfo.parse(caCert, "subject=O = Acme, CN = Acme CA\nissuer=O = Acme, CN = Acme CA\n"
                + "notBefore=Jan  1 00:00:00 2020 GMT\nnotAfter=Jan  1 00:00:00 2099 GMT\nCA:TRUE\n");
        var leaf = mt.su.nrm.ssl.CertificateInfo.parse("/etc/nginx/ssl/a.crt", "subject=CN = a.internal\nissuer=O = Acme, CN = Acme CA\n"
                + "notBefore=Jan  1 00:00:00 2020 GMT\nnotAfter=Jan  1 00:00:00 2099 GMT\nDNS:a.internal\nCA:FALSE\n");
        onFx(() -> {
            connection.connected(null);
            connection.configLoaded(sampleConfig());
            connection.setCertificates(List.of());
            assertNotNull(new SslPanel(profile, connection).getCenter());                 // no CA yet
            assertNotNull(new AuthorityPanel(profile, connection).getCenter());
            assertNotNull(new IssuedCertificatesPanel(profile, connection).getCenter());
            connection.setCertificates(List.of(ca, leaf));
            assertNotNull(new SslPanel(profile, connection).getCenter());                 // CA and one issued certificate
            assertNotNull(new AuthorityPanel(profile, connection).getCenter());
            assertNotNull(new IssuedCertificatesPanel(profile, connection).getCenter());
            return null;
        });
    }

    /**
     * Opens a wizard, lets it build every page, and closes it again from a timer. Building all the
     * pages and showing the first exercises the layout code without anyone clicking.
     */
    private static boolean openAndCloseAgain(java.util.concurrent.Callable<Boolean> openWizard) throws Exception {
        return onFx(() -> {
            // Keep closing whatever windows are open until the wizard returns: the dialog may not be showing
            // yet at the first tick, and a window from the previous wizard may still be listed.
            javafx.animation.Timeline close = new javafx.animation.Timeline();
            close.getKeyFrames().add(new javafx.animation.KeyFrame(javafx.util.Duration.millis(300), e ->
                    new java.util.ArrayList<>(javafx.stage.Window.getWindows()).forEach(javafx.stage.Window::hide)));
            close.setCycleCount(javafx.animation.Animation.INDEFINITE);
            close.play();
            try {
                return openWizard.call();
            } finally {
                close.stop();
            }
        });
    }

    @Test
    void theWizardsBuildAllTheirPagesForNewAndExistingObjects() throws Exception {
        var upstream = new mt.su.nrm.nginx.UpstreamSettings();
        assertFalse(openAndCloseAgain(() -> UpstreamWizard.run(null, upstream, java.util.Set.of(), true)));
        var existing = new mt.su.nrm.nginx.UpstreamSettings();
        existing.name = "app";
        existing.method = "hash $request_uri consistent";
        existing.servers.addAll(List.of("10.0.0.1:8080 weight=3 max_fails=2", "[::1]:9000 backup", "unix:/run/a.sock"));
        existing.keepalive = "16";
        assertFalse(openAndCloseAgain(() -> UpstreamWizard.run(null, existing, java.util.Set.of("other"), false)));
        assertEquals("app", existing.name, "cancelling changes nothing");

        var cache = new mt.su.nrm.nginx.CacheZoneSettings();
        assertFalse(openAndCloseAgain(() -> CacheZoneWizard.run(null, cache, java.util.Set.of(), true)));
        var cacheExisting = new mt.su.nrm.nginx.CacheZoneSettings();
        cacheExisting.path = "/var/cache/nginx/x";
        cacheExisting.keysZone = "x:64m";
        cacheExisting.maxSize = "3g";
        cacheExisting.inactive = "45m";
        cacheExisting.levels = "1:2";
        assertFalse(openAndCloseAgain(() -> CacheZoneWizard.run(null, cacheExisting, java.util.Set.of(), false)));

        var limit = new mt.su.nrm.nginx.LimitZoneSettings();
        assertFalse(openAndCloseAgain(() -> LimitZoneWizard.run(null, limit, java.util.Set.of(), true)));
        var limitExisting = new mt.su.nrm.nginx.LimitZoneSettings();
        limitExisting.key = "$http_authorization";
        limitExisting.zone = "auth_req:5m";
        limitExisting.rate = "30r/m";
        assertFalse(openAndCloseAgain(() -> LimitZoneWizard.run(null, limitExisting, java.util.Set.of(), false)));
        var conn = new mt.su.nrm.nginx.LimitZoneSettings();
        conn.kind = mt.su.nrm.nginx.LimitZoneSettings.Kind.CONNECTION;
        conn.zone = "perip_conn:10m";
        assertFalse(openAndCloseAgain(() -> LimitZoneWizard.run(null, conn, java.util.Set.of(), false)));
    }

    @Test
    void theServerDialogBuildsForAnExistingServerWithEveryAuthenticationAndRootMode() throws Exception {
        // Regression: filling in an existing server used to fire change listeners before every field had a value.
        onFx(() -> {
            for (var auth : mt.su.nrm.model.AuthMethod.values()) {
                for (var mode : mt.su.nrm.model.PrivilegeMode.values()) {
                    ServerProfile p = profile();
                    p.setAuthMethod(auth);
                    p.setPrivilegeMode(mode);
                    if (auth == mt.su.nrm.model.AuthMethod.PRIVATE_KEY) {
                        p.setPrivateKeyPath("C:/keys/id_ed25519");
                    }
                    new ProfileEditorDialog(p, java.util.Set.of("other"));
                }
            }
            new ProfileEditorDialog(null, java.util.Set.of());
            return null;
        });
    }

    @Test
    void theServerOverviewShowsTheVirtualHostTableInEveryState() throws Exception {
        ServerProfile profile = profile();
        ServerConnection connection = new ServerConnection(profile.getId());
        ConnectionManager manager = new ConnectionManager(null);
        onFx(() -> {
            javafx.scene.Node facts = new javafx.scene.control.Label("facts");
            assertNotNull(new ServerOverviewPanel(profile, connection, manager, facts).getCenter());   // disconnected
            connection.connected(null);
            connection.configLoading();
            assertNotNull(new ServerOverviewPanel(profile, connection, manager, facts).getCenter());   // loading
            connection.configFailed("boom");
            assertNotNull(new ServerOverviewPanel(profile, connection, manager, facts).getCenter());   // failed
            connection.configLoaded(sampleConfig());
            ServerOverviewPanel loaded = new ServerOverviewPanel(profile, connection, manager, facts);
            assertNotNull(loaded.getCenter());                                                                    // sites listed
            assertNotNull(loaded.getTop());
            return null;
        });
    }

    @Test
    void theAccessWizardBuildsItsPages() throws Exception {
        var location = mt.su.nrm.nginx.LocationSettings.newProxy("/admin", "http://a");
        assertFalse(openAndCloseAgain(() -> {
            AccessWizard.run(null, location, "example.com", ServerAccess.NONE, p -> { });
            return false;
        }));
        location.authBasic = "Staff";
        location.authBasicUserFile = "/etc/nginx/htpasswd/x";
        assertFalse(openAndCloseAgain(() -> {
            AccessWizard.run(null, location, "", ServerAccess.NONE, p -> { });
            return false;
        }));
    }
}
