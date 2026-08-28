package net.okitsu.ysmepicfightcompat.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import net.okitsu.ysmepicfightcompat.render.SubEntityRenderPolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Returns YSM's dispatcher wrapper to Epic Fight/vanilla vehicle rendering. */
@Mixin(targets = "net.okitsu.ysmepicfightcompat.ysmref.CustomVehicleRendererAlias",
        remap = false)
public abstract class YsmVehicleRendererMixin {
    @Inject(
            method = "renderVehicle(Lnet/minecraft/world/entity/Entity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)Z",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void ysmCompat$selectVehicleRenderer(
            Entity vehicle, float entityYaw, float partialTick,
            PoseStack poseStack, MultiBufferSource buffers, int packedLight,
            CallbackInfoReturnable<Boolean> callback) {
        if (SubEntityRenderPolicy.suppressYsmVehicle(vehicle)) {
            callback.setReturnValue(true);
        }
    }
}
