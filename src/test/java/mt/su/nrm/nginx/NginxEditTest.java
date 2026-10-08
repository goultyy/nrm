package mt.su.nrm.nginx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Editing the tree must change only what was touched, and keep comments and layout around it. */
class NginxEditTest {

    private static ConfigFile parse(String text) throws NginxParseException {
        return ConfigFile.parse("x", text);
    }

    @Test
    void changingArgumentsRewritesOnlyThatStatement() throws Exception {
        ConfigFile file = parse("server {\n    listen   80;   # http\n    server_name  a.com;\n}\n");
        Block server = file.serverBlocks().get(0);
        server.first("server_name").setArgs(List.of("a.com", "b.com"));

        assertEquals("server {\n    listen   80;   # http\n    server_name a.com b.com;\n}\n", file.generate());
        assertTrue(file.isModified());
    }

    @Test
    void settingTheSameValuesKeepsTheOriginalText() throws Exception {
        ConfigFile file = parse("server {\n    listen   80;\n    add_header X \"a  b\";\n}\n");
        Block server = file.serverBlocks().get(0);
        server.first("listen").setArgs(List.of("80"));
        server.first("add_header").setArgs(List.of("X", "a  b"));
        assertFalse(file.isModified());
    }

    @Test
    void insertAtEndKeepsTheTrailingCommentWithItsStatement() throws Exception {
        ConfigFile file = parse("server {\n    listen 80;   # http\n}\n");
        file.serverBlocks().get(0).add(Directive.create("server_name", List.of("a.com")));
        assertEquals("server {\n    listen 80;   # http\n    server_name a.com;\n}\n", file.generate());
    }

    @Test
    void insertInTheMiddleKeepsCommentsAboveTheNextStatement() throws Exception {
        ConfigFile file = parse("server {\n    listen 80;\n\n    # the root\n    root /x;\n}\n");
        Block server = file.serverBlocks().get(0);
        server.insert(1, Directive.create("server_name", List.of("a.com")));
        assertEquals("server {\n    listen 80;\n    server_name a.com;\n\n    # the root\n    root /x;\n}\n",
                file.generate());
    }

    @Test
    void insertUsesTheIndentationOfTheExistingStatements() throws Exception {
        ConfigFile file = parse("http {\n\tserver {\n\t\tlisten 80;\n\t}\n}\n");
        file.serverBlocks().get(0).add(Directive.create("root", List.of("/x")));
        assertEquals("http {\n\tserver {\n\t\tlisten 80;\n\t\troot /x;\n\t}\n}\n", file.generate());
    }

    @Test
    void insertIntoAnEmptyBlockIndentsOneLevelIn() throws Exception {
        ConfigFile file = parse("http {\n    server {\n    }\n}\n");
        file.serverBlocks().get(0).add(Directive.create("listen", List.of("80")));
        assertEquals("http {\n    server {\n        listen 80;\n    }\n}\n", file.generate());
    }

    @Test
    void newBlocksGetTheirOwnClosingBraceAndIndentedChildren() throws Exception {
        ConfigFile file = parse("server {\n    listen 80;\n}\n");
        Block server = file.serverBlocks().get(0);
        Block location = Block.create("location", List.of("/api"));
        server.add(location);
        location.add(Directive.create("proxy_pass", List.of("http://127.0.0.1:3000")));
        assertEquals("server {\n    listen 80;\n    location /api {\n        proxy_pass http://127.0.0.1:3000;\n    }\n}\n",
                file.generate());
        // And the result is valid syntax that parses back to the same thing.
        assertEquals(file.generate(), ConfigFile.parse("x", file.generate()).generate());
    }

    @Test
    void removingAStatementTakesItsCommentsButNotTheNeighboursTrailingComment() throws Exception {
        ConfigFile file = parse("a;  # about a\n# about b\nb;\nc;\n");
        Block root = file.root();
        root.remove(root.first("b"));
        assertEquals("a;  # about a\nc;\n", file.generate());
    }

    @Test
    void removingTheLastStatementKeepsTheClosingBraceOnItsOwnLine() throws Exception {
        ConfigFile file = parse("server {\n    a;  # note\n    b;\n}\n");
        Block server = file.serverBlocks().get(0);
        server.remove(server.first("b"));
        assertEquals("server {\n    a;  # note\n}\n", file.generate());
    }

    @Test
    void valuesNeedingQuotesAreQuotedAndReadBackIdentically() {
        for (String v : List.of("plain", "with space", "semi;colon", "has\"double", "has'single", "both\"and'",
                "brace{", "", "#hash", "back\\slash", "ends\\")) {
            assertEquals(v, Arg.of(v).value(), "value: " + v);
        }
        assertEquals("plain", Arg.of("plain").raw());
        assertEquals("\"with space\"", Arg.of("with space").raw());
    }

    @Test
    void injectionThroughValuesIsNeutralised() throws Exception {
        ConfigFile file = parse("server {\n    listen 80;\n}\n");
        Block server = file.serverBlocks().get(0);
        server.add(Directive.create("add_header", List.of("X", "v\"; } server { listen 1; #")));
        ConfigFile reparsed = parse(file.generate());
        // Still exactly one server block with one add_header holding the original text.
        assertEquals(1, reparsed.serverBlocks().size());
        assertEquals(List.of("X", "v\"; } server { listen 1; #"),
                reparsed.serverBlocks().get(0).first("add_header").values());
    }

    @Test
    void parseValuesKeepsQuotedPartsTogether() {
        assertEquals(List.of("a", "b c", "d"), Arg.parseValues("a \"b c\" d"));
        assertEquals(List.of(), Arg.parseValues("   "));
        assertEquals(List.of("zone=x", "burst=5"), Arg.parseValues(" zone=x   burst=5 "));
    }
}
