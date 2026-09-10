package net.okitsu.ysmepicfightcompat.integration.configured;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.okitsu.ysmepicfightcompat.config.ClientPreferences;
import net.okitsu.ysmepicfightcompat.config.ServerPreferences;

import java.util.List;

/** Preserves unchanged settings when Configured saves a partial category tree. */
public final class ConfiguredConfigUpdates {
    private ConfiguredConfigUpdates() {
    }

    public static void putAll(IConfigSpec spec, CommentedConfig destination,
                              UnmodifiableConfig changes) {
        if (spec != ClientPreferences.CLIENT_SPEC && spec != ServerPreferences.COMMON_SPEC) {
            destination.putAll(changes);
            return;
        }
        mergeCategories(destination, changes, ((ModConfigSpec) spec).getValues());
    }

    private static void mergeCategories(Config destination, UnmodifiableConfig changes,
                                        UnmodifiableConfig values) {
        for (UnmodifiableConfig.Entry entry : changes.entrySet()) {
            List<String> path = List.of(entry.getKey());
            Object replacement = entry.getRawValue();
            Object declaration = values.getRaw(path);
            if (declaration instanceof UnmodifiableConfig category
                    && replacement instanceof UnmodifiableConfig changedCategory) {
                Object existing = destination.getRaw(path);
                Config target;
                if (existing instanceof Config existingCategory) {
                    target = existingCategory;
                } else {
                    target = destination.createSubConfig();
                    destination.set(path, target);
                }
                mergeCategories(target, changedCategory, category);
            } else {
                // ConfigValue leaves can themselves contain Config maps. Replace those
                // whole values so deleted model rules are not merged back into the save.
                destination.set(path, replacement);
            }
        }
    }
}
