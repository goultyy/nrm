package mt.su.nrm.cloudflare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void parsesNestedDocument() {
        Map<String, Object> o = Json.asObject(Json.parse(
                " {\"a\": [1, 2.5, true, null, \"x\"], \"b\": {\"c\": \"d\"}} "));
        List<Object> a = Json.asList(o.get("a"));
        assertEquals(new BigDecimal("1"), a.get(0));
        assertEquals(new BigDecimal("2.5"), a.get(1));
        assertEquals(Boolean.TRUE, a.get(2));
        assertEquals(null, a.get(3));
        assertEquals("d", Json.asString(Json.asObject(o.get("b")).get("c")));
    }

    @Test
    void writeThenParseRoundTripsAwkwardStrings() {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("s", "quote\" back\\slash\nnewline\ttab \u0001 unicode \u00e9");
        o.put("list", List.of("a", "b"));
        o.put("n", 5);
        assertEquals(Json.asObject(Json.parse(Json.write(o))).get("s"), o.get("s"));
        assertEquals(Json.write(o), Json.write(Json.parse(Json.write(o))));
    }

    @Test
    void parsesUnicodeEscape() {
        assertEquals("\u00e9", Json.parse("\"\\u00e9\""));
    }

    @Test
    void preservesKeyOrderWhenWriting() {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("z", 1);
        o.put("a", 2);
        assertEquals("{\"z\":1,\"a\":2}", Json.write(o));
    }

    @Test
    void rejectsMalformedInput() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":1} x"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[1,]"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("\"unterminated"));
    }
}
