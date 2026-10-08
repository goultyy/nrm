package mt.su.nrm.ui;

import mt.su.nrm.cloudflare.CloudflareException;
import mt.su.nrm.cloudflare.CloudflareSession;
import mt.su.nrm.cloudflare.CloudflareWorkspace;
import mt.su.nrm.cloudflare.Zone;
import mt.su.nrm.model.ServerProfile;
import mt.su.nrm.ssh.SshExecutor;
import javafx.application.Platform;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * What the Cloudflare pages of one server share: opening the session, running Cloudflare calls off the JavaFX
 * thread (with one busy state for all pages), and the review, apply and discard of staged changes. Only used on the
 * JavaFX thread.
 */
final class CloudflareHub {

    static final String NO_TOKEN = "No Cloudflare API token is saved for this server.\n\n"
            + "Choose Edit on the server in the Servers list, open the Cloudflare section and paste an API token "
            + "with these permissions: Zone: Read, DNS: Edit and Cloudflare Tunnel: Edit.";

    private final ServerConnection connection;
    private final BooleanProperty busy = new SimpleBooleanProperty();
    private final StringProperty busyText = new SimpleStringProperty("");
    private boolean opening;
    private final List<Consumer<CloudflareSession>> readyWaiters = new ArrayList<>();
    private final List<Consumer<String>> failWaiters = new ArrayList<>();

    CloudflareHub(ServerConnection connection) {
        this.connection = connection;
    }

    BooleanProperty busyProperty() {
        return busy;
    }

    StringProperty busyTextProperty() {
        return busyText;
    }

    // ---------------------------------------------------------------- session

    /**
     * Calls {@code onReady} with an open session, connecting first if needed (the zones and tunnels the token can
     * see are read once). Several pages asking at once share one connection attempt.
     */
    void whenReady(ServerProfile profile, Consumer<CloudflareSession> onReady, Consumer<String> onFail) {
        String token = profile.getCloudflareToken();
        if (token == null || token.isBlank()) {
            onFail.accept(NO_TOKEN);
            return;
        }
        CloudflareSession existing = connection.cloudflare();
        if (existing != null && existing.usesToken(token)) {
            onReady.accept(existing);
            return;
        }
        readyWaiters.add(onReady);
        failWaiters.add(onFail);
        if (opening) {
            return;
        }
        opening = true;
        run("Connecting to Cloudflare...", () -> CloudflareSession.open(token.trim(), connection.log()), opened -> {
            opening = false;
            connection.setCloudflare(opened);
            List<Consumer<CloudflareSession>> waiting = new ArrayList<>(readyWaiters);
            readyWaiters.clear();
            failWaiters.clear();
            waiting.forEach(w -> w.accept(opened));
        }, problem -> {
            opening = false;
            List<Consumer<String>> waiting = new ArrayList<>(failWaiters);
            readyWaiters.clear();
            failWaiters.clear();
            waiting.forEach(w -> w.accept(problem));
        });
    }

    /** Reads the zone's records if they aren't loaded yet (or {@code force}), then calls {@code done} on success. */
    void ensureZoneLoaded(CloudflareSession session, Zone zone, boolean force, Runnable done, Consumer<String> onFail) {
        CloudflareWorkspace workspace = session.workspace();
        if (!force && workspace.isDnsLoaded(zone)) {
            done.run();
            return;
        }
        run("Reading " + zone.name() + " from Cloudflare...", () -> {
            workspace.loadDns(zone);
            return null;
        }, ignored -> done.run(), onFail);
    }

    // ---------------------------------------------------------------- running calls

    /** Runs blocking Cloudflare work off the FX thread, then reports back on it. */
    <T> void run(String text, Callable<T> work, Consumer<T> onOk, Consumer<String> onFail) {
        busy.set(true);
        busyText.set(text);
        SshExecutor.submit(work).whenComplete((result, failure) -> Platform.runLater(() -> {
            busy.set(false);
            busyText.set("");
            if (failure != null) {
                onFail.accept(describe(failure));
            } else {
                onOk.accept(result);
            }
        }));
    }

    private static String describe(Throwable failure) {
        Throwable t = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        if (t instanceof CloudflareException) {
            return t.getMessage();
        }
        return "Unexpected problem: " + t;
    }

    // ---------------------------------------------------------------- staged changes

    /** Staged changes or loaded data changed: refreshes the count shown in the tree and on the pages. */
    void changed() {
        CloudflareSession session = connection.cloudflare();
        if (session != null) {
            connection.cloudflarePendingProperty().set(session.workspace().changeCount());
        }
    }

    /** True while there is nothing staged, or a call is running: the buttons that act on staged changes use it. */
    BooleanBinding nothingToApply() {
        return busy.or(connection.cloudflarePendingProperty().isEqualTo(0));
    }

    /** Lists everything staged and, once confirmed, sends it; {@code refresh} then redraws the page. */
    void reviewAndApply(Window owner, Runnable refresh) {
        CloudflareSession session = connection.cloudflare();
        if (session == null || !session.workspace().hasChanges()) {
            Dialogs.info(owner, "Nothing to apply", "There are no staged Cloudflare changes.");
            return;
        }
        CloudflareWorkspace workspace = session.workspace();
        if (!CloudflareDialogs.review(owner, workspace.summary())) {
            return;
        }
        run("Applying to Cloudflare...", workspace::apply, done -> {
            changed();
            refresh.run();
            Dialogs.info(owner, "Applied to Cloudflare", done.size()
                    + (done.size() == 1 ? " change was" : " changes were") + " sent:\n\n" + String.join("\n", done));
        }, problem -> {
            changed();
            refresh.run();
            Dialogs.error(owner, "Cloudflare changes were not fully applied", problem);
        });
    }

    void discardAll(Window owner, Runnable refresh) {
        CloudflareSession session = connection.cloudflare();
        if (session == null || !session.workspace().hasChanges()) {
            return;
        }
        if (Dialogs.confirm(owner, "Discard all staged changes?",
                "Everything you staged for Cloudflare is dropped. Nothing has been sent.", "Discard")) {
            session.workspace().discardAll();
            changed();
            refresh.run();
        }
    }
}
