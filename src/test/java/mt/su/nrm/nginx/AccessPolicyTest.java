package mt.su.nrm.nginx;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AccessPolicyTest {

    private static AccessPolicy policy(AccessPolicy.Mode mode) {
        AccessPolicy p = new AccessPolicy();
        p.mode = mode;
        p.realm = "Staff";
        p.passwordFile = "/etc/nginx/htpasswd/site";
        p.networks = List.of("10.0.0.0/8", "203.0.113.7");
        return p;
    }

    @Test
    void everyModeSurvivesApplyThenRead() {
        for (AccessPolicy.Mode mode : AccessPolicy.Mode.values()) {
            LocationSettings l = new LocationSettings();
            policy(mode).applyTo(l);
            AccessPolicy back = AccessPolicy.from(l);
            assertEquals(mode, back.mode, mode.name());
            if (mode.usesPassword()) {
                assertEquals("Staff", back.realm);
                assertEquals("/etc/nginx/htpasswd/site", back.passwordFile);
            }
            if (mode.usesNetworks()) {
                assertEquals(List.of("10.0.0.0/8", "203.0.113.7"), back.networks);
                assertEquals("deny all", l.accessRules.get(l.accessRules.size() - 1));
            }
        }
    }

    @Test
    void satisfyAnyOnlyForEither() {
        LocationSettings l = new LocationSettings();
        policy(AccessPolicy.Mode.EITHER).applyTo(l);
        assertEquals("any", l.satisfy);
        policy(AccessPolicy.Mode.BOTH).applyTo(l);
        assertEquals("", l.satisfy);
    }

    @Test
    void everyoneClearsAccessButKeepsAnExplicitAuthOff() {
        LocationSettings l = new LocationSettings();
        policy(AccessPolicy.Mode.BOTH).applyTo(l);
        policy(AccessPolicy.Mode.EVERYONE).applyTo(l);
        assertEquals("", l.authBasic);
        assertTrue(l.accessRules.isEmpty());
        l.authBasic = "off";
        policy(AccessPolicy.Mode.EVERYONE).applyTo(l);
        assertEquals("off", l.authBasic);
    }

    @Test
    void problemsAreReported() {
        AccessPolicy p = policy(AccessPolicy.Mode.EITHER);
        assertTrue(p.problems().isEmpty());
        p.networks = List.of("999.1.1.1", "10.0.0.0/33", "banana");
        assertEquals(3, p.problems().size());
        p.networks = List.of();
        assertFalse(p.problems().isEmpty());
        p = policy(AccessPolicy.Mode.PASSWORD);
        p.passwordFile = "relative/file";
        p.realm = "a\"b";
        assertEquals(2, p.problems().size());
        assertTrue(AccessPolicy.isNetwork("::1"));
        assertTrue(AccessPolicy.isNetwork("2001:db8::/32"));
    }
}
