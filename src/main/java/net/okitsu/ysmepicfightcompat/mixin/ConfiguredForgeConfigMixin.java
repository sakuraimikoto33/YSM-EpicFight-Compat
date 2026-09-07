package net.okitsu.ysmepicfightcompat.mixin;

import net.minecraftforge.common.ForgeConfigSpec;
import net.okitsu.ysmepicfightcompat.animation.ModAnimationType;
import net.okitsu.ysmepicfightcompat.config.ClientPreferences;
import net.okitsu.ysmepicfightcompat.integration.configured.ConfiguredClientLayout;
import net.okitsu.ysmepicfightcompat.integration.configured.ConfiguredHeldItemRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Persists Configured entries that represent the dynamic Forge config table. */
@Pseudo
@Mixin(targets = "com.mrcrayfish.configured.impl.forge.ForgeConfig",
        remap = false)
public abstract class ConfiguredForgeConfigMixin {
    @Inject(
            method = "getAllConfigValues(Lnet/minecraftforge/common/ForgeConfigSpec;)Ljava/util/List;",
            at = @At("RETURN"),
            cancellable = true,
            remap = false,
            require = 0
    )
    private static void ysmEpicFightCompat$preserveUnavailableSettings(
            ForgeConfigSpec spec, CallbackInfoReturnable<List<?>> info) {
        if (spec == ClientPreferences.CLIENT_SPEC) {
            info.setReturnValue(ConfiguredClientLayout.visibleForgeValues(info.getReturnValue(),
                    ClientPreferences.isOptionalAnimationAvailable(ModAnimationType.PARCOOL),
                    ClientPreferences.isOptionalAnimationAvailable(ModAnimationType.SWEM)));
        }
    }

    @Inject(
            method = "update",
            at = @At("HEAD"),
            remap = false,
            require = 0
    )
    private void ysmEpicFightCompat$prepareHeldItemRuleSave(
            @Coerce Object entry, CallbackInfo info) {
        ConfiguredHeldItemRules.prepareSave(entry);
    }

    @Inject(
            method = "update",
            at = @At("RETURN"),
            remap = false,
            require = 0
    )
    private void ysmEpicFightCompat$finishHeldItemRuleSave(
            @Coerce Object entry, CallbackInfo info) {
        ConfiguredHeldItemRules.finishSave(entry);
    }
}
