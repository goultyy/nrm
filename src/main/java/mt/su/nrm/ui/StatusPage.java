package mt.su.nrm.ui;

import mt.su.nrm.model.ServerProfile;
import mt.su.nrm.nginx.RemoteConfig;
import mt.su.nrm.ssh.NetworkService;
import mt.su.nrm.ssh.SshExecutor;
import mt.su.nrm.ssh.SshSession;
import mt.su.nrm.status.NginxBuild;
import mt.su.nrm.status.StatusPageConfig;
import mt.su.nrm.status.StatusService;
import mt.su.nrm.status.StubStatus;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.io.IOException;
import java.time.LocalTime;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Live figures from nginx's {@code stub_status}: connections now, requests per second, what the connections are
 * doing, and a chart of the last minutes. It reads the page from the server itself, so each reading is a command in the
 * command log; it only reads while it is on screen, slows to the chosen interval, and stops after repeated failures so it
 * can't flood the log.
 */
final class StatusPage extends FeaturePage {

    private static final int VISIBLE_POINTS = 120;
    private static final int GIVE_UP_AFTER = 3;

    private final Timeline timer = new Timeline();
    // A chart series can belong to only one chart, so each time the live view is built it gets fresh ones.
    private XYChart.Series<Number, Number> requestSeries = new XYChart.Series<>();
    private XYChart.Series<Number, Number> activeSeries = new XYChart.Series<>();
    private final Label active = figure();
    private final Label requestRate = figure();
    private final Label acceptRate = figure();
    private final Label dropped = figure();
    private final Label readingText = new Label();
    private final Label writingText = new Label();
    private final Label waitingText = new Label();
    private final ProgressBar readingBar = new ProgressBar(0);
    private final ProgressBar writingBar = new ProgressBar(0);
    private final ProgressBar waitingBar = new ProgressBar(0);
    private final Label message = new Label();
    private final Label pendingNote = new Label();
    private final Button pause = new Button("Pause");
    private final ComboBox<Integer> every = new ComboBox<>(FXCollections.observableArrayList(2, 5, 10, 30));

    private final long startedNanos = System.nanoTime();
    private StatusPageConfig.Endpoint shown;
    private boolean paused;
    private boolean inFlight;
    private int failures;
    private StubStatus previous;
    private long previousNanos;

    StatusPage(ServerProfile profile, ServerConnection connection) {
        super(profile, connection);
        every.setValue(5);
        every.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Integer seconds) {
                return seconds == null ? "" : "Every " + seconds + " seconds";
            }

            @Override
            public Integer fromString(String s) {
                return null;
            }
        });
        every.setOnAction(e -> {
            if (shown != null && !paused) {
                startPolling(false);
            }
        });
        pause.setOnAction(e -> {
            if (paused) {
                startPolling(true);
            } else {
                paused = true;
                timer.stop();
                pause.setText("Resume");
                note("Paused.", false);
            }
        });
        message.setWrapText(true);
        pendingNote.setWrapText(true);
        pendingNote.setStyle("-fx-text-fill: #8a6d00;");
    }

    // ---------------------------------------------------------------- drawing

    @Override
    void refresh() {
        if (!ready()) {
            stopPolling();
            shown = null;
            setCenter(notReadyView());
            return;
        }
        Optional<StatusPageConfig.Endpoint> found = StatusPageConfig.find(config());
        if (found.isEmpty()) {
            stopPolling();
            shown = null;
            setCenter(offView());
            return;
        }
        pendingNote.setText(connection.pendingCountProperty().get() > 0
                ? "There are pending changes. If the status page is among them, it can be read once they are applied."
                : "");
        pendingNote.setManaged(!pendingNote.getText().isEmpty());
        pendingNote.setVisible(!pendingNote.getText().isEmpty());
        if (!found.get().equals(shown)) {
            shown = found.get();
            resetData();
            setCenter(liveView(shown));
            startPolling(true);
        } else if (!paused && timer.getStatus() != Animation.Status.RUNNING) {
            startPolling(true);
        }
    }

    @Override
    void reload() {
        shown = null;
        refresh();
    }

    @Override
    void hidden() {
        stopPolling();
    }

    private Node offView() {
        TextField port = new TextField(String.valueOf(StatusPageConfig.suggestPort(StatusPageConfig.listenPorts(config()))));
        port.setPrefColumnCount(6);
        Button enable = new Button("Enable the status page");
        enable.setOnAction(e -> enable(port.getText()));
        HBox row = new HBox(8, new Label("Port on this server"), port, enable);
        row.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(12, heading("Status page"),
                hint("nginx can report how many connections it has and how many requests it serves. Turning this on "
                        + "adds a small server of its own to the configuration that answers on this machine only "
                        + "(127.0.0.1), so it can't be reached from outside and doesn't touch your sites. It is a "
                        + "pending change: you review it, then apply it."),
                row,
                hint("The numbers are for the whole nginx, not per site, and there are no response codes or bandwidth. "
                        + "Turning the page off removes exactly what was added."));
        box.setPadding(new Insets(16));
        return box;
    }

    private Node liveView(StatusPageConfig.Endpoint ep) {
        every.setValue(every.getValue() == null ? 5 : every.getValue());
        pause.setText("Pause");
        paused = false;
        requestSeries = new XYChart.Series<>();
        activeSeries = new XYChart.Series<>();

        HBox controls = new HBox(8, pause, every);
        controls.setAlignment(Pos.CENTER_LEFT);
        if (ep.ours()) {
            Button disable = new Button("Turn off the status page");
            disable.setOnAction(e -> disable());
            controls.getChildren().add(disable);
        }
        Label where = hint((ep.ours() ? "Added by NRM. " : "Found in " + ep.file() + ". ") + "Read from the server at "
                + ep.url() + ".");
        VBox top = new VBox(8, heading("Status page"), where, controls);
        if (!ep.restricted()) {
            Label open = new Label("Nothing limits who can read this page. If that port is reachable from outside, "
                    + "anyone can see these figures. Add allow/deny lines to its location.");
            open.setWrapText(true);
            open.setStyle("-fx-text-fill: #b00020;");
            top.getChildren().add(open);
        }
        top.getChildren().add(pendingNote);

        HBox cards = new HBox(10, card("Active connections", active), card("Requests per second", requestRate),
                card("Connections accepted per second", acceptRate), card("Dropped connections", dropped));
        GridPane states = new GridPane();
        states.setHgap(10);
        states.setVgap(6);
        addState(states, 0, "Reading", readingText, readingBar);
        addState(states, 1, "Writing", writingText, writingBar);
        addState(states, 2, "Waiting", waitingText, waitingBar);

        VBox body = new VBox(12, cards, hint("What the open connections are doing right now:"), states,
                chart("Requests per second", requestSeries), chart("Active connections", activeSeries), message);
        VBox.setVgrow(body, Priority.ALWAYS);
        VBox page = new VBox(10, top, body);
        page.setPadding(new Insets(12));
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(page);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent;");
        return scroll;
    }

    private static Label figure() {
        Label label = new Label("-");
        label.setStyle("-fx-font-size: 22px; -fx-font-weight: bold;");
        return label;
    }

    private static Node card(String title, Label value) {
        Label name = new Label(title);
        name.setOpacity(0.75);
        name.setWrapText(true);
        VBox box = new VBox(2, name, value);
        box.setPadding(new Insets(8, 12, 8, 12));
        box.setMinWidth(150);
        box.setStyle("-fx-border-color: rgba(128,128,128,0.45); -fx-border-radius: 4;");
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    private static void addState(GridPane grid, int row, String name, Label value, ProgressBar bar) {
        bar.setPrefWidth(260);
        grid.add(new Label(name), 0, row);
        grid.add(bar, 1, row);
        grid.add(value, 2, row);
    }

    private static Node chart(String title, XYChart.Series<Number, Number> series) {
        NumberAxis x = new NumberAxis();
        x.setForceZeroInRange(false);
        x.setLabel("Seconds since this page opened");
        NumberAxis y = new NumberAxis();
        y.setForceZeroInRange(true);
        LineChart<Number, Number> chart = new LineChart<>(x, y);
        chart.setTitle(title);
        chart.setCreateSymbols(false);
        chart.setAnimated(false);
        chart.setLegendVisible(false);
        chart.setPrefHeight(190);
        chart.setMinHeight(150);
        chart.getData().add(series);
        return chart;
    }

    // ---------------------------------------------------------------- polling

    /**
     * Starts (or restarts) the timer. A fresh start or a resume ({@code readNow}) also forgets earlier failures; just
     * changing how often it reads must not, or the give-up limit could be dodged by touching the interval.
     */
    private void startPolling(boolean readNow) {
        paused = false;
        pause.setText("Pause");
        if (readNow) {
            failures = 0;
            previous = null;
            note("", false);
        }
        timer.stop();
        timer.getKeyFrames().setAll(new KeyFrame(Duration.seconds(every.getValue()), e -> poll()));
        timer.setCycleCount(Animation.INDEFINITE);
        timer.play();
        if (readNow) {
            poll();
        }
    }

    private void stopPolling() {
        timer.stop();
    }

    private void poll() {
        StatusPageConfig.Endpoint ep = shown;
        SshSession session = connection.session();
        if (ep == null || inFlight || getScene() == null) {
            return;
        }
        if (session == null || !connection.isConnected()) {
            note("Not connected to the server.", true);
            stopPolling();
            return;
        }
        inFlight = true;
        SshExecutor.submit(() -> StatusService.sample(session, ep.host(), ep.port(), ep.path(), ep.hostHeader()))
                .whenComplete((status, failure) -> Platform.runLater(() -> {
                    inFlight = false;
                    if (getScene() == null || !ep.equals(shown)) {
                        return;
                    }
                    if (failure != null) {
                        failed(failure);
                    } else {
                        succeeded(status);
                    }
                }));
    }

    private void failed(Throwable failure) {
        Throwable cause = failure.getCause() instanceof IOException ? failure.getCause() : failure;
        failures++;
        if (failures >= GIVE_UP_AFTER) {
            paused = true;
            timer.stop();
            pause.setText("Resume");
            note(cause.getMessage() + "\nStopped after " + failures + " failed readings. Press Resume to try again.", true);
        } else {
            note(cause.getMessage(), true);
        }
    }

    private void succeeded(StubStatus status) {
        failures = 0;
        long now = System.nanoTime();
        note("Last reading at " + LocalTime.now().withNano(0), false);
        active.setText(String.valueOf(status.active()));
        dropped.setText(String.valueOf(status.dropped()));
        dropped.setStyle("-fx-font-size: 22px; -fx-font-weight: bold;"
                + (status.dropped() > 0 ? "-fx-text-fill: #b00020;" : ""));
        state(readingText, readingBar, status.reading(), status.active());
        state(writingText, writingBar, status.writing(), status.active());
        state(waitingText, waitingBar, status.waiting(), status.active());
        double t = (now - startedNanos) / 1e9;
        add(activeSeries, t, status.active());
        if (previous != null) {
            Optional<StubStatus.Rates> rates = StubStatus.rates(previous, status, (now - previousNanos) / 1e9);
            if (rates.isPresent()) {
                requestRate.setText(format(rates.get().requestsPerSecond()));
                acceptRate.setText(format(rates.get().acceptsPerSecond()));
                add(requestSeries, t, rates.get().requestsPerSecond());
            }
        }
        previous = status;
        previousNanos = now;
    }

    private static void state(Label text, ProgressBar bar, long value, long total) {
        text.setText(String.valueOf(value));
        bar.setProgress(total <= 0 ? 0 : Math.min(1.0, (double) value / total));
    }

    private static void add(XYChart.Series<Number, Number> series, double x, double y) {
        series.getData().add(new XYChart.Data<>(x, y));
        while (series.getData().size() > VISIBLE_POINTS) {
            series.getData().remove(0);
        }
    }

    private static String format(double perSecond) {
        return perSecond >= 100 ? String.format("%.0f", perSecond) : String.format("%.1f", perSecond);
    }

    private void note(String text, boolean problem) {
        message.setText(text);
        message.setStyle(problem ? "-fx-text-fill: #b00020;" : "-fx-opacity: 0.75;");
    }

    private void resetData() {
        requestSeries.getData().clear();
        activeSeries.getData().clear();
        previous = null;
        failures = 0;
        for (Label l : new Label[] {active, requestRate, acceptRate, dropped}) {
            l.setText("-");
        }
        note("", false);
    }

    // ---------------------------------------------------------------- turning it on and off

    private void enable(String portText) {
        RemoteConfig cfg = config();
        Long typed = WizardParts.number(portText);
        if (cfg == null || typed == null) {
            Dialogs.error(window(), "Can't turn it on", "Enter the port as a number.");
            return;
        }
        int port = typed.intValue();
        SshSession session = connection.session();
        String binary = profile.getPaths().getNginxBinary();
        // Read, before changing anything: which ports the server really uses, and whether this nginx can do it.
        SshExecutor.submit(() -> new Object[] {
                session == null ? NetworkService.Facts.EMPTY : NetworkService.read(session),
                session == null ? "" : readBuild(session, binary)})
                .whenComplete((found, failure) -> Platform.runLater(() -> {
                    NetworkService.Facts facts = failure == null ? (NetworkService.Facts) found[0] : NetworkService.Facts.EMPTY;
                    String build = failure == null ? (String) found[1] : "";
                    if (NginxBuild.hasStubStatus(build).filter(has -> !has).isPresent()) {
                        Dialogs.error(window(), "This nginx can't do it", "This nginx was built without the "
                                + "stub_status module (--with-http_stub_status_module), so the status page can't be "
                                + "turned on. Nothing was changed.");
                        return;
                    }
                    Set<Integer> inUse = new LinkedHashSet<>(StatusPageConfig.listenPorts(cfg));
                    inUse.addAll(facts.allPorts());
                    StatusPageConfig.Result result = StatusPageConfig.enable(cfg, port, inUse);
                    if (!result.ok()) {
                        Dialogs.error(window(), "Can't turn it on", result.problem());
                        return;
                    }
                    connection.configChanged();
                    Dialogs.info(window(), "Staged", "The status page is added as a pending change. Review and apply "
                            + "it under Pending Changes; nothing has changed on the server yet.");
                }));
    }

    private static String readBuild(SshSession session, String binary) {
        try {
            return NginxBuild.read(session, binary);
        } catch (IOException e) {
            return "";
        }
    }

    private void disable() {
        if (!Dialogs.confirm(window(), "Turn off the status page?", "The server NRM added for it is removed as a "
                + "pending change. Nothing changes on the server until you apply.", "Turn off")) {
            return;
        }
        if (StatusPageConfig.disable(config()) > 0) {
            connection.configChanged();
        }
    }

    // For tests.

    boolean isPolling() {
        return timer.getStatus() == Animation.Status.RUNNING;
    }

    String shownUrl() {
        return shown == null ? "" : shown.url();
    }

    String activeText() {
        return active.getText();
    }

    String messageText() {
        return message.getText();
    }

    /** Chooses how often to read (one of the offered intervals), starting the timer again if it is running. */
    void setEverySeconds(int seconds) {
        every.setValue(seconds);
        if (shown != null && !paused) {
            startPolling(false);
        }
    }
}
