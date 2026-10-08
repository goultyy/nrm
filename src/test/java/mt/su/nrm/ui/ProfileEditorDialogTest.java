package mt.su.nrm.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mt.su.nrm.model.ServerProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TitledPane;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The Add/Edit Server dialog: every optional section starts collapsed. Skipped where JavaFX can't start. */
class ProfileEditorDialogTest {

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

    private static void collect(Node node, List<Node> into) {
        into.add(node);
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) {
            collect(scroll.getContent(), into);
        }
        if (node instanceof TitledPane titled && titled.getContent() != null) {
            collect(titled.getContent(), into);
        }
        if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> collect(child, into));
        }
    }

    private static List<TitledPane> sections(ProfileEditorDialog dialog) {
        List<Node> all = new ArrayList<>();
        collect(dialog.dialogPane().getContent(), all);
        return all.stream().filter(n -> n instanceof TitledPane).map(n -> (TitledPane) n).toList();
    }

    private static ServerProfile server(boolean gateway) {
        ServerProfile p = new ServerProfile();
        p.setId(UUID.randomUUID());
        p.setName("Web 1");
        p.setHost("web1.example.com");
        p.setUsername("deploy");
        p.setGatewayEnabled(gateway);
        if (gateway) {
            p.setGatewayHost("bastion.example.com");
            p.setGatewayUsername("jump");
        }
        p.setCloudflareToken("a-token");
        return p;
    }

    @Test
    void everySectionStartsCollapsedWhenAddingAServer() throws Exception {
        List<Boolean> expanded = onFx(() -> sections(new ProfileEditorDialog(null, Set.of())).stream()
                .map(TitledPane::isExpanded).toList());
        assertEquals(3, expanded.size(), "Gateway, Cloudflare and Paths");
        assertFalse(expanded.contains(true), "no section may start open: " + expanded);
    }

    @Test
    void everySectionStartsCollapsedWhenEditingAServerWithoutAGateway() throws Exception {
        List<Boolean> expanded = onFx(() -> sections(new ProfileEditorDialog(server(false), Set.of())).stream()
                .map(TitledPane::isExpanded).toList());
        assertFalse(expanded.contains(true), "no section may start open: " + expanded);
    }

    @Test
    void editingAServerWithAGatewayDoesNotOpenTheGatewaySection() throws Exception {
        // Loading the saved server ticks the gateway box, which used to open the section by itself.
        List<Boolean> expanded = onFx(() -> sections(new ProfileEditorDialog(server(true), Set.of())).stream()
                .map(TitledPane::isExpanded).toList());
        assertFalse(expanded.contains(true), "no section may start open: " + expanded);
    }

    @Test
    void tickingTheGatewayBoxYourselfStillOpensItsSection() throws Exception {
        boolean opened = onFx(() -> {
            ProfileEditorDialog dialog = new ProfileEditorDialog(server(false), Set.of());
            List<Node> all = new ArrayList<>();
            collect(dialog.dialogPane().getContent(), all);
            CheckBox gateway = all.stream().filter(n -> n instanceof CheckBox c && c.getText().startsWith("Connect through"))
                    .map(n -> (CheckBox) n).findFirst().orElseThrow();
            gateway.setSelected(true);
            return sections(dialog).stream().anyMatch(t -> t.getText().startsWith("Gateway") && t.isExpanded());
        });
        assertTrue(opened, "a user ticking the box should still see the gateway fields");
    }
}
