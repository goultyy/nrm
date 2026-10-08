package mt.su.nrm.nginx;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * Password file entries for nginx's {@code auth_basic_user_file}, in the Apache {@code apr1}
 * (MD5-based) format that nginx understands everywhere. Passwords are hashed here, on this
 * computer, so only the hashes are ever sent to a server.
 */
public final class Htpasswd {

    private static final String ALPHABET = "./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final Pattern USER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._@-]{0,63}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private Htpasswd() {
    }

    /**
     * Checks a user name for use in a password file.
     *
     * @param user the name to check, may be null
     * @return true if it is safe to write (1-64 characters: letters, digits and {@code . _ @ -}, starting with a
     *         letter or digit; so no colon, spaces or control characters), false otherwise
     */
    public static boolean isValidUser(String user) {
        return user != null && USER.matcher(user).matches();
    }

    /**
     * Builds one password file line with a fresh random salt.
     *
     * @param user     the user name; check it with {@link #isValidUser} first
     * @param password the clear-text password (not kept or logged)
     * @return {@code user:$apr1$salt$hash}
     */
    public static String entry(String user, String password) {
        return user + ":" + apr1(password, randomSalt());
    }

    /**
     * Hashes a password the way Apache's {@code htpasswd -m} and {@code openssl passwd -apr1} do.
     *
     * @param password the clear-text password, hashed as UTF-8
     * @param salt     the salt; only the first 8 characters are used
     * @return the hash, e.g. {@code $apr1$r31.....$HqJZimcKQFAMYayBlzkrA/}
     * @throws IllegalStateException if the JVM has no MD5 implementation
     */
    public static String apr1(String password, String salt) {
        byte[] pw = password.getBytes(StandardCharsets.UTF_8);
        byte[] s = salt.substring(0, Math.min(8, salt.length())).getBytes(StandardCharsets.UTF_8);
        byte[] magic = "$apr1$".getBytes(StandardCharsets.UTF_8);
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");

            md5.update(pw);
            md5.update(s);
            md5.update(pw);
            byte[] fin = md5.digest();

            md5.reset();
            md5.update(pw);
            md5.update(magic);
            md5.update(s);
            for (int pl = pw.length; pl > 0; pl -= 16) {
                md5.update(fin, 0, Math.min(16, pl));
            }
            for (int i = pw.length; i != 0; i >>= 1) {
                if ((i & 1) != 0) {
                    md5.update((byte) 0);
                } else {
                    md5.update(pw.length == 0 ? 0 : pw[0]);
                }
            }
            fin = md5.digest();

            for (int i = 0; i < 1000; i++) {
                md5.reset();
                if ((i & 1) != 0) {
                    md5.update(pw);
                } else {
                    md5.update(fin);
                }
                if (i % 3 != 0) {
                    md5.update(s);
                }
                if (i % 7 != 0) {
                    md5.update(pw);
                }
                if ((i & 1) != 0) {
                    md5.update(fin);
                } else {
                    md5.update(pw);
                }
                fin = md5.digest();
            }

            StringBuilder out = new StringBuilder("$apr1$").append(new String(s, StandardCharsets.UTF_8)).append('$');
            to64(out, ((fin[0] & 0xff) << 16) | ((fin[6] & 0xff) << 8) | (fin[12] & 0xff), 4);
            to64(out, ((fin[1] & 0xff) << 16) | ((fin[7] & 0xff) << 8) | (fin[13] & 0xff), 4);
            to64(out, ((fin[2] & 0xff) << 16) | ((fin[8] & 0xff) << 8) | (fin[14] & 0xff), 4);
            to64(out, ((fin[3] & 0xff) << 16) | ((fin[9] & 0xff) << 8) | (fin[15] & 0xff), 4);
            to64(out, ((fin[4] & 0xff) << 16) | ((fin[10] & 0xff) << 8) | (fin[5] & 0xff), 4);
            to64(out, fin[11] & 0xff, 2);
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is unavailable", e);
        }
    }

    private static void to64(StringBuilder out, int value, int chars) {
        int v = value;
        for (int i = 0; i < chars; i++) {
            out.append(ALPHABET.charAt(v & 0x3f));
            v >>= 6;
        }
    }

    private static String randomSalt() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
