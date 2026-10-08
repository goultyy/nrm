package mt.su.nrm.proxy;

import java.util.List;

/**
 * Adds proxy presets to the built-in ones. Register an implementation in
 * {@code META-INF/services/mt.su.nrm.proxy.ProxyPresetProvider} (it is found with {@link java.util.ServiceLoader})
 * and its presets appear in the real-IP screen with no other change.
 */
public interface ProxyPresetProvider {

    List<ProxyPreset> presets();
}
