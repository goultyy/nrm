package mt.su.nrm.ssh;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AuthServiceTest {

    private static final String ENTRY = "bob:$apr1$abcdefgh$AAAAAAAAAAAAAAAAAAAAAA";

    private static AuthService.PasswordFileRequest req(String path, boolean replace, String group) {
        return new AuthService.PasswordFileRequest(path, replace, List.of(ENTRY), group);
    }

    @Test
    void pathsOutsideTheConfigFolderOrOfConfigTypesAreRefused() {
        assertTrue(AuthService.problems("/etc/nginx", req("/etc/nginx/htpasswd/site", true, "www-data")).isEmpty());
        for (String bad : List.of("/etc/passwd", "/etc/nginx/../shadow", "/etc/nginx/x.conf", "/etc/nginx/nginx.conf",
                "relative", "/etc/nginx/a b", "/etc/nginx/x;rm")) {
            assertFalse(AuthService.problems("/etc/nginx", req(bad, true, "")).isEmpty(), bad);
        }
    }

    @Test
    void scriptIsAtomicAndSafe() {
        String s = AuthService.script(req("/etc/nginx/htpasswd/site", false, "www-data"), "n1");
        assertTrue(s.contains("umask 027"));
        assertTrue(s.contains("chmod a+x \"$D\""), "the folder must be traversable by the nginx worker");
        assertTrue(s.contains("mv "));
        assertTrue(s.contains("@@NRM-n1 DONE"));
        assertTrue(s.contains("www-data"));
        assertTrue(s.contains("640"));
        assertTrue(s.contains("644"), "falls back when the group doesn't exist");
        assertTrue(s.contains("awk"), "adding must replace an existing user exactly");
    }
}
