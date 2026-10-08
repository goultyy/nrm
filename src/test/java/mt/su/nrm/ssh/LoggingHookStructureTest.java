package mt.su.nrm.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guards the rule that nothing reaches a server without passing the logging hook. The hook is
 * {@link SshSession}; these tests fail if code is added that could go around it.
 */
class LoggingHookStructureTest {

    private static final Path MAIN = Path.of("src", "main", "java");

    private static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test
    void onlySshjTransportTouchesTheSshLibrary() throws IOException {
        for (Path file : sources()) {
            String name = file.getFileName().toString();
            if (name.equals("SshjTransport.java")) {
                continue;
            }
            assertFalse(Files.readString(file).contains("net.schmizz"), name + " must not use sshj directly");
        }
    }

    @Test
    void theTransportIsNotVisibleOutsideTheSshPackage() {
        assertFalse(Modifier.isPublic(SshTransport.class.getModifiers()));
        assertFalse(Modifier.isPublic(SshjTransport.class.getModifiers()));
    }

    @Test
    void sessionHasNoPublicConstructorAndNoTransportAccessor() {
        assertTrue(SshSession.class.getConstructors().length == 0);
        for (var method : SshSession.class.getMethods()) {
            assertFalse(method.getReturnType().getSimpleName().contains("Transport"), method.getName());
        }
    }

    @Test
    void everyPublicOperationOnASessionIsCoveredByATest() {
        // If a public method is added to SshSession, add it here and to SshSessionTest, so the
        // new operation is checked for logging too. (isOpen only reads a flag; it sends nothing.)
        List<String> covered = List.of("exec", "execPrivileged", "upload", "download", "delete", "close", "log", "isOpen",
                "connect", "sftpList", "sftpResolve", "sftpMkdir", "sftpCreateFile", "sftpRename", "sftpRmdir",
                "sftpUploadFile", "sftpDownloadFile");
        for (var method : SshSession.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())) {
                assertTrue(covered.contains(method.getName()),
                        "SshSession." + method.getName() + " is new: make sure it logs, then list it here");
            }
        }
        assertEquals(0, SshSession.class.getConstructors().length);
    }

    @Test
    void noOtherPackageOpensSocketsOrRunsProcesses() throws IOException {
        for (Path file : sources()) {
            if (file.toString().contains("mt" + java.io.File.separator + "su" + java.io.File.separator + "nrm" + java.io.File.separator + "ssh")) {
                continue;
            }
            String text = Files.readString(file);
            String name = file.getFileName().toString();
            assertFalse(text.contains("java.net.Socket"), name);
            assertFalse(text.contains("ProcessBuilder"), name);
            assertFalse(text.contains("Runtime.getRuntime().exec"), name);
        }
    }
}
