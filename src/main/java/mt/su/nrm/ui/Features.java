package mt.su.nrm.ui;

import mt.su.nrm.util.AppLog;
import mt.su.nrm.util.FeatureSettings;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * The optional features and which of them are on. Everything is off until the user turns it on, and the choice is
 * remembered. Used on the JavaFX thread.
 */
public final class Features {

    private static Set<String> enabled;
    private static final List<Runnable> LISTENERS = new ArrayList<>();

    private Features() {
    }

    /** The built-in features, then any from {@link ServiceLoader}; a provided one can't replace a built-in id. */
    public static List<Feature> all() {
        List<Feature> all = new ArrayList<>(List.of(new StatusPageFeature(), new IpAddressesFeature()));
        for (Feature provided : ServiceLoader.load(Feature.class)) {
            if (all.stream().noneMatch(f -> f.id().equals(provided.id()))) {
                all.add(provided);
            }
        }
        return List.copyOf(all);
    }

    public static Optional<Feature> byId(String id) {
        return all().stream().filter(f -> f.id().equals(id)).findFirst();
    }

    private static Set<String> ids() {
        if (enabled == null) {
            enabled = new LinkedHashSet<>(FeatureSettings.load(settingsFile()));
        }
        return enabled;
    }

    private static java.nio.file.Path settingsFile() {
        return mt.su.nrm.util.ThemeSettings.file();
    }

    public static boolean isEnabled(Feature feature) {
        return ids().contains(feature.id());
    }

    /** The features that are on, in the order they are listed. */
    public static List<Feature> enabled() {
        return all().stream().filter(Features::isEnabled).toList();
    }

    /** Turns exactly these features on, remembers it, and tells the listeners (the tree rebuilds itself). */
    public static void setEnabled(Collection<String> featureIds) {
        Set<String> now = new LinkedHashSet<>(featureIds);
        if (now.equals(ids())) {
            return;
        }
        enabled = now;
        try {
            FeatureSettings.save(settingsFile(), now);
        } catch (IOException e) {
            // They still apply for this session.
            AppLog.warn("Could not save which features are on", e);
        }
        new ArrayList<>(LISTENERS).forEach(Runnable::run);
    }

    /** Runs when the set of features that are on changes. */
    public static void addListener(Runnable listener) {
        LISTENERS.add(listener);
    }

    /** Forgets what was loaded so the settings file is read again (used by tests). */
    static void reload() {
        enabled = null;
    }
}
