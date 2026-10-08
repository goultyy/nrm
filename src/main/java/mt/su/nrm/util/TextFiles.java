package mt.su.nrm.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Decides whether a file is safe to edit as text and converts between its bytes and the text shown in an
 * editor. A file that is too big, binary or not UTF-8 is refused rather than opened and damaged on save.
 */
public final class TextFiles {

    /** Largest file the editor opens. */
    public static final long MAX_BYTES = 2_000_000;

    /**
     * @param text the content with {@code \n} line endings
     * @param crlf true if the file used {@code \r\n}, so saving writes them back
     */
    public record Text(String text, boolean crlf) {
    }

    private TextFiles() {
    }

    /** Why a file of this size can't be opened, or null if its size is fine. */
    public static String sizeProblem(long size) {
        return size > MAX_BYTES ? "This file is " + (size / 1024) + " KB; the editor opens text files up to "
                + (MAX_BYTES / 1000) + " KB." : null;
    }

    /** @throws IOException with a message for the user if the bytes are not editable text */
    public static Text decode(byte[] data) throws IOException {
        String problem = sizeProblem(data.length);
        if (problem != null) {
            throw new IOException(problem);
        }
        for (byte b : data) {
            if (b == 0) {
                throw new IOException("This looks like a binary file, not text, so it can't be edited here.");
            }
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("This file isn't valid UTF-8 text, so it can't be edited here without damaging it.");
        }
        if (text.startsWith("﻿")) {
            throw new IOException("This file starts with a byte-order mark, which the editor would drop on saving.");
        }
        boolean crlf = text.contains("\r\n");
        if (crlf) {
            text = text.replace("\r\n", "\n");
        }
        return new Text(text, crlf);
    }

    public static byte[] encode(String text, boolean crlf) {
        String out = crlf ? text.replace("\r\n", "\n").replace("\n", "\r\n") : text;
        return out.getBytes(StandardCharsets.UTF_8);
    }
}
