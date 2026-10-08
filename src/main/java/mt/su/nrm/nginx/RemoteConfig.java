package mt.su.nrm.nginx;

import mt.su.nrm.model.ConfigLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * What the app knows about one server's nginx configuration: the main file and every file it
 * includes, parsed, plus the edits made to them that have not been applied yet. It holds no
 * connection; loading and applying are done by the SSH layer.
 */
public final class RemoteConfig {

    /** A symlink to a config file, as found on the server. */
    public record Link(String path, String target) {
    }

    /** One file: the main config or an included one. */
    static final class Entry {
        final ConfigFile file;
        final List<Link> links;
        final String readOnlyReason;
        final String enableLink;
        boolean created;
        boolean deleted;

        Entry(ConfigFile file, List<Link> links, String readOnlyReason, String enableLink, boolean created) {
            this.file = file;
            this.links = links;
            this.readOnlyReason = readOnlyReason;
            this.enableLink = enableLink;
            this.created = created;
        }
    }

    private final String confDir;
    private final ConfigLayout layout;
    private Entry mainEntry;
    private final List<Entry> entries = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();

    public RemoteConfig(String confDir, ConfigLayout layout, ConfigFile main) {
        this.confDir = confDir.endsWith("/") && confDir.length() > 1 ? confDir.substring(0, confDir.length() - 1) : confDir;
        this.layout = layout;
        this.mainEntry = new Entry(main, List.of(), main.path().startsWith(this.confDir + "/") ? null
                : "The main configuration is outside the nginx config folder (" + this.confDir + ").", null, false);
    }

    public String confDir() {
        return confDir;
    }

    public ConfigLayout layout() {
        return layout;
    }

    /** The main nginx.conf. */
    public ConfigFile mainFile() {
        return mainEntry.file;
    }

    /** Why the main nginx.conf may not be edited, or null if it may. */
    public String mainReadOnlyReason() {
        return mainEntry.readOnlyReason;
    }

    /**
     * Adds a parsed file that the main config includes.
     *
     * @param readOnlyReason why the file must not be edited, or null if it may be
     */
    public void addFile(ConfigFile file, List<Link> links, String readOnlyReason) {
        entries.add(new Entry(file, List.copyOf(links), readOnlyReason, null, false));
    }

    /** Records a file that could not be loaded or parsed. Such files are never edited. */
    public void addProblem(String message) {
        problems.add(message);
    }

    /** Files that could not be read or parsed, with the reason. */
    public List<String> problems() {
        return List.copyOf(problems);
    }

    // ------------------------------------------------------------------------------- hosts

    /** The main file and every included file that is not pending deletion, read-only ones too. */
    public List<ConfigFile> files() {
        List<ConfigFile> files = new ArrayList<>();
        for (Entry e : allEntries()) {
            files.add(e.file);
        }
        return files;
    }

    /** Every virtual host in the loaded files, including read-only ones. */
    public List<VirtualHost> virtualHosts() {
        List<VirtualHost> hosts = new ArrayList<>();
        for (Entry e : allEntries()) {
            for (Block server : e.file.serverBlocks()) {
                hosts.add(new VirtualHost(e.file, server));
            }
        }
        return hosts;
    }

    /** Why a host may not be edited, or null if it may. */
    public String readOnlyReason(VirtualHost host) {
        Entry entry = entryOf(host.file());
        return entry == null ? "Unknown file." : entry.readOnlyReason;
    }

    /** Creates a new, empty virtual host in a new file placed where this server's layout expects it. */
    public VirtualHost createVirtualHost(String primaryName) {
        String path = LayoutDetector.newFilePath(confDir, layout, primaryName);
        for (Entry e : allEntries()) {
            if (e.file.path().equals(path)) {
                throw new IllegalArgumentException("A file named " + path + " already exists.");
            }
        }
        ConfigFile file = ConfigFile.empty(path);
        String link = LayoutDetector.enabledLinkPath(confDir, layout, primaryName);
        Entry entry = new Entry(file, List.of(), null, link, true);
        entries.add(entry);
        return VirtualHost.createNew(file);
    }

    /**
     * Removes a virtual host. If it is the only thing in its file the whole file is deleted (with
     * the symlinks that enable it); otherwise just its block is removed from the file.
     */
    public void deleteVirtualHost(VirtualHost host) {
        Entry entry = entryOf(host.file());
        if (entry == null) {
            throw new IllegalArgumentException("This virtual host cannot be deleted.");
        }
        boolean onlyServer = entry != mainEntry && host.file().root().children().size() == 1
                && host.file().serverBlocks().size() == 1;
        if (onlyServer && entry.created) {
            entries.remove(entry);
        } else if (onlyServer) {
            entry.deleted = true;
        } else {
            host.remove();
        }
    }

    // ------------------------------------------------------------------------------- http-level objects

    /** Every {@code upstream} block. */
    public List<Upstream> upstreams() {
        return collectBlocks("upstream", Upstream::new);
    }

    /** Adds an empty upstream to the http block of the main config. */
    public Upstream createUpstream(String name) {
        Block http = requireHttp();
        Block block = Block.create("upstream", List.of(name));
        http.add(block);
        return new Upstream(mainEntry.file, block);
    }

    public void deleteUpstream(Upstream upstream) {
        upstream.block().parent().remove(upstream.block());
    }

    /** Every {@code proxy_cache_path} directive. */
    public List<CacheZone> cacheZones() {
        return collectDirectives(List.of("proxy_cache_path"), CacheZone::new);
    }

    public CacheZone createCacheZone(String path) {
        Directive d = Directive.create("proxy_cache_path", List.of(path));
        requireHttp().add(d);
        return new CacheZone(mainEntry.file, d);
    }

    public void deleteCacheZone(CacheZone zone) {
        zone.directive().parent().remove(zone.directive());
    }

    /** Every {@code limit_req_zone} and {@code limit_conn_zone} directive. */
    public List<LimitZone> limitZones() {
        return collectDirectives(List.of("limit_req_zone", "limit_conn_zone"), LimitZone::new);
    }

    public LimitZone createLimitZone(LimitZoneSettings.Kind kind) {
        Directive d = Directive.create(kind == LimitZoneSettings.Kind.REQUEST ? "limit_req_zone" : "limit_conn_zone",
                List.of("$binary_remote_addr"));
        requireHttp().add(d);
        return new LimitZone(mainEntry.file, d);
    }

    public void deleteLimitZone(LimitZone zone) {
        zone.directive().parent().remove(zone.directive());
    }

    /** Every {@code log_format} directive at http level. */
    public List<LogFormat> logFormats() {
        return collectDirectives(List.of("log_format"), LogFormat::new);
    }

    /**
     * Adds an empty log format to the http block of the main config. nginx looks a format up by name at the moment it
     * reads the {@code access_log} that uses it, so the format must come before every use: it goes right after the
     * existing log formats, or at the very top of the http block if there are none.
     */
    public LogFormat createLogFormat(String name) {
        Block http = requireHttp();
        Directive d = Directive.create("log_format", List.of(name, ""));
        int at = 0;
        List<Node> kids = http.children();
        for (int i = 0; i < kids.size(); i++) {
            if (kids.get(i) instanceof Directive && kids.get(i).name().equals("log_format")) {
                at = i + 1;
            }
        }
        http.insert(at, d);
        return new LogFormat(mainEntry.file, d);
    }

    public void deleteLogFormat(LogFormat format) {
        format.directive().parent().remove(format.directive());
    }

    /**
     * Where the named format is used: one line for each {@code access_log} in any server or location (or at http
     * level) that names it, such as "server a.com in /etc/nginx/conf.d/a.conf".
     */
    public List<String> logFormatUses(String formatName) {
        List<String> uses = new ArrayList<>();
        for (ConfigFile f : files()) {
            findUses(f.root(), f.path(), "", formatName, uses);
        }
        return uses;
    }

    private static void findUses(Block block, String file, String owner, String name, List<String> into) {
        for (Node n : block.children()) {
            if (n instanceof Directive d && d.name().equals("access_log") && name.equals(d.arg(1))) {
                into.add((owner.isEmpty() ? "http level" : owner) + " in " + file);
            } else if (n instanceof Block child && !child.isOpaque()) {
                String childOwner = owner;
                if (child.name().equals("server")) {
                    Directive names = child.first("server_name");
                    childOwner = "server" + (names == null ? "" : " " + String.join(" ", names.values()));
                } else if (child.name().equals("location") && !owner.isEmpty()) {
                    childOwner = owner + ", location " + String.join(" ", child.values());
                }
                findUses(child, file, childOwner, name, into);
            }
        }
    }

    /** The global settings in the main config, or null if it is not a normal nginx.conf (no http block). */
    public GlobalConfig global() {
        return mainEntry.file.root().blocks("http").isEmpty() ? null : new GlobalConfig(mainEntry.file);
    }

    private Block requireHttp() {
        List<Block> http = mainEntry.file.root().blocks("http");
        if (http.isEmpty()) {
            throw new IllegalStateException("The main configuration has no http block.");
        }
        return http.get(0);
    }

    /** Statements that live at http level: inside http blocks, or at the top of an included file. */
    private List<Block> httpContainers(Entry e) {
        List<Block> containers = new ArrayList<>();
        containers.add(e.file.root());
        for (Block http : e.file.root().blocks("http")) {
            if (!http.isOpaque()) {
                containers.add(http);
            }
        }
        return containers;
    }

    private <T> List<T> collectBlocks(String name, java.util.function.BiFunction<ConfigFile, Block, T> make) {
        List<T> result = new ArrayList<>();
        for (Entry e : allEntries()) {
            for (Block container : httpContainers(e)) {
                for (Block b : container.blocks(name)) {
                    result.add(make.apply(e.file, b));
                }
            }
        }
        return result;
    }

    private <T> List<T> collectDirectives(List<String> names, java.util.function.BiFunction<ConfigFile, Directive, T> make) {
        List<T> result = new ArrayList<>();
        for (Entry e : allEntries()) {
            for (Block container : httpContainers(e)) {
                for (Node n : container.children()) {
                    if (n instanceof Directive && names.contains(n.name())) {
                        result.add(make.apply(e.file, (Directive) n));
                    }
                }
            }
        }
        return result;
    }

    /** Why the file holding an http-level object may not be edited, or null. */
    public String readOnlyReason(ConfigFile file) {
        Entry entry = entryOf(file);
        return entry == null ? "Unknown file." : entry.readOnlyReason;
    }

    // ------------------------------------------------------------------------------- pending

    /** Everything that differs from what is on the server, one entry per file. */
    public List<PendingChange> pendingChanges() {
        List<PendingChange> changes = new ArrayList<>();
        for (Entry e : allEntries(true)) {
            String path = e.file.path();
            if (e.deleted) {
                changes.add(new PendingChange(PendingChange.Kind.DELETE, e.file, path, e.file.originalText(), "",
                        null, e.links));
            } else if (e.created) {
                changes.add(new PendingChange(PendingChange.Kind.CREATE, e.file, path, "", e.file.generate(),
                        e.enableLink, List.of()));
            } else if (e.file.isModified()) {
                changes.add(new PendingChange(PendingChange.Kind.MODIFY, e.file, path, e.file.originalText(),
                        e.file.generate(), null, List.of()));
            }
        }
        return changes;
    }

    public boolean hasPending() {
        return !pendingChanges().isEmpty();
    }

    /** Drops one file's pending change, restoring it to what is on the server. */
    public void discard(PendingChange change) throws NginxParseException {
        Entry entry = entryOf(change.file());
        if (entry == null) {
            return;
        }
        if (entry.created) {
            entries.remove(entry);
            return;
        }
        Entry restored = new Entry(ConfigFile.parse(entry.file.path(), entry.file.originalText()), entry.links,
                entry.readOnlyReason, entry.enableLink, false);
        if (entry == mainEntry) {
            mainEntry = restored;
        } else {
            entries.set(entries.indexOf(entry), restored);
        }
    }

    public void discardAll() throws NginxParseException {
        for (PendingChange change : pendingChanges()) {
            discard(change);
        }
    }

    // ------------------------------------------------------------------------------- helpers

    private List<Entry> allEntries() {
        return allEntries(false);
    }

    /** The main file first, then the included files; deleted files only when asked for. */
    private List<Entry> allEntries(boolean includeDeleted) {
        List<Entry> all = new ArrayList<>();
        all.add(mainEntry);
        for (Entry e : entries) {
            if (includeDeleted || !e.deleted) {
                all.add(e);
            }
        }
        return all;
    }

    private Entry entryOf(ConfigFile file) {
        for (Entry e : allEntries(true)) {
            if (e.file == file) {
                return e;
            }
        }
        return null;
    }
}
