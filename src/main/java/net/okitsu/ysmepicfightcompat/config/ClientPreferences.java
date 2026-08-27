package net.okitsu.ysmepicfightcompat.config;

import com.electronwill.nightconfig.core.Config;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.okitsu.ysmepicfightcompat.network.HeldItemModelPolicy;
import net.okitsu.ysmepicfightcompat.network.MovementAnimationPolicy;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Client-owned memory limits and one-time notification state. */
public final class ClientPreferences {
    public static final String CONFIG_FILE =
            "ysm_epicfight_compat/ysm_epicfight_compat-client.toml";
    public static final ForgeConfigSpec CLIENT_SPEC;
    public static final ForgeConfigSpec.IntValue CLIENT_MODEL_MEMORY_CACHE_SIZE;
    public static final ForgeConfigSpec.IntValue CLIENT_MODEL_DISK_CACHE_MIB;
    public static final ForgeConfigSpec.IntValue REMOTE_MODEL_DISK_CACHE_MIB;
    public static final ForgeConfigSpec.BooleanValue SUPPRESS_BATTLE_MODE_OVERLAY;
    public static final ForgeConfigSpec.BooleanValue USE_YSM_HELD_ITEM_MODELS_BY_DEFAULT;
    public static final ForgeConfigSpec.ConfigValue<Config>
            HELD_ITEM_MODEL_OVERRIDES;
    public static final ForgeConfigSpec.BooleanValue
            USE_YSM_HELD_ITEM_SWITCH_ANIMATIONS_BY_DEFAULT;
    public static final ForgeConfigSpec.ConfigValue<Config>
            HELD_ITEM_SWITCH_ANIMATION_OVERRIDES;
    public static final ForgeConfigSpec.BooleanValue
            USE_YSM_MOVEMENT_ANIMATIONS_BY_DEFAULT;
    public static final ForgeConfigSpec.ConfigValue<Config>
            MOVEMENT_ANIMATION_OVERRIDES;
    public static final ForgeConfigSpec.BooleanValue YSM_WARNING_ACKNOWLEDGED;

    static {
        ForgeConfigSpec.Builder config = new ForgeConfigSpec.Builder();
        config.comment("Client preferences.")
                .translation("config.ysm_epicfight_compat.client")
                .push("client");
        CLIENT_MODEL_MEMORY_CACHE_SIZE = config
                .comment("Maximum number of converted YSM combat meshes retained in memory.")
                .translation("config.ysm_epicfight_compat.client_model_memory_cache_size")
                .defineInRange("clientModelMemoryCacheSize", 64, 8, 512);
        CLIENT_MODEL_DISK_CACHE_MIB = config
                .comment("Maximum disk space in MiB for parsed models available on this client.",
                        "Set to zero to disable and clear this disk cache.")
                .translation("config.ysm_epicfight_compat.client_model_disk_cache_mib")
                .defineInRange("clientModelDiskCacheMiB", 64, 0, 4096);
        REMOTE_MODEL_DISK_CACHE_MIB = config
                .comment("Maximum disk space in MiB for models received from multiplayer servers.",
                        "Set to zero to disable and clear this disk cache.")
                .translation("config.ysm_epicfight_compat.remote_model_disk_cache_mib")
                .defineInRange("remoteModelDiskCacheMiB", 64, 0, 4096);
        SUPPRESS_BATTLE_MODE_OVERLAY = config
                .comment("Suppress official YSM's extra player overlay while Epic Fight battle mode is active.",
                        "Set this to false to let official YSM render the overlay again.",
                        "The value is read for every overlay frame, so a live client-config reload takes effect without restarting.")
                .translation("config.ysm_epicfight_compat.suppress_battle_overlay")
                .define("suppressBattleModeOverlay", true);
        USE_YSM_HELD_ITEM_MODELS_BY_DEFAULT = config
                .comment("Use model-authored YSM held-item models by default when available.",
                        "Only the resolved per-hand display state is synchronized; these rules remain local.")
                .translation("config.ysm_epicfight_compat.use_ysm_held_item_models_by_default")
                .define("useYsmHeldItemModelsByDefault", true);
        HELD_ITEM_MODEL_OVERRIDES = config
                .comment("Model-specific item IDs or #item_tags that use the opposite of the default.",
                        "Each model ID is a table key whose value is a list of item selectors.",
                        "Example: \"wine_fox/21_saint\" = [\"minecraft:diamond_sword\", \"#forge:tools/bows\"].",
                        "Rule contents remain local; only resolved per-hand display state is synchronized.")
                .translation("config.ysm_epicfight_compat.held_item_model_overrides")
                .define("heldItemModelOverrides", Config::inMemory,
                        HeldItemModelPolicy::isValidConfiguration);
        USE_YSM_HELD_ITEM_SWITCH_ANIMATIONS_BY_DEFAULT = config
                .comment("Use official YSM held-item switch animations by default when the current item is not replaced by model-authored geometry.",
                        "A model-authored replacement continues to follow useYsmHeldItemModelsByDefault and heldItemModelOverrides.",
                        "Only the resolved per-hand animation state is synchronized; these rules remain local.")
                .translation("config.ysm_epicfight_compat.use_ysm_held_item_switch_animations_by_default")
                .define("useYsmHeldItemSwitchAnimationsByDefault", true);
        HELD_ITEM_SWITCH_ANIMATION_OVERRIDES = config
                .comment("Model-specific item IDs or #item_tags that use the opposite of the held-item switch animation default.",
                        "These rules apply only when Epic Fight keeps rendering the ordinary item.",
                        "Use minecraft:air to target the animation that switches to an empty hand.",
                        "Each model ID is a table key whose value is a list of item selectors.",
                        "Only the resolved per-hand animation state is synchronized; these rules remain local.")
                .translation("config.ysm_epicfight_compat.held_item_switch_animation_overrides")
                .define("heldItemSwitchAnimationOverrides", Config::inMemory,
                        HeldItemModelPolicy::isValidConfiguration);
        USE_YSM_MOVEMENT_ANIMATIONS_BY_DEFAULT = config
                .comment("Use full-body YSM movement animations by default.",
                        "This is enabled by default; model-specific rules can opt individual movement states out.",
                        "Only the current resolved movement state is synchronized; these rules remain local.")
                .translation("config.ysm_epicfight_compat.use_ysm_movement_animations_by_default")
                .define("useYsmMovementAnimationsByDefault", true);
        MOVEMENT_ANIMATION_OVERRIDES = config
                .comment("Model-specific movement states that use the opposite of the default.",
                        "Each model ID is a table key whose value is a list of semantic movement names.",
                        "Example: \"wine_fox/21_saint\" = [\"run\", \"creative_flight\"].",
                        "Rule contents remain local; only the current resolved pose decision is synchronized.")
                .translation("config.ysm_epicfight_compat.movement_animation_overrides")
                .define("movementAnimationOverrides", Config::inMemory,
                        MovementAnimationPolicy::isValidConfiguration);
        YSM_WARNING_ACKNOWLEDGED = config
                .comment("Whether the official YSM/Epic Fight compatibility warning was already shown.")
                .translation("config.ysm_epicfight_compat.warning_acknowledged")
                .define("epicFightCompatibilityWarningShown", false);
        config.pop();
        CLIENT_SPEC = config.build();
    }

    private ClientPreferences() {
    }

    @SuppressWarnings("removal")
    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, CLIENT_SPEC, CONFIG_FILE);
    }

    public static boolean suppressBattleModeOverlay() {
        return SUPPRESS_BATTLE_MODE_OVERLAY.get();
    }

    public static Map<String, List<String>> heldItemModelOverrides() {
        return HeldItemModelPolicy.decodeConfiguration(
                HELD_ITEM_MODEL_OVERRIDES.get());
    }

    public static void setHeldItemModelOverrides(
            Map<String, ? extends Collection<String>> rules) {
        HELD_ITEM_MODEL_OVERRIDES.set(
                HeldItemModelPolicy.encodeConfiguration(rules));
        HELD_ITEM_MODEL_OVERRIDES.clearCache();
    }

    public static Map<String, List<String>> heldItemSwitchAnimationOverrides() {
        return HeldItemModelPolicy.decodeConfiguration(
                HELD_ITEM_SWITCH_ANIMATION_OVERRIDES.get());
    }

    public static void setHeldItemSwitchAnimationOverrides(
            Map<String, ? extends Collection<String>> rules) {
        HELD_ITEM_SWITCH_ANIMATION_OVERRIDES.set(
                HeldItemModelPolicy.encodeConfiguration(rules));
        HELD_ITEM_SWITCH_ANIMATION_OVERRIDES.clearCache();
    }

    public static Map<String, List<String>> movementAnimationOverrides() {
        return MovementAnimationPolicy.decodeConfiguration(
                MOVEMENT_ANIMATION_OVERRIDES.get());
    }

    public static void setMovementAnimationOverrides(
            Map<String, ? extends Collection<String>> rules) {
        MOVEMENT_ANIMATION_OVERRIDES.set(
                MovementAnimationPolicy.encodeConfiguration(rules));
        MOVEMENT_ANIMATION_OVERRIDES.clearCache();
    }
}
