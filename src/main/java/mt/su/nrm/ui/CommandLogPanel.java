package mt.su.nrm.ui;

import mt.su.nrm.ssh.CommandLog;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Console-style view of a {@link CommandLog}: one row per line, every output line shown in full
 * (scroll sideways for long ones), passwords already masked by the log itself.
 */
public final class CommandLogPanel extends BorderPane {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final CommandLog log;
    private final ObservableList<CommandLog.Line> items = FXCollections.observableArrayList();
    private final ListView<CommandLog.Line> list = new ListView<>(items);
    private final ConcurrentLinkedQueue<CommandLog.Line> pending = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean flushScheduled = new AtomicBoolean(false);
    private final java.util.function.Consumer<CommandLog.Line> listener = this::enqueue;

    public CommandLogPanel(CommandLog log) {
        this.log = log;

        list.setCellFactory(v -> new LineCell());
        list.setStyle("-fx-font-family: 'Consolas', 'Menlo', monospace;");
        list.setPlaceholder(new Label("Nothing has been sent to a server yet."));
        BorderPane.setMargin(list, new Insets(8, 0, 0, 0));

        Button copy = new Button("Copy all");
        copy.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(items.stream().map(CommandLogPanel::plainText).collect(Collectors.joining("\n")));
            Clipboard.getSystemClipboard().setContent(content);
        });
        Button clear = new Button("Clear");
        clear.setOnAction(e -> {
            log.clear();
            items.clear();
        });
        HBox bar = new HBox(8, new Label("Command log"), spacer(), copy, clear);
        bar.setAlignment(Pos.CENTER_LEFT);

        setTop(bar);
        setCenter(list);
        setPadding(new Insets(10));

        items.setAll(log.snapshot());
        scrollToEnd();
        // Listen only while shown, so a closed window doesn't keep receiving lines.
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) {
                log.removeListener(listener);
            } else {
                log.addListener(listener);
            }
        });
    }

    /** Opens the log in its own window. */
    public static Stage showInWindow(CommandLog log, Window owner, String title) {
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.setTitle(title);
        stage.setScene(new javafx.scene.Scene(new CommandLogPanel(log), 860, 420));
        stage.show();
        return stage;
    }

    /** Lines arrive from SSH threads; batch them so a chatty command doesn't flood the FX thread. */
    private void enqueue(CommandLog.Line line) {
        pending.add(line);
        if (flushScheduled.compareAndSet(false, true)) {
            Platform.runLater(this::flush);
        }
    }

    private void flush() {
        flushScheduled.set(false);
        List<CommandLog.Line> batch = new ArrayList<>();
        CommandLog.Line line;
        while ((line = pending.poll()) != null) {
            batch.add(line);
        }
        if (batch.isEmpty()) {
            return;
        }
        items.addAll(batch);
        int overflow = items.size() - CommandLog.DEFAULT_CAPACITY;
        if (overflow > 0) {
            items.remove(0, overflow);
        }
        scrollToEnd();
    }

    private void scrollToEnd() {
        if (!items.isEmpty()) {
            list.scrollTo(items.size() - 1);
        }
    }

    private static String plainText(CommandLog.Line line) {
        return TIME.format(line.time()) + "  " + line.text();
    }

    private static javafx.scene.layout.Region spacer() {
        javafx.scene.layout.Region r = new javafx.scene.layout.Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    private static final class LineCell extends ListCell<CommandLog.Line> {
        @Override
        protected void updateItem(CommandLog.Line line, boolean empty) {
            super.updateItem(line, empty);
            if (empty || line == null) {
                setText(null);
                setStyle("");
                return;
            }
            setText(plainText(line));
            switch (line.kind()) {
                case COMMAND:
                    setStyle("-fx-font-weight: bold;");
                    break;
                case ERROR_OUTPUT:
                    setStyle("-fx-text-fill: #b00020;");
                    break;
                case STATUS:
                case INFO:
                    setStyle("-fx-opacity: 0.7;");
                    break;
                default:
                    setStyle("");
            }
        }
    }
}
