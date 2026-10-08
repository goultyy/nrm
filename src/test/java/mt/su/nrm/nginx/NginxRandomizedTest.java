package mt.su.nrm.nginx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mt.su.nrm.nginx.VhostSettings.HeaderSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Randomised checks with fixed seeds (so any failure is reproducible): generated configs with
 * messy formatting must round-trip exactly, applying unchanged settings must change nothing, and
 * random edits must always produce valid syntax that reads back as intended.
 */
class NginxRandomizedTest {

    private static final String[] ARGS = {"80", "on", "off", "a.example.com", "/var/www/html", "\"quoted arg\"",
            "'single q'", "$uri", "${var}x", "~*", "^/(a|b)$", "/etc/nginx/x.conf", "http://127.0.0.1:3000",
            "zone=z:10m", "\"semi;colon\"", "\"{brace}\"", "'#not comment'", "a\\.b"};
    private static final String[] MANAGED = {"listen", "server_name", "root", "index", "ssl_certificate",
            "ssl_certificate_key", "client_max_body_size", "access_log", "error_log", "add_header", "limit_req",
            "error_page", "limit_conn"};
    private static final String[] UNMANAGED = {"gzip", "charset", "error_page", "proxy_read_timeout", "set",
            "auth_basic", "future_directive"};

    private static String gap(Random r) {
        String[] gaps = {" ", "  ", "\t", " \n        ", " # inline\n    "};
        return gaps[r.nextInt(gaps.length)];
    }

    private static String indentFor(Random r, int depth) {
        return (r.nextInt(6) == 0 ? "\t" : "    ").repeat(depth);
    }

    private static String statement(Random r, String name, int depth) {
        StringBuilder sb = new StringBuilder(name);
        int n = 1 + r.nextInt(3);
        for (int i = 0; i < n; i++) {
            sb.append(gap(r)).append(ARGS[r.nextInt(ARGS.length)]);
        }
        sb.append(r.nextInt(5) == 0 ? " ;" : ";");
        return sb.toString();
    }

    private static String between(Random r, int depth) {
        StringBuilder sb = new StringBuilder();
        int blank = r.nextInt(3) == 0 ? 1 : 0;
        for (int i = 0; i < blank; i++) {
            sb.append('\n');
        }
        if (r.nextInt(4) == 0) {
            sb.append('\n').append(indentFor(r, depth)).append("# comment ").append(r.nextInt(100)).append(" } ; {");
        }
        if (r.nextInt(6) == 0) {
            sb.append("   # trailing");
        }
        sb.append('\n').append(indentFor(r, depth));
        return sb.toString();
    }

    private static String location(Random r, int depth) {
        String[] heads = {"/", "/api", "= /health", "~* \\.(png|jpg)$", "^~ /static/", "@named"};
        String[] bodies = {"proxy_pass http://127.0.0.1:3000;", "root /srv/x;", "return 301 https://x.example.com;",
                "try_files $uri =404;", "gzip on;"};
        StringBuilder sb = new StringBuilder("location ").append(heads[r.nextInt(heads.length)]).append(" {");
        // Directives the editor manages inside locations, with random argument counts and formatting.
        String[] managed = {"auth_basic", "auth_basic_user_file", "allow", "deny", "limit_req", "limit_conn", "gzip",
                "gzip_types", "proxy_cache", "proxy_cache_valid", "proxy_read_timeout", "error_page",
                "gzip_min_length", "gzip_comp_level"};
        int n = r.nextInt(6);
        for (int i = 0; i < n; i++) {
            sb.append(between(r, depth + 1));
            sb.append(r.nextInt(3) == 0 ? statement(r, managed[r.nextInt(managed.length)], depth + 1)
                    : bodies[r.nextInt(bodies.length)]);
        }
        sb.append('\n').append(indentFor(r, depth)).append('}');
        return sb.toString();
    }

    private static String server(Random r) {
        StringBuilder sb = new StringBuilder("server {");
        int n = 1 + r.nextInt(12);
        for (int i = 0; i < n; i++) {
            sb.append(between(r, 1));
            int kind = r.nextInt(10);
            if (kind < 4) {
                sb.append(statement(r, MANAGED[r.nextInt(MANAGED.length)], 1));
            } else if (kind < 7) {
                sb.append(statement(r, UNMANAGED[r.nextInt(UNMANAGED.length)], 1));
            } else if (kind < 9) {
                sb.append(location(r, 1));
            } else {
                sb.append("if ($x = 1) {").append(between(r, 2)).append("return 403;").append('\n').append("    }");
            }
        }
        sb.append('\n').append(r.nextInt(5) == 0 ? "   # end\n" : "").append("}");
        return sb.toString();
    }

    private static String config(Random r) {
        StringBuilder sb = new StringBuilder();
        if (r.nextBoolean()) {
            sb.append("# generated\n");
        }
        sb.append("http {");
        int servers = 1 + r.nextInt(3);
        for (int i = 0; i < servers; i++) {
            sb.append("\n\n    ").append(server(r).replace("\n", "\n    "));
        }
        sb.append("\n}\n");
        String text = sb.toString();
        if (r.nextInt(4) == 0) {
            text = text.replace("\n", "\r\n");
        }
        if (r.nextInt(4) == 0) {
            text = text.stripTrailing();
        }
        return text;
    }

    @Test
    void generatedConfigsRoundTripExactlyAndNoOpApplyChangesNothing() throws Exception {
        for (long seed = 1; seed <= 400; seed++) {
            String text = config(new Random(seed));
            ConfigFile file;
            try {
                file = ConfigFile.parse("x", text);
            } catch (NginxParseException e) {
                throw new AssertionError("seed " + seed + " did not parse: " + e.getMessage() + "\n" + text, e);
            }
            assertEquals(text, file.generate(), "seed " + seed);
            for (Block server : file.serverBlocks()) {
                VirtualHost host = new VirtualHost(file, server);
                host.apply(host.read());
            }
            assertEquals(text, file.generate(), "no-op apply, seed " + seed + "\n"
                    + UnifiedDiff.diff("before", "after", text, file.generate()));
        }
    }

    @Test
    void randomEditsAlwaysProduceValidSyntaxThatReadsBackAsIntended() throws Exception {
        for (long seed = 1; seed <= 400; seed++) {
            Random r = new Random(seed);
            String text = config(r);
            ConfigFile file = ConfigFile.parse("x", text);
            List<Block> servers = file.serverBlocks();
            Block target = servers.get(r.nextInt(servers.size()));
            VirtualHost host = new VirtualHost(file, target);
            VhostSettings s = host.read();
            int unmanagedBefore = host.unmanaged().size();

            s.serverNames.add("added" + seed + ".example.com");
            if (r.nextBoolean() && !s.headers.isEmpty()) {
                s.headers.remove(r.nextInt(s.headers.size()));
            }
            s.headers.add(new HeaderSpec("X-Seed", "value " + seed + "; \"quoted\"", r.nextBoolean()));
            s.clientMaxBodySize = r.nextBoolean() ? "" : (1 + r.nextInt(50)) + "m";
            if (!s.locations.isEmpty() && r.nextBoolean()) {
                s.locations.remove(r.nextInt(s.locations.size()));
            }
            LocationSettings added = LocationSettings.newProxy("/added" + seed, "http://127.0.0.1:" + (3000 + seed));
            s.locations.add(added);
            VhostSettings expected = s.copy();
            host.apply(s);

            String out = file.generate();
            ConfigFile reparsed;
            try {
                reparsed = ConfigFile.parse("x", out);
            } catch (NginxParseException e) {
                throw new AssertionError("seed " + seed + " produced invalid syntax: " + e.getMessage() + "\n" + out, e);
            }
            assertEquals(out, reparsed.generate(), "seed " + seed);
            assertEquals(servers.size(), reparsed.serverBlocks().size(), "seed " + seed);

            int index = servers.indexOf(target);
            VirtualHost after = new VirtualHost(reparsed, reparsed.serverBlocks().get(index));
            VhostSettings actual = after.read();
            assertEquals(expected.serverNames, actual.serverNames, "names, seed " + seed);
            assertEquals(expected.headers.size(), actual.headers.size(), "headers, seed " + seed);
            for (int i = 0; i < expected.headers.size(); i++) {
                assertEquals(expected.headers.get(i).name, actual.headers.get(i).name, "seed " + seed);
                assertEquals(expected.headers.get(i).value, actual.headers.get(i).value, "seed " + seed);
                assertEquals(expected.headers.get(i).always, actual.headers.get(i).always, "seed " + seed);
            }
            assertEquals(expected.clientMaxBodySize.isEmpty(), actual.clientMaxBodySize.isEmpty(), "seed " + seed);
            assertEquals(expected.locations.size(), actual.locations.size(), "locations, seed " + seed);
            assertEquals("http://127.0.0.1:" + (3000 + seed),
                    actual.locations.get(actual.locations.size() - 1).proxyPass, "seed " + seed);
            // Statements the editor doesn't manage are never lost.
            assertEquals(unmanagedBefore, after.unmanaged().size(), "unmanaged, seed " + seed);
            // Other servers in the file are untouched.
            for (int i = 0; i < servers.size(); i++) {
                if (i != index) {
                    assertEquals(generateBlock(servers.get(i)), generateBlock(reparsed.serverBlocks().get(i)),
                            "other server " + i + ", seed " + seed);
                }
            }
        }
    }

    private static String generateBlock(Block block) {
        StringBuilder sb = new StringBuilder();
        block.emit(sb);
        return sb.toString();
    }

    @Test
    void generatorActuallyCoversTheInterestingCases() {
        // Guard against the generator silently degenerating into trivial input.
        int withLocations = 0;
        int withCrlf = 0;
        int withComments = 0;
        List<String> samples = new ArrayList<>();
        for (long seed = 1; seed <= 400; seed++) {
            String t = config(new Random(seed));
            samples.add(t);
            withLocations += t.contains("location") ? 1 : 0;
            withCrlf += t.contains("\r\n") ? 1 : 0;
            withComments += t.contains("# comment") ? 1 : 0;
        }
        assertTrue(withLocations > 200, "locations: " + withLocations);
        assertTrue(withCrlf > 40, "crlf: " + withCrlf);
        assertTrue(withComments > 100, "comments: " + withComments);
        assertFalse(samples.stream().allMatch(samples.get(0)::equals));
    }
}
