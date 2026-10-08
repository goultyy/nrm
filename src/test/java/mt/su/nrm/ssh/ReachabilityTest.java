package mt.su.nrm.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mt.su.nrm.ssh.Reachability.Result;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReachabilityTest {

    @Test
    void anOpenPortConnectsAndAClosedOneSaysWhy() throws Exception {
        try (ServerSocket open = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int closed;
            try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                closed = probe.getLocalPort();
            }
            List<Result> results = Reachability.check("127.0.0.1", List.of(open.getLocalPort(), closed), new CommandLog(),
                    Reachability.tcp());
            assertTrue(results.get(0).open());
            assertFalse(results.get(1).open());
            assertFalse(results.get(1).detail().isBlank());
        }
    }

    @Test
    void everyAttemptIsWrittenToTheCommandLogBeforeItIsMade() {
        CommandLog log = new CommandLog();
        List<String> order = new ArrayList<>();
        Reachability.check("203.0.113.9", List.of(80, 443), log, (h, p, t) -> {
            order.add("connect " + h + ":" + p + " after " + log.snapshot().size() + " log lines");
            if (p == 443) {
                throw new IOException("Connection timed out");
            }
        });
        assertEquals(List.of("connect 203.0.113.9:80 after 1 log lines", "connect 203.0.113.9:443 after 3 log lines"), order);
        assertTrue(log.snapshot().stream().anyMatch(l -> l.text().contains("port 443 did not connect: Connection timed out")));
        assertTrue(log.snapshot().get(0).text().contains("no data is sent"));
    }

    @Test
    void portsAreDeDuplicatedCappedAndRangeChecked() {
        List<Integer> asked = new ArrayList<>();
        List<Integer> many = new ArrayList<>(List.of(0, 70000, -1, 80, 80));
        for (int i = 1000; i < 1040; i++) {
            many.add(i);
        }
        List<Result> results = Reachability.check("203.0.113.9", many, new CommandLog(), (h, p, t) -> asked.add(p));
        assertEquals(16, results.size(), "at most 16 attempts, and invalid ports don't use them up");
        assertEquals(16, asked.size());
        assertFalse(asked.contains(0) || asked.contains(70000) || asked.contains(-1));
        assertEquals(1, asked.stream().filter(p -> p == 80).count());
    }
}
