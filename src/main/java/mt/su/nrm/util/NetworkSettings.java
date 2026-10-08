package mt.su.nrm.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * App-wide settings for finding a server's external address: which "what is my IP" services may be asked, and whether
 * the user has agreed to that. Kept in the same plain settings file as the theme (nothing here is secret), and a
 * missing or damaged file just means the defaults.
 * <p>
 * Only {@code https://} addresses are accepted, and only plain ones (a host, an optional port and path), so a stored
 * value can't smuggle anything into the command that uses it.
 */
public final class NetworkSettings {

    /** Tried in this order; the first that answers wins. */
    public static final List<String> DEFAULT_SERVICES = List.of("https://api.ipify.org", "https://icanhazip.com");

    private static final String SERVICES = "echo.services";
    private static final String CONFIRMED = "echo.confirmed";
    private static final String SEPARATOR = "|";
    private static final int MAX_SERVICES = 8;

    private static final Pattern SERVICE = Pattern.compile(
            "https://[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?(:[0-9]{1,5})?(/[A-Za-z0-9._~/-]{0,100})?");

    private NetworkSettings() {
    }

    public static Path file() {
        return AppDirs.dataDir().resolve("settings.properties");
    }

    /** True for an address that may be asked: https, a host name or IP, an optional port and a plain path. */
    public static boolean validService(String url) {
        return url != null && SERVICE.matcher(url).matches() && !url.contains("..");
    }

    /** What is wrong with a list of addresses, worded for the user; empty if it can be saved. */
    public static List<String> problems(List<String> services) {
        List<String> problems = new ArrayList<>();
        if (services.isEmpty()) {
            problems.add("Add at least one address, or reset to the defaults.");
        }
        if (services.size() > MAX_SERVICES) {
            problems.add("Keep it to " + MAX_SERVICES + " addresses or fewer.");
        }
        for (String s : services) {
            if (!validService(s)) {
                problems.add("\"" + s + "\" is not usable. It must start with https:// and have only a host name, an "
                        + "optional port and a plain path.");
            }
        }
        return problems;
    }

    /** The saved list, or the defaults if none is saved or the saved one is not usable. */
    public static List<String> services(Path file) {
        Properties props = read(file);
        String saved = props.getProperty(SERVICES);
        if (saved == null || saved.isBlank()) {
            return DEFAULT_SERVICES;
        }
        List<String> list = new ArrayList<>(new LinkedHashSet<>(List.of(saved.split("\\" + SEPARATOR))));
        list.removeIf(String::isBlank);
        return problems(list).isEmpty() ? List.copyOf(list) : DEFAULT_SERVICES;
    }

    /** Saves the list (an empty list or the defaults clears the saved choice), keeping the other settings. */
    public static void saveServices(Path file, List<String> services) throws IOException {
        List<String> clean = new ArrayList<>(new LinkedHashSet<>(services.stream().map(String::strip).toList()));
        List<String> problems = problems(clean);
        if (!problems.isEmpty()) {
            throw new IOException(String.join(" ", problems));
        }
        Properties props = read(file);
        if (clean.equals(DEFAULT_SERVICES)) {
            props.remove(SERVICES);
        } else {
            props.setProperty(SERVICES, String.join(SEPARATOR, clean));
        }
        write(file, props);
    }

    /** Whether the user has agreed to asking an outside service for the server's address. */
    public static boolean confirmed(Path file) {
        return Boolean.parseBoolean(read(file).getProperty(CONFIRMED, "false"));
    }

    public static void setConfirmed(Path file, boolean value) throws IOException {
        Properties props = read(file);
        props.setProperty(CONFIRMED, String.valueOf(value));
        write(file, props);
    }

    private static Properties read(Path file) {
        Properties props = new Properties();
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException | IllegalArgumentException ignored) {
                // A damaged file is treated as empty.
            }
        }
        return props;
    }

    private static void write(Path file, Properties props) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream out = Files.newOutputStream(file)) {
            props.store(out, "NRM settings");
        }
    }
}
