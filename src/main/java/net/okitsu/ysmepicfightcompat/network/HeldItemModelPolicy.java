package net.okitsu.ysmepicfightcompat.network;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Client-local rules for choosing YSM held-item models for a selected YSM model. */
public final class HeldItemModelPolicy {
    public static final int MAX_MODELS = 256;
    public static final int MAX_SELECTORS_PER_MODEL = 256;
    public static final int MAX_MODEL_ID_LENGTH = 256;
    public static final int MAX_SELECTOR_LENGTH = 256;
    public static final HeldItemModelPolicy DEFAULT =
            new HeldItemModelPolicy(true, Map.of());

    private final boolean ysmByDefault;
    private final Map<String, ModelRules> modelRules;

    private HeldItemModelPolicy(boolean ysmByDefault,
                                Map<String, ModelRules> modelRules) {
        this.ysmByDefault = ysmByDefault;
        this.modelRules = Map.copyOf(modelRules);
    }

    public static HeldItemModelPolicy create(boolean ysmByDefault,
                                             Map<String, ? extends Collection<?>> configured) {
        Map<String, ? extends Collection<?>> source =
                configured == null ? Map.of() : configured;
        if (source.size() > MAX_MODELS) {
            throw new IllegalArgumentException("Too many held-item model entries");
        }

        Map<String, ModelRules> parsedRules = new LinkedHashMap<>();
        source.forEach((rawModelId, selectors) -> {
            String modelId = normalizeModelId(rawModelId);
            if (!isValidModelId(modelId) || parsedRules.containsKey(modelId)) {
                throw new IllegalArgumentException(
                        "Invalid or duplicate held-item model ID: " + rawModelId);
            }
            Collection<?> values = selectors == null ? List.of() : selectors;
            if (values.size() > MAX_SELECTORS_PER_MODEL) {
                throw new IllegalArgumentException(
                        "Too many held-item selectors for model " + rawModelId);
            }
            LinkedHashSet<ResourceLocation> items = new LinkedHashSet<>();
            LinkedHashSet<ResourceLocation> tags = new LinkedHashSet<>();
            for (Object value : values) {
                ParsedSelector parsed = parseSelector(value);
                if (parsed == null) {
                    throw new IllegalArgumentException(
                            "Invalid held-item selector for model " + rawModelId + ": " + value);
                }
                (parsed.tag() ? tags : items).add(parsed.id());
            }
            parsedRules.put(modelId,
                    new ModelRules(Set.copyOf(items), Set.copyOf(tags)));
        });

        return parsedRules.isEmpty() && ysmByDefault ? DEFAULT
                : new HeldItemModelPolicy(ysmByDefault, parsedRules);
    }

    /** Validates the dynamic model table stored as one Forge config value. */
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

    /** Converts the NightConfig table into an immutable Java representation. */
    public static Map<String, List<String>> decodeConfiguration(Object value) {
        if (!isValidConfiguration(value)) {
            return Map.of();
        }
        UnmodifiableConfig config = (UnmodifiableConfig) value;
        Map<String, List<String>> result = new LinkedHashMap<>();
        config.valueMap().forEach((modelId, rawSelectors) -> {
            List<?> selectors = (List<?>) rawSelectors;
            List<String> copied = selectors.stream()
                    .map(selector -> ((String) selector).trim().toLowerCase(Locale.ROOT))
                    .toList();
            result.put(normalizeModelId(modelId), copied);
        });
        return Map.copyOf(result);
    }

    /** Creates the nested table used by Forge/NightConfig's TOML writer. */
    public static Config encodeConfiguration(
            Map<String, ? extends Collection<String>> rules) {
        Map<String, ? extends Collection<String>> source =
                rules == null ? Map.of() : rules;
        Config result = Config.inMemory();
        source.forEach((modelId, selectors) -> result.set(List.of(normalizeModelId(modelId)),
                selectors == null ? List.of() : selectors.stream()
                        .map(value -> value.trim().toLowerCase(Locale.ROOT))
                        .distinct()
                        .toList()));
        if (!isValidConfiguration(result)) {
            throw new IllegalArgumentException("Invalid held-item model configuration");
        }
        return result;
    }

    /** Configured list-entry validator for item IDs and item tags. */
    public static boolean isValidSelector(Object value) {
        return parseSelector(value) != null;
    }

    public boolean usesYsm(String modelId, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return usesYsm(modelId, itemId,
                tag -> stack.is(TagKey.create(Registries.ITEM, tag)));
    }

    boolean usesYsm(String modelId, ResourceLocation itemId,
                    Predicate<ResourceLocation> tagMatcher) {
        if (itemId == null) {
            return false;
        }
        ModelRules rules = modelRules.get(normalizeModelId(modelId));
        boolean overridden = rules != null && (rules.items().contains(itemId)
                || rules.tags().stream().anyMatch(tagMatcher));
        return overridden ? !ysmByDefault : ysmByDefault;
    }

    private static ParsedSelector parseSelector(Object source) {
        if (!(source instanceof String value)) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.length() > MAX_SELECTOR_LENGTH) {
            return null;
        }
        boolean tag = normalized.charAt(0) == '#';
        String idText = tag ? normalized.substring(1) : normalized;
        if (idText.isEmpty()) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(idText);
        return id == null ? null : new ParsedSelector(tag, id);
    }

    private static boolean isValidModelId(String modelId) {
        return !modelId.isEmpty() && modelId.length() <= MAX_MODEL_ID_LENGTH
                && modelId.chars().noneMatch(Character::isISOControl);
    }

    private static String normalizeModelId(String modelId) {
        return modelId == null ? "" : modelId.trim().toLowerCase(Locale.ROOT);
    }

    private record ParsedSelector(boolean tag, ResourceLocation id) {
    }

    private record ModelRules(Set<ResourceLocation> items,
                              Set<ResourceLocation> tags) {
    }
}
