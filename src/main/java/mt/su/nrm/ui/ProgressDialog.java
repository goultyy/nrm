package mt.su.nrm.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

/** A small modal "please wait" window for server work that can take a while on a slow link. */
final class ProgressDialog {

    private final Stage stage = new Stage(StageStyle.UTILITY);
    private final Label label = new Label();
    private final Label detail = new Label();

    private ProgressDialog(Window owner, String title, String message) {
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle(title);
        stage.setResizable(false);
        // Closing it by hand would leave the operation running unseen, so ignore the close button.
        stage.setOnCloseRequest(e -> e.consume());

        ProgressIndicator spinner = spinner(36);
        label.setText(message);
        label.setWrapText(true);
        label.setPrefWidth(340);
        label.setMaxWidth(340);
        label.setStyle("-fx-font-weight: bold;");
        detail.setWrapText(true);
        detail.setPrefWidth(340);
        detail.setMaxWidth(340);
        detail.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        detail.setAlignment(Pos.TOP_LEFT);
        label.setAlignment(Pos.TOP_LEFT);
        label.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        detail.setStyle("-fx-text-fill: #666666;");
        detail.managedProperty().bind(detail.textProperty().isNotEmpty());
        javafx.scene.layout.VBox text = new javafx.scene.layout.VBox(4, label, detail);
        text.setMinWidth(340);
        text.setPrefWidth(340);
        HBox box = new HBox(14, spinner, text);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(20));
        stage.setScene(new Scene(box));
    }

    /**
     * A spinner that keeps its size: layouts may squeeze the text beside it but never the spinner, which would
     * otherwise shrink to nothing next to long text.
     */
    static ProgressIndicator spinner(double size) {
        ProgressIndicator spinner = new ProgressIndicator(ProgressIndicator.INDETERMINATE_PROGRESS);
        spinner.setMinSize(size, size);
        spinner.setPrefSize(size, size);
        spinner.setMaxSize(size, size);
        return spinner;
    }

    /** Opens the window; the caller must {@link #close()} it when the work ends. */
    static ProgressDialog show(Window owner, String title, String message) {
        ProgressDialog d = new ProgressDialog(owner, title, message);
        d.stage.show();
        return d;
    }

    /** Sets the headline of what is happening now, e.g. "Connecting to web1...". */
    void setStatus(String message) {
        label.setText(message);
    }

    /** Sets the smaller line under the headline, e.g. the latest step reported by the server. */
    void setDetail(String text) {
        detail.setText(text.length() > 110 ? text.substring(0, 107) + "..." : text);
    }

    void close() {
        stage.setOnCloseRequest(null);
        stage.close();
    }
}
