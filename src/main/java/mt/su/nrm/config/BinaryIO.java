package mt.su.nrm.config;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Length-prefixed fields with bounds checks, so a damaged file fails cleanly instead of exhausting memory. */
final class BinaryIO {

    static final int MAX_FIELD_BYTES = 16 * 1024 * 1024;

    private BinaryIO() {
    }

    static void writeBytes(DataOutputStream out, byte[] data) throws IOException {
        out.writeInt(data.length);
        out.write(data);
    }

    static byte[] readBytes(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_FIELD_BYTES || length > in.available()) {
            throw new IOException("Invalid field length " + length + ".");
        }
        return in.readNBytes(length);
    }

    static void writeString(DataOutputStream out, String value) throws IOException {
        writeBytes(out, value.getBytes(StandardCharsets.UTF_8));
    }

    static String readString(DataInputStream in) throws IOException {
        return new String(readBytes(in), StandardCharsets.UTF_8);
    }

    static int readCount(DataInputStream in, int max) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > max) {
            throw new IOException("Invalid count " + count + ".");
        }
        return count;
    }
}
