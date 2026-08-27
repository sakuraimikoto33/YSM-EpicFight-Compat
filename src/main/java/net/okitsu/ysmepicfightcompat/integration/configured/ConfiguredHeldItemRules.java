package net.okitsu.ysmepicfightcompat.integration.configured;

import com.mrcrayfish.configured.api.IConfigEntry;
import com.mrcrayfish.configured.api.IConfigValue;
import com.mrcrayfish.configured.api.ValueEntry;
import com.mrcrayfish.configured.client.screen.list.IListConfigValue;
import com.mrcrayfish.configured.client.screen.list.IListType;
import com.mrcrayfish.configured.client.screen.list.ListTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.okitsu.ysmepicfightcompat.config.ClientPreferences;
import net.okitsu.ysmepicfightcompat.network.HeldItemModelPolicy;
import net.okitsu.ysmepicfightcompat.network.MovementAnimationPolicy;
import net.okitsu.ysmepicfightcompat.render.PlayerSelectionResolver;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Optional Configured dynamic-rule entries embedded into the regular Forge client-config tree.
 *
 * <p>This class deliberately remains behind string-targeted optional mixins. Nothing
 * outside this package may link Configured classes, so Configured is not required at
 * runtime.</p>
 */
public final class ConfiguredHeldItemRules {
    private ConfiguredHeldItemRules() {
    }

    /** Called through an Object-only mixin boundary to avoid hard optional linkage. */
    public static boolean isPlaceholder(Object entry) {
        if (!(entry instanceof IConfigEntry configEntry) || !configEntry.isLeaf()) {
            return false;
        }
        IConfigValue<?> value = configEntry.getValue();
        return value != null && RuleKind.fromEntryName(value.getName()) != null;
    }

    /** Stable cache key used by the Object-only mixin boundary. */
    public static String placeholderKey(Object entry) {
        if (!(entry instanceof IConfigEntry configEntry)
                || configEntry.getValue() == null) {
            return "";
        }
        RuleKind kind = RuleKind.fromEntryName(configEntry.getValue().getName());
        return kind == null ? "" : kind.entryName();
    }

    /** Called through an Object-only mixin boundary to avoid hard optional linkage. */
    public static Object createEntry(Object placeholder) {
        if (!(placeholder instanceof IConfigEntry configEntry)
                || configEntry.getValue() == null) {
            throw new IllegalArgumentException("Missing dynamic-rule placeholder");
        }
        RuleKind kind = RuleKind.fromEntryName(configEntry.getValue().getName());
        if (kind == null) {
            throw new IllegalArgumentException("Unknown dynamic-rule placeholder");
        }
        return new RulesFolder(kind, kind.initialRules(), selectedModelId());
    }

    /**
     * Writes the dynamic values before Configured gathers its changed-value set.
     * RuleValue remains changed until {@link #finishSave(Object)}, which lets
     * Configured run its normal Forge reload notification path.
     */
    public static void prepareSave(Object entry) {
        findRulesFolders(entry).stream().filter(RulesFolder::isChanged)
                .forEach(RulesFolder::prepareSave);
    }

    /** Called after Configured has completed its normal Forge-config update. */
    public static void finishSave(Object entry) {
        findRulesFolders(entry).forEach(RulesFolder::markSaved);
    }

    private static List<RulesFolder> findRulesFolders(Object entry) {
        if (!(entry instanceof IConfigEntry configEntry)) {
            return List.of();
        }
        if (configEntry instanceof RulesFolder folder) {
            return List.of(folder);
        }
        List<RulesFolder> result = new java.util.ArrayList<>();
        for (IConfigEntry child : configEntry.getChildren()) {
            result.addAll(findRulesFolders(child));
        }
        return List.copyOf(result);
    }

    private static final class RulesFolder implements IConfigEntry {
        private final RuleKind kind;
        private final Map<String, RuleValue> values = new LinkedHashMap<>();

        private RulesFolder(RuleKind kind, Map<String, List<String>> initial,
                            String selectedModelId) {
            this.kind = kind;
            initial.forEach((modelId, selectors) -> values.put(modelId,
                    new RuleValue(kind, modelId, selectors)));
            ensureModel(selectedModelId);
        }

        private void ensureModel(String modelId) {
            String normalized = normalizeModelId(modelId);
            if (!normalized.isEmpty()
                    && values.size() < kind.maxModels()) {
                values.computeIfAbsent(normalized,
                        key -> new RuleValue(kind, key, List.of()));
            }
        }

        private boolean isChanged() {
            return values.values().stream().anyMatch(RuleValue::isChanged);
        }

        private Map<String, List<String>> nonEmptyRules() {
            Map<String, List<String>> result = new LinkedHashMap<>();
            values.values().stream()
                    .sorted(Comparator.comparing(RuleValue::getName))
                    .filter(value -> !value.get().isEmpty())
                    .forEach(value -> result.put(value.getName(), value.get()));
            return Map.copyOf(result);
        }

        private void markSaved() {
            values.values().forEach(RuleValue::markSaved);
        }

        private void prepareSave() {
            kind.save(nonEmptyRules());
        }

        @Override
        public List<IConfigEntry> getChildren() {
            ensureModel(selectedModelId());
            return values.values().stream()
                    .sorted(Comparator.comparing(RuleValue::getName))
                    .map(ValueEntry::new)
                    .map(IConfigEntry.class::cast)
                    .toList();
        }

        @Override
        public boolean isRoot() {
            return false;
        }

        @Override
        public boolean isLeaf() {
            return false;
        }

        @Override
        public IConfigValue<?> getValue() {
            return null;
        }

        @Override
        public String getEntryName() {
            return kind.entryName();
        }

        @Override
        public Component getTooltip() {
            return Component.translatable(
                    kind.translationKey() + ".tooltip");
        }

        @Override
        public String getTranslationKey() {
            return kind.translationKey();
        }
    }

    private static final class RuleValue implements IListConfigValue<String> {
        private final RuleKind kind;
        private final String modelId;
        private List<String> initial;
        private List<String> current;

        private RuleValue(RuleKind kind, String modelId,
                          Collection<String> selectors) {
            this.kind = kind;
            this.modelId = normalizeModelId(modelId);
            this.current = normalizeSelectors(selectors);
            this.initial = current;
        }

        private void markSaved() {
            initial = current;
        }

        @Override
        public List<String> get() {
            return current;
        }

        @Override
        public List<String> getDefault() {
            return List.of();
        }

        @Override
        public void set(List<String> value) {
            if (isValid(value)) {
                current = normalizeSelectors(value);
            }
        }

        @Override
        public boolean isValid(List<String> value) {
            return value != null
                    && value.size()
                    <= kind.maxSelectorsPerModel()
                    && value.stream().allMatch(kind::isValidSelector);
        }

        @Override
        public boolean isDefault() {
            return current.isEmpty();
        }

        @Override
        public boolean isChanged() {
            return !current.equals(initial);
        }

        @Override
        public void restore() {
            current = List.of();
        }

        @Override
        public Component getComment() {
            return Component.translatable(
                    kind.entryTooltipKey());
        }

        @Nullable
        @Override
        public String getTranslationKey() {
            return null;
        }

        @Override
        public Component getValidationHint() {
            return Component.translatable(
                    kind.invalidKey());
        }

        @Override
        public String getName() {
            return modelId;
        }

        @Override
        public void cleanCache() {
        }

        @Override
        public boolean requiresWorldRestart() {
            return false;
        }

        @Override
        public boolean requiresGameRestart() {
            return false;
        }

        @Override
        public IListType<String> getListType() {
            return ListTypes.STRING;
        }

        private static List<String> normalizeSelectors(
                Collection<String> selectors) {
            if (selectors == null) {
                return List.of();
            }
            LinkedHashSet<String> normalized = new LinkedHashSet<>();
            selectors.stream()
                    .filter(Objects::nonNull)
                    .map(value -> value.trim().toLowerCase(Locale.ROOT))
                    .filter(value -> !value.isEmpty())
                    .forEach(normalized::add);
            return List.copyOf(normalized);
        }
    }

    private static String selectedModelId() {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null) {
            return "";
        }
        PlayerSelectionResolver.Selection selection =
                PlayerSelectionResolver.current(player);
        return selection == null ? "" : selection.modelId();
    }

    private static String normalizeModelId(String modelId) {
        return modelId == null ? ""
                : modelId.trim().toLowerCase(Locale.ROOT);
    }

    private enum RuleKind {
        HELD_ITEM("heldItemModelExclusions",
                "config.ysm_epicfight_compat.held_item_model_exclusions",
                "config.ysm_epicfight_compat.held_item_model_entry.tooltip",
                "config.ysm_epicfight_compat.held_item_model_entry.invalid"),
        HELD_ITEM_SWITCH("heldItemSwitchAnimationExclusions",
                "config.ysm_epicfight_compat.held_item_switch_animation_exclusions",
                "config.ysm_epicfight_compat.held_item_switch_animation_entry.tooltip",
                "config.ysm_epicfight_compat.held_item_switch_animation_entry.invalid"),
        MOVEMENT("movementAnimationExclusions",
                "config.ysm_epicfight_compat.movement_animation_exclusions",
                "config.ysm_epicfight_compat.movement_animation_entry.tooltip",
                "config.ysm_epicfight_compat.movement_animation_entry.invalid");

        private final String entryName;
        private final String translationKey;
        private final String entryTooltipKey;
        private final String invalidKey;

        RuleKind(String entryName, String translationKey,
                 String entryTooltipKey, String invalidKey) {
            this.entryName = entryName;
            this.translationKey = translationKey;
            this.entryTooltipKey = entryTooltipKey;
            this.invalidKey = invalidKey;
        }

        private static RuleKind fromEntryName(String name) {
            for (RuleKind kind : values()) {
                if (kind.entryName.equals(name)) {
                    return kind;
                }
            }
            return null;
        }

        private Map<String, List<String>> initialRules() {
            return switch (this) {
                case HELD_ITEM -> ClientPreferences.heldItemModelExclusions();
                case HELD_ITEM_SWITCH ->
                        ClientPreferences.heldItemSwitchAnimationExclusions();
                case MOVEMENT -> ClientPreferences.movementAnimationExclusions();
            };
        }

        private void save(Map<String, List<String>> rules) {
            switch (this) {
                case HELD_ITEM -> ClientPreferences.setHeldItemModelExclusions(rules);
                case HELD_ITEM_SWITCH ->
                        ClientPreferences.setHeldItemSwitchAnimationExclusions(rules);
                case MOVEMENT -> ClientPreferences.setMovementAnimationExclusions(rules);
            }
        }

        private int maxModels() {
            return this == MOVEMENT ? MovementAnimationPolicy.MAX_MODELS
                    : HeldItemModelPolicy.MAX_MODELS;
        }

        private int maxSelectorsPerModel() {
            return this == MOVEMENT
                    ? MovementAnimationPolicy.MAX_SELECTORS_PER_MODEL
                    : HeldItemModelPolicy.MAX_SELECTORS_PER_MODEL;
        }

        private boolean isValidSelector(Object value) {
            return this == MOVEMENT
                    ? MovementAnimationPolicy.isValidSelector(value)
                    : HeldItemModelPolicy.isValidSelector(value);
        }

        private String entryName() {
            return entryName;
        }

        private String translationKey() {
            return translationKey;
        }

        private String entryTooltipKey() {
            return entryTooltipKey;
        }

        private String invalidKey() {
            return invalidKey;
        }
    }
}
