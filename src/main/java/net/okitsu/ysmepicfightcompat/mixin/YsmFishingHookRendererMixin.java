package net.okitsu.ysmepicfightcompat.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.projectile.FishingHook;
import net.okitsu.ysmepicfightcompat.render.SubEntityRenderPolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Restores the complete vanilla hook-and-line renderer when YSM is disabled. */
@Mixin(targets = "net.okitsu.ysmepicfightcompat.ysmref.CustomFishingHookRendererAlias",
        remap = false)
public abstract class YsmFishingHookRendererMixin {
    @Inject(
            method = "tryRenderCustomHook(Lnet/minecraft/world/entity/projectile/FishingHook;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)Z",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void ysmCompat$selectFishingHookRenderer(
            FishingHook hook, float entityYaw, float partialTick,
            PoseStack poseStack, MultiBufferSource buffers, int packedLight,
            CallbackInfoReturnable<Boolean> callback) {
        if (SubEntityRenderPolicy.suppressYsmFishingHook(hook)) {
            callback.setReturnValue(true);
        }
    }
}
