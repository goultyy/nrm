package mt.su.nrm.ssh;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Whether an address can be reached from this computer: a plain TCP connection to each port, nothing sent. It shows
 * whether the firewall or security group lets the outside in, which the server cannot tell about itself. Each attempt
 * is written to the command log, so the app never contacts anything without showing it.
 */
public final class Reachability {

    /** The result for one port: {@code detail} says why when it did not connect. */
    public record Result(int port, boolean open, String detail) {
    }

    /** Opens one connection; replaceable so the checks can be tested without a network. */
    @FunctionalInterface
    public interface Connector {
        void connect(String host, int port, int timeoutMillis) throws IOException;
    }

    private static final int TIMEOUT_MILLIS = 4000;
    private static final int MAX_PORTS = 16;

    private Reachability() {
    }

    public static Connector tcp() {
        return (host, port, timeout) -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), timeout);
            }
        };
    }

    /** Tries each port in turn (at most 16). Blocking: run off the JavaFX thread. */
    public static List<Result> check(String host, List<Integer> ports, CommandLog log, Connector connector) {
        List<Result> results = new ArrayList<>();
        for (int port : ports.stream().filter(p -> p >= 1 && p <= 65535).distinct().limit(MAX_PORTS).toList()) {
            log.log(CommandLog.Kind.COMMAND, "Connect from this computer to " + host + " port " + port + " (no data is sent)");
            try {
                connector.connect(host, port, TIMEOUT_MILLIS);
                results.add(new Result(port, true, "connected"));
                log.log(CommandLog.Kind.STATUS, "port " + port + " connected");
            } catch (IOException e) {
                String why = e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
                results.add(new Result(port, false, why));
                log.log(CommandLog.Kind.STATUS, "port " + port + " did not connect: " + why);
            }
        }
        return results;
    }
}
