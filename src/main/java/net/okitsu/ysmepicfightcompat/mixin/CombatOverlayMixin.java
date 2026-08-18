package net.okitsu.ysmepicfightcompat.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.okitsu.ysmepicfightcompat.render.CombatOverlayPolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stops the optional YSM paper-doll pass while Epic Fight owns combat rendering. */
@Mixin(targets = "net.okitsu.ysmepicfightcompat.ysmref.ModelPreviewRendererAlias", remap = false)
public abstract class CombatOverlayMixin {
    @Inject(
            method = "renderPlayerOverlay(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/player/LocalPlayer;DDFFIF)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void ysmCompat$suppressBattleOverlay(
            GuiGraphics graphics, LocalPlayer player, double x, double y,
            float scale, float yawOffset, int depth, float partialTick,
            CallbackInfo callback) {
        if (CombatOverlayPolicy.shouldSuppress(player)) {
            callback.cancel();
        }
    }
}
