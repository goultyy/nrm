package mt.su.nrm.ui;

import mt.su.nrm.model.ServerProfile;
import mt.su.nrm.nginx.ConfigFile;
import mt.su.nrm.nginx.NginxParseException;
import mt.su.nrm.nginx.PendingChange;
import mt.su.nrm.nginx.RemoteConfig;
import mt.su.nrm.nginx.VhostValidator;
import mt.su.nrm.nginx.VirtualHost;
import mt.su.nrm.ssh.ApplyPipeline;
import mt.su.nrm.ssh.SshExecutor;
import mt.su.nrm.ssh.SshSession;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The validate/apply pipeline as the user experiences it:
 * <ol>
 *   <li>check the pending changes locally (syntax and the virtual host rules);</li>
 *   <li>upload them to temporary files and have nginx test them on the server ({@code nginx -t}),
 *       without touching the live config;</li>
 *   <li>show the diff and ask for confirmation;</li>
 *   <li>back up, write, test again, and reload nginx (restoring everything if the test fails).</li>
 * </ol>
 * All server work runs on background threads while a progress window is shown.
 */
final class ApplyFlow {

    private ApplyFlow() {
    }

    static void run(Window owner, ServerProfile profile, ServerConnection connection, ConnectionManager manager,
                    Runnable onFinished) {
        RemoteConfig config = connection.config();
        SshSession session = connection.session();
        if (config == null || session == null) {
            Dialogs.error(owner, "Not connected", "Connect to the server first.");
            onFinished.run();
            return;
        }
        List<PendingChange> changes = config.pendingChanges();
        if (changes.isEmpty()) {
            Dialogs.info(owner, "Nothing to apply", "There are no pending changes.");
            onFinished.run();
            return;
        }
        runChanges(owner, profile, connection, manager, changes, onFinished);
    }

    /**
     * The same pipeline for a given list of changes instead of the pending ones: the history screen uses it to put an
     * earlier version back, so a restore gets the same checks, {@code nginx -t}, diff review and automatic rollback.
     */
    static void runChanges(Window owner, ServerProfile profile, ServerConnection connection, ConnectionManager manager,
                           List<PendingChange> changes, Runnable onFinished) {
        RemoteConfig config = connection.config();
        SshSession session = connection.session();
        if (config == null || session == null) {
            Dialogs.error(owner, "Not connected", "Connect to the server first.");
            onFinished.run();
            return;
        }

        // 1. Local checks.
        List<String> problems = localProblems(config, changes);
        if (!problems.isEmpty()) {
            Dialogs.showOutput(owner, Alert.AlertType.WARNING, "These changes can't be applied yet",
                    "Fix the following, then try again.", String.join("\n", problems));
            onFinished.run();
            return;
        }

        // 2. Test on the server.
        ProgressDialog progress = ProgressDialog.show(owner, "Testing",
                "Uploading the changes and testing them with nginx -t on the server.\nThis can take a while on a slow connection.");
        SshExecutor.submit(() -> ApplyPipeline.test(session, profile.getPaths(), config, changes))
                .whenComplete((outcome, failure) -> Platform.runLater(() -> {
                    progress.close();
                    if (failure != null) {
                        fail(owner, "The test could not be run", failure, profile, connection);
                        onFinished.run();
                    } else if (!outcome.ok()) {
                        Dialogs.showOutput(owner, Alert.AlertType.ERROR, "nginx rejected the new configuration",
                                "Nothing was changed on the server. Fix the problem and try again.", outcome.output());
                        onFinished.run();
                    } else {
                        confirmAndApply(owner, profile, connection, manager, changes, outcome.output(), onFinished);
                    }
                }));
    }

    // ---------------------------------------------------------------- confirm

    private static void confirmAndApply(Window owner, ServerProfile profile, ServerConnection connection,
                                        ConnectionManager manager, List<PendingChange> changes, String testOutput,
                                        Runnable onFinished) {
        String diff = changes.stream().map(c -> c.describe() + "\n" + c.diff()).collect(Collectors.joining("\n\n"));

        TextArea area = new TextArea(diff);
        area.setEditable(false);
        area.setStyle("-fx-font-family: 'Consolas', 'Menlo', monospace;");
        area.setPrefSize(820, 380);
        VBox.setVgrow(area, Priority.ALWAYS);
        Label note = new Label("nginx -t passed on the server for this configuration:\n" + testOutput);
        note.setWrapText(true);
        VBox content = new VBox(10, note, area);

        ButtonType apply = new ButtonType("Apply and reload nginx", ButtonBar.ButtonData.OK_DONE);
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Review changes");
        dialog.setHeaderText("Review what will be written to " + profile.getName());
        dialog.setResizable(true);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(apply, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(ButtonType.CANCEL).requestFocus();

        if (dialog.showAndWait().filter(b -> b == apply).isEmpty()) {
            onFinished.run();
            return;
        }

        // 3. Apply.
        RemoteConfig config = connection.config();
        SshSession session = connection.session();
        if (config == null || session == null) {
            Dialogs.error(owner, "Not connected", "The connection was lost before the changes were applied.");
            onFinished.run();
            return;
        }
        ProgressDialog progress = ProgressDialog.show(owner, "Applying",
                "Writing the changes, testing, and reloading nginx.\nThis can take a while on a slow connection.");
        SshExecutor.submit(() -> ApplyPipeline.apply(session, profile.getPaths(), config, changes, Clock.systemUTC()))
                .whenComplete((outcome, failure) -> Platform.runLater(() -> {
                    progress.close();
                    if (failure != null) {
                        fail(owner, "Applying stopped unexpectedly", failure, profile, connection);
                        onFinished.run();
                        return;
                    }
                    mt.su.nrm.util.AppLog.info("Applied " + changes.size() + " file change(s) to " + profile.getName()
                            + ": " + outcome.status());
                    switch (outcome.status()) {
                        case OK:
                            Dialogs.showOutput(owner, Alert.AlertType.INFORMATION, "Applied",
                                    "The changes are live and nginx was reloaded.\nThe previous files were backed up in "
                                            + outcome.backupDir() + " on the server.", outcome.output());
                            reload(profile, manager, onFinished);
                            break;
                        case RELOAD_FAILED:
                            Dialogs.showOutput(owner, Alert.AlertType.WARNING, "Written, but nginx did not reload",
                                    "The new files are in place and pass nginx -t, but nginx would not reload "
                                            + "(is it running?). Previous files are in " + outcome.backupDir() + ".",
                                    outcome.output());
                            reload(profile, manager, onFinished);
                            break;
                        default:
                            Dialogs.showOutput(owner, Alert.AlertType.ERROR, "The changes were rolled back",
                                    "nginx rejected the configuration once written, so the previous files were "
                                            + "restored and nginx was not reloaded. Your changes are still pending.",
                                    outcome.output());
                            onFinished.run();
                    }
                }));
    }

    private static void reload(ServerProfile profile, ConnectionManager manager, Runnable onFinished) {
        manager.loadConfig(profile, onFinished);
    }

    private static void fail(Window owner, String header, Throwable failure, ServerProfile profile,
                             ServerConnection connection) {
        String text = ConnectionManager.describeFailure(profile, connection, failure);
        Dialogs.showOutput(owner, Alert.AlertType.ERROR, header,
                "The server state is uncertain. Use Refresh to reload it from the server before continuing.", text);
    }

    // ---------------------------------------------------------------- local checks

    /** Syntax and virtual host rules for every file that will be written. */
    static List<String> localProblems(RemoteConfig config, List<PendingChange> changes) {
        List<String> problems = new ArrayList<>();
        Set<ConfigFile> writing = new HashSet<>();
        for (PendingChange c : changes) {
            if (c.kind() == PendingChange.Kind.DELETE) {
                continue;
            }
            writing.add(c.file());
            try {
                ConfigFile.parse(c.path(), c.newText());
            } catch (NginxParseException e) {
                problems.add(c.path() + ": " + e.getMessage());
            }
        }
        for (VirtualHost host : config.virtualHosts()) {
            if (!writing.contains(host.file())) {
                continue;
            }
            for (VhostValidator.Issue issue : VhostValidator.validate(host.read())) {
                if (issue.severity() == VhostValidator.Severity.ERROR) {
                    problems.add(host.displayName() + " (" + host.file().path() + "): " + issue.message());
                }
            }
        }
        return problems;
    }
}
