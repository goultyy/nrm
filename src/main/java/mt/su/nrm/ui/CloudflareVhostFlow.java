package mt.su.nrm.ui;

import mt.su.nrm.cloudflare.CloudflareException;
import mt.su.nrm.cloudflare.CloudflareSession;
import mt.su.nrm.cloudflare.CloudflareWorkspace;
import mt.su.nrm.cloudflare.PublishPlan;
import mt.su.nrm.cloudflare.Tunnel;
import mt.su.nrm.cloudflare.Zone;
import mt.su.nrm.model.ServerProfile;
import mt.su.nrm.nginx.ListenEndpoint;
import mt.su.nrm.nginx.VhostSettings;
import mt.su.nrm.ssh.NetworkService;
import mt.su.nrm.ssh.SshSession;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * "Add to Cloudflare" for a virtual host: connects to Cloudflare if needed, reads the server's addresses and ports,
 * runs the wizard and stages what it decided. Every call runs off the JavaFX thread, and nothing reaches Cloudflare
 * until the staged changes are applied on the Cloudflare pages.
 */
final class CloudflareVhostFlow {

    private CloudflareVhostFlow() {
    }

    /**
     * @param show called once the plan is staged, to open the page where it can be reviewed: the parent entry
     *             ({@code CF_ZONES} or {@code CF_TUNNELS}) and the zone or tunnel in it
     */
    static void start(Window owner, ServerProfile profile, ServerConnection connection, VhostSettings site,
                      BiConsumer<MainWindow.NavKind, Object> show) {
        List<String> hostnames = PublishPlan.hostnameChoices(site.serverNames);
        if (hostnames.isEmpty()) {
            Dialogs.info(owner, "Nothing to publish", "This virtual host has no server name Cloudflare could use. "
                    + "Give it a server name such as app.example.com first.");
            return;
        }
        CloudflareHub hub = connection.cloudflareHub();
        hub.whenReady(profile, session -> {
            SshSession ssh = connection.session();
            hub.run("Reading the server and Cloudflare...", () -> gather(ssh, session, hostnames),
                    gathered -> wizard(owner, hub, session, site, hostnames, gathered.facts(), gathered.failures(), show),
                    // Suggestions and warnings are a convenience: without them the wizard still works.
                    problem -> wizard(owner, hub, session, site, hostnames, NetworkService.Facts.EMPTY,
                            List.of(problem), show));
        }, problem -> Dialogs.error(owner, "Cloudflare isn't available", problem));
    }

    /** What was read before the wizard opens; {@code failures} says what could not be read. */
    private record Gathered(NetworkService.Facts facts, List<String> failures) {
    }

    /**
     * Reads the server's addresses and ports, and what Cloudflare already holds that could clash with the site's
     * names: the records of the zones those names belong to, and the routes of every tunnel. A part that can't be
     * read is reported, not fatal.
     */
    private static Gathered gather(SshSession ssh, CloudflareSession session, List<String> hostnames) {
        NetworkService.Facts facts = ssh == null ? NetworkService.Facts.EMPTY : NetworkService.read(ssh);
        CloudflareWorkspace workspace = session.workspace();
        List<String> failures = new ArrayList<>();
        for (Zone zone : session.zones()) {
            boolean relevant = hostnames.stream().anyMatch(h -> h.equals(zone.name()) || h.endsWith("." + zone.name()));
            if (relevant && !workspace.isDnsLoaded(zone)) {
                try {
                    workspace.loadDns(zone);
                } catch (CloudflareException e) {
                    failures.add("the records of " + zone.name() + " could not be read: " + e.getMessage());
                }
            }
        }
        for (Tunnel tunnel : session.tunnels()) {
            if (!workspace.isTunnelLoaded(tunnel)) {
                try {
                    workspace.loadTunnel(session.accountOf(tunnel), tunnel);
                } catch (CloudflareException e) {
                    failures.add("the routes of tunnel " + tunnel.name() + " could not be read: " + e.getMessage());
                }
            }
        }
        return new Gathered(facts, failures);
    }

    private static void wizard(Window owner, CloudflareHub hub, CloudflareSession session, VhostSettings site,
                               List<String> hostnames, NetworkService.Facts facts, List<String> checkFailures,
                               BiConsumer<MainWindow.NavKind, Object> show) {
        List<Integer> sitePorts = new ArrayList<>();
        List<Integer> sslPorts = new ArrayList<>();
        for (VhostSettings.ListenSpec listen : site.listens) {
            ListenEndpoint.parse(listen.endpoint).ifPresent(e -> {
                if (!sitePorts.contains(e.port())) {
                    sitePorts.add(e.port());
                }
                if (listen.has("ssl") && !sslPorts.contains(e.port())) {
                    sslPorts.add(e.port());
                }
            });
        }
        int defaultPort = !sslPorts.isEmpty() ? sslPorts.get(0) : !sitePorts.isEmpty() ? sitePorts.get(0) : 80;
        CloudflareVhostWizard.Inputs inputs = new CloudflareVhostWizard.Inputs(hostnames, sitePorts, sslPorts,
                defaultPort, session.zones(), session.tunnels(), facts, session.workspace(), checkFailures);
        CloudflareVhostWizard.run(owner, inputs).ifPresent(plan -> stage(owner, hub, session, plan, show));
    }

    private static void stage(Window owner, CloudflareHub hub, CloudflareSession session, PublishPlan plan,
                              BiConsumer<MainWindow.NavKind, Object> show) {
        CloudflareWorkspace workspace = session.workspace();
        hub.run("Reading from Cloudflare...", () -> {
            if (!workspace.isDnsLoaded(plan.zone())) {
                workspace.loadDns(plan.zone());
            }
            if (plan.tunnel() != null && !workspace.isTunnelLoaded(plan.tunnel())) {
                workspace.loadTunnel(session.accountOf(plan.tunnel()), plan.tunnel());
            }
            return null;
        }, done -> {
            try {
                plan.stageInto(workspace);
            } catch (IllegalArgumentException | IllegalStateException e) {
                Dialogs.error(owner, "This can't be added to Cloudflare", e.getMessage());
                return;
            }
            hub.changed();
            Dialogs.info(owner, "Staged in Cloudflare", String.join("\n", plan.describe())
                    + "\n\nNothing has been sent yet. Review and apply it on the Cloudflare page.");
            if (plan.kind() == PublishPlan.Kind.TUNNEL) {
                show.accept(MainWindow.NavKind.CF_TUNNELS, plan.tunnel());
            } else {
                show.accept(MainWindow.NavKind.CF_ZONES, plan.zone());
            }
        }, problem -> Dialogs.error(owner, "Could not read from Cloudflare", problem));
    }
}
