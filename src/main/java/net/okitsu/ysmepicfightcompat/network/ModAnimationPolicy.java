package net.okitsu.ysmepicfightcompat.network;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import net.okitsu.ysmepicfightcompat.animation.ModAnimationClips;
import net.okitsu.ysmepicfightcompat.animation.ModAnimationType;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Bounded model-specific exclusions within one optional mod's official clip vocabulary. */
public final class ModAnimationPolicy {
    public static final int MAX_MODELS = 256;
    public static final int MAX_MODEL_ID_LENGTH = 256;

    private final ModAnimationType type;
    private final boolean ysmEnabled;
    private final Map<String, Set<String>> modelRules;

    private ModAnimationPolicy(ModAnimationType type, boolean ysmEnabled,
                               Map<String, List<String>> normalizedRules) {
        this.type = type;
        this.ysmEnabled = ysmEnabled;
        Map<String, Set<String>> copied = new LinkedHashMap<>();
        normalizedRules.forEach((modelId, selectors) -> copied.put(modelId, Set.copyOf(selectors)));
        this.modelRules = Map.copyOf(copied);
    }

    public static ModAnimationPolicy create(ModAnimationType type, boolean ysmEnabled,
                                             Map<String, ? extends Collection<?>> configured) {
        return new ModAnimationPolicy(type, ysmEnabled,
                normalizeRules(type, configured == null ? Map.of() : configured, false));
    }

    /** Exclusions can only disable a known canonical clip of this policy's family. */
    public boolean usesYsm(String modelId, String canonicalClipName) {
        String normalizedModelId = normalizeModelId(modelId);
        if (!ysmEnabled || !isValidModelId(normalizedModelId)
                || ModAnimationClips.type(canonicalClipName) != type) {
            return false;
        }
        String selector = canonicalClipName.substring(canonicalClipName.indexOf(':') + 1);
        Set<String> excluded = modelRules.get(normalizedModelId);
        return excluded == null || !excluded.contains(selector);
    }

    public static boolean isValidConfiguration(ModAnimationType type, Object value) {
        if (!(value instanceof UnmodifiableConfig config)) {
            return false;
        }
        try {
            normalizeRules(type, config.valueMap(), true);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    /** Invalid external configuration is rejected as a whole, not partially applied. */
    public static Map<String, List<String>> decodeConfiguration(ModAnimationType type, Object value) {
        if (!(value instanceof UnmodifiableConfig config)) {
            return Map.of();
        }
        try {
            return normalizeRules(type, config.valueMap(), true);
        } catch (IllegalArgumentException invalid) {
            return Map.of();
        }
    }

    public static Config encodeConfiguration(ModAnimationType type,
                                              Map<String, ? extends Collection<String>> rules) {
        // Validate before Config#set: otherwise normalized model-ID collisions could
        // silently overwrite an earlier entry, and invalid selectors could disappear.
        Map<String, List<String>> normalized = normalizeRules(
                type, rules == null ? Map.of() : rules, false);
        Config result = Config.inMemory();
        normalized.forEach((modelId, selectors) -> result.set(List.of(modelId), selectors));
        return result;
    }

    public static boolean isValidSelector(ModAnimationType type, Object value) {
        return normalizeSelector(type, value) != null;
    }

    public static int maxSelectorsPerModel(ModAnimationType type) {
        return ModAnimationClips.selectors(type).size();
    }

    public static boolean isValidModelId(String modelId) {
        return modelId != null && !modelId.isEmpty() && modelId.length() <= MAX_MODEL_ID_LENGTH
                && modelId.chars().noneMatch(Character::isISOControl);
    }

    public static String normalizeModelId(String modelId) {
        return modelId == null ? "" : modelId.trim().toLowerCase(Locale.ROOT);
    }

    private static Map<String, List<String>> normalizeRules(
            ModAnimationType type, Map<?, ?> source, boolean requireLists) {
        if (type == null) {
            throw new IllegalArgumentException("Missing mod-animation family");
        }
        if (source.size() > MAX_MODELS) {
            throw new IllegalArgumentException("Too many mod-animation model entries");
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String rawModelId)) {
                throw new IllegalArgumentException("Mod-animation model ID must be a string");
            }
            String modelId = normalizeModelId(rawModelId);
            if (!isValidModelId(modelId) || result.containsKey(modelId)) {
                throw new IllegalArgumentException("Invalid or duplicate mod-animation model ID: " + rawModelId);
            }
            Object rawSelectors = entry.getValue();
            if (!(rawSelectors instanceof Collection<?> selectors)
                    || requireLists && !(rawSelectors instanceof List<?>)) {
                throw new IllegalArgumentException("Mod-animation selectors must be a list for model " + rawModelId);
            }
            if (selectors.size() > maxSelectorsPerModel(type)) {
                throw new IllegalArgumentException("Too many mod-animation selectors for model " + rawModelId);
            }
            Set<String> normalizedSelectors = new LinkedHashSet<>();
            for (Object value : selectors) {
                String selector = normalizeSelector(type, value);
                if (selector == null) {
                    throw new IllegalArgumentException("Invalid mod-animation selector for model " + rawModelId);
                }
                normalizedSelectors.add(selector);
            }
            result.put(modelId, List.copyOf(normalizedSelectors));
        }
        return Map.copyOf(result);
    }

    private static String normalizeSelector(ModAnimationType type, Object value) {
        if (!(value instanceof String text)) {
            return null;
        }
        String selector = text.trim().toLowerCase(Locale.ROOT);
        return ModAnimationClips.selectors(type).contains(selector) ? selector : null;
    }
}
