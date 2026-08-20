package net.okitsu.ysmepicfightcompat.mixin;

import net.minecraft.client.Minecraft;
import net.okitsu.ysmepicfightcompat.animation.OfficialConfigurationVariables;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/** Mirrors configuration expressions that official YSM queues while combat rendering owns pose. */
@Mixin(targets = "net.okitsu.ysmepicfightcompat.ysmref.AnimationRouletteScreenAlias",
        remap = false)
public abstract class YsmConfigurationExpressionMixin {
    @Inject(
            method = "executeConfigurationExpression(Ljava/lang/String;Ljava/util/function/Consumer;)V",
            at = @At("HEAD"),
            remap = false
    )
    private void ysmCompat$mirrorConfigurationExpression(
            String expression, Consumer<String> callback, CallbackInfo info) {
        OfficialConfigurationVariables.apply(Minecraft.getInstance().player, expression);
    }
}
