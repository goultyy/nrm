package mt.su.nrm.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TryFilesEditorTest {

    @Test
    void theCommonValueReadsAsASentence() {
        assertEquals("nginx looks for the file itself, then a folder of that name (and opens its index file). "
                + "If none exists, it answers with error 404 (Not Found).", TryFilesEditor.describe("$uri $uri/ =404"));
    }

    @Test
    void aSinglePageAppFallbackIsExplained() {
        String text = TryFilesEditor.describe("$uri $uri/ /index.html");
        assertTrue(text.contains("it serves /index.html instead"), text);
    }

    @Test
    void namedLocationsAndOddCandidatesAreHandled() {
        assertTrue(TryFilesEditor.describe("$uri.html @app").contains("named location @app"));
        assertTrue(TryFilesEditor.describe("$uri.html =404").contains("with \".html\" added"));
        assertTrue(TryFilesEditor.describe("$uri =500").contains("error 500"));
    }

    @Test
    void emptyAndIncompleteValuesAreExplainedToo() {
        assertTrue(TryFilesEditor.describe("").startsWith("nothing is checked first"));
        assertTrue(TryFilesEditor.describe("$uri").startsWith("needs at least one"));
        assertEquals(TryFilesEditor.describe("$uri   $uri/  =404"), TryFilesEditor.describe("$uri $uri/ =404"));
    }

    @Test
    void everyPresetIsValidAndDescribable() {
        for (var p : TryFilesEditor.STATIC_PRESETS) {
            if (p.value() != null && !p.value().isEmpty()) {
                assertTrue(!TryFilesEditor.describe(p.value()).startsWith("needs"), p.label());
            }
        }
    }
}
