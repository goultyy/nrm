package mt.su.nrm.logs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mt.su.nrm.logformat.LogFormatDesign;
import mt.su.nrm.logformat.LogFormatPresets;
import mt.su.nrm.nginx.LogFormatSettings;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LogAnalysisTest {

    private static final String COMBINED = "203.0.113.7 - - [07/Oct/2026:22:15:01 +0100] \"GET /index.html?id=7 HTTP/1.1\" "
            + "200 1234 \"https://example.org/start\" \"Mozilla/5.0 (Windows NT 10.0) Firefox/131.0\"";

    private static LogEntry parse(LogParser p, String line) {
        return p.parse(line).orElseThrow(() -> new AssertionError("not parsed: " + line));
    }

    // ---------------------------------------------------------------- parsing

    @Test
    void theStandardFormatIsReadFieldByField() {
        LogEntry e = parse(LogParser.auto(), COMBINED);
        assertEquals("203.0.113.7", e.ip());
        assertEquals(Instant.parse("2026-10-07T21:15:01Z"), e.time(), "+0100 is converted to UTC");
        assertEquals("GET", e.method());
        assertEquals("/index.html?id=7", e.uri());
        assertEquals("/index.html", e.path());
        assertEquals(200, e.status());
        assertEquals(1234, e.bytes());
        assertEquals(-1, e.requestTime());
        assertEquals("https://example.org/start", e.referrer());
        assertTrue(e.userAgent().contains("Firefox"));
        assertNull(LogParser.auto().parse(COMBINED.replace("\"https://example.org/start\"", "\"-\"")).get().referrer(),
                "a dash means nothing was logged");
    }

    @Test
    void anAuthenticatedUserOrAnEmptyBodyDoesNotConfuseIt() {
        LogEntry e = parse(LogParser.auto(), "10.0.0.1 - alice [07/Oct/2026:22:15:01 +0000] \"POST /login HTTP/1.1\" 302 - \"-\" \"curl/8.4.0\"");
        assertEquals("POST", e.method());
        assertEquals(-1, e.bytes());
        assertTrue(e.looksAutomated());
    }

    @Test
    void theTimingExtrasOfTheTimingPresetAreReadToo() {
        LogFormatDesign timing = LogFormatPresets.all().get(1).design().get();
        LogEntry fromPreview = parse(LogParser.auto(), timing.preview());
        assertEquals(200, fromPreview.status());
        assertTrue(fromPreview.requestTime() >= 0, "rt= at the end gives the request time");
        LogEntry e = parse(LogParser.auto(), COMBINED + " rt=1.250 uct=\"0.001\" urt=\"1.249\"");
        assertEquals(1.25, e.requestTime(), 1e-9);
    }

    @Test
    void jsonLinesAreReadWhateverTheKeysAreCalled() {
        LogEntry e = parse(LogParser.auto(), "{\"time\":\"2026-10-07T22:15:01+01:00\",\"remote_addr\":\"198.51.100.9\","
                + "\"method\":\"GET\",\"uri\":\"/api/items?x=1\",\"status\":503,\"bytes\":77,\"request_time\":0.5,"
                + "\"user_agent\":\"Googlebot/2.1\",\"referrer\":\"-\",\"host\":\"a.com\"}");
        assertEquals("198.51.100.9", e.ip());
        assertEquals(Instant.parse("2026-10-07T21:15:01Z"), e.time());
        assertEquals("/api/items", e.path());
        assertEquals(503, e.status());
        assertEquals(0.5, e.requestTime(), 1e-9);
        assertEquals("a.com", e.host());
        assertTrue(e.looksAutomated());
        assertEquals(List.of(), List.of(LogParser.auto().parse("{not json").stream().toArray()), "broken JSON is unreadable");
    }

    @Test
    void theJsonPresetsOwnOutputIsReadBack() {
        LogFormatDesign json = LogFormatPresets.all().stream().filter(p -> p.id().equals("json")).findFirst().orElseThrow()
                .design().get();
        LogEntry e = parse(LogParser.forFormat(json.toSettings("j")), json.preview());
        assertEquals("203.0.113.7", e.ip());
        assertEquals(200, e.status());
        assertEquals("/index.html", e.path());
    }

    @Test
    void aCustomTextFormatBecomesItsOwnParser() {
        LogFormatSettings s = new LogFormatSettings();
        s.text = "$time_iso8601|$remote_addr|$request_method $request_uri|$status|$request_time|$host";
        LogEntry e = parse(LogParser.forFormat(s), "2026-10-07T21:15:01+00:00|192.0.2.4|PUT /v1/x?y=1|201|0.042|b.org");
        assertEquals("192.0.2.4", e.ip());
        assertEquals("PUT", e.method());
        assertEquals("/v1/x", e.path());
        assertEquals(201, e.status());
        assertEquals(0.042, e.requestTime(), 1e-9);
        assertEquals("b.org", e.host());
        assertTrue(LogParser.forFormat(s).parse(COMBINED).isEmpty(), "another format's line is not guessed at");
    }

    @Test
    void everyTextPresetReadsItsOwnSampleLine() {
        for (LogFormatPresets.Preset p : LogFormatPresets.all()) {
            if (p.id().equals("blank")) {
                continue;
            }
            LogFormatDesign d = p.design().get();
            LogEntry e = parse(LogParser.forFormat(d.toSettings("x")), d.preview());
            assertEquals(200, e.status(), p.id());
            // Behind a proxy the visitor is the forwarded address, not the proxy that connected.
            assertEquals(p.id().equals("proxy") ? "198.51.100.4" : "203.0.113.7", e.ip(), p.id());
        }
    }

    @Test
    void theRealVisitorIsPreferredWhenTheFormatLogsOne() {
        LogFormatSettings s = new LogFormatSettings();
        s.text = "$remote_addr ($http_x_forwarded_for) \"$request\" $status";
        LogParser p = LogParser.forFormat(s);
        assertEquals("203.0.113.50", parse(p, "10.0.0.2 (203.0.113.50, 10.1.1.1) \"GET / HTTP/1.1\" 200").ip());
        assertEquals("10.0.0.2", parse(p, "10.0.0.2 (-) \"GET / HTTP/1.1\" 200").ip());
        LogFormatSettings cf = new LogFormatSettings();
        cf.text = "$remote_addr cf=$http_cf_connecting_ip \"$request\" $status";
        assertEquals("198.51.100.1", parse(LogParser.forFormat(cf), "172.16.0.1 cf=198.51.100.1 \"GET / HTTP/1.1\" 200").ip());
    }

    @Test
    void garbageIsUnreadableNotGuessed() {
        for (String junk : List.of("", "   ", "hello world", "\u0016\u0003\u0001 binary", "1.2.3.4 - - [bad time] \"GET / HTTP/1.1\" 200 1 \"-\" \"-\"x")) {
            LogEntry e = LogParser.auto().parse(junk).orElse(null);
            if (e != null) {
                assertEquals(-1, e.status(), "only a line with a real shape may come back: " + junk);
            }
        }
        assertTrue(LogParser.auto().parse("hello world").isEmpty());
    }

    // ---------------------------------------------------------------- statistics

    private static String line(String ip, String path, int status, int bytes, String agent, int second) {
        return ip + " - - [07/Oct/2026:22:15:" + String.format("%02d", second) + " +0000] \"GET " + path + " HTTP/1.1\" "
                + status + " " + bytes + " \"-\" \"" + agent + "\"";
    }

    @Test
    void theNumbersAddUp() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            lines.add(line("1.1.1.1", "/a?x=" + i, 200, 100, "Firefox", i));
        }
        lines.add(line("2.2.2.2", "/b", 404, 10, "Firefox", 10));
        lines.add(line("2.2.2.2", "/b", 404, 10, "Googlebot", 11));
        lines.add(line("3.3.3.3", "/c", 500, 0, "curl/8", 20));
        lines.add(line("3.3.3.3", "/old", 301, 0, "Firefox", 30));
        lines.add("not a log line");
        LogStats s = LogStats.ofLines(String.join("\n", lines), LogParser.auto(), 5, 4);

        assertEquals(10, s.total);
        assertEquals(1, s.unreadable);
        assertEquals(6, s.success);
        assertEquals(1, s.redirects);
        assertEquals(2, s.clientErrors);
        assertEquals(1, s.serverErrors);
        assertEquals(0.3, s.errorRate(), 1e-9);
        assertEquals(2, s.automated);
        assertEquals(620, s.bytes);
        assertEquals("/a", s.pages.get(0).key(), "queries are folded into one page");
        assertEquals(6, s.pages.get(0).count());
        assertEquals("1.1.1.1", s.visitors.get(0).key());
        assertEquals(List.of("/b", "/c"), s.failingPages.stream().map(LogStats.Counted::key).toList());
        assertEquals(2L, s.failingPages.get(0).count());
        assertEquals(Long.valueOf(6), s.byStatus.get(200));
        assertEquals(List.of(200, 301, 404, 500), List.copyOf(s.byStatus.keySet()));
        assertEquals("Firefox", s.agents.get(0).key());
    }

    @Test
    void theTimelineCoversTheWholeSpanAndLosesNoRequest() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            lines.add(line("1.1.1.1", "/", i % 10 == 0 ? 500 : 200, 1, "x", i));
        }
        LogStats s = LogStats.ofLines(String.join("\n", lines), LogParser.auto(), 5, 5);
        assertEquals(5, s.timeline.size());
        assertEquals(50, s.timeline.stream().mapToLong(LogStats.Bucket::requests).sum());
        assertEquals(5, s.timeline.stream().mapToLong(LogStats.Bucket::errors).sum());
        assertEquals(Instant.parse("2026-10-07T22:15:00Z"), s.from);
        assertEquals(Instant.parse("2026-10-07T22:15:49Z"), s.to);
    }

    @Test
    void slowRequestsAndPercentilesNeedTimingsAndSayNothingWithout() {
        LogStats none = LogStats.ofLines(COMBINED, LogParser.auto(), 5, 5);
        assertEquals(-1, none.averageTime);
        assertTrue(none.slowest.isEmpty());

        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            lines.add(COMBINED + " rt=" + (i / 100.0));
        }
        LogStats s = LogStats.ofLines(String.join("\n", lines), LogParser.auto(), 3, 5);
        assertEquals(0.505, s.averageTime, 1e-9);
        assertEquals(0.95, s.slowestPercentile, 1e-9);
        assertEquals(List.of(1.0, 0.99, 0.98), s.slowest.stream().map(LogEntry::requestTime).toList());
    }

    @Test
    void emptyInputGivesEmptyResultsNotErrors() {
        LogStats s = LogStats.ofLines("", LogParser.auto(), 5, 10);
        assertEquals(0, s.total);
        assertEquals(0, s.errorRate());
        assertTrue(s.timeline.isEmpty() && s.pages.isEmpty() && s.byStatus.isEmpty());
        assertNull(s.from);
        assertFalse(s.classes().isEmpty());
    }

    @Test
    void aHugeLogIsAnalysedQuickly() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            sb.append(line("10.0." + (i % 200) + "." + (i % 250), "/p" + (i % 500), i % 17 == 0 ? 404 : 200, i % 4000,
                    "Mozilla", i % 60)).append('\n');
        }
        long start = System.nanoTime();
        LogStats s = LogStats.ofLines(sb.toString(), LogParser.auto(), 10, 30);
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertEquals(20_000, s.total);
        assertTrue(ms < 3000, "took " + ms + " ms");
    }
}
