package net.okitsu.ysmepicfightcompat.network;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import net.okitsu.ysmepicfightcompat.animation.MovementAnimationType;

import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Client-local model rules deciding which semantic movement states use YSM poses. */
public final class MovementAnimationPolicy {
    public static final int MAX_MODELS = 256;
    public static final int MAX_SELECTORS_PER_MODEL = MovementAnimationType.values().length;
    public static final int MAX_MODEL_ID_LENGTH = 256;
    public static final MovementAnimationPolicy DEFAULT =
            new MovementAnimationPolicy(false, Map.of());

    private final boolean ysmByDefault;
    private final Map<String, Set<MovementAnimationType>> modelRules;

    private MovementAnimationPolicy(
            boolean ysmByDefault,
            Map<String, Set<MovementAnimationType>> modelRules) {
        this.ysmByDefault = ysmByDefault;
        this.modelRules = Map.copyOf(modelRules);
    }

    public static MovementAnimationPolicy create(
            boolean ysmByDefault,
            Map<String, ? extends Collection<?>> configured) {
        Map<String, ? extends Collection<?>> source =
                configured == null ? Map.of() : configured;
        if (source.size() > MAX_MODELS) {
            throw new IllegalArgumentException("Too many movement-animation model entries");
        }

        Map<String, Set<MovementAnimationType>> parsed = new LinkedHashMap<>();
        source.forEach((rawModelId, selectors) -> {
            String modelId = normalizeModelId(rawModelId);
            if (!isValidModelId(modelId) || parsed.containsKey(modelId)) {
                throw new IllegalArgumentException(
                        "Invalid or duplicate movement-animation model ID: " + rawModelId);
            }
            Collection<?> values = selectors == null ? List.of() : selectors;
            if (values.size() > MAX_SELECTORS_PER_MODEL) {
                throw new IllegalArgumentException(
                        "Too many movement selectors for model " + rawModelId);
            }
            EnumSet<MovementAnimationType> kinds =
                    EnumSet.noneOf(MovementAnimationType.class);
            for (Object value : values) {
                MovementAnimationType kind = MovementAnimationType.fromConfigKey(value);
                if (kind == null) {
                    throw new IllegalArgumentException(
                            "Invalid movement selector for model " + rawModelId + ": " + value);
                }
                kinds.add(kind);
            }
            parsed.put(modelId, Set.copyOf(kinds));
        });
        return parsed.isEmpty() && !ysmByDefault ? DEFAULT
                : new MovementAnimationPolicy(ysmByDefault, parsed);
    }

    public boolean usesYsm(String modelId, MovementAnimationType kind) {
        if (kind == null) {
            return false;
        }
        Set<MovementAnimationType> rules = modelRules.get(normalizeModelId(modelId));
        boolean overridden = rules != null && rules.contains(kind);
        return overridden ? !ysmByDefault : ysmByDefault;
    }

    public static boolean isValidConfiguration(Object value) {
        if (!(value instanceof UnmodifiableConfig config)
                || config.size() > MAX_MODELS) {
            return false;
        }
        Set<String> normalizedIds = new LinkedHashSet<>();
        for (Map.Entry<String, Object> entry : config.valueMap().entrySet()) {
            String modelId = normalizeModelId(entry.getKey());
            if (!isValidModelId(modelId) || !normalizedIds.add(modelId)
                    || !(entry.getValue() instanceof List<?> selectors)
                    || selectors.size() > MAX_SELECTORS_PER_MODEL
                    || selectors.stream().anyMatch(selector -> !isValidSelector(selector))) {
                return false;
            }
        }
        return true;
    }

    public static Map<String, List<String>> decodeConfiguration(Object value) {
        if (!isValidConfiguration(value)) {
            return Map.of();
        }
        UnmodifiableConfig config = (UnmodifiableConfig) value;
        Map<String, List<String>> result = new LinkedHashMap<>();
        config.valueMap().forEach((modelId, rawSelectors) -> {
            List<?> selectors = (List<?>) rawSelectors;
            List<String> copied = selectors.stream()
                    .map(MovementAnimationType::fromConfigKey)
                    .map(MovementAnimationType::configKey)
                    .distinct().toList();
            result.put(normalizeModelId(modelId), copied);
        });
        return Map.copyOf(result);
    }

    public static Config encodeConfiguration(
            Map<String, ? extends Collection<String>> rules) {
        Map<String, ? extends Collection<String>> source =
                rules == null ? Map.of() : rules;
        Config result = Config.inMemory();
        source.forEach((modelId, selectors) -> result.set(
                List.of(normalizeModelId(modelId)), selectors == null ? List.of()
                        : selectors.stream()
                        .map(MovementAnimationType::fromConfigKey)
                        .map(kind -> kind == null ? "" : kind.configKey())
                        .filter(value -> !value.isEmpty())
                        .distinct().toList()));
        if (!isValidConfiguration(result)) {
            throw new IllegalArgumentException("Invalid movement-animation configuration");
        }
        return result;
    }

    public static boolean isValidSelector(Object value) {
        return MovementAnimationType.fromConfigKey(value) != null;
    }

    static boolean isValidModelId(String modelId) {
        return !modelId.isEmpty() && modelId.length() <= MAX_MODEL_ID_LENGTH
                && modelId.chars().noneMatch(Character::isISOControl);
    }

    public static String normalizeModelId(String modelId) {
        return modelId == null ? "" : modelId.trim().toLowerCase(Locale.ROOT);
    }
}
