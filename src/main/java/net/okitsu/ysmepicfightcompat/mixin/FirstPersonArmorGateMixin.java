package net.okitsu.ysmepicfightcompat.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.world.entity.LivingEntity;
import net.okitsu.ysmepicfightcompat.render.RenderFrameContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.client.renderer.patched.layer.WearableItemLayer;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

/** Suppresses biped armor during a converted first-person draw. */
@Mixin(value = WearableItemLayer.class, remap = false)
public abstract class FirstPersonArmorGateMixin {
    @Inject(method = "renderLayer(Lyesman/epicfight/world/capabilities/entitypatch/LivingEntityPatch;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/layers/HumanoidArmorLayer;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I[Lyesman/epicfight/api/utils/math/OpenMatrix4f;FFFF)V",
            at = @At("HEAD"), cancellable = true)
    private void ysmCompat$suppress(LivingEntityPatch<?> patch, LivingEntity entity,
                                    HumanoidArmorLayer<?, ?, ?> layer, PoseStack matrices,
                                    MultiBufferSource buffers, int light, OpenMatrix4f[] poses,
                                    float bob, float yaw, float pitch, float partialTick,
                                    CallbackInfo callback) {
        if (patch.isFirstPerson() && RenderFrameContext.isFirstPersonFor(entity)) {
            callback.cancel();
        }
    }
}
