package mt.su.nrm.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mt.su.nrm.model.ConfigLayout;
import mt.su.nrm.model.ServerProfile;
import mt.su.nrm.nginx.ConfigFile;
import mt.su.nrm.nginx.RemoteConfig;
import mt.su.nrm.ssh.CommandLog;
import mt.su.nrm.ssh.FakeSessions;
import mt.su.nrm.ssh.FakeSessions.Reply;
import mt.su.nrm.ssh.SshSession;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.scene.Scene;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The change history loads by itself, whenever the server becomes reachable or the configuration is reloaded. */
class HistoryPanelTest {

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

    private static final String ONE = "@@NRM-BACKUP 20260101-010101-aaaa\nM 0 /etc/nginx/a\n";
    private static final String TWO = ONE + "@@NRM-BACKUP 20260202-020202-bbbb\nM 0 /etc/nginx/b\n";

    private static ServerProfile profile() {
        ServerProfile p = new ServerProfile();
        p.setId(UUID.randomUUID());
        p.setName("Test");
        return p;
    }

    private static RemoteConfig config() throws Exception {
        return new RemoteConfig("/etc/nginx", ConfigLayout.CONF_D,
                ConfigFile.parse("/etc/nginx/nginx.conf", "events {}\nhttp {\n}\n"));
    }

    private static SshSession session(Function<String, Reply> script, List<String> ran) {
        return FakeSessions.session(script, ran, new CommandLog());
    }

    private static void waitForCount(HistoryPanel panel, int expected) throws Exception {
        for (int i = 0; i < 200; i++) {
            if (onFx(panel::backupCount) == expected) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("expected " + expected + " backups but there are " + onFx(panel::backupCount)
                + " (" + onFx(panel::statusText) + ")");
    }

    @Test
    void aPanelOpenedWhileTheConnectionIsStillComingUpFillsInOnceItIsUp() throws Exception {
        ServerConnection connection = new ServerConnection(UUID.randomUUID());
        List<String> ran = Collections.synchronizedList(new ArrayList<>());
        HistoryPanel panel = onFx(() -> {
            HistoryPanel p = new HistoryPanel(profile(), connection, null);
            new Scene(p);
            return p;
        });
        assertTrue(onFx(panel::statusText).startsWith("Connect to the server"), onFx(panel::statusText));
        assertEquals(0, onFx(panel::backupCount));

        onFx(() -> {
            connection.connected(session(c -> Reply.ok(ONE), ran));
            return null;
        });
        waitForCount(panel, 1); // no Refresh button pressed
        assertEquals(1, ran.size());
    }

    @Test
    void aPanelOpenedOnAConnectedServerLoadsWithoutBeingAsked() throws Exception {
        ServerConnection connection = new ServerConnection(UUID.randomUUID());
        onFx(() -> {
            connection.connected(session(c -> Reply.ok(TWO), new ArrayList<>()));
            return null;
        });
        HistoryPanel panel = onFx(() -> {
            HistoryPanel p = new HistoryPanel(profile(), connection, null);
            new Scene(p);
            return p;
        });
        waitForCount(panel, 2);
    }

    @Test
    void anApplyThatReloadsTheConfigurationUpdatesTheOpenHistory() throws Exception {
        ServerConnection connection = new ServerConnection(UUID.randomUUID());
        AtomicInteger applies = new AtomicInteger();
        onFx(() -> {
            connection.connected(session(c -> Reply.ok(applies.get() == 0 ? ONE : TWO), new ArrayList<>()));
            return null;
        });
        HistoryPanel panel = onFx(() -> {
            HistoryPanel p = new HistoryPanel(profile(), connection, null);
            new Scene(p);
            return p;
        });
        waitForCount(panel, 1);

        applies.incrementAndGet(); // the server now holds one more backup
        onFx(() -> {
            connection.configLoading();
            connection.configLoaded(config()); // what the app does after a successful apply
            return null;
        });
        waitForCount(panel, 2);
    }

    @Test
    void disconnectingEmptiesTheListInsteadOfShowingAnotherSessionsHistory() throws Exception {
        ServerConnection connection = new ServerConnection(UUID.randomUUID());
        onFx(() -> {
            connection.connected(session(c -> Reply.ok(TWO), new ArrayList<>()));
            return null;
        });
        HistoryPanel panel = onFx(() -> {
            HistoryPanel p = new HistoryPanel(profile(), connection, null);
            new Scene(p);
            return p;
        });
        waitForCount(panel, 2);
        onFx(() -> {
            connection.disconnected();
            return null;
        });
        assertEquals(0, onFx(panel::backupCount));
        assertTrue(onFx(panel::statusText).startsWith("Connect to the server"));
    }

    @Test
    void aChangeThatArrivesWhileTheHistoryIsBeingReadIsReadAfterwardsNotLost() throws Exception {
        ServerConnection connection = new ServerConnection(UUID.randomUUID());
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        onFx(() -> {
            connection.connected(session(c -> {
                if (reads.incrementAndGet() == 1) {
                    firstStarted.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return Reply.ok(ONE); // an answer that is already out of date
                }
                return Reply.ok(TWO);
            }, new ArrayList<>()));
            return null;
        });
        HistoryPanel panel = onFx(() -> {
            HistoryPanel p = new HistoryPanel(profile(), connection, null);
            new Scene(p);
            return p;
        });
        assertTrue(firstStarted.await(10, TimeUnit.SECONDS), "the first read should have started");
        onFx(() -> {
            connection.configLoaded(config()); // news arrives while the first read is in flight
            return null;
        });
        release.countDown();
        waitForCount(panel, 2);
        assertEquals(2, reads.get(), "read once more, rather than showing the stale answer");
    }
}
