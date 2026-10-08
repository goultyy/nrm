package mt.su.nrm.logformat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mt.su.nrm.cloudflare.Json;
import mt.su.nrm.logformat.LogFormatDesign.Element;
import mt.su.nrm.logformat.LogFormatDesign.Style;
import mt.su.nrm.model.ConfigLayout;
import mt.su.nrm.nginx.CacheStatsLogging;
import mt.su.nrm.nginx.ConfigFile;
import mt.su.nrm.nginx.LogFormat;
import mt.su.nrm.nginx.LogFormatSettings;
import mt.su.nrm.nginx.LogFormatSettings.Escape;
import mt.su.nrm.nginx.NginxParseException;
import mt.su.nrm.nginx.RemoteConfig;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LogFormatTest {

    private static LogFormatSettings settings(String text, Escape escape) {
        LogFormatSettings s = new LogFormatSettings();
        s.name = "x";
        s.text = text;
        s.escape = escape;
        return s;
    }

    private static RemoteConfig config(String http) throws NginxParseException {
        ConfigFile main = ConfigFile.parse("/etc/nginx/nginx.conf", "events {}\nhttp {\n" + http + "}\n");
        return new RemoteConfig("/etc/nginx", ConfigLayout.CONF_D, main);
    }

    // ---------------------------------------------------------------- the catalog

    @Test
    void theCatalogIsConsistent() {
        Set<String> seen = new HashSet<>();
        for (LogField f : LogFields.all()) {
            assertTrue(LogFields.VARIABLE.matcher(f.variable()).matches(), f.variable());
            assertTrue(seen.add(f.variable()), "duplicate " + f.variable());
            assertFalse(f.title().isBlank() || f.description().isBlank() || f.sample().isBlank(), f.variable());
            assertTrue(LogFields.categories().contains(f.category()), f.variable() + " is in an unlisted group");
            assertTrue(f.defaultKey().matches("[A-Za-z_][A-Za-z0-9_.-]*"), f.variable() + " -> " + f.defaultKey());
            if (f.numeric()) {
                assertTrue(f.sample().matches("[0-9.]+"), f.variable() + " is numeric but its sample is not");
            }
        }
        assertTrue(LogFields.all().size() >= 30);
    }

    @Test
    void variablesAreFoundWithOrWithoutBraces() {
        assertEquals("$status", LogFields.find("${status}").orElseThrow().variable());
        assertTrue(LogFields.find("$not_a_real_one").isEmpty());
        assertEquals("$http_x_request_id", LogFields.requestHeader("X-Request-Id"));
        assertEquals("(header value)", LogFields.sample("$http_x_anything"));
    }

    @Test
    void aFormatStringIsSplitIntoTextAndVariables() {
        List<String[]> tokens = LogFields.tokens("a=$x \"${y}\" $50");
        assertEquals("text", tokens.get(0)[0]);
        assertEquals("variable", tokens.get(1)[0]);
        assertEquals("$x", tokens.get(1)[1]);
        assertEquals("${y}", tokens.get(3)[1]);
        assertEquals("text", tokens.get(tokens.size() - 1)[0], "$50 is not a variable name, so it stays text");
    }

    // ---------------------------------------------------------------- presets

    @Test
    void everyPresetIsValidAndTheStandardOneIsExactlyNginxsOwn() {
        for (LogFormatPresets.Preset p : LogFormatPresets.all()) {
            LogFormatDesign d = p.design().get();
            if (!p.id().equals("blank")) {
                assertTrue(d.problems("fmt", Set.of()).isEmpty(), p.id() + ": " + d.problems("fmt", Set.of()));
            }
        }
        LogFormatDesign combined = LogFormatPresets.all().get(0).design().get();
        assertEquals("$remote_addr - $remote_user [$time_local] \"$request\" $status $body_bytes_sent "
                + "\"$http_referer\" \"$http_user_agent\"", combined.text());
        assertEquals("203.0.113.7 - - [07/Oct/2026:22:15:01 +0100] \"GET /index.html?id=7 HTTP/1.1\" 200 1234 "
                + "\"https://example.org/start\" \"Mozilla/5.0 (Windows NT 10.0; Win64; x64) Firefox/131.0\"",
                combined.preview());
    }

    @Test
    void presetsAreFreshEachTimeSoEditingOneNeverChangesTheNext() {
        LogFormatPresets.Preset p = LogFormatPresets.all().get(0);
        LogFormatDesign first = p.design().get();
        first.elements().clear();
        assertFalse(p.design().get().elements().isEmpty());
    }

    @Test
    void theJsonPresetMakesRealJsonWithNumbersUnquoted() {
        LogFormatDesign d = LogFormatPresets.all().stream().filter(p -> p.id().equals("json")).findFirst()
                .orElseThrow().design().get();
        assertEquals(Escape.JSON, d.escape());
        Map<String, Object> line = Json.asObject(Json.parse(d.preview()));
        assertEquals("203.0.113.7", line.get("remote_addr"));
        assertTrue(line.get("status") instanceof java.math.BigDecimal, "status must be a JSON number");
        assertTrue(line.get("user_agent") instanceof String);
        assertTrue(d.text().startsWith("{\"time\":\"$time_iso8601\""), d.text());
    }

    @Test
    void aJsonLogOfEveryFieldInTheCatalogIsStillValidJson() {
        LogFormatDesign d = LogFormatDesign.blank();
        d.style(Style.JSON);
        for (LogField f : LogFields.all()) {
            d.elements().add(Element.field(f.variable()).withKey(f.name()));
        }
        assertTrue(d.problems("all", Set.of()).isEmpty(), d.problems("all", Set.of()).toString());
        Map<String, Object> line = Json.asObject(Json.parse(d.preview()));
        assertEquals(LogFields.all().size(), line.size());
    }

    // ---------------------------------------------------------------- reading existing formats back into rows

    @Test
    void everyTextPresetReadsBackToTheSameTextAndRows() {
        for (LogFormatPresets.Preset p : LogFormatPresets.all()) {
            if (p.id().equals("json") || p.id().equals("blank")) {
                continue;
            }
            LogFormatDesign d = p.design().get();
            LogFormatDesign again = LogFormatDesign.from(d.toSettings("x"));
            assertEquals(d.text(), again.text(), p.id());
            assertEquals(d.elements(), again.elements(), p.id());
        }
    }

    @Test
    void aJsonFormatIsReadBackKeepingKeysAndWhichValuesWereQuoted() {
        LogFormatDesign d = LogFormatDesign.from(settings("{\"s\":$status,\"ua\":\"$http_user_agent\", \"t\": \"$time_iso8601\"}",
                Escape.JSON));
        assertEquals(Style.JSON, d.style());
        assertEquals(List.of("s", "ua", "t"), d.elements().stream().map(Element::key).toList());
        assertEquals(List.of(false, true, true), d.elements().stream().map(Element::quoted).toList());
        assertEquals("{\"s\":$status,\"ua\":\"$http_user_agent\",\"t\":\"$time_iso8601\"}", d.text());
        assertEquals(Escape.JSON, d.toSettings("x").escape);
    }

    @Test
    void aJsonEscapedFormatThatIsNotAPlainObjectIsKeptAsTextWithItsEscaping() {
        LogFormatDesign d = LogFormatDesign.from(settings("{\"a\":\"$status and more text\"}", Escape.JSON));
        assertEquals(Style.TEXT, d.style());
        assertEquals(Escape.JSON, d.escape());
        assertEquals("{\"a\":\"$status and more text\"}", d.text(), "nothing may be lost reading it back");
    }

    @Test
    void anyFormatSurvivesBeingReadBackAndWrittenAgain() {
        for (String text : List.of("", "plain", "$a$b", "${a}x", "x $ y", "\"$a\" '$b' \\ $c;", "$a  $b\t$c")) {
            assertEquals(text, LogFormatDesign.from(settings(text, Escape.DEFAULT)).text(), text);
        }
    }

    // ---------------------------------------------------------------- changing style

    @Test
    void switchingStyleKeepsTheFieldsAndDropsThePunctuation() {
        LogFormatDesign d = LogFormatDesign.from(settings("$remote_addr [$time_local] \"$request\" $status", Escape.DEFAULT));
        d.style(Style.JSON);
        assertEquals("{\"remote_addr\":\"$remote_addr\",\"time_local\":\"$time_local\",\"request\":\"$request\","
                + "\"status\":$status}", d.text());
        d.style(Style.TEXT);
        assertEquals("$remote_addr $time_local $request $status", d.text());
    }

    // ---------------------------------------------------------------- checking

    @Test
    void namesAreChecked() {
        LogFormatDesign d = LogFormatPresets.all().get(0).design().get();
        assertTrue(d.problems("my_format-2", Set.of()).isEmpty());
        for (String bad : List.of("", " ", "9lives", "has space", "a=b", "x;", "-dash")) {
            assertFalse(d.problems(bad, Set.of()).isEmpty(), "'" + bad + "'");
        }
        assertTrue(d.problems("main", Set.of("main")).get(0).contains("already exists"));
    }

    @Test
    void aFormatNeedsAFieldAndSafeText() {
        LogFormatDesign d = LogFormatDesign.blank();
        assertFalse(d.problems("x", Set.of()).isEmpty(), "empty");
        d.elements().add(Element.text("only text"));
        assertFalse(d.problems("x", Set.of()).isEmpty(), "text alone says nothing");
        d.elements().add(Element.field("$status"));
        assertTrue(d.problems("x", Set.of()).isEmpty());
        d.elements().add(Element.text("cost $5 and $abc"));
        assertTrue(d.problems("x", Set.of()).stream().anyMatch(p -> p.contains("dollar sign")), "a $name in text is a variable");
        d.elements().remove(d.elements().size() - 1);
        d.elements().add(Element.text("two\nlines"));
        assertTrue(d.problems("x", Set.of()).stream().anyMatch(p -> p.contains("one line")));
    }

    @Test
    void jsonKeysMustBeUsableAndUnique() {
        LogFormatDesign d = LogFormatDesign.blank();
        d.style(Style.JSON);
        d.elements().add(Element.field("$status").withKey("a"));
        d.elements().add(Element.field("$host").withKey("a"));
        assertTrue(d.problems("x", Set.of()).stream().anyMatch(p -> p.contains("used twice")));
        d.elements().set(1, Element.field("$host").withKey("bad key"));
        assertTrue(d.problems("x", Set.of()).stream().anyMatch(p -> p.contains("can't be used as a JSON key")));
    }

    @Test
    void warningsExplainWhatIsRiskyWithoutBlocking() {
        LogFormatDesign d = LogFormatDesign.from(settings("$realip_remote_addr $my_own_var $http_x_trace", Escape.NONE));
        assertTrue(d.problems("combined", Set.of()).isEmpty());
        String warnings = String.join("\n", d.warnings("combined"));
        assertTrue(warnings.contains("real-IP module"), warnings);
        assertTrue(warnings.contains("$my_own_var is not one of the common variables"), warnings);
        assertFalse(warnings.contains("$http_x_trace is not"), "header variables are built from the name, so they are fine");
        assertTrue(warnings.contains("replaces it"), warnings);
        assertTrue(warnings.contains("no escaping"), warnings);
    }

    // ---------------------------------------------------------------- in the configuration

    @Test
    void aFormatIsWrittenAndReadBackThroughTheRealConfigurationText() throws Exception {
        for (String text : List.of(LogFormatPresets.COMBINED_TEXT, "it's \"quoted\" and a \\ backslash $status",
                "{\"a\":\"$remote_addr\"}", "trailing backslash \\")) {
            for (Escape escape : Escape.values()) {
                RemoteConfig config = config("");
                LogFormat created = config.createLogFormat("fmt");
                created.apply(settings(text, escape).copy());
                LogFormatSettings s = settings(text, escape);
                s.name = "fmt";
                created.apply(s);

                String written = config.mainFile().generate();
                RemoteConfig reread = new RemoteConfig("/etc/nginx", ConfigLayout.CONF_D,
                        ConfigFile.parse("/etc/nginx/nginx.conf", written));
                LogFormatSettings back = reread.logFormats().get(0).read();
                assertEquals(text, back.text, "text differs after a round trip: " + written);
                assertEquals(escape, back.escape, written);
                assertEquals("fmt", back.name);
            }
        }
    }

    @Test
    void jsonEscapingIsWrittenAsTheSecondArgumentWhichIsWhereNginxWantsIt() throws Exception {
        RemoteConfig config = config("");
        LogFormat f = config.createLogFormat("j");
        LogFormatSettings s = settings("{\"a\":\"$status\"}", Escape.JSON);
        s.name = "j";
        f.apply(s);
        assertTrue(config.mainFile().generate().contains("log_format j escape=json "), config.mainFile().generate());
    }

    @Test
    void severalStringArgumentsAreReadAsOne() throws Exception {
        RemoteConfig config = config("    log_format wide '$remote_addr [$time_local]'\n                    ' \"$request\" $status';\n");
        assertEquals("$remote_addr [$time_local] \"$request\" $status", config.logFormats().get(0).read().text);
    }

    @Test
    void aNewFormatGoesBeforeEverythingThatCouldUseIt() throws Exception {
        RemoteConfig config = config("    log_format existing '$status';\n    access_log /var/log/a.log existing;\n"
                + "    server { listen 80; }\n");
        LogFormat created = config.createLogFormat("later");
        LogFormatSettings s = settings("$host", Escape.DEFAULT);
        s.name = "later";
        created.apply(s);
        String out = config.mainFile().generate();
        assertTrue(out.indexOf("log_format later") > out.indexOf("log_format existing"), out);
        assertTrue(out.indexOf("log_format later") < out.indexOf("access_log"), "nginx reads formats in order: " + out);

        RemoteConfig empty = config("    server { listen 80; }\n");
        empty.createLogFormat("first");
        String out2 = empty.mainFile().generate();
        assertTrue(out2.indexOf("log_format first") < out2.indexOf("server"), out2);
    }

    @Test
    void aFormatCanBeDeletedAndTheRestIsLeftExactly() throws Exception {
        RemoteConfig config = config("    server { listen 80; }\n");
        String before = config.mainFile().generate();
        LogFormat f = config.createLogFormat("temp");
        assertEquals(1, config.logFormats().size());
        config.deleteLogFormat(f);
        assertEquals(before, config.mainFile().generate());
        assertTrue(config.logFormats().isEmpty());
    }

    @Test
    void whereAFormatIsUsedIsReportedForEveryLevel() throws Exception {
        RemoteConfig config = config("    log_format fmt '$status';\n    access_log /var/log/h.log fmt;\n"
                + "    server { listen 80; server_name a.com; access_log /var/log/a.log fmt;\n"
                + "      location /x { access_log /var/log/x.log fmt gzip; } }\n"
                + "    server { listen 81; server_name b.com; access_log /var/log/b.log combined; }\n");
        List<String> uses = config.logFormatUses("fmt");
        assertEquals(3, uses.size(), uses.toString());
        assertTrue(uses.stream().anyMatch(u -> u.startsWith("http level")), uses.toString());
        assertTrue(uses.stream().anyMatch(u -> u.startsWith("server a.com in")), uses.toString());
        assertTrue(uses.stream().anyMatch(u -> u.contains("location /x")), uses.toString());
        assertTrue(config.logFormatUses("combined").size() == 1 && config.logFormatUses("nope").isEmpty());
    }

    @Test
    void theFormatTheCacheStatisticsFeatureAddsIsMarkedAsManaged() throws Exception {
        RemoteConfig config = config("    log_format " + CacheStatsLogging.FORMAT + " '" + CacheStatsLogging.FORMAT_TEXT
                + "';\n    log_format mine '$status';\n");
        List<LogFormat> formats = config.logFormats();
        assertTrue(formats.stream().filter(f -> f.name().equals(CacheStatsLogging.FORMAT)).findFirst().orElseThrow().isManaged());
        assertFalse(formats.stream().filter(f -> f.name().equals("mine")).findFirst().orElseThrow().isManaged());
    }
}
