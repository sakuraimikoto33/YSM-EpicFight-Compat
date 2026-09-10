package net.okitsu.ysmepicfightcompat.config;

import com.electronwill.nightconfig.core.Config;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.fml.ModList;
import net.okitsu.ysmepicfightcompat.animation.ModAnimationType;
import net.okitsu.ysmepicfightcompat.network.ModAnimationPolicy;

import java.util.List;

/** Keeps optional settings recognizable without creating them for absent mods. */
final class OptionalModConfig {
    private OptionalModConfig() {
    }

    static boolean isAvailable(ModAnimationType family) {
        ModList mods = ModList.get();
        return mods != null && mods.isLoaded(switch (family) {
            case PARCOOL -> "parcool";
            case SWEM -> "swem";
        });
    }

    static ModConfigSpec.ConfigValue<Boolean> defineBoolean(
            ModConfigSpec.Builder builder, String key, boolean available,
            String... comments) {
        commentWhenAvailable(builder, available, comments);
        // Removing the spec would make Forge delete existing user preferences.
        // Accept missing values only while absent; get() supplies the default
        // without inserting a key into the loaded configuration.
        return builder.define(List.of(key), () -> true,
                value -> (!available && value == null) || isForgeBoolean(value),
                Boolean.class);
    }

    static ModConfigSpec.ConfigValue<Config> defineExclusions(
            ModConfigSpec.Builder builder, String key, ModAnimationType family,
            boolean available, String... comments) {
        commentWhenAvailable(builder, available, comments);
        return builder.define(key, Config::inMemory,
                value -> (!available && value == null)
                        || ModAnimationPolicy.isValidConfiguration(family, value));
    }

    private static void commentWhenAvailable(
            ModConfigSpec.Builder builder, boolean available, String[] comments) {
        // An orphaned comment on a missing key cannot survive TOML serialization
        // and would otherwise trigger another Forge correction on every load.
        if (available) {
            builder.comment(comments);
        }
    }

    private static boolean isForgeBoolean(Object value) {
        // Match Forge's BooleanValue validator, including already accepted strings.
        return value instanceof Boolean || value instanceof String text
                && (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false"));
    }
}
