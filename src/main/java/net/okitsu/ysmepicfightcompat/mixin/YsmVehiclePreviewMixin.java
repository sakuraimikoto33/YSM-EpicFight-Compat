package net.okitsu.ysmepicfightcompat.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.entity.Entity;
import net.okitsu.ysmepicfightcompat.render.SubEntityRenderPolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents a disabled YSM vehicle locator from moving its Epic Fight passenger. */
@Mixin(targets = "net.okitsu.ysmepicfightcompat.ysmref.ModelPreviewRendererAlias",
        remap = false)
public abstract class YsmVehiclePreviewMixin {
    @Inject(
            method = "renderVehicleModel(Lnet/minecraft/world/entity/Entity;Lcom/mojang/blaze3d/vertex/PoseStack;F)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void ysmCompat$selectVehiclePassengerLocator(
            Entity rider, PoseStack poseStack, float partialTick,
            CallbackInfo callback) {
        if (SubEntityRenderPolicy.suppressYsmVehicleLocator(rider)) {
            callback.cancel();
        }
    }
}
