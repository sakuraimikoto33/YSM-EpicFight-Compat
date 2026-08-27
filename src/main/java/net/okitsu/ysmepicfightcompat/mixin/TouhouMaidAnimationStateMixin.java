package net.okitsu.ysmepicfightcompat.mixin;

import net.okitsu.ysmepicfightcompat.integration.tlm.TouhouMaidAnimationStateAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observes TLM roulette starts without linking or modifying its optional state. */
@Pseudo
@Mixin(
        targets = "com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid",
        remap = false
)
public abstract class TouhouMaidAnimationStateMixin {
    @Inject(
            method = "playRouletteAnim(Ljava/lang/String;)V",
            at = @At("HEAD"), require = 0, remap = false
    )
    private void ysmCompat$observeRouletteStart(
            String animationName, CallbackInfo info) {
        TouhouMaidAnimationStateAccess.animationStarted(this, animationName);
    }
}
