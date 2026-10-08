package mt.su.nrm.ssh;

import mt.su.nrm.cloudflare.Json;
import mt.su.nrm.util.NetworkSettings;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Finding the address the internet sees for one of a server's own addresses. A server behind NAT (a cloud instance
 * with a private address, a machine behind a router) cannot know it by itself, so it has to ask, in two ways:
 * <ol>
 *   <li>the cloud's own metadata service ({@link #metadata}): exact, but only on AWS, Google Cloud and Azure, which
 *       publish the public address there. Oracle Cloud's metadata names the private address only, so it is reported as
 *       Oracle without an external address and the second way is used;</li>
 *   <li>a "what is my IP" service, asked from the server <i>through that address</i> ({@link #echo}), which returns the
 *       address the request left from. That is the outbound address: usually the same one that reaches the server
 *       from outside, but not guaranteed behind a shared NAT gateway.</li>
 * </ol>
 * Both run on the server through the logged session. They are read-only, and everything put into a command is
 * validated first, so a stored service address or an odd local address can't alter the command.
 */
public final class ExternalAddressService {

    private static final Duration META_TIMEOUT = Duration.ofSeconds(25);
    private static final Duration ECHO_TIMEOUT = Duration.ofSeconds(20);
    private static final String MARK = "@@NRM-META ";

    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:]{2,39}(\\.[0-9.]+)?");

    /**
     * What a cloud's metadata said.
     *
     * @param provider    a display name such as "Amazon Web Services"
     * @param external    private address to its public address, for the addresses the metadata gave one for
     * @param privateOnly private addresses the metadata knows but without a public one
     */
    public record Metadata(String provider, Map<String, String> external, List<String> privateOnly) {
    }

    /** An external address found for one of the server's own addresses, and how it was found. */
    public record Found(String address, String source) {
    }

    private ExternalAddressService() {
    }

    // ---------------------------------------------------------------- the cloud's metadata

    /**
     * Asks the cloud metadata addresses (all of which are link-local, so never leave the server's own network). Nothing
     * here depends on user input. Where there is no metadata service it stops after one second.
     */
    static String metadataScript() {
        return String.join("\n",
                "command -v curl >/dev/null 2>&1 || { echo '" + MARK + "nocurl'; exit 0; }",
                "M=169.254.169.254",
                "curl -s -o /dev/null --connect-timeout 1 -m 2 \"http://$M/\"; rc=$?",
                "[ $rc -ne 0 ] && { echo '" + MARK + "none'; exit 0; }",
                "c() { curl -sf --connect-timeout 1 -m 3 \"$@\" 2>/dev/null; }",
                "T=$(c -X PUT \"http://$M/latest/api/token\" -H 'X-aws-ec2-metadata-token-ttl-seconds: 30')",
                "if [ -n \"$T\" ]; then H=\"X-aws-ec2-metadata-token: $T\"; else H='X-nrm: 1'; fi",
                "L=$(c -H \"$H\" \"http://$M/latest/meta-data/local-ipv4\")",
                "case \"$L\" in [0-9]*.[0-9]*.[0-9]*.[0-9]*) P=$(c -H \"$H\" \"http://$M/latest/meta-data/public-ipv4\");"
                        + " echo '" + MARK + "aws'; echo \"local=$L\"; echo \"public=$P\";; esac",
                "G=$(c -H 'Metadata-Flavor: Google' \"http://$M/computeMetadata/v1/instance/network-interfaces/0/ip\")",
                "case \"$G\" in [0-9]*.[0-9]*.[0-9]*.[0-9]*) E=$(c -H 'Metadata-Flavor: Google' "
                        + "\"http://$M/computeMetadata/v1/instance/network-interfaces/0/access-configs/0/external-ip\");"
                        + " echo '" + MARK + "gcp'; echo \"local=$G\"; echo \"public=$E\";; esac",
                "A=$(c -H 'Metadata: true' \"http://$M/metadata/instance/network/interface?api-version=2021-02-01\" | tr -d '\\n')",
                "case \"$A\" in '['*) echo '" + MARK + "azure'; echo \"$A\";; esac",
                "O=$(c -H 'Authorization: Bearer Oracle' \"http://$M/opc/v2/vnics/\" | tr -d '\\n')",
                "case \"$O\" in '['*) echo '" + MARK + "oracle'; echo \"$O\";; esac",
                "echo '" + MARK + "end'",
                "");
    }

    /** Asks the server's cloud, if it is in one. Empty when there is no metadata service or it can't be asked. */
    public static List<Metadata> metadata(SshSession session) {
        try {
            CommandResult r = session.exec(inSh(metadataScript()), META_TIMEOUT);
            return parseMetadata(r.stdout());
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Reads the output of {@link #metadataScript}; anything that doesn't look right is left out. */
    static List<Metadata> parseMetadata(String output) {
        List<Metadata> found = new ArrayList<>();
        String provider = null;
        List<String> body = new ArrayList<>();
        for (String line : (output + "\n" + MARK + "end\n").split("\r?\n")) {
            if (line.startsWith(MARK)) {
                if (provider != null) {
                    readSection(provider, body).ifPresent(found::add);
                }
                provider = line.substring(MARK.length()).strip();
                body.clear();
            } else if (provider != null) {
                body.add(line.strip());
            }
        }
        return List.copyOf(found);
    }

    private static Optional<Metadata> readSection(String provider, List<String> body) {
        switch (provider) {
            case "aws", "gcp": {
                String local = valueOf(body, "local=");
                String pub = valueOf(body, "public=");
                if (!validIp(local)) {
                    return Optional.empty();
                }
                String name = provider.equals("aws") ? "Amazon Web Services" : "Google Cloud";
                return Optional.of(validIp(pub)
                        ? new Metadata(name, Map.of(local, pub), List.of())
                        : new Metadata(name, Map.of(), List.of(local)));
            }
            case "azure", "oracle":
                return readJsonInterfaces(provider, String.join("", body));
            default:
                return Optional.empty();
        }
    }

    private static Optional<Metadata> readJsonInterfaces(String provider, String json) {
        Map<String, String> external = new LinkedHashMap<>();
        List<String> privateOnly = new ArrayList<>();
        try {
            for (Object entry : Json.asList(Json.parse(json))) {
                Map<String, Object> nic = Json.asObject(entry);
                if (provider.equals("azure")) {
                    for (Object ip : Json.asList(Json.asObject(nic.get("ipv4")).get("ipAddress"))) {
                        Map<String, Object> a = Json.asObject(ip);
                        add(external, privateOnly, Json.asString(a.get("privateIpAddress")),
                                Json.asString(a.get("publicIpAddress")));
                    }
                } else {
                    // Oracle's metadata lists the private address; a public one is used if it ever includes it.
                    add(external, privateOnly, Json.asString(nic.get("privateIp")), Json.asString(nic.get("publicIp")));
                }
            }
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (external.isEmpty() && privateOnly.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Metadata(provider.equals("azure") ? "Microsoft Azure" : "Oracle Cloud", external,
                privateOnly));
    }

    private static void add(Map<String, String> external, List<String> privateOnly, String local, String pub) {
        if (!validIp(local)) {
            return;
        }
        if (validIp(pub)) {
            external.put(local, pub);
        } else {
            privateOnly.add(local);
        }
    }

    private static String valueOf(List<String> lines, String prefix) {
        for (String l : lines) {
            if (l.startsWith(prefix)) {
                return l.substring(prefix.length()).strip();
            }
        }
        return "";
    }

    // ---------------------------------------------------------------- an echo service

    /**
     * The command that asks a service for the address a request through {@code localAddress} leaves from.
     *
     * @throws IOException if the address or the service address is not one that may go into a command
     */
    static String echoCommand(String localAddress, String serviceUrl) throws IOException {
        if (!validIp(localAddress)) {
            throw new IOException("\"" + localAddress + "\" is not an IP address.");
        }
        if (!NetworkSettings.validService(serviceUrl)) {
            throw new IOException("\"" + serviceUrl + "\" is not a usable service address.");
        }
        return "command -v curl >/dev/null 2>&1 || exit 127\n"
                + "curl -sf " + (localAddress.contains(":") ? "-6" : "-4") + " --interface " + Shell.quote(localAddress)
                + " --connect-timeout 5 -m 8 " + Shell.quote(serviceUrl) + "\n";
    }

    /**
     * Asks one service, through one of the server's own addresses, what address it sees.
     *
     * @return the external address, or empty if the service gave no usable answer
     * @throws IOException if curl is missing, the request failed, or an argument was not acceptable
     */
    public static Optional<Found> echo(SshSession session, String localAddress, String serviceUrl) throws IOException {
        CommandResult r = session.exec(inSh(echoCommand(localAddress, serviceUrl)), ECHO_TIMEOUT);
        if (r.exitStatus() == 127) {
            throw new IOException("curl is not installed on the server, so it can't ask an outside service.");
        }
        if (!r.ok()) {
            throw new IOException("The request through " + localAddress + " to " + serviceUrl + " failed (exit "
                    + r.exitStatus() + ").");
        }
        return parseEcho(r.stdout()).map(address -> new Found(address, serviceUrl.replaceFirst("^https://", "")));
    }

    /** The answer must be exactly one IP address and nothing else. */
    static Optional<String> parseEcho(String body) {
        if (body == null) {
            return Optional.empty();
        }
        String text = body.strip();
        return text.length() <= 45 && validIp(text) ? Optional.of(text) : Optional.empty();
    }

    // ---------------------------------------------------------------- putting it together

    /** What a lookup found: an external address per local address, and notes the user should see. */
    public record Lookup(Map<String, Found> found, List<String> notes) {
    }

    /**
     * Finds the external address of each of the server's addresses. A public address is its own. A private one comes
     * from the cloud's metadata if that gives it, else from the first echo service that answers. Loopback and
     * link-local addresses are skipped. Nothing is an error: what can't be found is simply missing, with a note.
     * Blocking: run off the JavaFX thread.
     */
    public static Lookup lookup(SshSession session, List<NetworkService.LocalAddress> addresses, List<String> services) {
        Map<String, Found> found = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        Map<String, Found> fromCloud = new LinkedHashMap<>();
        for (Metadata m : metadata(session)) {
            for (Map.Entry<String, String> e : m.external().entrySet()) {
                fromCloud.put(e.getKey(), new Found(e.getValue(), m.provider() + " metadata"));
            }
            if (m.external().isEmpty()) {
                notes.add("This looks like " + m.provider() + ", whose metadata names the private address only, so the "
                        + "external address was asked of an outside service instead.");
            }
        }
        List<String> failures = new ArrayList<>();
        for (NetworkService.LocalAddress a : addresses) {
            if (a.isLoopback() || a.isLinkLocal()) {
                continue;
            }
            String address = a.address();
            if (a.kind().equals("public")) {
                found.put(address, new Found(address, "its own address"));
            } else if (fromCloud.containsKey(address)) {
                found.put(address, fromCloud.get(address));
            } else {
                for (String service : services) {
                    try {
                        Optional<Found> echoed = echo(session, address, service);
                        if (echoed.isPresent()) {
                            found.put(address, new Found(echoed.get().address(), echoed.get().source() + " (outbound)"));
                            break;
                        }
                    } catch (IOException e) {
                        String message = address + ": " + e.getMessage();
                        if (!failures.contains(message)) {
                            failures.add(message);
                        }
                        if (e.getMessage().startsWith("curl is not installed")) {
                            break; // the other services would fail the same way
                        }
                    }
                }
            }
        }
        if (found.values().stream().anyMatch(f -> f.source().endsWith("(outbound)"))) {
            notes.add("\"outbound\" means the address an outside service sees when the server connects out through that "
                    + "address. It is usually the same address that reaches the server from outside, but behind a shared "
                    + "NAT gateway it can differ.");
        }
        notes.addAll(failures);
        return new Lookup(found, notes);
    }

    /** Runs a script with {@code sh} whatever the login shell is (the scripts use only POSIX syntax). */
    static String inSh(String script) {
        return "sh -c " + Shell.quote(script);
    }

    // ---------------------------------------------------------------- addresses

    /** True for an IPv4 address (four numbers up to 255) or an IPv6 address; nothing with other characters. */
    public static boolean validIp(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        if (IPV4.matcher(text).matches()) {
            for (String part : text.split("\\.")) {
                if (Integer.parseInt(part) > 255) {
                    return false;
                }
            }
            return true;
        }
        if (text.contains(":") && IPV6.matcher(text).matches()) {
            try {
                // A literal containing ':' is parsed, never looked up.
                java.net.InetAddress.getByName(text);
                return true;
            } catch (java.net.UnknownHostException e) {
                return false;
            }
        }
        return false;
    }
}
