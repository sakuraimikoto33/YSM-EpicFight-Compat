package net.okitsu.ysmepicfightcompat.mixin;

import net.okitsu.ysmepicfightcompat.animation.OfficialRoamingVariables;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps native animation evaluation from overwriting another player's synchronized state. */
@Mixin(targets = "net.okitsu.ysmepicfightcompat.ysmref.AnimationContextAlias", remap = false)
public abstract class YsmRoamingProviderBindingMixin {
    @Inject(method = "bindRoamingProvider(Ljava/lang/Object;)V", at = @At("HEAD"),
            cancellable = true, remap = false)
    private void ysmCompat$guardRoamingProvider(@Coerce Object provider, CallbackInfo callback) {
        if (OfficialRoamingVariables.bindNativeProvider(this, provider)) callback.cancel();
    }
}
