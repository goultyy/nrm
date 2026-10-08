package mt.su.nrm.nginx;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Helpers for directives whose trailing arguments are {@code key=value} parameters. */
final class Params {

    private Params() {
    }

    /** The value of {@code key=...} among the arguments from index {@code from}, or "" if absent. */
    static String get(List<String> values, int from, String key) {
        for (int i = from; i < values.size(); i++) {
            String t = values.get(i);
            if (t.startsWith(key + "=")) {
                return t.substring(key.length() + 1);
            }
        }
        return "";
    }

    /** True if the bare word (no value) is among the arguments from {@code from}. */
    static boolean has(List<String> values, int from, String word) {
        return values.subList(Math.min(from, values.size()), values.size()).contains(word);
    }

    /**
     * Returns the arguments with the named parameters set to the wanted values (an empty value
     * removes the parameter). Parameters not named, and everything before {@code from}, are kept
     * exactly and in order; parameters that were missing are appended.
     */
    static List<String> update(List<String> current, int from, Map<String, String> wanted) {
        List<String> out = new ArrayList<>(current.subList(0, Math.min(from, current.size())));
        Set<String> used = new HashSet<>();
        for (int i = from; i < current.size(); i++) {
            String t = current.get(i);
            int eq = t.indexOf('=');
            String key = eq > 0 ? t.substring(0, eq) : null;
            if (key != null && wanted.containsKey(key)) {
                used.add(key);
                String v = wanted.get(key);
                if (!v.isEmpty()) {
                    out.add(key + "=" + v);
                }
            } else {
                out.add(t);
            }
        }
        for (Map.Entry<String, String> e : new LinkedHashMap<>(wanted).entrySet()) {
            if (!used.contains(e.getKey()) && !e.getValue().isEmpty()) {
                out.add(e.getKey() + "=" + e.getValue());
            }
        }
        return out;
    }
}
