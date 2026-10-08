package mt.su.nrm.ui;

import mt.su.nrm.cloudflare.CloudflareSession;
import mt.su.nrm.cloudflare.CloudflareWorkspace;
import mt.su.nrm.cloudflare.Tunnel;
import mt.su.nrm.cloudflare.Zone;
import javafx.stage.Window;

/**
 * Removing a DNS zone or a tunnel. Deleting a zone takes the domain and every record in it out of Cloudflare, and
 * deleting a tunnel stops everything it publishes, so both are guarded: not while changes are staged for them, and
 * only after their name has been typed. Like creating, it happens on Cloudflare at once (it can't be staged).
 * <p>
 * Used by the zone and tunnel pages, the lists and the tree's right-click menu alike, so none needs a page.
 */
final class CloudflareRemoval {

    private CloudflareRemoval() {
    }

    /** @param after runs once the zone is gone (for example to redraw a list) */
    static void zone(Window owner, ServerConnection connection, Zone zone, Runnable after) {
        CloudflareHub hub = connection.cloudflareHub();
        CloudflareSession session = connection.cloudflare();
        if (session == null || hub.busyProperty().get()) {
            return;
        }
        if (session.workspace().hasStagedChanges(zone)) {
            Dialogs.info(owner, "There are staged changes for " + zone.name(),
                    "Apply or discard the staged changes for this zone before deleting it.");
            return;
        }
        // The records are read first so the confirmation can say how many will be lost.
        hub.ensureZoneLoaded(session, zone, false, () -> {
            int records = session.workspace().dnsRecords(zone).size();
            String details = "This removes " + zone.name() + " from your Cloudflare account together with all its "
                    + records + " DNS record" + (records == 1 ? "" : "s") + ". Everything that uses this domain "
                    + "through Cloudflare stops working. This cannot be undone from here.\n\nIt happens on Cloudflare "
                    + "immediately; it is not staged.";
            if (!CloudflareDialogs.confirmRemoval(owner, "Delete DNS zone " + zone.name() + "?", details, zone.name(),
                    "Delete zone")) {
                return;
            }
            hub.run("Deleting " + zone.name() + " from Cloudflare...", () -> {
                session.deleteZone(zone);
                return null;
            }, done -> {
                CloudflareSession now = connection.cloudflare() != null ? connection.cloudflare() : session;
                connection.setCloudflare(now.withoutZone(zone));
                Dialogs.info(owner, zone.name() + " was deleted", "The zone is gone from Cloudflare.");
                after.run();
            }, problem -> Dialogs.error(owner, "The zone was not deleted", problem));
        }, problem -> Dialogs.error(owner, "Could not read " + zone.name() + " from Cloudflare", problem));
    }

    /** @param after runs once the tunnel is gone */
    static void tunnel(Window owner, ServerConnection connection, Tunnel tunnel, Runnable after) {
        CloudflareHub hub = connection.cloudflareHub();
        CloudflareSession session = connection.cloudflare();
        if (session == null || hub.busyProperty().get()) {
            return;
        }
        if (session.workspace().hasStagedChanges(tunnel)) {
            Dialogs.info(owner, "There are staged changes for " + tunnel.name(),
                    "Apply or discard the staged changes for this tunnel before deleting it.");
            return;
        }
        CloudflareWorkspace workspace = session.workspace();
        // The routes are read first so the confirmation can say how many applications depend on the tunnel.
        hub.run("Reading " + tunnel.name() + " from Cloudflare...", () -> {
            if (!workspace.isTunnelLoaded(tunnel)) {
                workspace.loadTunnel(session.accountOf(tunnel), tunnel);
            }
            return null;
        }, loaded -> {
            int apps = workspace.tunnelConfig(tunnel).routes().size();
            String details = "This deletes the tunnel " + tunnel.name() + ". The " + apps + " published application"
                    + (apps == 1 ? "" : "s") + " on it stop" + (apps == 1 ? "s" : "") + " working, and DNS records that "
                    + "point at the tunnel are left behind for you to remove.\n\nStop cloudflared on the tunnel's "
                    + "servers first: Cloudflare refuses to delete a tunnel that is still connected. It happens on "
                    + "Cloudflare immediately; it is not staged.";
            if (!CloudflareDialogs.confirmRemoval(owner, "Delete tunnel " + tunnel.name() + "?", details, tunnel.name(),
                    "Delete tunnel")) {
                return;
            }
            hub.run("Deleting tunnel " + tunnel.name() + "...", () -> {
                session.deleteTunnel(tunnel);
                return null;
            }, done -> {
                CloudflareSession now = connection.cloudflare() != null ? connection.cloudflare() : session;
                connection.setCloudflare(now.withoutTunnel(tunnel));
                Dialogs.info(owner, "Tunnel " + tunnel.name() + " was deleted", "The tunnel is gone from Cloudflare.");
                after.run();
            }, problem -> Dialogs.error(owner, "The tunnel was not deleted", problem));
        }, problem -> Dialogs.error(owner, "Could not read " + tunnel.name() + " from Cloudflare", problem));
    }
}
