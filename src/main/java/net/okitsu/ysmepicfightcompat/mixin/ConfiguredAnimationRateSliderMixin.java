package net.okitsu.ysmepicfightcompat.mixin;

import net.okitsu.ysmepicfightcompat.integration.configured.ConfiguredAnimationRateSlider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Replaces only our animation-rate number editor when Configured is installed. */
@Pseudo
@Mixin(targets = "com.mrcrayfish.configured.client.screen.ConfigScreen", remap = false)
public abstract class ConfiguredAnimationRateSliderMixin {
    @Shadow
    private void updateButtons() {
        throw new AssertionError();
    }

    @Inject(method = "createItemFromEntry", at = @At("HEAD"), cancellable = true,
            remap = false, require = 0)
    private void ysmEpicFightCompat$animationRateSlider(
            @Coerce Object entry, CallbackInfoReturnable<Object> info) {
        Object replacement = ConfiguredAnimationRateSlider.createEntry(this, entry, this::updateButtons);
        if (replacement != null) {
            info.setReturnValue(replacement);
        }
    }
}
