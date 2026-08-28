package net.okitsu.ysmepicfightcompat.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.projectile.Projectile;
import net.okitsu.ysmepicfightcompat.render.SubEntityRenderPolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Returns YSM's dispatcher wrapper to the original projectile renderer when disabled. */
@Mixin(targets = "net.okitsu.ysmepicfightcompat.ysmref.CustomProjectileRendererAlias",
        remap = false)
public abstract class YsmProjectileRendererMixin {
    @Inject(
            method = "renderProjectile(Lnet/minecraft/world/entity/projectile/Projectile;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)Z",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void ysmCompat$selectProjectileRenderer(
            Projectile projectile, float entityYaw, float partialTick,
            PoseStack poseStack, MultiBufferSource buffers, int packedLight,
            CallbackInfoReturnable<Boolean> callback) {
        if (SubEntityRenderPolicy.suppressYsmProjectile(projectile)) {
            callback.setReturnValue(true);
        }
    }
}
