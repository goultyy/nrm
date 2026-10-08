package mt.su.nrm.ui;

import mt.su.nrm.ssh.SshExecutor;
import mt.su.nrm.util.TextFiles;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * A plain text editor for one file, opened from the File Explorer. It knows nothing about where the file lives:
 * saving goes through a {@link Saver}, which the explorer points at the server (over the logged SFTP session) or
 * at this computer. Saving runs in the background; the window asks before discarding unsaved changes.
 */
final class FileEditorWindow {

    /** Writes the bytes to the file's real location. */
    interface Saver {
        void save(byte[] data) throws IOException;
    }

    private final Stage stage = new Stage();
    private final TextArea area = new TextArea();
    private final Label status = new Label();
    private final Button save = new Button("Save");
    private final String name;
    private final Saver saver;
    private final boolean crlf;
    private final Runnable onSaved;
    private String savedText;
    private boolean saving;

    /**
     * @param where    the full path, shown in the title and status line
     * @param onSaved  runs on the UI thread after each successful save (the explorer re-reads its folder)
     * @param onClosed runs once when the window has closed
     */
    FileEditorWindow(Window owner, String where, TextFiles.Text loaded, Saver saver, Runnable onSaved, Runnable onClosed) {
        this.name = where;
        this.saver = saver;
        this.crlf = loaded.crlf();
        this.onSaved = onSaved;
        this.savedText = loaded.text();

        area.setText(loaded.text());
        area.setStyle("-fx-font-family: 'Consolas', 'Menlo', monospace; -fx-font-size: 13px;");
        CheckBox wrap = new CheckBox("Wrap lines");
        area.wrapTextProperty().bind(wrap.selectedProperty());
        save.setOnAction(e -> save());
        save.setDefaultButton(false);
        Button close = new Button("Close");
        close.setOnAction(e -> stage.fireEvent(new javafx.stage.WindowEvent(stage, javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST)));
        status.setOpacity(0.75);
        HBox.setHgrow(status, Priority.ALWAYS);
        status.setMaxWidth(Double.MAX_VALUE);
        HBox bar = new HBox(8, save, close, wrap, status);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8, 10, 8, 10));

        BorderPane root = new BorderPane(area);
        root.setBottom(bar);
        Scene scene = new Scene(root, 860, 600);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN), this::save);
        stage.setScene(scene);
        if (owner != null) {
            stage.initOwner(owner);
        }
        area.textProperty().addListener((obs, o, n) -> updateState());
        stage.setOnCloseRequest(e -> {
            if (dirty() && !Dialogs.confirm(stage, "Discard unsaved changes?",
                    "\"" + name + "\" has changes that haven't been saved.", "Discard")) {
                e.consume();
            }
        });
        stage.setOnHidden(e -> onClosed.run());
        updateState();
        status.setText(name + (crlf ? "  (Windows line endings kept)" : ""));
    }

    void show() {
        stage.show();
        stage.toFront();
        area.requestFocus();
    }

    /** Brings an already open editor forward instead of opening the file twice. */
    void focus() {
        stage.setIconified(false);
        stage.toFront();
    }

    private boolean dirty() {
        return !area.getText().equals(savedText);
    }

    private void updateState() {
        stage.setTitle((dirty() ? "* " : "") + name);
        save.setDisable(saving || !dirty());
    }

    private void save() {
        if (saving || !dirty()) {
            return;
        }
        saving = true;
        String text = area.getText();
        byte[] data = TextFiles.encode(text, crlf);
        status.setText("Saving...");
        updateState();
        SshExecutor.submit(() -> {
            saver.save(data);
            return null;
        }).whenComplete((ignored, failure) -> Platform.runLater(() -> {
            saving = false;
            if (failure != null) {
                Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                String message = String.valueOf(cause.getMessage());
                status.setText("Not saved: " + message.replace('\n', ' '));
                Dialogs.error(stage, "Could not save " + name, message
                        + "\n\nYour changes are still in the editor.");
            } else {
                savedText = text;
                status.setText("Saved " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " - " + name);
                onSaved.run();
            }
            updateState();
        }));
    }
}
