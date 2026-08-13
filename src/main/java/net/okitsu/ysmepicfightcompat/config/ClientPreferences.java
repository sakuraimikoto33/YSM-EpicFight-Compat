package net.okitsu.ysmepicfightcompat.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

/** Client-owned memory limits and one-time notification state. */
public final class ClientPreferences {
    public static final ForgeConfigSpec CLIENT_SPEC;
    public static final ForgeConfigSpec.IntValue MODEL_CACHE_CAPACITY;
    public static final ForgeConfigSpec.BooleanValue YSM_WARNING_ACKNOWLEDGED;

    static {
        ForgeConfigSpec.Builder config = new ForgeConfigSpec.Builder();
        config.push("client");
        MODEL_CACHE_CAPACITY = config
                .comment("Maximum number of converted YSM combat meshes retained in memory.")
                .defineInRange("lazyModelCacheSize", 64, 8, 512);
        YSM_WARNING_ACKNOWLEDGED = config
                .comment("Whether the official YSM/Epic Fight compatibility warning was already shown.")
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
}
