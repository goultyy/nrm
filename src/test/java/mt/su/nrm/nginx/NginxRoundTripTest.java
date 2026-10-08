package mt.su.nrm.nginx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The parser is only safe to use on live servers if parse-then-generate gives back exactly what
 * was read. These tests hold it to that on real-world configs and on awkward formatting.
 */
class NginxRoundTripTest {

    static final List<String> CORPUS = List.of("ubuntu-nginx.conf", "ubuntu-default-site", "complex.conf",
            "confd-app.conf", "php-site.conf");

    static String resource(String name) throws IOException {
        try (InputStream in = NginxRoundTripTest.class.getResourceAsStream("/nginx/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void assertRoundTrip(String text) throws NginxParseException {
        ConfigFile file = ConfigFile.parse("/etc/nginx/x.conf", text);
        assertEquals(text, file.generate());
        assertFalse(file.isModified());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ubuntu-nginx.conf", "ubuntu-default-site", "complex.conf", "confd-app.conf"})
    void realWorldConfigsRoundTripExactly(String name) throws Exception {
        assertRoundTrip(resource(name));
    }

    @Test
    void windowsLineEndingsSurvive() throws Exception {
        for (String name : CORPUS) {
            assertRoundTrip(resource(name).replace("\n", "\r\n"));
        }
    }

    @Test
    void tabIndentationAndMissingFinalNewlineSurvive() throws Exception {
        for (String name : CORPUS) {
            String text = resource(name).replace("    ", "\t");
            assertRoundTrip(text);
            assertRoundTrip(text.stripTrailing());
        }
    }

    @Test
    void awkwardFormattingSurvives() throws Exception {
        assertRoundTrip("");
        assertRoundTrip("\n\n");
        assertRoundTrip("# only a comment");
        assertRoundTrip("server{listen 80;server_name a;}");
        assertRoundTrip("server {\nlisten\t80 ;\n   server_name a   # inline\n      b ;\n}\n");
        assertRoundTrip("  a  b ;  \n\n\n c;\n");
        assertRoundTrip("map $a $b {\n  default 0;\n  \"~^x[0-9]{2}$\" 1;\n}\n");
        assertRoundTrip("a \"quoted ; { } # text\" 'single ; \"' plain;\n");
        assertRoundTrip("set $x ${y}z;\n");
        assertRoundTrip("location ~ \\.php$ { }\n");
    }

    @Test
    void luaBodiesAreKeptOpaqueAndNotInterpreted() throws Exception {
        String text = "content_by_lua_block {\n  local t = {a = '}', b = \"{\"}\n  -- }\n  --[[ } ]]\n  x = [[}]]\n}\nnext_directive on;\n";
        ConfigFile file = ConfigFile.parse("x", text);
        assertEquals(text, file.generate());
        Block lua = (Block) file.root().children().get(0);
        assertTrue(lua.isOpaque());
        assertEquals("next_directive", file.root().children().get(1).name());
    }

    @Test
    void unknownDirectivesAreKeptAsDirectivesNotDropped() throws Exception {
        String text = "some_future_module_option  weird=value \"and quoted\";\nother_block arg { nested { deep 1; } }\n";
        ConfigFile file = ConfigFile.parse("x", text);
        assertEquals(text, file.generate());
        assertEquals("some_future_module_option", file.root().children().get(0).name());
        assertEquals(List.of("weird=value", "and quoted"), file.root().children().get(0).values());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "server {",                      // never closed
            "server { listen 80 }",          // missing semicolon
            "}",                             // stray brace
            "listen 80",                     // missing semicolon at end of file
            ";",                             // empty statement
            "a \"unterminated;",             // open string
            "{ a; }",                        // block without a name
    })
    void invalidSyntaxIsRejectedNotGuessedAt(String text) {
        assertThrows(NginxParseException.class, () -> ConfigFile.parse("x", text));
    }

    @Test
    void parseErrorsReportTheLine() {
        NginxParseException e = assertThrows(NginxParseException.class,
                () -> ConfigFile.parse("x", "a;\nb;\nserver {\n  listen 80\n}\n"));
        assertTrue(e.line() >= 4, e.getMessage());
    }

    @Test
    void valuesAreUnquotedButRawTextIsKept() throws Exception {
        ConfigFile file = ConfigFile.parse("x", "add_header X-A \"a b\" always;\n");
        Node d = file.root().children().get(0);
        assertEquals(List.of("X-A", "a b", "always"), d.values());
        assertEquals("\"a b\"", d.args.get(1).raw());
    }

    @Test
    void includePatternsAreFoundAtAnyDepth() throws Exception {
        ConfigFile file = ConfigFile.parse("/etc/nginx/nginx.conf", resource("ubuntu-nginx.conf"));
        assertEquals(List.of("/etc/nginx/modules-enabled/*.conf", "/etc/nginx/mime.types",
                "/etc/nginx/conf.d/*.conf", "/etc/nginx/sites-enabled/*"), file.includePatterns());
    }
}
