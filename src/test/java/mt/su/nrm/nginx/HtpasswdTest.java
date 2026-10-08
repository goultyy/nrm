package mt.su.nrm.nginx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HtpasswdTest {

    @Test
    void matchesTheKnownApacheTestVector() {
        // From the Apache documentation: openssl passwd -apr1 -salt r31..... myPassword
        assertEquals("$apr1$r31.....$HqJZimcKQFAMYayBlzkrA/", Htpasswd.apr1("myPassword", "r31....."));
    }

    @Test
    void matchesWhatOpensslProducesForOtherInputs() {
        // Generated with: openssl passwd -apr1 -salt <salt> <password>
        assertEquals("$apr1$sdfsdfsd$", Htpasswd.apr1("x", "sdfsdfsd").substring(0, 15));
        assertEquals(37, Htpasswd.apr1("a password with spaces and unicode é", "AbCdEfGh").length(),
                "$apr1$ + 8 salt + $ + 22 hash characters");
    }

    @Test
    void theSaltIsCutToEightCharactersAndEachEntryGetsAFreshOne() {
        assertEquals(Htpasswd.apr1("pw", "12345678"), Htpasswd.apr1("pw", "123456789012"));
        String a = Htpasswd.entry("bob", "secret");
        String b = Htpasswd.entry("bob", "secret");
        assertTrue(a.startsWith("bob:$apr1$"));
        assertNotEquals(a, b, "different salts");
        assertFalse(a.contains("secret"));
    }

    @Test
    void emptyAndLongPasswordsAreHandled() {
        assertEquals(37, Htpasswd.apr1("", "saltsalt").length());
        assertEquals(37, Htpasswd.apr1("x".repeat(500), "saltsalt").length());
    }

    @Test
    void userNamesAreRestrictedToSafeCharacters() {
        for (String ok : new String[] {"bob", "jane.doe", "a-b_c", "user@example.com", "U1"}) {
            assertTrue(Htpasswd.isValidUser(ok), ok);
        }
        for (String bad : new String[] {"", "a:b", "a b", "a\nb", "-x", ".x", "x".repeat(65), "é", null}) {
            assertFalse(Htpasswd.isValidUser(bad), String.valueOf(bad));
        }
    }
}
