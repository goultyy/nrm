package mt.su.nrm.nginx;

import java.util.Optional;

/**
 * The address and port of a {@code listen} directive's first argument: {@code 80}, {@code 127.0.0.1:8080},
 * {@code [::]:443}, {@code *:80} or a bare address (which nginx takes as port 80). Unix sockets have none.
 *
 * @param host the address as written ({@code ""} when only a port was given)
 */
public record ListenEndpoint(String host, int port) {

    public static Optional<ListenEndpoint> parse(String endpoint) {
        String e = endpoint == null ? "" : endpoint.strip();
        if (e.isEmpty() || e.startsWith("unix:")) {
            return Optional.empty();
        }
        if (e.chars().allMatch(Character::isDigit)) {
            return port(e).map(p -> new ListenEndpoint("", p));
        }
        if (e.startsWith("[")) {
            int close = e.indexOf(']');
            if (close < 0) {
                return Optional.empty();
            }
            String host = e.substring(0, close + 1);
            String rest = e.substring(close + 1);
            if (rest.isEmpty()) {
                return Optional.of(new ListenEndpoint(host, 80));
            }
            return rest.startsWith(":") ? port(rest.substring(1)).map(p -> new ListenEndpoint(host, p)) : Optional.empty();
        }
        int colon = e.lastIndexOf(':');
        if (colon < 0) {
            return Optional.of(new ListenEndpoint(e, 80));
        }
        String host = e.substring(0, colon);
        return port(e.substring(colon + 1)).map(p -> new ListenEndpoint(host, p));
    }

    private static Optional<Integer> port(String text) {
        try {
            int p = Integer.parseInt(text);
            return p >= 1 && p <= 65535 ? Optional.of(p) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
