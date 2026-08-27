package net.okitsu.ysmepicfightcompat.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

/** Global settings used by both dedicated and integrated servers. */
public final class ServerPreferences {
    public static final String CONFIG_FILE =
            "ysm_epicfight_compat/ysm_epicfight_compat-common.toml";
    public static final ForgeConfigSpec COMMON_SPEC;
    public static final ForgeConfigSpec.BooleanValue SERVER_MODEL_DISK_CACHE_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<Integer>
            SERVER_MODEL_DISK_CACHE_MIB;

    static {
        ForgeConfigSpec.Builder config = new ForgeConfigSpec.Builder();
        config.comment("Server model transfer cache settings.")
                .translation("config.ysm_epicfight_compat.server")
                .push("server");
        SERVER_MODEL_DISK_CACHE_ENABLED = config
                .comment("Persist generated server model transfer payloads between sessions.",
                        "The bounded in-memory transfer cache remains enabled when this is false.",
                        "Default: true")
                .translation("config.ysm_epicfight_compat.server_model_disk_cache_enabled")
                .define("serverModelDiskCacheEnabled", true);
        SERVER_MODEL_DISK_CACHE_MIB = config
                .comment("Maximum disk space in MiB for generated server model payloads.",
                        "Set to zero to disable and clear the persistent cache.",
                        "Range: 0 ~ 4096",
                        "Default: 256")
                .translation("config.ysm_epicfight_compat.server_model_disk_cache_mib")
                .define("serverModelDiskCacheMiB", 256,
                        value -> integerInRange(value, 0, 4096));
        config.pop();
        COMMON_SPEC = config.build();
    }

    private ServerPreferences() {
    }

    private static boolean integerInRange(Object value, int minimum, int maximum) {
        return value instanceof Integer integer
                && integer >= minimum && integer <= maximum;
    }

    @SuppressWarnings("removal")
    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, COMMON_SPEC, CONFIG_FILE);
    }

    public static boolean diskCacheEnabled() {
        return SERVER_MODEL_DISK_CACHE_ENABLED.get()
                && SERVER_MODEL_DISK_CACHE_MIB.get() > 0;
    }
}
