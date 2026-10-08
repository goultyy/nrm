package mt.su.nrm.ui;

import mt.su.nrm.model.ServerProfile;
import mt.su.nrm.nginx.PendingChange;
import mt.su.nrm.ssh.BackupService;
import mt.su.nrm.ssh.BackupService.Backup;
import mt.su.nrm.ssh.BackupService.Entry;
import mt.su.nrm.ssh.SshExecutor;
import mt.su.nrm.ssh.SshSession;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Change history: every time changes were applied, the server kept the files they replaced. This lists those
 * applies, newest first, and puts an earlier version back. Restoring is not a shortcut around the safety checks:
 * it builds the changes that undo the selected apply and sends them through the normal pipeline, so they are
 * tested with {@code nginx -t}, shown as a diff for confirmation, and rolled back if nginx rejects them.
 */
final class HistoryPanel extends BorderPane {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm:ss");

    private final ServerProfile profile;
    private final ServerConnection connection;
    private final ConnectionManager manager;

    private final ObservableList<Backup> backups = FXCollections.observableArrayList();
    private final ObservableList<Entry> files = FXCollections.observableArrayList();
    private final TableView<Backup> table = new TableView<>(backups);
    private final TableView<Entry> detail = new TableView<>(files);
    private final Label status = new Label();
    private final SimpleBooleanProperty busy = new SimpleBooleanProperty();

    HistoryPanel(ServerProfile profile, ServerConnection connection, ConnectionManager manager) {
        this.profile = profile;
        this.connection = connection;
        this.manager = manager;

        TableColumn<Backup, String> when = new TableColumn<>("When");
        when.setCellValueFactory(c -> new SimpleStringProperty(
                WHEN.format(c.getValue().time().atZone(ZoneId.systemDefault()))));
        when.setPrefWidth(170);
        TableColumn<Backup, String> count = new TableColumn<>("Files");
        count.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().entries().size())));
        count.setPrefWidth(60);
        TableColumn<Backup, String> what = new TableColumn<>("What was changed");
        what.setCellValueFactory(c -> new SimpleStringProperty(summary(c.getValue())));
        what.setPrefWidth(520);
        table.getColumns().addAll(List.of(when, count, what));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("No history yet. Each time changes are applied, the files they replaced are kept "
                + "here\nso that you can go back."));

        TableColumn<Entry, String> file = new TableColumn<>("File in that change");
        file.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().path()));
        file.setPrefWidth(430);
        TableColumn<Entry, String> undo = new TableColumn<>("Going back to before it");
        undo.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().undoes()));
        undo.setPrefWidth(300);
        detail.getColumns().addAll(List.of(file, undo));
        detail.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        detail.setPlaceholder(new Label("Select a change above to see its files."));

        table.getSelectionModel().selectedItemProperty().addListener((obs, o, n) ->
                files.setAll(n == null ? List.of() : n.entries()));

        Label intro = new Label("Each row is one time changes were applied to this server. \"Restore\" puts the files of "
                + "that change back as they were just before it, after showing you the differences and testing them "
                + "with nginx -t.");
        intro.setWrapText(true);

        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> reload());
        Button preview = new Button("Show differences");
        preview.setOnAction(e -> previewRestore());
        Button restore = new Button("Restore before this change");
        restore.setOnAction(e -> restore());
        Button delete = new Button("Delete backup");
        delete.setOnAction(e -> deleteSelected());
        for (Button b : List.of(preview, restore, delete)) {
            b.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull().or(busy));
        }
        refresh.disableProperty().bind(busy);
        var spinner = ProgressDialog.spinner(16);
        spinner.visibleProperty().bind(busy);
        HBox buttons = new HBox(8, refresh, preview, restore, delete, spinner);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox top = new VBox(8, intro, buttons);
        top.setPadding(new Insets(12, 12, 8, 12));
        SplitPane split = new SplitPane(table, detail);
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.62);
        VBox.setVgrow(split, Priority.ALWAYS);
        VBox center = new VBox(split);
        center.setPadding(new Insets(0, 12, 0, 12));
        status.setOpacity(0.75);
        status.setPadding(new Insets(4, 12, 8, 12));
        status.setWrapText(true);
        setTop(top);
        setCenter(center);
        setBottom(status);

        if (connection.isConnected()) {
            reload();
        } else {
            status.setText("Connect to the server to see its change history.");
        }
    }

    /** "2 changed, 1 created" style summary, with the first file names. */
    static String summary(Backup backup) {
        long changed = backup.entries().stream().filter(e -> e.kind() == PendingChange.Kind.MODIFY).count();
        long created = backup.entries().stream().filter(e -> e.kind() == PendingChange.Kind.CREATE).count();
        long deleted = backup.entries().stream().filter(e -> e.kind() == PendingChange.Kind.DELETE).count();
        String counts = java.util.stream.Stream.of(count(changed, "changed"), count(created, "created"),
                count(deleted, "deleted")).filter(s -> !s.isEmpty()).collect(Collectors.joining(", "));
        String names = backup.entries().stream().limit(3).map(e -> e.path().substring(e.path().lastIndexOf('/') + 1))
                .collect(Collectors.joining(", "));
        return counts + ": " + names + (backup.entries().size() > 3 ? ", ..." : "");
    }

    private static String count(long n, String word) {
        return n == 0 ? "" : n + " " + word;
    }

    // ---------------------------------------------------------------- actions

    void reload() {
        SshSession session = connection.session();
        if (session == null || busy.get()) {
            return;
        }
        busy.set(true);
        status.setText("Reading the history from the server...");
        SshExecutor.submit(() -> BackupService.list(session)).whenComplete((list, failure) -> Platform.runLater(() -> {
            busy.set(false);
            if (failure != null) {
                status.setText(ConnectionManager.describeFailure(profile, connection, failure).replace('\n', ' '));
                return;
            }
            backups.setAll(list);
            status.setText(list.isEmpty() ? "No history on this server yet."
                    : list.size() + " change" + (list.size() == 1 ? "" : "s") + " kept in /var/backups/nrm. Changes made "
                    + "before history was added are not listed.");
        }));
    }

    private void previewRestore() {
        plan(changes -> {
            if (changes.isEmpty()) {
                Dialogs.info(window(), "Nothing to change", "The server already has these files as they were before "
                        + "that change.");
                return;
            }
            Dialogs.showOutput(window(), Alert.AlertType.INFORMATION, "What restoring would change",
                    "These are the differences between the server now and the version before that change.",
                    changes.stream().map(c -> c.describe() + "\n" + c.diff()).collect(Collectors.joining("\n\n")));
        });
    }

    private void restore() {
        if (connection.config() != null && !connection.config().pendingChanges().isEmpty()) {
            Dialogs.info(window(), "Apply or discard your pending changes first",
                    "There are changes waiting to be applied. Restoring an earlier version would mix with them, so "
                            + "apply or discard them under Pending Changes first.");
            return;
        }
        plan(changes -> {
            if (changes.isEmpty()) {
                Dialogs.info(window(), "Nothing to change", "The server already has these files as they were before "
                        + "that change.");
                return;
            }
            ApplyFlow.runChanges(window(), profile, connection, manager, changes, this::reload);
        });
    }

    private void plan(java.util.function.Consumer<List<PendingChange>> then) {
        Backup chosen = table.getSelectionModel().getSelectedItem();
        SshSession session = connection.session();
        if (chosen == null || session == null) {
            return;
        }
        busy.set(true);
        status.setText("Comparing that version with the server...");
        SshExecutor.submit(() -> BackupService.restorePlan(session, chosen))
                .whenComplete((changes, failure) -> Platform.runLater(() -> {
                    busy.set(false);
                    if (failure != null) {
                        status.setText("");
                        Dialogs.showOutput(window(), Alert.AlertType.ERROR, "That version can't be restored",
                                "Nothing was changed on the server.",
                                ConnectionManager.describeFailure(profile, connection, failure));
                        return;
                    }
                    status.setText("");
                    then.accept(changes);
                }));
    }

    private void deleteSelected() {
        Backup chosen = table.getSelectionModel().getSelectedItem();
        SshSession session = connection.session();
        if (chosen == null || session == null) {
            return;
        }
        if (!Dialogs.confirm(window(), "Delete this backup?", "The saved copies of " + chosen.entries().size()
                + " file(s) from " + WHEN.format(chosen.time().atZone(ZoneId.systemDefault())) + " are removed from "
                + "the server. You will no longer be able to go back to before that change. The live configuration is "
                + "not touched.", "Delete backup")) {
            return;
        }
        busy.set(true);
        SshExecutor.submit(() -> {
            BackupService.delete(session, chosen);
            return null;
        }).whenComplete((ignored, failure) -> Platform.runLater(() -> {
            busy.set(false);
            if (failure != null) {
                Dialogs.showOutput(window(), Alert.AlertType.ERROR, "Could not delete the backup", "",
                        ConnectionManager.describeFailure(profile, connection, failure));
            }
            reload();
        }));
    }

    private Window window() {
        return getScene() == null ? null : getScene().getWindow();
    }

    // For tests.

    int backupCount() {
        return backups.size();
    }
}
