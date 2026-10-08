package mt.su.nrm.proxy;

import mt.su.nrm.ssh.CommandLog;

import java.io.IOException;
import java.util.List;

/**
 * A kind of proxy that sits in front of nginx, and what nginx has to trust to see the real visitor behind it: the
 * header the proxy puts the visitor's address in, and the addresses the proxy itself connects from.
 * <p>
 * Cloudflare is one preset, not a special case. Anything else (a load balancer, HAProxy, another nginx, a CDN) is
 * another one, and more can be added by providing a {@link ProxyPresetProvider}.
 *
 * @param header    the header carrying the visitor's address, or {@code proxy_protocol}
 * @param recursive whether nginx should look past trusted addresses in a header that lists several
 * @param sources   the addresses or ranges to trust; empty when they come from {@code fetcher} or the user
 * @param fetcher   how to get the ranges from the proxy's own published list, or null if there isn't one
 */
public record ProxyPreset(String id, String name, String description, String header, boolean recursive,
                          List<String> sources, RangeFetcher fetcher) {

    /** Reads a proxy's published address ranges; blocking, so call it off the JavaFX thread. */
    @FunctionalInterface
    public interface RangeFetcher {
        List<String> fetch(CommandLog log) throws IOException;
    }

    public ProxyPreset {
        sources = List.copyOf(sources);
    }

    /** True if the ranges are fetched from the proxy's own list rather than fixed. */
    public boolean fetched() {
        return fetcher != null;
    }

    @Override
    public String toString() {
        return name;
    }
}
