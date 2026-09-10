package net.okitsu.ysmepicfightcompat.integration.configured;

import com.mrcrayfish.configured.api.IConfigEntry;
import com.mrcrayfish.configured.api.IConfigValue;
import com.mrcrayfish.configured.impl.neoforge.NeoForgeConfig;

import java.util.ArrayList;
import java.util.List;

/** Hides unavailable integrations without resetting their persisted preferences. */
public final class ConfiguredOptionalSettings {
    private static final List<String> ANIMATIONS = List.of("common", "animations");
    private static final List<String> EXCLUSIONS = List.of("common", "animations", "exclusions");
    private static final List<String> PARCOOL_TOGGLE =
            List.of("common", "animations", "useYsmParCoolAnimations");
    private static final List<String> PARCOOL_RULES =
            List.of("common", "animations", "exclusions", "parcoolAnimationExclusions");
    private static final List<String> SWEM_TOGGLE =
            List.of("common", "animations", "useYsmSwemAnimations");
    private static final List<String> SWEM_RULES =
            List.of("common", "animations", "exclusions", "swemAnimationExclusions");

    private ConfiguredOptionalSettings() {
    }

    /** Only these real Forge categories contain settings hidden by this integration. */
    public static boolean containsOptionalEntries(List<String> parentPath) {
        return ANIMATIONS.equals(parentPath) || EXCLUSIONS.equals(parentPath);
    }

    /** Filter before creating dynamic rule folders; stored values are never read or changed. */
    public static boolean isVisibleClientEntry(List<String> parentPath, Object entry,
                                               boolean parCoolAvailable, boolean swemAvailable) {
        if (!containsOptionalEntries(parentPath) || !(entry instanceof IConfigEntry configEntry)) {
            return true;
        }
        String name;
        if (configEntry.isLeaf()) {
            IConfigValue<?> value = configEntry.getValue();
            name = value == null ? "" : value.getName();
        } else {
            name = configEntry.getEntryName();
        }
        List<String> path = new ArrayList<>(parentPath);
        path.add(name);
        return isVisiblePath(path, parCoolAvailable, swemAvailable);
    }

    /** Configured's global reset and changed-state checks use this separate Forge-value list. */
    public static List<?> visibleForgeValues(List<?> values, boolean parCoolAvailable,
                                             boolean swemAvailable) {
        return values.stream().filter(value -> ForgeValueEntryAccess.isVisible(
                value, parCoolAvailable, swemAvailable)).toList();
    }

    private static boolean isVisiblePath(List<String> path, boolean parCoolAvailable,
                                         boolean swemAvailable) {
        if (PARCOOL_TOGGLE.equals(path) || PARCOOL_RULES.equals(path)) {
            return parCoolAvailable;
        }
        if (SWEM_TOGGLE.equals(path) || SWEM_RULES.equals(path)) {
            return swemAvailable;
        }
        return true;
    }

    /** Access Configured's protected record without constructing a NeoForgeConfig. */
    private abstract static class ForgeValueEntryAccess extends NeoForgeConfig {
        private ForgeValueEntryAccess() {
            super(null);
        }

        private static boolean isVisible(Object entry, boolean parCoolAvailable,
                                          boolean swemAvailable) {
            return !(entry instanceof ForgeValueEntry forgeEntry)
                    || isVisiblePath(forgeEntry.value().getPath(), parCoolAvailable, swemAvailable);
        }
    }
}
