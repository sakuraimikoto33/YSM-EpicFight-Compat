package net.okitsu.ysmepicfightcompat.config;

import com.electronwill.nightconfig.core.Config;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.okitsu.ysmepicfightcompat.network.EntityModelPolicy;
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
    public static final ForgeConfigSpec.ConfigValue<Integer>
            CLIENT_MODEL_MEMORY_CACHE_SIZE;
    public static final ForgeConfigSpec.ConfigValue<Integer>
            CLIENT_MODEL_DISK_CACHE_MIB;
    public static final ForgeConfigSpec.ConfigValue<Integer>
            REMOTE_MODEL_DISK_CACHE_MIB;
    public static final ForgeConfigSpec.BooleanValue SUPPRESS_BATTLE_MODE_OVERLAY;
    public static final ForgeConfigSpec.BooleanValue USE_YSM_HELD_ITEM_MODELS;
    public static final ForgeConfigSpec.ConfigValue<Config>
            HELD_ITEM_MODEL_EXCLUSIONS;
    public static final ForgeConfigSpec.BooleanValue USE_YSM_PROJECTILE_MODELS;
    public static final ForgeConfigSpec.ConfigValue<Config>
            PROJECTILE_MODEL_EXCLUSIONS;
    public static final ForgeConfigSpec.BooleanValue USE_YSM_VEHICLE_MODELS;
    public static final ForgeConfigSpec.ConfigValue<Config>
            VEHICLE_MODEL_EXCLUSIONS;
    public static final ForgeConfigSpec.BooleanValue
            USE_YSM_HELD_ITEM_SWITCH_ANIMATIONS;
    public static final ForgeConfigSpec.ConfigValue<Config>
            HELD_ITEM_SWITCH_ANIMATION_EXCLUSIONS;
    public static final ForgeConfigSpec.BooleanValue
            USE_YSM_MOVEMENT_ANIMATIONS;
    public static final ForgeConfigSpec.ConfigValue<Config>
            MOVEMENT_ANIMATION_EXCLUSIONS;
    public static final ForgeConfigSpec.BooleanValue YSM_WARNING_ACKNOWLEDGED;

    static {
        ForgeConfigSpec.Builder config = new ForgeConfigSpec.Builder();
        config.comment("Client preferences.")
                .translation("config.ysm_epicfight_compat.client")
                .push("client");
        CLIENT_MODEL_MEMORY_CACHE_SIZE = config
                .comment("Maximum number of converted YSM combat meshes retained in memory.",
                        "Range: 8 ~ 512",
                        "Default: 64")
                .translation("config.ysm_epicfight_compat.client_model_memory_cache_size")
                .define("clientModelMemoryCacheSize", 64,
                        value -> integerInRange(value, 8, 512));
        CLIENT_MODEL_DISK_CACHE_MIB = config
                .comment("Maximum disk space in MiB for parsed models available on this client.",
                        "Set to zero to disable and clear this disk cache.",
                        "Range: 0 ~ 4096",
                        "Default: 64")
                .translation("config.ysm_epicfight_compat.client_model_disk_cache_mib")
                .define("clientModelDiskCacheMiB", 64,
                        value -> integerInRange(value, 0, 4096));
        REMOTE_MODEL_DISK_CACHE_MIB = config
                .comment("Maximum disk space in MiB for models received from multiplayer servers.",
                        "Set to zero to disable and clear this disk cache.",
                        "Range: 0 ~ 4096",
                        "Default: 64")
                .translation("config.ysm_epicfight_compat.remote_model_disk_cache_mib")
                .define("remoteModelDiskCacheMiB", 64,
                        value -> integerInRange(value, 0, 4096));
        SUPPRESS_BATTLE_MODE_OVERLAY = config
                .comment("Suppress official YSM's extra player overlay while Epic Fight battle mode is active.",
                        "Set this to false to let official YSM render the overlay again.",
                        "The value is read for every overlay frame, so a live client-config reload takes effect without restarting.",
                        "Default: true")
                .translation("config.ysm_epicfight_compat.suppress_battle_overlay")
                .define("suppressBattleModeOverlay", true);
        USE_YSM_HELD_ITEM_MODELS = config
                .comment("Use model-authored YSM held-item models when available.",
                        "Only the resolved per-hand display state is synchronized; these rules remain local.",
                        "Default: true")
                .translation("config.ysm_epicfight_compat.use_ysm_held_item_models")
                .define("useYsmHeldItemModels", true);
        HELD_ITEM_MODEL_EXCLUSIONS = config
                .comment("Model-specific item IDs or #item_tags that disable YSM held-item models.",
                        "The list never enables YSM held-item models when the main setting is disabled.",
                        "Rule contents remain local; only resolved per-hand display state is synchronized.",
                        "Example: \"wine_fox/21_saint\" = [\"minecraft:diamond_sword\", \"#forge:tools/bows\"].",
                        "Default: {}")
                .translation("config.ysm_epicfight_compat.held_item_model_exclusions")
                .define("heldItemModelExclusions", Config::inMemory,
                        HeldItemModelPolicy::isValidConfiguration);
        USE_YSM_PROJECTILE_MODELS = config
                .comment("Use model-authored YSM projectile models when no corresponding YSM held-item model controls the projectile.",
                        "Only the resolved projectile display state is synchronized; these rules remain local.",
                        "Default: true")
                .translation("config.ysm_epicfight_compat.use_ysm_projectile_models")
                .define("useYsmProjectileModels", true);
        PROJECTILE_MODEL_EXCLUSIONS = config
                .comment("Model-specific entity IDs or #entity_type_tags that disable YSM projectile models.",
                        "The list never enables YSM projectile models when the main setting is disabled.",
                        "Rule contents remain local; only the resolved projectile display state is synchronized.",
                        "Example: \"wine_fox/22_elf\" = [\"minecraft:arrow\", \"#minecraft:arrows\"].",
                        "Default: {}")
                .translation("config.ysm_epicfight_compat.projectile_model_exclusions")
                .define("projectileModelExclusions", Config::inMemory,
                        EntityModelPolicy::isValidConfiguration);
        USE_YSM_VEHICLE_MODELS = config
                .comment("Use model-authored YSM vehicle models when available.",
                        "Only the resolved vehicle display state is synchronized; these rules remain local.",
                        "Default: true")
                .translation("config.ysm_epicfight_compat.use_ysm_vehicle_models")
                .define("useYsmVehicleModels", true);
        VEHICLE_MODEL_EXCLUSIONS = config
                .comment("Model-specific entity IDs or #entity_type_tags that disable YSM vehicle models.",
                        "The list never enables YSM vehicle models when the main setting is disabled.",
                        "Rule contents remain local; only the resolved vehicle display state is synchronized.",
                        "Example: \"wine_fox/01_taisho_maid\" = [\"minecraft:boat\", \"#minecraft:boats\"].",
                        "Default: {}")
                .translation("config.ysm_epicfight_compat.vehicle_model_exclusions")
                .define("vehicleModelExclusions", Config::inMemory,
                        EntityModelPolicy::isValidConfiguration);
        USE_YSM_HELD_ITEM_SWITCH_ANIMATIONS = config
                .comment("Use official YSM held-item switch animations when the current item is not replaced by model-authored geometry.",
                        "A model-authored replacement continues to follow useYsmHeldItemModels and heldItemModelExclusions.",
                        "Only the resolved per-hand animation state is synchronized; these rules remain local.",
                        "Default: true")
                .translation("config.ysm_epicfight_compat.use_ysm_held_item_switch_animations")
                .define("useYsmHeldItemSwitchAnimations", true);
        HELD_ITEM_SWITCH_ANIMATION_EXCLUSIONS = config
                .comment("Model-specific item IDs or #item_tags that disable YSM held-item switch animations.",
                        "These rules apply only when Epic Fight keeps rendering the ordinary item.",
                        "The list never enables YSM switch animations when the main setting is disabled.",
                        "Use minecraft:air to target the animation that switches to an empty hand.",
                        "Only the resolved per-hand animation state is synchronized; these rules remain local.",
                        "Example: \"wine_fox/05_magical\" = [\"minecraft:diamond_pickaxe\", \"minecraft:air\", \"#forge:tools/pickaxes\"].",
                        "Default: {}")
                .translation("config.ysm_epicfight_compat.held_item_switch_animation_exclusions")
                .define("heldItemSwitchAnimationExclusions", Config::inMemory,
                        HeldItemModelPolicy::isValidConfiguration);
        USE_YSM_MOVEMENT_ANIMATIONS = config
                .comment("Use full-body YSM movement animations.",
                        "Only the current resolved movement state is synchronized; these rules remain local.",
                        "Default: true")
                .translation("config.ysm_epicfight_compat.use_ysm_movement_animations")
                .define("useYsmMovementAnimations", true);
        MOVEMENT_ANIMATION_EXCLUSIONS = config
                .comment("Model-specific movement states that disable YSM movement animations.",
                        "The list never enables YSM movement animations when the main setting is disabled.",
                        "Rule contents remain local; only the current resolved pose decision is synchronized.",
                        "Example: \"wine_fox/21_saint\" = [\"run\", \"creative_flight\"].",
                        "Default: {}")
                .translation("config.ysm_epicfight_compat.movement_animation_exclusions")
                .define("movementAnimationExclusions", Config::inMemory,
                        MovementAnimationPolicy::isValidConfiguration);
        YSM_WARNING_ACKNOWLEDGED = config
                .comment("Whether the official YSM/Epic Fight compatibility warning was already shown.",
                        "Default: false")
                .translation("config.ysm_epicfight_compat.warning_acknowledged")
                .define("epicFightCompatibilityWarningShown", false);
        config.pop();
        CLIENT_SPEC = config.build();
    }

    private ClientPreferences() {
    }

    private static boolean integerInRange(Object value, int minimum, int maximum) {
        return value instanceof Integer integer
                && integer >= minimum && integer <= maximum;
    }

    @SuppressWarnings("removal")
    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, CLIENT_SPEC, CONFIG_FILE);
    }

    public static boolean suppressBattleModeOverlay() {
        return SUPPRESS_BATTLE_MODE_OVERLAY.get();
    }

    public static Map<String, List<String>> heldItemModelExclusions() {
        return HeldItemModelPolicy.decodeConfiguration(
                HELD_ITEM_MODEL_EXCLUSIONS.get());
    }

    public static void setHeldItemModelExclusions(
            Map<String, ? extends Collection<String>> rules) {
        HELD_ITEM_MODEL_EXCLUSIONS.set(
                HeldItemModelPolicy.encodeConfiguration(rules));
        HELD_ITEM_MODEL_EXCLUSIONS.clearCache();
    }

    public static Map<String, List<String>> projectileModelExclusions() {
        return EntityModelPolicy.decodeConfiguration(
                PROJECTILE_MODEL_EXCLUSIONS.get());
    }

    public static void setProjectileModelExclusions(
            Map<String, ? extends Collection<String>> rules) {
        PROJECTILE_MODEL_EXCLUSIONS.set(
                EntityModelPolicy.encodeConfiguration(rules));
        PROJECTILE_MODEL_EXCLUSIONS.clearCache();
    }

    public static Map<String, List<String>> vehicleModelExclusions() {
        return EntityModelPolicy.decodeConfiguration(
                VEHICLE_MODEL_EXCLUSIONS.get());
    }

    public static void setVehicleModelExclusions(
            Map<String, ? extends Collection<String>> rules) {
        VEHICLE_MODEL_EXCLUSIONS.set(
                EntityModelPolicy.encodeConfiguration(rules));
        VEHICLE_MODEL_EXCLUSIONS.clearCache();
    }

    public static Map<String, List<String>> heldItemSwitchAnimationExclusions() {
        return HeldItemModelPolicy.decodeConfiguration(
                HELD_ITEM_SWITCH_ANIMATION_EXCLUSIONS.get());
    }

    public static void setHeldItemSwitchAnimationExclusions(
            Map<String, ? extends Collection<String>> rules) {
        HELD_ITEM_SWITCH_ANIMATION_EXCLUSIONS.set(
                HeldItemModelPolicy.encodeConfiguration(rules));
        HELD_ITEM_SWITCH_ANIMATION_EXCLUSIONS.clearCache();
    }

    public static Map<String, List<String>> movementAnimationExclusions() {
        return MovementAnimationPolicy.decodeConfiguration(
                MOVEMENT_ANIMATION_EXCLUSIONS.get());
    }

    public static void setMovementAnimationExclusions(
            Map<String, ? extends Collection<String>> rules) {
        MOVEMENT_ANIMATION_EXCLUSIONS.set(
                MovementAnimationPolicy.encodeConfiguration(rules));
        MOVEMENT_ANIMATION_EXCLUSIONS.clearCache();
    }
}
