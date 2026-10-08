package mt.su.nrm.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mt.su.nrm.cloudflare.CloudflareApi;
import mt.su.nrm.cloudflare.CloudflareSession;
import mt.su.nrm.cloudflare.DnsRecord;
import mt.su.nrm.cloudflare.Json;
import mt.su.nrm.cloudflare.Tunnel;
import mt.su.nrm.cloudflare.TunnelConfig;
import mt.su.nrm.cloudflare.Zone;
import mt.su.nrm.model.ServerProfile;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Builds the Cloudflare pages on the JavaFX thread against an in-memory Cloudflare, so construction errors and bad
 * bindings show up here. Skipped where JavaFX can't start.
 */
class CloudflarePagesSmokeTest {

    private static boolean toolkitAvailable;

    private static final Zone ZONE = new Zone("z1", "example.com", "acc");
    private static final Tunnel TUNNEL = new Tunnel("t1", "edge", "healthy");

    /** Refuses every change: the pages must not write anything on their own. */
    private static final CloudflareApi FAKE = new CloudflareApi() {
        @Override
        public List<Zone> verifyToken() {
            return listZones();
        }

        @Override
        public List<Zone> listZones() {
            return List.of(ZONE);
        }

        @Override
        public List<Tunnel> listTunnels(String accountId) {
            return List.of(TUNNEL, new Tunnel("t2", "spare", "down"));
        }

        @Override
        public TunnelConfig getTunnelConfig(String accountId, String tunnelId) {
            return TunnelConfig.fromReply(Json.parse("{\"source\":\"cloudflare\",\"config\":{\"ingress\":["
                    + "{\"hostname\":\"app.example.com\",\"service\":\"http://localhost:1\"},"
                    + "{\"service\":\"http_status:404\"}]}}"));
        }

        @Override
        public void putTunnelConfig(String accountId, String tunnelId, TunnelConfig config) {
            throw new AssertionError("a page must not write on its own");
        }

        @Override
        public void deleteZone(String zoneId) {
            throw new AssertionError("a page must not delete anything on its own");
        }

        @Override
        public void deleteTunnel(String accountId, String tunnelId) {
            throw new AssertionError("a page must not delete anything on its own");
        }

        @Override
        public mt.su.nrm.cloudflare.ZoneCreated createZone(String accountId, String name) {
            throw new AssertionError("a page must not create anything on its own");
        }

        @Override
        public mt.su.nrm.cloudflare.TunnelCreated createTunnel(String accountId, String name) {
            throw new AssertionError("a page must not create anything on its own");
        }

        @Override
        public List<DnsRecord> listDnsRecords(String zoneId) {
            return List.of(new DnsRecord("r1", "A", "www.example.com", "203.0.113.10", true, 1, ""),
                    new DnsRecord("r2", "TXT", "example.com", "v=spf1 -all", false, 1, ""));
        }

        @Override
        public DnsRecord createDnsRecord(String zoneId, DnsRecord record) {
            throw new AssertionError("a page must not write on its own");
        }

        @Override
        public DnsRecord updateDnsRecord(String zoneId, DnsRecord record) {
            throw new AssertionError("a page must not write on its own");
        }

        @Override
        public void deleteDnsRecord(String zoneId, String recordId) {
            throw new AssertionError("a page must not write on its own");
        }
    };

    @BeforeAll
    static void startToolkit() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyRunning) {
            started.countDown();
        } catch (Throwable t) {
            return;
        }
        toolkitAvailable = started.await(15, TimeUnit.SECONDS);
        if (toolkitAvailable) {
            Platform.setImplicitExit(false);
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
            throw new AssertionError("FX thread did not finish");
        }
        if (failure.get() != null) {
            throw new AssertionError("failed on the FX thread: " + failure.get(), failure.get());
        }
        return result.get();
    }

    private static ServerProfile profile(String token) {
        ServerProfile p = new ServerProfile();
        p.setId(UUID.randomUUID());
        p.setName("Test");
        p.setCloudflareToken(token);
        return p;
    }

    private static ServerConnection connectionWithSession() throws Exception {
        ServerConnection connection = new ServerConnection(UUID.randomUUID());
        connection.setCloudflare(CloudflareSession.open("tok", FAKE));
        return connection;
    }

    /** The pages read on a background thread, so wait for a count to settle at the expected value. */
    private static int waitFor(IntSupplier onFx, int expected) throws Exception {
        int seen = 0;
        for (int i = 0; i < 50 && seen != expected; i++) {
            Thread.sleep(100);
            seen = onFx(onFx::getAsInt);
        }
        return seen;
    }

    @Test
    void theCloudflarePageExplainsAMissingToken() throws Exception {
        String text = onFx(() -> {
            CloudflareHomePage page = new CloudflareHomePage(profile(null), new ServerConnection(UUID.randomUUID()));
            new Scene(page);
            // No token: a large explanation instead of the two icons.
            VBox box = (VBox) ((javafx.scene.control.ScrollPane) page.getCenter()).getContent();
            StringBuilder all = new StringBuilder();
            box.getChildren().stream().filter(n -> n instanceof Label).forEach(n -> all.append(((Label) n).getText()));
            return all.toString();
        });
        assertTrue(text.contains("isn't set up"), text);
        assertTrue(text.contains("No API key is present"), text);
        assertFalse(text.contains("Zone: Read"), "the page must not explain how to make a token: " + text);
    }

    @Test
    void withATokenTheStatusIsInTheBottomBarNotAmongTheIcons() throws Exception {
        ServerConnection connection = connectionWithSession();
        CloudflareHomePage page = onFx(() -> {
            CloudflareHomePage p = new CloudflareHomePage(profile("tok"), connection);
            new Scene(p);
            return p;
        });
        assertTrue(onFx(() -> page.getBottom() != null), "the status bar is missing");
        assertTrue(onFx(() -> page.getCenter() instanceof javafx.scene.control.ScrollPane));
    }

    @Test
    void theZonesPageHasOneTileForEachZone() throws Exception {
        ServerConnection connection = connectionWithSession();
        CloudflareListPage page = onFx(() -> {
            CloudflareListPage p = new CloudflareListPage(profile("tok"), connection, CloudflareListPage.Kind.ZONES);
            new Scene(p);
            return p;
        });
        assertEquals(1, waitFor(page::tileCount, 1));
    }

    @Test
    void theTunnelsPageHasOneTileForEachTunnel() throws Exception {
        ServerConnection connection = connectionWithSession();
        CloudflareListPage page = onFx(() -> {
            CloudflareListPage p = new CloudflareListPage(profile("tok"), connection, CloudflareListPage.Kind.TUNNELS);
            new Scene(p);
            return p;
        });
        assertEquals(2, waitFor(page::tileCount, 2));
    }

    @Test
    void aZonePageListsItsRecordsAndStagesNothing() throws Exception {
        ServerConnection connection = connectionWithSession();
        CloudflareZonePage page = onFx(() -> {
            CloudflareZonePage p = new CloudflareZonePage(profile("tok"), connection, ZONE);
            new Scene(p);
            return p;
        });
        assertEquals(2, waitFor(page::rowCount, 2));
        assertEquals(0, connection.cloudflarePendingProperty().get());
    }

    @Test
    void aTunnelPageListsItsPublishedApplications() throws Exception {
        ServerConnection connection = connectionWithSession();
        CloudflareTunnelPage page = onFx(() -> {
            CloudflareTunnelPage p = new CloudflareTunnelPage(profile("tok"), connection, TUNNEL);
            new Scene(p);
            return p;
        });
        assertEquals(1, waitFor(page::rowCount, 1));
        assertEquals(0, connection.cloudflarePendingProperty().get());
    }
}
