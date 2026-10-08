package mt.su.nrm.ui;

import mt.su.nrm.logs.LogEntry;
import mt.su.nrm.logs.LogStats;
import mt.su.nrm.logs.LogStats.Counted;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.beans.property.SimpleStringProperty;

import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The analysis of a batch of access log lines: totals, how many failed, traffic over time, the busiest pages and
 * visitors, the slowest requests. Double-clicking a page, visitor or browser asks the log page to show only the lines
 * that mention it, so you can go from "who is hammering /login" to the actual requests.
 */
final class LogAnalysisView extends ScrollPane {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss");

    private final Consumer<String> onPick;
    private final Label message = new Label();
    private final Label summary = new Label();
    private final Label classes = new Label();
    private final BarChart<String, Number> chart;
    private final TableView<Counted> pages = counts("Page");
    private final TableView<Counted> failing = counts("Page that failed");
    private final TableView<Counted> visitors = counts("Visitor");
    private final TableView<Counted> agents = counts("Browser or program");
    private final TableView<java.util.Map.Entry<Integer, Long>> statuses = new TableView<>();
    private final TableView<LogEntry> slowest = new TableView<>();
    private final TableView<LogEntry> biggest = new TableView<>();
    private final VBox content = new VBox(12);
    private final GridPane grid = new GridPane();

    LogAnalysisView(Consumer<String> onPick) {
        this.onPick = onPick;

        CategoryAxis x = new CategoryAxis();
        NumberAxis y = new NumberAxis();
        y.setForceZeroInRange(true);
        y.setMinorTickVisible(false);
        y.setLabel("Requests");
        chart = new BarChart<>(x, y);
        chart.setAnimated(false);
        chart.setLegendVisible(true);
        chart.setCategoryGap(2);
        chart.setPrefHeight(210);
        chart.setMinHeight(170);

        summary.setWrapText(true);
        summary.setStyle("-fx-font-size: 13px;");
        classes.setWrapText(true);
        message.setWrapText(true);
        message.setOpacity(0.8);

        setupStatuses();
        setupEntries(slowest, true);
        setupEntries(biggest, false);
        wire(pages);
        wire(failing);
        wire(visitors);
        wire(agents);

        ColumnConstraints half = new ColumnConstraints();
        half.setPercentWidth(50);
        grid.getColumnConstraints().addAll(half, half);
        grid.setHgap(12);
        grid.setVgap(12);
        grid.add(section("Busiest pages", "Double-click one to see its requests", pages), 0, 0);
        grid.add(section("Pages with errors", "Requests that ended in 4xx or 5xx", failing), 1, 0);
        grid.add(section("Busiest visitors", "Double-click one to see their requests", visitors), 0, 1);
        grid.add(section("Browsers and programs", "Crawlers and scripts show up here", agents), 1, 1);
        grid.add(section("Slowest requests", "Needs request times in the log format (see Log Formats)", slowest), 0, 2);
        grid.add(section("Largest responses", "", biggest), 1, 2);
        grid.add(section("Status codes", "", statuses), 0, 3);

        content.setPadding(new Insets(12));
        content.getChildren().addAll(message, summary, classes, chart, grid);
        setContent(content);
        setFitToWidth(true);
        showMessage("Choose an access log to analyse.");
    }

    // ---------------------------------------------------------------- showing

    void showMessage(String text) {
        message.setText(text);
        message.setManaged(true);
        message.setVisible(true);
        summary.setText("");
        classes.setText("");
        summary.setManaged(false);
        classes.setManaged(false);
        chart.setVisible(false);
        chart.setManaged(false);
        grid.setVisible(false);
        grid.setManaged(false);
    }

    void show(LogStats s) {
        if (s.total == 0) {
            showMessage(s.unreadable == 0 ? "There are no lines to analyse."
                    : "None of the " + s.unreadable + " lines could be read with this format. Choose the format the log "
                    + "is written in, or leave it on automatic.");
            return;
        }
        message.setText(s.unreadable == 0 ? "" : s.unreadable + " line(s) did not match the format and were left out.");
        message.setManaged(s.unreadable > 0);
        message.setVisible(s.unreadable > 0);
        summary.setManaged(true);
        classes.setManaged(true);
        chart.setVisible(true);
        chart.setManaged(true);
        grid.setVisible(true);
        grid.setManaged(true);

        StringBuilder sum = new StringBuilder();
        sum.append(String.format(Locale.ROOT, "%,d requests", s.total));
        if (s.from != null) {
            sum.append(" from ").append(WHEN.format(s.from.atZone(ZoneId.systemDefault()))).append(" to ")
                    .append(WHEN.format(s.to.atZone(ZoneId.systemDefault())));
        }
        sum.append(String.format(Locale.ROOT, ".  %.1f%% failed.  %s sent.", s.errorRate() * 100, size(s.bytes)));
        if (s.automated > 0) {
            sum.append(String.format(Locale.ROOT, "  %,d (%.0f%%) look like crawlers or scripts.", s.automated,
                    100.0 * s.automated / s.total));
        }
        if (s.averageTime >= 0) {
            sum.append(String.format(Locale.ROOT, "  Average %.0f ms, 95%% under %.0f ms.", s.averageTime * 1000,
                    s.slowestPercentile * 1000));
        }
        summary.setText(sum.toString());
        StringBuilder cls = new StringBuilder();
        s.classes().forEach((k, v) -> cls.append(cls.length() == 0 ? "" : "     ").append(k).append(": ")
                .append(String.format(Locale.ROOT, "%,d", v)));
        classes.setText(cls.toString());

        fillChart(s);
        pages.setItems(FXCollections.observableArrayList(s.pages));
        failing.setItems(FXCollections.observableArrayList(s.failingPages));
        visitors.setItems(FXCollections.observableArrayList(s.visitors));
        agents.setItems(FXCollections.observableArrayList(s.agents));
        statuses.setItems(FXCollections.observableArrayList(s.byStatus.entrySet()));
        slowest.setItems(FXCollections.observableArrayList(s.slowest));
        biggest.setItems(FXCollections.observableArrayList(s.biggest));
    }

    private void fillChart(LogStats s) {
        XYChart.Series<String, Number> ok = new XYChart.Series<>();
        ok.setName("Requests");
        XYChart.Series<String, Number> bad = new XYChart.Series<>();
        bad.setName("Failed");
        boolean days = s.from != null && Duration.between(s.from, s.to).toHours() >= 36;
        DateTimeFormatter label = DateTimeFormatter.ofPattern(days ? "d MMM HH:mm" : "HH:mm:ss");
        for (LogStats.Bucket b : s.timeline) {
            String name = label.format(b.start().atZone(ZoneId.systemDefault()));
            ok.getData().add(new XYChart.Data<>(name, b.requests() - b.errors()));
            bad.getData().add(new XYChart.Data<>(name, b.errors()));
        }
        chart.getData().setAll(List.of(ok, bad));
        chart.setTitle("Traffic over the time covered");
    }

    // ---------------------------------------------------------------- building the tables

    private static TableView<Counted> counts(String keyTitle) {
        TableView<Counted> t = new TableView<>();
        TableColumn<Counted, String> key = new TableColumn<>(keyTitle);
        key.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().key()));
        key.setPrefWidth(260);
        TableColumn<Counted, String> n = new TableColumn<>("Requests");
        n.setCellValueFactory(c -> new SimpleStringProperty(String.format(Locale.ROOT, "%,d", c.getValue().count())));
        n.setPrefWidth(75);
        TableColumn<Counted, String> errors = new TableColumn<>("Failed");
        errors.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().errors() == 0 ? "" : String.format(Locale.ROOT, "%,d", c.getValue().errors())));
        errors.setPrefWidth(60);
        TableColumn<Counted, String> data = new TableColumn<>("Data");
        data.setCellValueFactory(c -> new SimpleStringProperty(size(c.getValue().bytes())));
        data.setPrefWidth(75);
        t.getColumns().addAll(List.of(key, n, errors, data));
        t.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        t.setPlaceholder(new Label("Nothing to show"));
        t.setPrefHeight(200);
        return t;
    }

    private void wire(TableView<Counted> table) {
        table.setOnMouseClicked(e -> {
            Counted picked = table.getSelectionModel().getSelectedItem();
            if (e.getClickCount() == 2 && picked != null) {
                onPick.accept(picked.key());
            }
        });
    }

    private void setupStatuses() {
        TableColumn<java.util.Map.Entry<Integer, Long>, String> code = new TableColumn<>("Status");
        code.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getKey() + "  " + meaning(c.getValue().getKey())));
        code.setPrefWidth(260);
        TableColumn<java.util.Map.Entry<Integer, Long>, String> n = new TableColumn<>("Requests");
        n.setCellValueFactory(c -> new SimpleStringProperty(String.format(Locale.ROOT, "%,d", c.getValue().getValue())));
        statuses.getColumns().addAll(List.of(code, n));
        statuses.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        statuses.setPrefHeight(200);
        statuses.setPlaceholder(new Label("Nothing to show"));
    }

    private void setupEntries(TableView<LogEntry> t, boolean byTime) {
        TableColumn<LogEntry, String> first = new TableColumn<>(byTime ? "Took" : "Size");
        first.setCellValueFactory(c -> new SimpleStringProperty(byTime
                ? String.format(Locale.ROOT, "%.0f ms", c.getValue().requestTime() * 1000) : size(c.getValue().bytes())));
        first.setPrefWidth(70);
        TableColumn<LogEntry, String> status = new TableColumn<>("Status");
        status.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().status() < 0 ? "" : String.valueOf(c.getValue().status())));
        status.setPrefWidth(55);
        TableColumn<LogEntry, String> uri = new TableColumn<>("Request");
        uri.setCellValueFactory(c -> new SimpleStringProperty(nullToEmpty(c.getValue().method()) + " " + nullToEmpty(c.getValue().uri())));
        uri.setPrefWidth(230);
        TableColumn<LogEntry, String> ip = new TableColumn<>("Visitor");
        ip.setCellValueFactory(c -> new SimpleStringProperty(nullToEmpty(c.getValue().ip())));
        ip.setPrefWidth(110);
        t.getColumns().addAll(List.of(first, status, uri, ip));
        t.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        t.setPlaceholder(new Label(byTime ? "No request times in this log" : "Nothing to show"));
        t.setPrefHeight(200);
        t.setOnMouseClicked(e -> {
            LogEntry picked = t.getSelectionModel().getSelectedItem();
            if (e.getClickCount() == 2 && picked != null && picked.uri() != null) {
                onPick.accept(picked.uri());
            }
        });
    }

    private static VBox section(String title, String hint, javafx.scene.Node table) {
        Label heading = new Label(title);
        heading.setStyle("-fx-font-weight: bold;");
        VBox box = new VBox(4, heading);
        if (!hint.isEmpty()) {
            Label h = new Label(hint);
            h.setOpacity(0.7);
            h.setWrapText(true);
            box.getChildren().add(h);
        }
        box.getChildren().add(table);
        GridPane.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    // ---------------------------------------------------------------- wording

    static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = bytes;
        int i = -1;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.ROOT, v >= 100 ? "%.0f %s" : "%.1f %s", v, units[i]);
    }

    static String meaning(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No content";
            case 206 -> "Partial content";
            case 301 -> "Moved permanently";
            case 302 -> "Found (temporary redirect)";
            case 304 -> "Not modified";
            case 307 -> "Temporary redirect";
            case 308 -> "Permanent redirect";
            case 400 -> "Bad request";
            case 401 -> "Unauthorised";
            case 403 -> "Forbidden";
            case 404 -> "Not found";
            case 405 -> "Method not allowed";
            case 408 -> "Request timeout";
            case 413 -> "Request too large";
            case 429 -> "Too many requests";
            case 499 -> "Client closed the request";
            case 500 -> "Server error";
            case 501 -> "Not implemented";
            case 502 -> "Bad gateway";
            case 503 -> "Service unavailable";
            case 504 -> "Gateway timeout";
            default -> "";
        };
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    // For tests.

    int rowsOf(String table) {
        return switch (table) {
            case "pages" -> pages.getItems().size();
            case "failing" -> failing.getItems().size();
            case "visitors" -> visitors.getItems().size();
            case "slowest" -> slowest.getItems().size();
            case "statuses" -> statuses.getItems().size();
            default -> -1;
        };
    }

    String summaryText() {
        return summary.getText();
    }

    String messageText() {
        return message.getText();
    }

    int chartBuckets() {
        return chart.getData().isEmpty() ? 0 : chart.getData().get(0).getData().size();
    }

    boolean showingResults() {
        return grid.isVisible();
    }
}
