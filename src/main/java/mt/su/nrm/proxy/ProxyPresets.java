package mt.su.nrm.proxy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

/** The proxy presets: the built-in ones, then any added through {@link ProxyPresetProvider}. */
public final class ProxyPresets {

    public static final String CLOUDFLARE = "cloudflare";
    public static final String CLOUDFLARE_TUNNEL = "cloudflare-tunnel";
    public static final String LOCAL_PROXY = "local-proxy";
    public static final String PRIVATE_NETWORK = "private-network";
    public static final String CUSTOM = "custom";

    private static final List<String> LOOPBACK = List.of("127.0.0.1", "::1");
    private static final List<String> PRIVATE = List.of("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7");

    private ProxyPresets() {
    }

    public static List<ProxyPreset> builtIn() {
        return List.of(
                new ProxyPreset(CLOUDFLARE, "Cloudflare (proxied DNS records)",
                        "Visitors reach nginx through Cloudflare's network. Trusts Cloudflare's published address "
                                + "ranges, fetched from Cloudflare, and reads the visitor from CF-Connecting-IP.",
                        "CF-Connecting-IP", false, List.of(), CloudflareRanges::fetch),
                new ProxyPreset(CLOUDFLARE_TUNNEL, "Cloudflare Tunnel (cloudflared on this server)",
                        "Visitors reach nginx through a tunnel, so requests come from cloudflared on this machine. "
                                + "Trusts only this machine and reads the visitor from CF-Connecting-IP. If cloudflared "
                                + "runs on another machine, add its address.",
                        "CF-Connecting-IP", false, LOOPBACK, null),
                new ProxyPreset(LOCAL_PROXY, "Another proxy on this server",
                        "HAProxy, Traefik, another nginx or similar, running on this machine in front of nginx. "
                                + "Trusts only this machine and reads the visitor from X-Forwarded-For.",
                        "X-Forwarded-For", true, LOOPBACK, null),
                new ProxyPreset(PRIVATE_NETWORK, "Load balancer in your own network",
                        "A load balancer or reverse proxy on a private network in front of nginx. Trusts the private "
                                + "address ranges (10.x, 172.16-31.x, 192.168.x) and reads the visitor from "
                                + "X-Forwarded-For. Narrow it to the balancer's own address if you can.",
                        "X-Forwarded-For", true, PRIVATE, null),
                new ProxyPreset(CUSTOM, "Something else",
                        "Any other proxy or CDN: you choose the header and the addresses to trust.",
                        "X-Forwarded-For", true, List.of(), null));
    }

    /** Built-in presets first, then those from providers; a provider's preset can't replace a built-in id. */
    public static List<ProxyPreset> all() {
        List<ProxyPreset> all = new ArrayList<>(builtIn());
        for (ProxyPresetProvider provider : ServiceLoader.load(ProxyPresetProvider.class)) {
            for (ProxyPreset preset : provider.presets()) {
                if (all.stream().noneMatch(p -> p.id().equals(preset.id()))) {
                    all.add(preset);
                }
            }
        }
        return List.copyOf(all);
    }

    public static Optional<ProxyPreset> byId(String id) {
        return all().stream().filter(p -> p.id().equals(id)).findFirst();
    }
}
