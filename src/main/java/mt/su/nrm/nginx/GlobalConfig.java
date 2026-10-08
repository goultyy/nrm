package mt.su.nrm.nginx;

import java.util.List;

/** The global settings of the main nginx.conf (main, events and http contexts), viewed for editing. */
public final class GlobalConfig {

    private static final List<String> MAIN_ORDER = List.of("user", "worker_processes", "pid", "error_log");
    private static final List<String> HTTP_ORDER = List.of("sendfile", "tcp_nopush", "keepalive_timeout",
            "client_max_body_size", "server_tokens", "ssl_protocols", "ssl_prefer_server_ciphers", "access_log",
            "gzip", "gzip_comp_level", "gzip_min_length", "gzip_types");
    private static final List<String> EVENTS_ORDER = List.of("worker_connections");

    private final ConfigFile file;

    GlobalConfig(ConfigFile file) {
        this.file = file;
    }

    public GlobalSettings read() {
        Block main = file.root();
        Block events = first(main, "events");
        Block http = first(main, "http");
        GlobalSettings s = new GlobalSettings();
        s.workerProcesses = single(main, "worker_processes");
        s.errorLog = joined(main, "error_log");
        if (events != null) {
            s.workerConnections = single(events, "worker_connections");
        }
        if (http != null) {
            s.sendfile = single(http, "sendfile");
            s.tcpNopush = single(http, "tcp_nopush");
            s.keepaliveTimeout = single(http, "keepalive_timeout");
            s.clientMaxBodySize = single(http, "client_max_body_size");
            s.serverTokens = single(http, "server_tokens");
            s.sslProtocols = joined(http, "ssl_protocols");
            s.sslPreferServerCiphers = single(http, "ssl_prefer_server_ciphers");
            s.accessLog = joined(http, "access_log");
            s.gzip = single(http, "gzip");
            s.gzipCompLevel = single(http, "gzip_comp_level");
            s.gzipMinLength = single(http, "gzip_min_length");
            s.gzipTypes = joined(http, "gzip_types");
        }
        return s;
    }

    public void apply(GlobalSettings s) {
        Block main = file.root();
        Block events = first(main, "events");
        Block http = first(main, "http");
        VirtualHost.syncFirstArg(main, "worker_processes", s.workerProcesses, MAIN_ORDER);
        VirtualHost.syncSingle(main, "error_log", Arg.parseValues(s.errorLog), MAIN_ORDER);
        if (events != null) {
            VirtualHost.syncFirstArg(events, "worker_connections", s.workerConnections, EVENTS_ORDER);
        }
        if (http != null) {
            VirtualHost.syncFirstArg(http, "sendfile", s.sendfile, HTTP_ORDER);
            VirtualHost.syncFirstArg(http, "tcp_nopush", s.tcpNopush, HTTP_ORDER);
            VirtualHost.syncFirstArg(http, "keepalive_timeout", s.keepaliveTimeout, HTTP_ORDER);
            VirtualHost.syncFirstArg(http, "client_max_body_size", s.clientMaxBodySize, HTTP_ORDER);
            VirtualHost.syncFirstArg(http, "server_tokens", s.serverTokens, HTTP_ORDER);
            VirtualHost.syncSingle(http, "ssl_protocols", Arg.parseValues(s.sslProtocols), HTTP_ORDER);
            VirtualHost.syncFirstArg(http, "ssl_prefer_server_ciphers", s.sslPreferServerCiphers, HTTP_ORDER);
            VirtualHost.syncSingle(http, "access_log", Arg.parseValues(s.accessLog), HTTP_ORDER);
            VirtualHost.syncFirstArg(http, "gzip", s.gzip, HTTP_ORDER);
            VirtualHost.syncFirstArg(http, "gzip_comp_level", s.gzipCompLevel, HTTP_ORDER);
            VirtualHost.syncFirstArg(http, "gzip_min_length", s.gzipMinLength, HTTP_ORDER);
            VirtualHost.syncSingle(http, "gzip_types", Arg.parseValues(s.gzipTypes), HTTP_ORDER);
        }
    }

    private static Block first(Block parent, String name) {
        List<Block> blocks = parent.blocks(name);
        return blocks.isEmpty() || blocks.get(0).isOpaque() ? null : blocks.get(0);
    }

    private static String single(Block block, String name) {
        Directive d = block.first(name);
        return d == null || d.args.isEmpty() ? "" : d.arg(0);
    }

    private static String joined(Block block, String name) {
        Directive d = block.first(name);
        return d == null ? "" : Arg.displayJoin(d.args);
    }
}
