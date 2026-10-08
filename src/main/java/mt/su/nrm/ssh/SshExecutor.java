package mt.su.nrm.ssh;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs blocking SSH work on background threads so the JavaFX thread stays responsive. Results
 * arrive as futures; UI code completes them back onto the FX thread with {@code Platform.runLater}.
 */
public final class SshExecutor {

    private static final ExecutorService POOL = Executors.newCachedThreadPool(runnable -> {
        Thread t = new Thread(runnable, "nrm-ssh");
        t.setDaemon(true);
        return t;
    });

    private SshExecutor() {
    }

    public static <T> CompletableFuture<T> submit(Callable<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        POOL.execute(() -> {
            try {
                future.complete(work.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }
}
