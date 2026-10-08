package mt.su.nrm.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LineEntriesTest {

    @Test
    void rewritesRoundTrip() {
        for (String line : List.of("rewrite ^/old/(.*)$ /new/$1 permanent", "rewrite ^/a$ /b", "return 301 https://$host$request_uri",
                "return 403", "return 200 \"hello world\"")) {
            var r = LineEntries.parseRewrite(line);
            assertNotNull(r, line);
            assertEquals(line, LineEntries.formatRewrite(r), line);
            assertTrue(LineEntries.rewriteProblems(r).isEmpty(), line);
        }
        assertEquals(LineEntries.RewriteKind.REDIRECT, LineEntries.parseRewrite("return 302 /x").kind());
        assertNull(LineEntries.parseRewrite("set $a 1"));
        assertNull(LineEntries.parseRewrite("rewrite ^/a$ /b sideways"));
        assertEquals("rewrite ^/a$ /b sideways", LineEntries.describeRewrite("rewrite ^/a$ /b sideways"));
        assertFalse(LineEntries.rewriteProblems(LineEntries.Rewrite.rewrite("(", "/x", "")).isEmpty());
    }

    @Test
    void limitReqKeepsUnknownTokens() {
        var l = LineEntries.parseLimitReq("zone=perip burst=10 nodelay");
        assertEquals("perip", l.zone());
        assertEquals("zone=perip burst=10 nodelay", LineEntries.formatLimitReq(l));
        assertEquals("zone=a burst=5 delay=2", LineEntries.formatLimitReq(LineEntries.parseLimitReq("zone=a burst=5 delay=2")));
        assertEquals("zone=a status=429".replace(" status=429", "") + " status=429",
                LineEntries.formatLimitReq(LineEntries.parseLimitReq("zone=a status=429")));
        assertNull(LineEntries.parseLimitReq("burst=3"));
        assertFalse(LineEntries.limitReqProblems("", "x").isEmpty());
    }

    @Test
    void limitConnAccessLogAndErrorPageRoundTrip() {
        assertEquals("perip 10", LineEntries.formatLimitConn(LineEntries.parseLimitConn("perip 10")));
        assertNull(LineEntries.parseLimitConn("perip"));
        for (String line : List.of("off", "/var/log/nginx/a.log", "/var/log/nginx/a.log main buffer=32k gzip")) {
            assertEquals(line, LineEntries.formatAccessLog(LineEntries.parseAccessLog(line)), line);
        }
        assertFalse(LineEntries.accessLogProblems(LineEntries.parseAccessLog("relative.log")).isEmpty());
        for (String line : List.of("404 /404.html", "500 502 503 504 /50x.html", "404 =200 /empty.gif", "403 @denied",
                "404 https://example.com/missing")) {
            assertEquals(line, LineEntries.formatErrorPage(LineEntries.parseErrorPage(line)), line);
            assertTrue(LineEntries.errorPageProblems(LineEntries.parseErrorPage(line)).isEmpty(), line);
        }
        assertNull(LineEntries.parseErrorPage("/only-a-page"));
        assertTrue(LineEntries.describeErrorPage("404 /404.html").contains("404 (Not Found)"));
    }

    @Test
    void listenLinesRoundTripAndAreDescribed() {
        for (String line : List.of("80", "443 ssl http2", "[::]:443 ssl http2", "80 default_server", "127.0.0.1:8080",
                "443 ssl default_server reuseport", "192.0.2.10:443 ssl")) {
            var l = LineEntries.parseListen(line);
            assertNotNull(l, line);
            assertEquals(line, LineEntries.formatListen(l), line);
            assertTrue(LineEntries.listenProblems(l).isEmpty(), line);
        }
        assertNull(LineEntries.parseListen("unix:/run/nginx.sock"));
        assertEquals("80", LineEntries.formatListen(LineEntries.parseListen("*:80")));
        assertTrue(LineEntries.describeListen("443 ssl http2 default_server").contains("no other site"));
        assertFalse(LineEntries.listenProblems(new LineEntries.Listen("", "70000", false, false, false, List.of())).isEmpty());
        assertFalse(LineEntries.listenProblems(new LineEntries.Listen("", "80", false, true, false, List.of())).isEmpty());
    }
}
