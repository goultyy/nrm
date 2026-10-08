package mt.su.nrm.nginx;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@code log_format} directive, viewed for editing: {@code log_format name [escape=default|json|none] string ...;}.
 * nginx joins several string arguments into one; they are read joined and written back as one.
 */
public final class LogFormat {

    private final ConfigFile file;
    private final Directive directive;

    LogFormat(ConfigFile file, Directive directive) {
        this.file = file;
        this.directive = directive;
    }

    public ConfigFile file() {
        return file;
    }

    public Directive directive() {
        return directive;
    }

    public String name() {
        return directive.arg(0) == null ? "" : directive.arg(0);
    }

    /**
     * True for the format the cache statistics feature adds and relies on. It is changed only by turning that feature
     * on or off (under Cache Zones), never here.
     */
    public boolean isManaged() {
        return CacheStatsLogging.FORMAT.equals(name());
    }

    public LogFormatSettings read() {
        List<String> v = directive.values();
        LogFormatSettings s = new LogFormatSettings();
        s.name = v.isEmpty() ? "" : v.get(0);
        int from = 1;
        if (v.size() > 1 && v.get(1).startsWith("escape=")) {
            s.escape = LogFormatSettings.Escape.of(v.get(1).substring("escape=".length()));
            from = 2;
        }
        s.text = String.join("", v.subList(Math.min(from, v.size()), v.size()));
        return s;
    }

    public void apply(LogFormatSettings s) {
        List<String> args = new ArrayList<>();
        args.add(s.name);
        if (s.escape != LogFormatSettings.Escape.DEFAULT) {
            args.add("escape=" + s.escape.keyword());
        }
        args.add(s.text);
        directive.setArgs(args);
    }
}
