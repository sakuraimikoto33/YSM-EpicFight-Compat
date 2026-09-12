package net.okitsu.ysmepicfightcompat.config;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.UnmodifiableConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Reuses decoded rule tables while still observing in-place NightConfig edits. */
final class DecodedRuleCache {
    private final Function<Object, Map<String, List<String>>> decoder;
    private final int maxModels;
    private final int maxSelectors;
    private UnmodifiableConfig snapshot;
    private Map<String, List<String>> decoded;

    DecodedRuleCache(Function<Object, Map<String, List<String>>> decoder,
                     int maxModels, int maxSelectors) {
        this.decoder = decoder;
        this.maxModels = maxModels;
        this.maxSelectors = maxSelectors;
    }

    synchronized Map<String, List<String>> get(Object raw, boolean loaded) {
        // Forge can supply a fresh default before loading in production. It must
        // not become a cached loaded value. The caller still reads ConfigValue.get()
        // first, preserving each loader's own pre-load checks and exceptions.
        if (!loaded) {
            snapshot = null;
            decoded = null;
            return decoder.apply(raw);
        }
        if (matches(raw)) {
            return decoded;
        }

        UnmodifiableConfig nextSnapshot = snapshot(raw);
        Map<String, List<String>> next = decoder.apply(nextSnapshot == null ? raw : nextSnapshot);
        if (decoded != null && decoded.equals(next)) {
            next = decoded;
        }
        snapshot = nextSnapshot;
        decoded = next;
        return next;
    }

    private boolean matches(Object raw) {
        if (snapshot == null || !(raw instanceof UnmodifiableConfig config)
                || config.size() > maxModels || config.size() != snapshot.size()) {
            return false;
        }
        Map<String, Object> values = config.valueMap();
        for (Map.Entry<String, Object> entry : snapshot.valueMap().entrySet()) {
            if (!(values.get(entry.getKey()) instanceof List<?> selectors)
                    || selectors.size() > maxSelectors || !entry.getValue().equals(selectors)) {
                return false;
            }
        }
        return true;
    }

    private UnmodifiableConfig snapshot(Object raw) {
        if (!(raw instanceof UnmodifiableConfig config) || config.size() > maxModels) {
            return null;
        }
        // Preserve the decoders' early size rejection: a malformed, oversized
        // table or list must not trigger allocation or a deep traversal here.
        for (Map.Entry<?, ?> entry : config.valueMap().entrySet()) {
            if (!(entry.getKey() instanceof String)
                    || !(entry.getValue() instanceof List<?> values) || values.size() > maxSelectors) {
                return null;
            }
        }
        Config copy = Config.inMemory();
        for (Map.Entry<String, Object> entry : config.valueMap().entrySet()) {
            List<?> values = (List<?>) entry.getValue();
            List<String> selectors = new ArrayList<>(values.size());
            for (Object value : values) {
                // Other shapes are invalid for every rule decoder. Do not retain
                // unknown mutable objects: delegate uncached until they are fixed.
                if (!(value instanceof String selector)) {
                    return null;
                }
                selectors.add(selector);
            }
            // Model IDs can contain dots; they are literal keys, not config paths.
            copy.valueMap().put(entry.getKey(), List.copyOf(selectors));
        }
        return copy.unmodifiable();
    }
}
