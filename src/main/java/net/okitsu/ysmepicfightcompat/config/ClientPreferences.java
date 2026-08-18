package net.okitsu.ysmepicfightcompat.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

/** Client-owned memory limits and one-time notification state. */
public final class ClientPreferences {
    public static final ForgeConfigSpec CLIENT_SPEC;
    public static final ForgeConfigSpec.IntValue MODEL_CACHE_CAPACITY;
    public static final ForgeConfigSpec.BooleanValue SUPPRESS_BATTLE_MODE_OVERLAY;
    public static final ForgeConfigSpec.BooleanValue YSM_WARNING_ACKNOWLEDGED;

    static {
        ForgeConfigSpec.Builder config = new ForgeConfigSpec.Builder();
        config.comment("Client preferences.")
                .translation("config.ysm_epicfight_compat.client")
                .push("client");
        MODEL_CACHE_CAPACITY = config
                .comment("Maximum number of converted YSM combat meshes retained in memory.")
                .translation("config.ysm_epicfight_compat.model_cache_capacity")
                .defineInRange("lazyModelCacheSize", 64, 8, 512);
        SUPPRESS_BATTLE_MODE_OVERLAY = config
                .comment("Suppress official YSM's extra player overlay while Epic Fight battle mode is active.",
                        "Set this to false to let official YSM render the overlay again.",
                        "The value is read for every overlay frame, so a live client-config reload takes effect without restarting.")
                .translation("config.ysm_epicfight_compat.suppress_battle_overlay")
                .define("suppressBattleModeOverlay", true);
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
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, CLIENT_SPEC);
    }

    public static boolean suppressBattleModeOverlay() {
        return SUPPRESS_BATTLE_MODE_OVERLAY.get();
    }
}
