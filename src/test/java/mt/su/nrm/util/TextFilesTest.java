package mt.su.nrm.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextFilesTest {

    @Test
    void plainTextRoundTripsByteForByte() throws IOException {
        byte[] original = "server {\n    listen 80;\n}\nümlaut\n".getBytes(StandardCharsets.UTF_8);
        TextFiles.Text t = TextFiles.decode(original);
        assertFalse(t.crlf());
        assertArrayEquals(original, TextFiles.encode(t.text(), t.crlf()));
    }

    @Test
    void windowsLineEndingsAreKept() throws IOException {
        byte[] original = "a\r\nb\r\n".getBytes(StandardCharsets.UTF_8);
        TextFiles.Text t = TextFiles.decode(original);
        assertTrue(t.crlf());
        assertEquals("a\nb\n", t.text());
        assertArrayEquals(original, TextFiles.encode(t.text(), t.crlf()));
    }

    @Test
    void noTrailingNewlineIsNotInvented() throws IOException {
        byte[] original = "one line".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(original, TextFiles.encode(TextFiles.decode(original).text(), false));
    }

    @Test
    void binaryNonUtf8AndHugeFilesAreRefused() {
        assertThrows(IOException.class, () -> TextFiles.decode(new byte[]{'a', 0, 'b'}));
        assertThrows(IOException.class, () -> TextFiles.decode(new byte[]{(byte) 0xff, (byte) 0xfe, 'x'}));
        assertThrows(IOException.class, () -> TextFiles.decode(new byte[(int) TextFiles.MAX_BYTES + 1]));
        assertNull(TextFiles.sizeProblem(10));
        assertTrue(TextFiles.sizeProblem(TextFiles.MAX_BYTES + 1) != null);
    }
}
