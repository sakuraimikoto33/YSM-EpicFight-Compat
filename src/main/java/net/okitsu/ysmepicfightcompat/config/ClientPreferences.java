package net.okitsu.ysmepicfightcompat.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

/** Client-owned memory limits and one-time notification state. */
public final class ClientPreferences {
    public static final String CONFIG_FILE =
            "ysm_epicfight_compat/ysm_epicfight_compat-client.toml";
    public static final ForgeConfigSpec CLIENT_SPEC;
    public static final ForgeConfigSpec.IntValue CLIENT_MODEL_MEMORY_CACHE_SIZE;
    public static final ForgeConfigSpec.IntValue CLIENT_MODEL_DISK_CACHE_MIB;
    public static final ForgeConfigSpec.IntValue REMOTE_MODEL_DISK_CACHE_MIB;
    public static final ForgeConfigSpec.BooleanValue SUPPRESS_BATTLE_MODE_OVERLAY;
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
}
