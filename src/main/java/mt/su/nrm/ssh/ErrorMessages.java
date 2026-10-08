package mt.su.nrm.ssh;

import java.io.FileNotFoundException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.NoSuchFileException;

/** Turns the exceptions an SSH connection can throw into something a person can act on. */
public final class ErrorMessages {

    private ErrorMessages() {
    }

    /**
     * @param host the server the operation was for (for the wording), may be null
     * @param port the SSH port
     */
    public static String describe(Throwable failure, String host, int port) {
        String where = host == null ? "the server" : host + ":" + port;
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            String message = t.getMessage() == null ? "" : t.getMessage();
            String lower = message.toLowerCase(java.util.Locale.ROOT);
            String type = t.getClass().getName();
            if (t instanceof HostKeyChangedException) {
                return message;
            }
            if (t instanceof AuthenticationFailedException) {
                return "Login to " + where + " failed: the username, password or private key was not accepted.";
            }
            if (t instanceof UnknownHostException) {
                return "Could not find " + (host == null ? "the server" : "\"" + host + "\"")
                        + ". Check the address and your network connection.";
            }
            if (t instanceof ConnectException && lower.contains("refused")) {
                return "The server at " + where + " refused the connection. Check that SSH is running there and that "
                        + "the port is right.";
            }
            if (t instanceof SocketTimeoutException || lower.contains("timed out")) {
                return "The server at " + where + " did not answer in time. Check the address, the port and any "
                        + "firewall, or try again if the connection is slow.";
            }
            if (t instanceof NoRouteToHostException) {
                return "There is no network route to " + where + ".";
            }
            if (t instanceof FileNotFoundException || t instanceof NoSuchFileException) {
                return "A file could not be found: " + message;
            }
            if (lower.contains("algorithm negotiation fail")) {
                return "This app and the server at " + where + " have no SSH algorithms in common. The server's SSH "
                        + "software may be very old or restricted.";
            }
            if (t instanceof SocketException || type.endsWith("TransportException") || type.endsWith("ConnectionException")
                    || lower.contains("broken pipe") || lower.contains("connection reset")
                    || lower.contains("connection closed") || lower.contains("stream closed")) {
                return "The connection to " + where + " was lost.";
            }
        }
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
