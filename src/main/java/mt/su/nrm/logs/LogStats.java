package mt.su.nrm.logs;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a batch of access log lines adds up to: how many requests, how many failed, the busiest pages and visitors, the
 * slowest requests and how traffic moved over time. Pure arithmetic over {@link LogEntry}s.
 */
public final class LogStats {

    /** One row of a "top" list. {@code errors} counts the 4xx and 5xx among {@code count}. */
    public record Counted(String key, long count, long errors, long bytes) {
    }

    /** Requests in one slice of the time covered. */
    public record Bucket(Instant start, long requests, long errors) {
    }

    public final int total;
    public final int unreadable;
    public final long bytes;
    public final long success;
    public final long redirects;
    public final long clientErrors;
    public final long serverErrors;
    public final long automated;
    public final Instant from;
    public final Instant to;
    /** Mean and 95th percentile of request time in seconds, or -1 if the lines carry no timing. */
    public final double averageTime;
    public final double slowestPercentile;
    public final Map<Integer, Long> byStatus;
    public final List<Counted> pages;
    public final List<Counted> visitors;
    public final List<Counted> agents;
    public final List<Counted> failingPages;
    public final List<LogEntry> slowest;
    public final List<LogEntry> biggest;
    public final List<Bucket> timeline;

    private LogStats(List<LogEntry> entries, int unreadable, int top, int buckets) {
        this.total = entries.size();
        this.unreadable = unreadable;
        this.bytes = entries.stream().mapToLong(e -> Math.max(0, e.bytes())).sum();
        this.success = entries.stream().filter(e -> e.status() >= 200 && e.status() < 300).count();
        this.redirects = entries.stream().filter(e -> e.status() >= 300 && e.status() < 400).count();
        this.clientErrors = entries.stream().filter(e -> e.status() >= 400 && e.status() < 500).count();
        this.serverErrors = entries.stream().filter(e -> e.status() >= 500 && e.status() < 600).count();
        this.automated = entries.stream().filter(LogEntry::looksAutomated).count();

        Instant first = null;
        Instant last = null;
        for (LogEntry e : entries) {
            if (e.time() != null) {
                first = first == null || e.time().isBefore(first) ? e.time() : first;
                last = last == null || e.time().isAfter(last) ? e.time() : last;
            }
        }
        this.from = first;
        this.to = last;

        double[] times = entries.stream().mapToDouble(LogEntry::requestTime).filter(t -> t >= 0).sorted().toArray();
        this.averageTime = times.length == 0 ? -1 : java.util.Arrays.stream(times).average().orElse(-1);
        this.slowestPercentile = times.length == 0 ? -1 : times[(int) Math.min(times.length - 1, Math.ceil(times.length * 0.95) - 1)];

        Map<Integer, Long> statuses = new TreeMap<>();
        for (LogEntry e : entries) {
            if (e.status() > 0) {
                statuses.merge(e.status(), 1L, Long::sum);
            }
        }
        this.byStatus = statuses;

        this.pages = counted(entries, LogEntry::path, top, false);
        this.visitors = counted(entries, LogEntry::ip, top, false);
        this.agents = counted(entries, LogEntry::userAgent, top, false);
        this.failingPages = counted(entries, LogEntry::path, top, true);
        this.slowest = entries.stream().filter(e -> e.requestTime() >= 0)
                .sorted(Comparator.comparingDouble(LogEntry::requestTime).reversed()).limit(top).toList();
        this.biggest = entries.stream().filter(e -> e.bytes() > 0)
                .sorted(Comparator.comparingLong(LogEntry::bytes).reversed()).limit(top).toList();
        this.timeline = timeline(entries, first, last, buckets);
    }

    public static LogStats of(List<LogEntry> entries, int unreadable, int top, int buckets) {
        return new LogStats(entries, unreadable, top, buckets);
    }

    /** Reads every line with the parser and adds the result up. */
    public static LogStats ofLines(String text, LogParser parser, int top, int buckets) {
        List<LogEntry> entries = new ArrayList<>();
        int unreadable = 0;
        for (String line : text.split("\r?\n")) {
            if (line.isBlank()) {
                continue;
            }
            var entry = parser.parse(line);
            if (entry.isPresent()) {
                entries.add(entry.get());
            } else {
                unreadable++;
            }
        }
        return of(entries, unreadable, top, buckets);
    }

    /** Share of requests that failed (4xx and 5xx), 0 to 1. */
    public double errorRate() {
        return total == 0 ? 0 : (double) (clientErrors + serverErrors) / total;
    }

    // ---------------------------------------------------------------- helpers

    private static List<Counted> counted(List<LogEntry> entries, java.util.function.Function<LogEntry, String> key, int top,
                                         boolean errorsOnly) {
        Map<String, long[]> sums = new HashMap<>();
        for (LogEntry e : entries) {
            String k = key.apply(e);
            if (k == null || k.isEmpty() || (errorsOnly && !e.isError())) {
                continue;
            }
            long[] s = sums.computeIfAbsent(k, x -> new long[3]);
            s[0]++;
            s[1] += e.isError() ? 1 : 0;
            s[2] += Math.max(0, e.bytes());
        }
        return sums.entrySet().stream()
                .map(e -> new Counted(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2]))
                .sorted(Comparator.comparingLong(Counted::count).reversed().thenComparing(Counted::key))
                .limit(top).toList();
    }

    private static List<Bucket> timeline(List<LogEntry> entries, Instant first, Instant last, int buckets) {
        if (first == null || buckets < 1) {
            return List.of();
        }
        long span = Duration.between(first, last).toMillis();
        long width = Math.max(1000, (long) Math.ceil((double) (span + 1) / buckets));
        int count = (int) Math.min(buckets, span / width + 1);
        long[] requests = new long[count];
        long[] errors = new long[count];
        for (LogEntry e : entries) {
            if (e.time() == null) {
                continue;
            }
            int i = (int) Math.min(count - 1, Duration.between(first, e.time()).toMillis() / width);
            requests[i]++;
            errors[i] += e.isError() ? 1 : 0;
        }
        List<Bucket> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            result.add(new Bucket(first.plusMillis(i * width), requests[i], errors[i]));
        }
        return result;
    }

    /** The status classes as label to count, in order, for display. */
    public Map<String, Long> classes() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("2xx success", success);
        m.put("3xx redirect", redirects);
        m.put("4xx client error", clientErrors);
        m.put("5xx server error", serverErrors);
        return m;
    }
}
