package net.okitsu.ysmepicfightcompat.mixin;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import net.neoforged.fml.config.ModConfig;
import net.okitsu.ysmepicfightcompat.animation.ModAnimationType;
import net.okitsu.ysmepicfightcompat.config.ClientPreferences;
import net.okitsu.ysmepicfightcompat.integration.configured.ConfiguredConfigUpdates;
import net.okitsu.ysmepicfightcompat.integration.configured.ConfiguredOptionalSettings;
import net.okitsu.ysmepicfightcompat.integration.configured.ConfiguredHeldItemRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Preserves Configured settings and persists the dynamic config tables. */
@Pseudo
@Mixin(targets = "com.mrcrayfish.configured.impl.neoforge.NeoForgeConfig",
        remap = false)
public abstract class ConfiguredForgeConfigMixin {
    @Shadow
    @Final
    protected ModConfig config;

    @Redirect(
            method = "update",
            at = @At(value = "INVOKE", target = "Lcom/electronwill/nightconfig/core/CommentedConfig;putAll(Lcom/electronwill/nightconfig/core/UnmodifiableConfig;)V"),
            remap = false,
            require = 0
    )
    private void ysmEpicFightCompat$preserveUnchangedSettings(
            CommentedConfig destination, UnmodifiableConfig changes) {
        ConfiguredConfigUpdates.putAll(config.getSpec(), destination, changes);
    }

    @Inject(
            method = "getAllConfigValues(Lnet/neoforged/fml/config/ModConfig;)Ljava/util/List;",
            at = @At("RETURN"),
            cancellable = true,
            remap = false,
            require = 0
    )
    private void ysmEpicFightCompat$preserveUnavailableSettings(
            ModConfig modConfig, CallbackInfoReturnable<List<?>> info) {
        if (modConfig.getSpec() == ClientPreferences.CLIENT_SPEC) {
            info.setReturnValue(ConfiguredOptionalSettings.visibleForgeValues(info.getReturnValue(),
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
            @Coerce Object entry, CallbackInfoReturnable<Object> info) {
        if (config.getSpec() == ClientPreferences.CLIENT_SPEC && config.getLoadedConfig() != null) {
            ConfiguredHeldItemRules.prepareSave(entry);
        }
    }

    @Inject(
            method = "update",
            at = @At("RETURN"),
            remap = false,
            require = 0
    )
    private void ysmEpicFightCompat$finishHeldItemRuleSave(
            @Coerce Object entry, CallbackInfoReturnable<Object> info) {
        ConfiguredHeldItemRules.finishSave(entry, info.getReturnValue());
    }
}
