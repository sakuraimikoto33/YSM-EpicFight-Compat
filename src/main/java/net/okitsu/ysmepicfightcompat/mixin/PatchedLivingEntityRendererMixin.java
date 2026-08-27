package net.okitsu.ysmepicfightcompat.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.okitsu.ysmepicfightcompat.integration.tlm.TouhouMaidRenderBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import yesman.epicfight.client.renderer.patched.entity.PatchedLivingEntityRenderer;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

/** Creates the converted-mesh scope inherited by EFTLM's maid renderer. */
@Mixin(value = PatchedLivingEntityRenderer.class, remap = false)
public abstract class PatchedLivingEntityRendererMixin {
    private static final String RENDER =
            "render(Lnet/minecraft/world/entity/LivingEntity;" +
                    "Lyesman/epicfight/world/capabilities/entitypatch/LivingEntityPatch;" +
                    "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;" +
                    "Lnet/minecraft/client/renderer/MultiBufferSource;" +
                    "Lcom/mojang/blaze3d/vertex/PoseStack;IF)V";

    @Inject(method = RENDER, at = @At("HEAD"), remap = false)
    private void ysmCompat$enterTouhouMaidRender(
            LivingEntity entity, LivingEntityPatch<?> patch,
            LivingEntityRenderer<LivingEntity, EntityModel<LivingEntity>> renderer,
            MultiBufferSource buffers, PoseStack matrices, int light,
            float partialTick, CallbackInfo info) {
        TouhouMaidRenderBridge.enter(this, entity, patch, partialTick);
    }

    @Inject(method = RENDER, at = @At("RETURN"), remap = false)
    private void ysmCompat$exitTouhouMaidRender(
            LivingEntity entity, LivingEntityPatch<?> patch,
            LivingEntityRenderer<LivingEntity, EntityModel<LivingEntity>> renderer,
            MultiBufferSource buffers, PoseStack matrices, int light,
            float partialTick, CallbackInfo info) {
        TouhouMaidRenderBridge.exit(this, entity, patch);
    }
}
