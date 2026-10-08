package mt.su.nrm.ui;

import mt.su.nrm.logformat.LogFormatDesign;
import mt.su.nrm.model.ServerProfile;
import mt.su.nrm.nginx.LogFormat;
import mt.su.nrm.nginx.LogFormatSettings;
import mt.su.nrm.nginx.RemoteConfig;
import javafx.stage.Window;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Log formats: the {@code log_format} lines that sites' access logs choose from, created with an interactive builder.
 * A format that something still uses can't be deleted, and the one the cache statistics feature relies on is left to
 * that feature.
 */
final class LogFormatsPanel extends HttpObjectsPanel<LogFormat> {

    private final ServerConnection connection;

    LogFormatsPanel(ServerProfile profile, ServerConnection connection, ConnectionManager manager) {
        super(profile, connection, manager, "No log formats. Add one to choose what each line of an access log says.");
        this.connection = connection;
        addColumn("Name", 120, f -> f.name() + (f.isManaged() ? "  (cache statistics)" : ""));
        addColumn("Layout", 70, f -> LogFormatDesign.from(f.read()).style() == LogFormatDesign.Style.JSON ? "JSON" : "Text");
        addColumn("A line looks like", 380, f -> oneLine(LogFormatDesign.from(f.read()).preview()));
        addColumn("Used by", 70, f -> {
            int uses = connection.config() == null ? 0 : connection.config().logFormatUses(f.name()).size();
            return uses == 0 ? "-" : uses + (uses == 1 ? " log" : " logs");
        });
        addColumn("File", 180, f -> f.file().path());
        start();
    }

    private static String oneLine(String line) {
        String flat = line.replace('\n', ' ');
        return flat.length() > 140 ? flat.substring(0, 137) + "..." : flat;
    }

    @Override
    List<LogFormat> items(RemoteConfig config) {
        return config.logFormats();
    }

    @Override
    boolean add(RemoteConfig config, Window owner) {
        LogFormatSettings s = new LogFormatSettings();
        if (!LogFormatWizard.run(owner, s, names(config, null), true, List.of())) {
            return false;
        }
        config.createLogFormat(s.name).apply(s);
        return true;
    }

    @Override
    boolean edit(RemoteConfig config, LogFormat item, Window owner) {
        LogFormatSettings s = item.read();
        if (!LogFormatWizard.run(owner, s, names(config, item), false, config.logFormatUses(item.name()))) {
            return false;
        }
        item.apply(s);
        return true;
    }

    private static Set<String> names(RemoteConfig config, LogFormat except) {
        Set<String> names = new HashSet<>();
        for (LogFormat f : config.logFormats()) {
            if (except == null || f.directive() != except.directive()) {
                names.add(f.name());
            }
        }
        // nginx's built-in format can be replaced on purpose, so its name is not reserved.
        return names;
    }

    @Override
    void delete(RemoteConfig config, LogFormat item) {
        config.deleteLogFormat(item);
    }

    @Override
    String describe(LogFormat item) {
        return "log format " + item.name();
    }

    @Override
    String readOnlyReason(RemoteConfig config, LogFormat item) {
        if (item.isManaged()) {
            return "This format belongs to the cache statistics feature (under Cache Zones). Turn those off there to "
                    + "remove it.";
        }
        return config.readOnlyReason(item.file());
    }

    @Override
    String deleteBlockedReason(RemoteConfig config, LogFormat item) {
        List<String> uses = config.logFormatUses(item.name());
        if (uses.isEmpty()) {
            return null;
        }
        return "The format \"" + item.name() + "\" is still used by " + uses.size() + " access log line"
                + (uses.size() == 1 ? "" : "s") + ", and nginx would refuse a configuration that names a format that "
                + "doesn't exist. Change those first:\n\n" + String.join("\n", uses.stream().limit(10).toList())
                + (uses.size() > 10 ? "\n... and " + (uses.size() - 10) + " more" : "");
    }
}
