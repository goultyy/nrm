package mt.su.nrm.ui;

import mt.su.nrm.cloudflare.CloudflareWorkspace;
import mt.su.nrm.cloudflare.PublishPlan;
import mt.su.nrm.cloudflare.Tunnel;
import mt.su.nrm.cloudflare.Zone;
import mt.su.nrm.ssh.NetworkService;
import javafx.collections.FXCollections;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Asks how a virtual host's hostname should reach the server through Cloudflare: a DNS record, or an endpoint on one of
 * the account's tunnels. Addresses and ports are suggested from what the server really has: its IP addresses and the
 * ports nginx listens on, plus the ports in the site's own {@code listen} lines. The result is a
 * {@link PublishPlan}; nothing is sent to Cloudflare here.
 */
final class CloudflareVhostWizard {

    /**
     * @param sitePorts  the ports in the site's {@code listen} lines
     * @param sslPorts   those of them that serve HTTPS
     * @param defaultPort the port to suggest for a tunnel endpoint
     */
    record Inputs(List<String> hostnames, List<Integer> sitePorts, List<Integer> sslPorts, int defaultPort,
                  List<Zone> zones, List<Tunnel> tunnels, NetworkService.Facts facts, CloudflareWorkspace workspace,
                  List<String> checkFailures) {
    }

    private CloudflareVhostWizard() {
    }

    static Optional<PublishPlan> run(Window owner, Inputs in) {
        NetworkService.Facts facts = in.facts();

        // ---- page 1: DNS record or tunnel endpoint
        boolean haveTunnels = !in.tunnels().isEmpty();
        ToggleGroup kind = new ToggleGroup();
        RadioButton dns = new RadioButton("A DNS record");
        RadioButton tunnel = new RadioButton("A tunnel endpoint");
        dns.setToggleGroup(kind);
        tunnel.setToggleGroup(kind);
        tunnel.setDisable(!haveTunnels);
        (haveTunnels ? tunnel : dns).setSelected(true);
        VBox page1 = new VBox(8, dns,
                WizardParts.hint("Points the hostname straight at this server's IP address. The server has to be "
                        + "reachable from the internet on the ports visitors use."),
                tunnel,
                WizardParts.hint(haveTunnels
                        ? "Publishes the site through a Cloudflare Tunnel. The tunnel connects out to Cloudflare, so the "
                        + "server needs no open ports; Cloudflare also creates the proxied CNAME for the hostname."
                        : "This token can't see any tunnels (it needs Cloudflare Tunnel: Edit), so only a DNS record is possible."));
        page1.setSpacing(10);

        // ---- page 2: hostname and zone
        ComboBox<String> host = new ComboBox<>(FXCollections.observableArrayList(in.hostnames()));
        host.setEditable(true);
        host.setMaxWidth(Double.MAX_VALUE);
        if (!in.hostnames().isEmpty()) {
            host.setValue(in.hostnames().get(0));
        }
        ComboBox<Zone> zone = new ComboBox<>(FXCollections.observableArrayList(in.zones()));
        zone.setConverter(new StringConverter<>() {
            @Override
            public String toString(Zone z) {
                return z == null ? "" : z.name();
            }

            @Override
            public Zone fromString(String s) {
                return null;
            }
        });
        zone.setMaxWidth(Double.MAX_VALUE);
        Runnable pickZone = () -> {
            String typed = text(host);
            in.zones().stream().filter(z -> typed.equals(z.name()) || typed.endsWith("." + z.name()))
                    .max(Comparator.comparingInt(z -> z.name().length())).ifPresent(zone::setValue);
        };
        // What Cloudflare already has under this name, so a clash is seen before anything is chosen or staged.
        Label existing = new Label();
        existing.setWrapText(true);
        Runnable showExisting = () -> {
            PublishPlan.Existing e = PublishPlan.existing(in.workspace(), zone.getValue(), in.tunnels(), text(host));
            existing.setText(existingText(e, text(host), zone.getValue(), in.checkFailures()));
            existing.setStyle(e.found().isEmpty() ? "-fx-opacity: 0.75;" : "-fx-text-fill: #8a6d00; -fx-font-weight: bold;");
        };
        host.getEditor().textProperty().addListener((obs, o, n) -> {
            pickZone.run();
            showExisting.run();
        });
        zone.valueProperty().addListener((obs, o, n) -> showExisting.run());
        pickZone.run();
        showExisting.run();
        GridPane page2 = WizardParts.form();
        int r = WizardParts.note(page2, 0, WizardParts.hint("Which of this site's names should go through Cloudflare? "
                + "The zone is picked from the name; change it if the wrong one is chosen."));
        r = WizardParts.row(page2, r, "Hostname", host);
        r = WizardParts.row(page2, r, "Cloudflare zone", zone);
        WizardParts.note(page2, r, existing);

        // ---- page 3 (DNS record): the server's address
        ComboBox<String> address = new ComboBox<>(FXCollections.observableArrayList(addressChoices(facts, false)));
        address.setEditable(true);
        address.setMaxWidth(Double.MAX_VALUE);
        if (!address.getItems().isEmpty()) {
            address.setValue(address.getItems().get(0));
        }
        CheckBox proxied = new CheckBox("Proxied through Cloudflare (recommended)");
        proxied.setSelected(true);
        Label addressNote = WizardParts.hint("");
        address.getEditor().textProperty().addListener((obs, o, n) -> addressNote.setText(describeAddress(text(address), facts)));
        addressNote.setText(describeAddress(text(address), facts));
        GridPane page3 = WizardParts.form();
        r = WizardParts.note(page3, 0, WizardParts.hint("Which of the server's addresses should the name point at? "
                + "These are the addresses found on the server; public ones come first."));
        r = WizardParts.row(page3, r, "IP address", address);
        r = WizardParts.note(page3, r, addressNote);
        r = WizardParts.row(page3, r, "", proxied);
        WizardParts.note(page3, r, WizardParts.hint("Cloudflare's proxy only reaches some ports (80, 443, 8080 and a few "
                + "others). " + portsSentence(in) + " Untick the box to point the name at the server directly."));

        // ---- page 4 (tunnel): where the tunnel sends traffic
        ComboBox<Tunnel> tunnelBox = new ComboBox<>(FXCollections.observableArrayList(in.tunnels()));
        tunnelBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(Tunnel t) {
                return t == null ? "" : t.name() + " (" + t.status() + ")";
            }

            @Override
            public Tunnel fromString(String s) {
                return null;
            }
        });
        in.tunnels().stream().filter(t -> t.status().equals("healthy")).findFirst()
                .ifPresentOrElse(tunnelBox::setValue, () -> {
                    if (!in.tunnels().isEmpty()) {
                        tunnelBox.setValue(in.tunnels().get(0));
                    }
                });
        tunnelBox.setMaxWidth(Double.MAX_VALUE);
        ComboBox<String> scheme = new ComboBox<>(FXCollections.observableArrayList("http", "https"));
        scheme.setValue(in.sslPorts().contains(in.defaultPort()) ? "https" : "http");
        ComboBox<String> origin = new ComboBox<>(FXCollections.observableArrayList(addressChoices(facts, true)));
        origin.setEditable(true);
        origin.setMaxWidth(Double.MAX_VALUE);
        origin.setValue("localhost");
        ComboBox<String> port = new ComboBox<>(FXCollections.observableArrayList(portChoices(in)));
        port.setEditable(true);
        port.setValue(String.valueOf(in.defaultPort()));
        CheckBox skipVerify = new CheckBox("Don't verify the origin's certificate (needed for https to an address or localhost)");
        skipVerify.setSelected(true);
        skipVerify.visibleProperty().bind(scheme.valueProperty().isEqualTo("https"));
        skipVerify.managedProperty().bind(skipVerify.visibleProperty());
        Label serviceLabel = new Label();
        serviceLabel.setStyle("-fx-font-family: 'Consolas', 'Menlo', monospace; -fx-font-weight: bold;");
        Label originNote = WizardParts.hint("");
        Label portNote = WizardParts.hint("");
        Supplier<String> service = () -> scheme.getValue() + "://" + bracket(text(origin)) + ":" + text(port);
        Runnable updateTunnel = () -> {
            serviceLabel.setText(service.get());
            originNote.setText(describeOrigin(text(origin), facts));
            portNote.setText(describePort(text(port), in));
        };
        origin.getEditor().textProperty().addListener((obs, o, n) -> updateTunnel.run());
        port.getEditor().textProperty().addListener((obs, o, n) -> {
            Long p = WizardParts.number(n);
            if (p != null && in.sslPorts().contains(p.intValue())) {
                scheme.setValue("https");
            } else if (p != null && in.sitePorts().contains(p.intValue())) {
                scheme.setValue("http");
            }
            updateTunnel.run();
        });
        scheme.valueProperty().addListener((obs, o, n) -> updateTunnel.run());
        updateTunnel.run();
        GridPane page4 = WizardParts.form();
        r = WizardParts.note(page4, 0, WizardParts.hint("Where should the tunnel send the traffic? Use localhost if "
                + "cloudflared runs on this server; otherwise an address of this server that the cloudflared machine can reach."));
        r = WizardParts.row(page4, r, "Tunnel", tunnelBox);
        r = WizardParts.row(page4, r, "Protocol", scheme);
        r = WizardParts.row(page4, r, "Address", origin);
        r = WizardParts.note(page4, r, originNote);
        r = WizardParts.row(page4, r, "Port", port);
        r = WizardParts.note(page4, r, portNote);
        r = WizardParts.row(page4, r, "", skipVerify);
        WizardParts.row(page4, r, "The tunnel will use", serviceLabel);

        // ---- the plan the controls describe right now
        Supplier<PublishPlan> plan = () -> dns.isSelected()
                ? PublishPlan.dnsRecord(zone.getValue(), text(host), text(address), proxied.isSelected())
                : PublishPlan.tunnel(zone.getValue(), tunnelBox.getValue(), text(host), service.get(),
                scheme.getValue().equals("https") && skipVerify.isSelected());

        List<WizardDialog.Page> pages = List.of(
                new WizardDialog.Page("What to create", "Choose how this site should be reached through Cloudflare.",
                        page1, List::of),
                new WizardDialog.Page("Hostname", "Choose the name and the zone it belongs to.", page2,
                        () -> plan.get().hostProblems()),
                new WizardDialog.Page("Server address", "Choose the address the DNS record points at.", page3,
                        () -> plan.get().targetProblems(in.workspace()), () -> { }, dns::isSelected),
                new WizardDialog.Page("Tunnel endpoint", "Choose the tunnel and where it sends traffic.", page4, () -> {
                    List<String> problems = new ArrayList<>(plan.get().targetProblems(in.workspace()));
                    Long p = WizardParts.number(text(port));
                    if (p == null || p < 1 || p > 65535) {
                        problems.add("Enter a port between 1 and 65535.");
                    }
                    return problems;
                }, updateTunnel, tunnel::isSelected));

        boolean finished = WizardDialog.show(owner, "Add to Cloudflare", "Stage in Cloudflare", pages,
                () -> {
                    PublishPlan current = plan.get();
                    String lines = String.join("\n", current.describe());
                    PublishPlan.Existing e = PublishPlan.existing(in.workspace(), current.zone(), in.tunnels(),
                            current.hostname());
                    return e.found().isEmpty() ? lines : lines + "\n\nWARNING: " + current.hostname()
                            + " already exists in Cloudflare:\n  " + String.join("\n  ", e.found());
                },
                () -> "Next: these changes are staged on the Cloudflare page. Nothing is sent to Cloudflare until you "
                        + "review and apply them there.");
        return finished ? Optional.of(plan.get()) : Optional.empty();
    }

    // ---------------------------------------------------------------- suggestions

    /**
     * The warning under the hostname: what already exists, or, if nothing does, whether that can be trusted (parts of
     * Cloudflare that weren't read prove nothing).
     */
    static String existingText(PublishPlan.Existing e, String hostname, Zone zone, List<String> checkFailures) {
        if (hostname.isEmpty()) {
            return "";
        }
        if (!e.found().isEmpty()) {
            return "Warning: " + hostname + " already exists in Cloudflare.\n  " + String.join("\n  ", e.found())
                    + "\nAdding it again can conflict, or be refused.";
        }
        List<String> unchecked = new ArrayList<>();
        if (zone != null && !e.dnsChecked()) {
            unchecked.add("the DNS records of " + zone.name());
        }
        if (!e.tunnelsChecked()) {
            unchecked.add("some tunnels' routes");
        }
        if (!unchecked.isEmpty() || !checkFailures.isEmpty()) {
            String what = unchecked.isEmpty() ? "everything" : String.join(" and ", unchecked);
            return "Nothing found, but " + what + " could not be checked."
                    + (checkFailures.isEmpty() ? "" : " (" + checkFailures.get(0) + ")");
        }
        return "Nothing in Cloudflare uses " + hostname + " yet.";
    }

    private static String text(ComboBox<String> box) {
        String typed = box.getEditor().getText();
        return typed == null ? "" : typed.strip();
    }

    /** An address as it goes into a URL: IPv6 needs brackets. */
    private static String bracket(String address) {
        return address.contains(":") && !address.startsWith("[") ? "[" + address + "]" : address;
    }

    /**
     * The server's addresses worth offering. For a DNS record: public ones first, then private. For a tunnel
     * endpoint: localhost first, then private, then public. Loopback and link-local addresses are never offered as DNS
     * targets.
     */
    static List<String> addressChoices(NetworkService.Facts facts, boolean forTunnel) {
        List<String> choices = new ArrayList<>();
        if (forTunnel) {
            choices.add("localhost");
            choices.add("127.0.0.1");
        }
        List<NetworkService.LocalAddress> usable = facts.addresses().stream()
                .filter(a -> !a.isLoopback() && !a.isLinkLocal()).toList();
        List<NetworkService.LocalAddress> ordered = new ArrayList<>(usable);
        ordered.sort(Comparator.comparing((NetworkService.LocalAddress a) -> a.isPrivate() == forTunnel ? 0 : 1)
                .thenComparing(NetworkService.LocalAddress::ipv6));
        ordered.forEach(a -> choices.add(a.address()));
        return choices;
    }

    /** The ports to offer: the ones nginx is listening on, then the site's own that nothing listens on yet. */
    static List<String> portChoices(Inputs in) {
        List<Integer> ports = new ArrayList<>(in.facts().nginxPorts());
        if (!in.facts().processKnown()) {
            // Without root rights nginx's ports can't be told apart, so the site's own come first.
            ports.clear();
        }
        for (int p : in.sitePorts()) {
            if (!ports.contains(p)) {
                ports.add(p);
            }
        }
        if (!in.facts().processKnown()) {
            for (int p : in.facts().allPorts()) {
                if (!ports.contains(p)) {
                    ports.add(p);
                }
            }
        }
        if (!ports.contains(in.defaultPort())) {
            ports.add(0, in.defaultPort());
        }
        return ports.stream().map(String::valueOf).toList();
    }

    private static String describeAddress(String address, NetworkService.Facts facts) {
        if (address.isEmpty()) {
            return "";
        }
        for (NetworkService.LocalAddress a : facts.addresses()) {
            if (a.address().equalsIgnoreCase(address)) {
                String where = a.iface().isEmpty() ? "" : " on " + a.iface();
                return switch (a.kind()) {
                    case "public" -> "A public address of this server" + where + ".";
                    case "private" -> "A private address of this server" + where
                            + ". It is only reachable from the internet if your router forwards to it.";
                    case "loopback" -> "The server's own loopback address: only the server itself can reach it.";
                    default -> "A link-local address" + where + ": not usable from other networks.";
                };
            }
        }
        return facts.addresses().isEmpty() ? "" : "Not an address this server has.";
    }

    private static String describeOrigin(String address, NetworkService.Facts facts) {
        if (address.equalsIgnoreCase("localhost") || address.equals("127.0.0.1") || address.equals("::1")) {
            return "The server itself: right when cloudflared runs on this server.";
        }
        return describeAddress(address, facts);
    }

    private static String describePort(String text, Inputs in) {
        Long p = WizardParts.number(text);
        if (p == null) {
            return "";
        }
        int port = p.intValue();
        if (in.facts().processKnown() && in.facts().nginxPorts().contains(port)) {
            return "nginx is listening on this port." + (in.sitePorts().contains(port) ? " This site uses it." : "");
        }
        if (in.sitePorts().contains(port)) {
            return in.facts().processKnown()
                    ? "This site's listen line uses this port, but nginx isn't listening on it yet (apply pending changes first)."
                    : "This site's listen line uses this port.";
        }
        if (in.facts().allPorts().contains(port)) {
            return in.facts().processKnown() ? "Something other than nginx is listening on this port."
                    : "Something is listening on this port.";
        }
        return in.facts().allPorts().isEmpty() ? "" : "Nothing is known to listen on this port.";
    }

    private static String portsSentence(Inputs in) {
        List<Integer> shown = in.facts().processKnown() && !in.facts().nginxPorts().isEmpty()
                ? in.facts().nginxPorts() : in.sitePorts();
        if (shown.isEmpty()) {
            return "";
        }
        String list = String.join(", ", shown.stream().map(String::valueOf).toList());
        return "nginx " + (in.facts().processKnown() ? "listens" : "is set to listen") + " on port"
                + (shown.size() == 1 ? " " : "s ") + list + ".";
    }
}
