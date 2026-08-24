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
 * Optional Configured entries embedded into the regular Forge client-config tree.
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
        return value != null && "heldItemModelOverrides".equals(value.getName());
    }

    /** Called through an Object-only mixin boundary to avoid hard optional linkage. */
    public static Object createEntry() {
        return new RulesFolder(ClientPreferences.heldItemModelOverrides(),
                selectedModelId());
    }

    /**
     * Writes the dynamic values before Configured gathers its changed-value set.
     * RuleValue remains changed until {@link #finishSave(Object)}, which lets
     * Configured run its normal Forge reload notification path.
     */
    public static void prepareSave(Object entry) {
        findRulesFolder(entry).filter(RulesFolder::isChanged).ifPresent(folder ->
                ClientPreferences.setHeldItemModelOverrides(folder.nonEmptyRules()));
    }

    /** Called after Configured has completed its normal Forge-config update. */
    public static void finishSave(Object entry) {
        findRulesFolder(entry).ifPresent(RulesFolder::markSaved);
    }

    private static java.util.Optional<RulesFolder> findRulesFolder(Object entry) {
        if (!(entry instanceof IConfigEntry configEntry)) {
            return java.util.Optional.empty();
        }
        if (configEntry instanceof RulesFolder folder) {
            return java.util.Optional.of(folder);
        }
        for (IConfigEntry child : configEntry.getChildren()) {
            java.util.Optional<RulesFolder> result = findRulesFolder(child);
            if (result.isPresent()) {
                return result;
            }
        }
        return java.util.Optional.empty();
    }

    private static final class RulesFolder implements IConfigEntry {
        private final Map<String, RuleValue> values = new LinkedHashMap<>();

        private RulesFolder(Map<String, List<String>> initial,
                            String selectedModelId) {
            initial.forEach((modelId, selectors) -> values.put(modelId,
                    new RuleValue(modelId, selectors)));
            ensureModel(selectedModelId);
        }

        private void ensureModel(String modelId) {
            String normalized = normalizeModelId(modelId);
            if (!normalized.isEmpty()
                    && values.size() < HeldItemModelPolicy.MAX_MODELS) {
                values.computeIfAbsent(normalized,
                        key -> new RuleValue(key, List.of()));
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

        @Override
        public List<IConfigEntry> getChildren() {
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
            return "heldItemModelOverrides";
        }

        @Override
        public Component getTooltip() {
            return Component.translatable(
                    "config.ysm_epicfight_compat.held_item_model_overrides.tooltip");
        }

        @Override
        public String getTranslationKey() {
            return "config.ysm_epicfight_compat.held_item_model_overrides";
        }
    }

    private static final class RuleValue implements IListConfigValue<String> {
        private final String modelId;
        private List<String> initial;
        private List<String> current;

        private RuleValue(String modelId, Collection<String> selectors) {
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
                    <= HeldItemModelPolicy.MAX_SELECTORS_PER_MODEL
                    && value.stream().allMatch(
                    HeldItemModelPolicy::isValidSelector);
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
                    "config.ysm_epicfight_compat.held_item_model_entry.tooltip");
        }

        @Nullable
        @Override
        public String getTranslationKey() {
            return null;
        }

        @Override
        public Component getValidationHint() {
            return Component.translatable(
                    "config.ysm_epicfight_compat.held_item_model_entry.invalid");
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
}
