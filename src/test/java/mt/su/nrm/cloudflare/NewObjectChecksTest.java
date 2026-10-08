package mt.su.nrm.cloudflare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class NewObjectChecksTest {

    @Test
    void realDomainsAreAccepted() {
        for (String ok : List.of("example.com", "Example.COM", "my-site.co.uk", "xn--bcher-kva.example", "a.io")) {
            assertTrue(NewObjectChecks.zoneProblems(ok, List.of()).isEmpty(), ok);
        }
    }

    @Test
    void notDomainsAreRefusedWithAReason() {
        for (String bad : List.of("", "  ", "localhost", "https://example.com", "example.com/path", "exa mple.com",
                "-bad.com", "bad-.com", "example.c", "example..com")) {
            assertFalse(NewObjectChecks.zoneProblems(bad, List.of()).isEmpty(), "'" + bad + "' should be refused");
        }
    }

    @Test
    void aDomainAlreadyInTheAccountIsRefusedIgnoringCase() {
        List<String> problems = NewObjectChecks.zoneProblems("EXAMPLE.com", List.of("example.com"));
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("already"));
    }

    @Test
    void tunnelNamesNeedToBeNewAndSensible() {
        assertTrue(NewObjectChecks.tunnelProblems("edge-2", List.of("edge-1")).isEmpty());
        assertFalse(NewObjectChecks.tunnelProblems("  ", List.of()).isEmpty());
        assertFalse(NewObjectChecks.tunnelProblems("Edge-1", List.of("edge-1")).isEmpty());
        assertFalse(NewObjectChecks.tunnelProblems("a".repeat(NewObjectChecks.MAX_TUNNEL_NAME + 1), List.of()).isEmpty());
        assertFalse(NewObjectChecks.tunnelProblems("bad\nname", List.of()).isEmpty());
    }
}
