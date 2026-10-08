package mt.su.nrm.ui;

import mt.su.nrm.logs.LogParser;
import mt.su.nrm.logs.LogStats;
import mt.su.nrm.model.ServerProfile;
import mt.su.nrm.nginx.LogFormatSettings;
import mt.su.nrm.nginx.LogLocations;
import mt.su.nrm.nginx.LogLocations.LogSource;
import mt.su.nrm.ssh.LogService;
import mt.su.nrm.ssh.SshExecutor;
import mt.su.nrm.ssh.SshSession;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Nginx logs: pick the general access or error log, or the log a particular virtual host writes, and read
 * its last lines. A search box narrows the lines, and "Follow" re-reads every few seconds like
 * {@code tail -f}. Everything is fetched over the logged SSH connection in the background.
 */
final class LogsPanel extends BorderPane {

    private static final List<Integer> LINE_CHOICES = List.of(100, 500, 1000, 5000, 20000);

    private final ServerProfile profile;
    private final ServerConnection connection;

    private final ComboBox<LogSource> source = new ComboBox<>();
    private final ComboBox<Integer> lines = new ComboBox<>(FXCollections.observableArrayList(LINE_CHOICES));
    private final TextField search = new TextField();
    private final CheckBox follow = new CheckBox("Follow");
    private final TextArea view = new TextArea();
    private final ToggleButton linesMode = new ToggleButton("Lines");
    private final ToggleButton analysisMode = new ToggleButton("Analysis");
    private final ComboBox<FormatChoice> format = new ComboBox<>();
    private final LogAnalysisView analysis = new LogAnalysisView(this::pick);
    private final VBox viewHolder = new VBox();
    private String lastText = "";
    private LogSource lastSource;
    private String lastFilter = "";
    private final Label status = new Label();
    private final javafx.scene.control.ProgressIndicator busyIndicator = ProgressDialog.spinner(16);
    private final Timeline ticker = new Timeline(new KeyFrame(Duration.seconds(4), e -> refresh(true)));

    private boolean busy;
    private boolean reloadingSources;

    /** A way to read the lines for the analysis: automatic, or one of the formats defined on the server. */
    private record FormatChoice(String name, LogFormatSettings settings) {
        @Override
        public String toString() {
            return name;
        }
    }

    LogsPanel(ServerProfile profile, ServerConnection connection) {
        this.profile = profile;
        this.connection = connection;

        source.setPrefWidth(430);
        lines.setValue(500);
        lines.setPrefWidth(90);
        search.setPromptText("Search these lines");
        search.setPrefColumnCount(18);
        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> refresh(false));
        Button clear = new Button("Clear search");
        clear.setOnAction(e -> search.clear());
        Button copy = new Button("Copy");
        copy.setOnAction(e -> {
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(view.getText());
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        });

        ToggleGroup modes = new ToggleGroup();
        linesMode.setToggleGroup(modes);
        analysisMode.setToggleGroup(modes);
        linesMode.setSelected(true);
        // One of the two is always on: clicking the selected one again must not leave neither.
        modes.selectedToggleProperty().addListener((obs, o, n) -> {
            if (n == null && o != null) {
                o.setSelected(true);
            } else {
                showMode();
            }
        });
        format.setPrefWidth(190);
        format.setTooltip(new javafx.scene.control.Tooltip("How the lines of an access log are read for the analysis. "
                + "Automatic handles the standard format and JSON lines; pick one of your own formats for anything else."));
        format.valueProperty().addListener((obs, o, n) -> {
            if (n != null && analysisMode.isSelected()) {
                analyse();
            }
        });
        Label readAs = new Label("Read as");

        HBox pick = new HBox(8, new Label("Log"), source, new Label("Last"), lines, new Label("lines"), readAs, format);
        pick.setAlignment(Pos.CENTER_LEFT);
        HBox tools = new HBox(8, linesMode, analysisMode, search, clear, refresh, follow, copy, busyIndicator);
        tools.setAlignment(Pos.CENTER_LEFT);
        busyIndicator.setVisible(false);
        VBox top = new VBox(8, pick, tools);
        top.setPadding(new Insets(12, 12, 8, 12));

        view.setEditable(false);
        view.setWrapText(false);
        view.setStyle("-fx-font-family: 'Consolas', 'Menlo', monospace; -fx-font-size: 12px;");
        VBox.setVgrow(view, Priority.ALWAYS);
        status.setOpacity(0.75);
        status.setPadding(new Insets(4, 12, 8, 12));

        setTop(top);
        viewHolder.getChildren().add(view);
        setCenter(viewHolder);
        setBottom(status);
        VBox.setVgrow(view, Priority.ALWAYS);
        VBox.setVgrow(analysis, Priority.ALWAYS);
        viewHolder.setPadding(new Insets(0, 12, 0, 12));
        loadFormats();

        ticker.setCycleCount(Animation.INDEFINITE);
        follow.selectedProperty().addListener((obs, o, on) -> {
            if (on) {
                refresh(false);
                ticker.play();
            } else {
                ticker.stop();
            }
        });
        source.valueProperty().addListener((obs, o, n) -> {
            if (!reloadingSources && n != null) {
                refresh(false);
            }
        });
        lines.valueProperty().addListener((obs, o, n) -> refresh(false));
        search.setOnAction(e -> refresh(false));
        // Stop polling when the panel is taken off screen.
        sceneProperty().addListener((obs, o, n) -> {
            if (n == null) {
                ticker.stop();
            } else if (follow.isSelected()) {
                ticker.play();
            }
        });

        loadSources();
        if (!connection.isConnected()) {
            status.setText("Connect to the server to read its logs.");
        } else if (source.getValue() != null) {
            refresh(false);
        }
    }

    /** Fills the list from the loaded configuration (or the usual defaults if it isn't loaded). */
    private void loadSources() {
        LogSource keep = source.getValue();
        reloadingSources = true;
        try {
            List<LogSource> found = LogLocations.discover(connection.config());
            source.setItems(FXCollections.observableArrayList(found));
            LogSource again = keep == null ? null : found.stream().filter(s -> s.path().equals(keep.path())
                    && s.error() == keep.error()).findFirst().orElse(null);
            source.setValue(again != null ? again : found.get(0));
        } finally {
            reloadingSources = false;
        }
    }

    // ---------------------------------------------------------------- analysis

    private void loadFormats() {
        FormatChoice keep = format.getValue();
        List<FormatChoice> choices = new java.util.ArrayList<>();
        choices.add(new FormatChoice("Automatic", null));
        if (connection.config() != null) {
            for (mt.su.nrm.nginx.LogFormat f : connection.config().logFormats()) {
                if (!f.name().isEmpty()) {
                    choices.add(new FormatChoice(f.name(), f.read()));
                }
            }
        }
        format.setItems(FXCollections.observableArrayList(choices));
        FormatChoice again = keep == null ? null : choices.stream().filter(c -> c.name().equals(keep.name())).findFirst().orElse(null);
        format.setValue(again != null ? again : choices.get(0));
    }

    private void showMode() {
        boolean showAnalysis = analysisMode.isSelected();
        viewHolder.getChildren().setAll(showAnalysis ? analysis : view);
        if (showAnalysis) {
            analyse();
        }
    }

    /** Analyses the lines last read. Reads them first if nothing has been read yet. */
    private void analyse() {
        if (lastSource == null) {
            refresh(false);
            return;
        }
        if (lastSource.error()) {
            analysis.showMessage("The analysis reads access logs. This is an error log: choose an access log above.");
            return;
        }
        FormatChoice choice = format.getValue();
        LogParser parser = choice == null || choice.settings() == null ? LogParser.auto()
                : LogParser.forFormat(choice.settings());
        analysis.show(LogStats.ofLines(lastText, parser, 10, 30));
        int total = lastText.isEmpty() ? 0 : (int) lastText.lines().count();
        status.setText(lastSource.path() + " - analysed the last " + total + " line(s)"
                + (lastFilter.isEmpty() ? "" : " matching \"" + lastFilter + "\"") + " - updated "
                + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
    }

    /** A double-click in the analysis: show the lines that mention it. */
    private void pick(String text) {
        search.setText(text.length() > 200 ? text.substring(0, 200) : text);
        linesMode.setSelected(true);
        refresh(false);
    }

    /** Selects the log of one virtual host, if it has its own (used when opening logs from a site). */
    void select(String siteName) {
        for (LogSource s : source.getItems()) {
            if (s.group().equals(siteName) && !s.error()) {
                source.setValue(s);
                return;
            }
        }
    }

    /** Re-reads the list of logs and the current log. */
    void reload() {
        loadSources();
        loadFormats();
        refresh(false);
    }

    private void refresh(boolean quiet) {
        SshSession session = connection.session();
        LogSource chosen = source.getValue();
        if (session == null || chosen == null) {
            return;
        }
        if (busy) {
            return;
        }
        busy = true;
        busyIndicator.setVisible(!quiet);
        int count = lines.getValue() == null ? 500 : lines.getValue();
        String filter = search.getText().strip();
        List<String> known = source.getItems().stream().map(LogSource::path).toList();
        if (!quiet) {
            status.setText("Reading " + chosen.path() + "...");
        }
        SshExecutor.submit(() -> LogService.tail(session, chosen.path(), known, count, filter))
                .whenComplete((text, failure) -> Platform.runLater(() -> {
                    busy = false;
                    busyIndicator.setVisible(false);
                    if (chosen != source.getValue()) {
                        return; // the choice changed while this was loading
                    }
                    if (failure != null) {
                        Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                        String message = cause instanceof java.io.IOException && cause.getMessage() != null
                                && cause.getMessage().startsWith("There is no log")
                                ? cause.getMessage() : ConnectionManager.describeFailure(profile, connection, failure);
                        view.setText("");
                        status.setText(message.replace('\n', ' '));
                        follow.setSelected(false);
                        return;
                    }
                    show(text, chosen, filter);
                }));
    }

    private void show(String text, LogSource chosen, String filter) {
        lastText = text;
        lastSource = chosen;
        lastFilter = filter;
        boolean atEnd = view.getScrollTop() >= 0 && (follow.isSelected() || view.getText().isEmpty());
        double top = view.getScrollTop();
        view.setText(text);
        if (atEnd) {
            view.positionCaret(text.length());
            view.setScrollTop(Double.MAX_VALUE);
        } else {
            view.setScrollTop(top);
        }
        int shown = text.isEmpty() ? 0 : (int) text.chars().filter(c -> c == '\n').count();
        status.setText(chosen.path() + " - " + shown + " line(s)" + (filter.isEmpty() ? "" : " matching \"" + filter + "\"")
                + " - updated " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                + (shown == 0 ? " - nothing to show" : ""));
        if (analysisMode.isSelected()) {
            analyse(); // after the line above, so its summary is the one left in the status bar
        }
    }

    // For tests.

    LogAnalysisView analysisView() {
        return analysis;
    }

    void showLinesForTest(LogSource source, String text) {
        show(text, source, "");
    }

    void chooseAnalysis(boolean on) {
        (on ? analysisMode : linesMode).setSelected(true);
    }
}
