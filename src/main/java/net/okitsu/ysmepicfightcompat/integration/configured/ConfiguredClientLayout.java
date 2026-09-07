package net.okitsu.ysmepicfightcompat.integration.configured;

import com.mrcrayfish.configured.api.IConfigEntry;
import com.mrcrayfish.configured.api.IConfigValue;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Presentation-only folders; Forge values and dynamic rule editors retain their identity. */
public final class ConfiguredClientLayout {
    private static final String TRANSLATION_PREFIX = "config.ysm_epicfight_compat.configured.";
    private static final List<String> ANIMATION_EXCLUSIONS = List.of(
            "heldItemSwitchAnimationExclusions", "movementAnimationExclusions",
            "parcoolAnimationExclusions", "swemAnimationExclusions");
    private static final List<String> ANIMATION_SETTINGS = List.of(
            "useYsmHeldItemSwitchAnimations", "useYsmMovementAnimations",
            "useYsmParCoolAnimations", "useYsmSwemAnimations", "useNaturalLadderAnimations");
    private static final List<String> MODEL_EXCLUSIONS = List.of(
            "heldItemModelExclusions", "projectileModelExclusions", "vehicleModelExclusions");
    private static final List<String> MODEL_SETTINGS = List.of(
            "useYsmHeldItemModels", "useYsmProjectileModels", "useYsmVehicleModels");

    private ConfiguredClientLayout() {
    }

    /**
     * Object-only boundary for the optional mixin. Call after replacing the dynamic
     * table placeholders; no config paths, values, or dirty state are rewritten.
     */
    public static List<?> groupClientEntries(List<?> entries) {
        List<Object> remaining = new ArrayList<>(entries.size());
        Map<String, List<IConfigEntry>> grouped = new LinkedHashMap<>();
        int insertionIndex = -1;
        for (Object entry : entries) {
            String name = entryName(entry);
            if (isGroupedEntry(name)) {
                if (insertionIndex < 0) {
                    insertionIndex = remaining.size();
                }
                grouped.computeIfAbsent(name, ignored -> new ArrayList<>())
                        .add((IConfigEntry) entry);
            } else {
                remaining.add(entry);
            }
        }
        if (insertionIndex < 0) {
            return List.copyOf(remaining);
        }

        List<IConfigEntry> categories = new ArrayList<>(2);
        addCategory(categories, "animations", "animation_exclusions",
                ANIMATION_EXCLUSIONS, ANIMATION_SETTINGS, grouped);
        addCategory(categories, "models", "model_exclusions",
                MODEL_EXCLUSIONS, MODEL_SETTINGS, grouped);
        remaining.addAll(insertionIndex, categories);
        return List.copyOf(remaining);
    }

    private static boolean isGroupedEntry(String name) {
        return ANIMATION_EXCLUSIONS.contains(name) || ANIMATION_SETTINGS.contains(name)
                || MODEL_EXCLUSIONS.contains(name) || MODEL_SETTINGS.contains(name);
    }

    private static String entryName(Object entry) {
        if (!(entry instanceof IConfigEntry configEntry)) {
            return "";
        }
        if (!configEntry.isLeaf()) {
            return configEntry.getEntryName();
        }
        IConfigValue<?> value = configEntry.getValue();
        return value == null ? "" : value.getName();
    }

    private static void addCategory(List<IConfigEntry> categories, String name,
                                    String exclusionsName, List<String> exclusionKeys,
                                    List<String> settingKeys,
                                    Map<String, List<IConfigEntry>> grouped) {
        List<IConfigEntry> children = new ArrayList<>();
        List<IConfigEntry> exclusions = collect(exclusionKeys, grouped);
        if (!exclusions.isEmpty()) {
            children.add(new GroupFolder(exclusionsName, exclusions));
        }
        children.addAll(collect(settingKeys, grouped));
        if (!children.isEmpty()) {
            categories.add(new GroupFolder(name, children));
        }
    }

    private static List<IConfigEntry> collect(List<String> keys,
                                             Map<String, List<IConfigEntry>> grouped) {
        List<IConfigEntry> result = new ArrayList<>();
        for (String key : keys) {
            result.addAll(grouped.getOrDefault(key, List.of()));
        }
        return result;
    }

    /** Configured handles navigation, search, reset, and saving through this same tree. */
    private record GroupFolder(String name, List<IConfigEntry> children) implements IConfigEntry {
        private GroupFolder {
            children = List.copyOf(children);
        }

        @Override
        public List<IConfigEntry> getChildren() {
            return children;
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
            return name;
        }

        @Override
        public Component getTooltip() {
            return Component.translatable(getTranslationKey() + ".tooltip");
        }

        @Override
        public String getTranslationKey() {
            return TRANSLATION_PREFIX + name;
        }
    }
}
