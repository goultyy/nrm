package mt.su.nrm.ui;

import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A step-by-step dialog: one question or group of questions per page, Back and Next buttons, a
 * short explanation on each page, live checking (Next stays disabled until the page is in order),
 * and a final review page that shows exactly what will be written before anything is added.
 */
final class WizardDialog {

    /** One page of the wizard. */
    static final class Page implements WizardModel.Step {
        private final String title;
        private final String explanation;
        private final Node content;
        private final Supplier<List<String>> problems;
        private final Runnable onShow;
        private final Supplier<Boolean> applies;

        Page(String title, String explanation, Node content, Supplier<List<String>> problems, Runnable onShow,
             Supplier<Boolean> applies) {
            this.applies = applies;
            this.title = title;
            this.explanation = explanation;
            this.content = content;
            this.problems = problems;
            this.onShow = onShow;
        }

        Page(String title, String explanation, Node content, Supplier<List<String>> problems) {
            this(title, explanation, content, problems, () -> { }, () -> true);
        }

        Page(String title, String explanation, Node content, Supplier<List<String>> problems, Runnable onShow) {
            this(title, explanation, content, problems, onShow, () -> true);
        }

        @Override
        public String title() {
            return title;
        }

        @Override
        public List<String> problems() {
            return problems.get();
        }

        @Override
        public boolean applies() {
            return applies.get();
        }
    }

    private WizardDialog() {
    }

    /**
     * Shows the wizard. The last page is a review generated from {@code preview}.
     *
     * @param preview   the text that will be written, evaluated whenever the review page is shown
     * @param afterText what to do next, shown under the preview (for example how to use the new object)
     * @return true if the user finished the wizard
     */
    static boolean show(Window owner, String title, String finishText, List<Page> pages, Supplier<String> preview,
                        Supplier<String> afterText) {
        return show(owner, title, finishText, pages, preview, afterText, 640, 330);
    }

    /** As above, with the size of the page area, for wizards whose pages need more room than the default. */
    static boolean show(Window owner, String title, String finishText, List<Page> pages, Supplier<String> preview,
                        Supplier<String> afterText, double viewportWidth, double viewportHeight) {
        TextArea previewArea = new TextArea();
        previewArea.setEditable(false);
        previewArea.setPrefRowCount(9);
        previewArea.setStyle("-fx-font-family: 'Consolas', 'Menlo', monospace;");
        Label after = new Label();
        after.setWrapText(true);
        VBox reviewContent = new VBox(10, previewArea, after);

        List<Page> all = new ArrayList<>(pages);
        all.add(new Page("Review", "This is exactly what will be added to the configuration. Nothing is written to the "
                + "server yet: it becomes a pending change that you review and apply.", reviewContent, List::of,
                () -> {
                    previewArea.setText(preview.get());
                    after.setText(afterText.get());
                }));
        WizardModel model = new WizardModel(all);

        Dialog<Boolean> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(title);
        dialog.setResizable(true);
        ButtonType back = new ButtonType("< Back", ButtonBar.ButtonData.BACK_PREVIOUS);
        ButtonType next = new ButtonType("Next >", ButtonBar.ButtonData.NEXT_FORWARD);
        ButtonType finish = new ButtonType(finishText, ButtonBar.ButtonData.FINISH);
        dialog.getDialogPane().getButtonTypes().addAll(back, next, finish, ButtonType.CANCEL);

        Label heading = new Label();
        heading.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        Label explanation = new Label();
        explanation.setWrapText(true);
        explanation.setOpacity(0.85);
        Label problems = new Label();
        problems.setWrapText(true);
        problems.setStyle("-fx-text-fill: #b00020;");
        problems.setMinHeight(Label.USE_PREF_SIZE);
        StackPane holder = new StackPane();
        ScrollPane scroll = new ScrollPane(holder);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(viewportHeight);
        scroll.setPrefViewportWidth(viewportWidth);
        scroll.setStyle("-fx-background-color: transparent;");

        Node backButton = dialog.getDialogPane().lookupButton(back);
        Node nextButton = dialog.getDialogPane().lookupButton(next);
        Node finishButton = dialog.getDialogPane().lookupButton(finish);

        Runnable refresh = () -> {
            Page page = all.get(model.index());
            holder.getChildren().setAll(page.content);
            heading.setText(model.heading());
            explanation.setText(page.explanation);
            List<String> found = page.problems();
            problems.setText(String.join("\n", found));
            backButton.setDisable(model.isFirst());
            nextButton.setVisible(!model.isLast());
            nextButton.setManaged(!model.isLast());
            nextButton.setDisable(!model.canGoNext());
            finishButton.setVisible(model.isLast());
            finishButton.setManaged(model.isLast());
            finishButton.setDisable(!model.canFinish());
        };
        // Keep the buttons in step with what the user types on the current page.
        javafx.animation.Timeline poll = new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                javafx.util.Duration.millis(200), e -> {
                    Page page = all.get(model.index());
                    List<String> found = page.problems();
                    problems.setText(String.join("\n", found));
                    nextButton.setDisable(!model.canGoNext());
                    finishButton.setDisable(!model.canFinish());
                }));
        poll.setCycleCount(javafx.animation.Animation.INDEFINITE);

        backButton.addEventFilter(ActionEvent.ACTION, e -> {
            e.consume();
            if (model.back()) {
                all.get(model.index()).onShow.run();
                refresh.run();
            }
        });
        nextButton.addEventFilter(ActionEvent.ACTION, e -> {
            e.consume();
            if (model.next()) {
                all.get(model.index()).onShow.run();
                refresh.run();
            }
        });

        VBox content = new VBox(10, heading, explanation, scroll, problems);
        content.setPadding(new Insets(12));
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(b -> b == finish);
        dialog.setOnShown(e -> {
            all.get(0).onShow.run();
            refresh.run();
            poll.play();
        });
        dialog.setOnHidden(e -> poll.stop());
        return Boolean.TRUE.equals(dialog.showAndWait().orElse(false));
    }
}
