package mt.su.nrm.util;

/** Small string checks shared by validators. */
public final class Text {

    private Text() {
    }

    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** True if the string contains ASCII control characters (newline, NUL, escape, DEL, ...). */
    public static boolean hasControlChars(String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasWhitespace(String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
