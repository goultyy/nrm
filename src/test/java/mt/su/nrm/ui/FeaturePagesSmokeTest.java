package mt.su.nrm.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mt.su.nrm.model.ConfigLayout;
import mt.su.nrm.model.ServerProfile;
import mt.su.nrm.nginx.ConfigFile;
import mt.su.nrm.nginx.RemoteConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The add-on pages built on the JavaFX thread in each state they can be in. Skipped where JavaFX can't start. */
class FeaturePagesSmokeTest {

    private static boolean toolkitAvailable;

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

    private static ServerProfile profile() {
        ServerProfile p = new ServerProfile();
        p.setId(UUID.randomUUID());
        p.setName("Test");
        return p;
    }

    private static RemoteConfig config(String http) throws Exception {
        ConfigFile main = ConfigFile.parse("/etc/nginx/nginx.conf", "events {}\nhttp {\n" + http + "}\n");
        return new RemoteConfig("/etc/nginx", ConfigLayout.CONF_D, main);
    }

    private static ServerConnection loaded(String http) throws Exception {
        ServerConnection connection = new ServerConnection(UUID.randomUUID());
        connection.configLoaded(config(http));
        return connection;
    }

    private static List<Node> all(Node root) {
        List<Node> found = new ArrayList<>();
        collect(root, found);
        return found;
    }

    private static void collect(Node node, List<Node> into) {
        into.add(node);
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) {
            collect(scroll.getContent(), into);
        }
        if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> collect(child, into));
        }
    }

    private static boolean has(Node root, Predicate<Node> test) {
        return all(root).stream().anyMatch(test);
    }

    private static boolean hasButton(Node root, String text) {
        return has(root, n -> n instanceof Button b && text.equals(b.getText()));
    }

    // ---------------------------------------------------------------- the status page

    @Test
    void theStatusPageExplainsWhenTheConfigurationIsNotLoaded() throws Exception {
        boolean explained = onFx(() -> {
            StatusPage page = new StatusPage(profile(), new ServerConnection(UUID.randomUUID()));
            new Scene(page);
            return page.getCenter() != null && !page.isPolling();
        });
        assertTrue(explained);
    }

    @Test
    void theStatusPageOffersToTurnItOnWhenThereIsNone() throws Exception {
        ServerConnection connection = loaded("    server { listen 80; server_name a.com; }\n");
        boolean offered = onFx(() -> {
            StatusPage page = new StatusPage(profile(), connection);
            new Scene(page);
            return hasButton(page.getCenter(), "Enable the status page") && !page.isPolling();
        });
        assertTrue(offered);
    }

    @Test
    void theStatusPageShowsAHandMadeStubStatusAndSaysItIsOpen() throws Exception {
        ServerConnection connection = loaded("    server { listen 8080; server_name mon.example.com;\n"
                + "      location = /basic_status { stub_status; } }\n");
        String[] seen = onFx(() -> {
            StatusPage page = new StatusPage(profile(), connection);
            new Scene(page);
            boolean warnsOpen = has(page.getCenter(),
                    n -> n instanceof javafx.scene.control.Label l && l.getText().startsWith("Nothing limits who can read"));
            boolean hasDisable = hasButton(page.getCenter(), "Turn off the status page");
            return new String[] {page.shownUrl(), String.valueOf(warnsOpen), String.valueOf(hasDisable)};
        });
        assertEquals("http://127.0.0.1:8080/basic_status", seen[0]);
        assertEquals("true", seen[1], "an unrestricted page must be flagged");
        assertEquals("false", seen[2], "a hand-made page must not offer to be removed");
    }

    @Test
    void theStatusPageOffersToTurnOffTheOneItAdded() throws Exception {
        RemoteConfig cfg = config("");
        assertTrue(mt.su.nrm.status.StatusPageConfig.enable(cfg, 8089, List.of()).ok());
        ServerConnection connection = new ServerConnection(UUID.randomUUID());
        connection.configLoaded(cfg);
        boolean offered = onFx(() -> {
            StatusPage page = new StatusPage(profile(), connection);
            new Scene(page);
            return hasButton(page.getCenter(), "Turn off the status page")
                    && page.shownUrl().equals("http://127.0.0.1:8089/nrm_status");
        });
        assertTrue(offered);
    }

    @Test
    void withoutASessionTheStatusPageDoesNotPoll() throws Exception {
        ServerConnection connection = loaded("    server { listen 8080; location = /s { stub_status; allow 127.0.0.1; deny all; } }\n");
        boolean polling = onFx(() -> {
            StatusPage page = new StatusPage(profile(), connection);
            new Scene(page);
            return page.isPolling();
        });
        assertFalse(polling, "no connection means nothing to read, so no timer should keep running");
    }

    private static final String PAGE = "Active connections: 291\nserver accepts handled requests\n 100 100 400\n"
            + "Reading: 6 Writing: 179 Waiting: 106\n";

    private static StatusPage livePage(ServerConnection connection, mt.su.nrm.ssh.FakeSessions.Reply reply,
                                       List<String> ran) throws Exception {
        connection.connected(mt.su.nrm.ssh.FakeSessions.session(c -> reply, ran, connection.log()));
        return onFx(() -> {
            StatusPage page = new StatusPage(profile(), connection);
            new Scene(page);
            return page;
        });
    }

    @Test
    void aLivePageReadsTheServerAndShowsTheFigures() throws Exception {
        ServerConnection connection = loaded("    server { listen 8080; location = /s { stub_status; allow 127.0.0.1; deny all; } }\n");
        List<String> ran = new java.util.concurrent.CopyOnWriteArrayList<>();
        StatusPage page = livePage(connection, mt.su.nrm.ssh.FakeSessions.Reply.ok(PAGE), ran);

        String shown = "-";
        for (int i = 0; i < 40 && shown.equals("-"); i++) {
            Thread.sleep(100);
            shown = onFx(page::activeText);
        }
        assertEquals("291", shown);
        assertTrue(onFx(page::isPolling), "a connected, readable page keeps polling");
        assertFalse(ran.isEmpty());
        assertTrue(ran.get(0).contains("http://127.0.0.1:8080/s"), ran.get(0));
        assertTrue(connection.log().snapshot().stream().anyMatch(l -> l.text().contains("http://127.0.0.1:8080/s")),
                "every reading must be visible in the command log");
        onFx(() -> {
            page.hidden();
            return null;
        });
        assertFalse(onFx(page::isPolling), "leaving the page must stop the readings");
    }

    @Test
    void repeatedFailuresStopThePollingInsteadOfFloodingTheLog() throws Exception {
        ServerConnection connection = loaded("    server { listen 8080; location = /s { stub_status; allow 127.0.0.1; deny all; } }\n");
        List<String> ran = new java.util.concurrent.CopyOnWriteArrayList<>();
        StatusPage page = livePage(connection,
                mt.su.nrm.ssh.FakeSessions.Reply.fail(7, "curl: (7) Failed to connect"), ran);
        onFx(() -> {
            page.setEverySeconds(2);
            return null;
        });

        boolean stopped = false;
        for (int i = 0; i < 100 && !stopped; i++) {
            Thread.sleep(100);
            stopped = !onFx(page::isPolling);
        }
        assertTrue(stopped, "polling should have stopped after repeated failures");
        assertEquals(3, ran.size(), "it must give up after exactly three failed readings");
        String message = onFx(page::messageText);
        assertTrue(message.contains("apply the pending changes") && message.contains("Stopped after 3"), message);
        int seen = ran.size();
        Thread.sleep(2500);
        assertEquals(seen, ran.size(), "no further commands may be sent once it has given up");
    }

    // ---------------------------------------------------------------- the features as plugged in

    @Test
    void everyFeatureMakesAPageWithoutFailing() throws Exception {
        List<String> titles = onFx(() -> {
            List<String> made = new ArrayList<>();
            for (Feature feature : Features.all()) {
                Node page = feature.page(profile(), new ServerConnection(UUID.randomUUID()));
                assertNotNull(page, feature.id());
                new Scene(new javafx.scene.layout.StackPane(page));
                made.add(feature.title());
            }
            return made;
        });
        assertTrue(titles.contains("Status page") && titles.contains("Test add-on"));
    }
}
