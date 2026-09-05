package net.okitsu.ysmepicfightcompat.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.LivingEntity;
import net.okitsu.ysmepicfightcompat.integration.tlm.TouhouMaidRenderBridge;
import net.okitsu.ysmepicfightcompat.render.RenderFrameContext;
import net.okitsu.ysmepicfightcompat.render.AttachmentArmatureScope;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.client.renderer.patched.entity.PatchedLivingEntityRenderer;
import yesman.epicfight.client.renderer.patched.layer.PatchedLayer;
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
    private static final String RENDER_LAYER =
            "renderLayer(Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;" +
                    "Lyesman/epicfight/world/capabilities/entitypatch/LivingEntityPatch;" +
                    "Lnet/minecraft/world/entity/LivingEntity;" +
                    "[Lyesman/epicfight/api/utils/math/OpenMatrix4f;" +
                    "Lnet/minecraft/client/renderer/MultiBufferSource;" +
                    "Lcom/mojang/blaze3d/vertex/PoseStack;IF)V";
    private static final String PATCHED_LAYER_RENDER =
            "Lyesman/epicfight/client/renderer/patched/layer/PatchedLayer;" +
                    "renderLayer(Lnet/minecraft/world/entity/LivingEntity;" +
                    "Lyesman/epicfight/world/capabilities/entitypatch/LivingEntityPatch;" +
                    "Lnet/minecraft/client/renderer/entity/layers/RenderLayer;" +
                    "Lcom/mojang/blaze3d/vertex/PoseStack;" +
                    "Lnet/minecraft/client/renderer/MultiBufferSource;" +
                    "I[Lyesman/epicfight/api/utils/math/OpenMatrix4f;FFFF)V";

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

    /** Keeps layer arguments and armature re-reads on the same completed body pose. */
    @Redirect(
            method = RENDER_LAYER,
            at = @At(
                    value = "INVOKE",
                    target = PATCHED_LAYER_RENDER,
                    remap = false
            ),
            require = 2,
            expect = 2,
            remap = false
    )
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void ysmCompat$renderWithDisplayedAttachments(
            PatchedLayer layer, LivingEntity entity, LivingEntityPatch patch,
            RenderLayer originalLayer, PoseStack matrices, MultiBufferSource buffers,
            int light, OpenMatrix4f[] originalPoses,
            float bob, float yRot, float xRot, float partialTick) {
        OpenMatrix4f[] displayed = RenderFrameContext.resolvePatchedLayerPoses(
                entity, originalPoses);
        try (AttachmentArmatureScope ignored = RenderFrameContext.openAttachmentScope(
                entity, patch.getArmature(), originalPoses)) {
            // Each layer gets a private view. An add-on changing its input must not
            // corrupt the body snapshot used by the next layer or by an armature read.
            OpenMatrix4f[] layerPoses = AttachmentArmatureScope.resolvePoseMatrices(
                    patch.getArmature(), displayed, false);
            layer.renderLayer(entity, patch, originalLayer, matrices, buffers, light,
                    layerPoses, bob, yRot, xRot, partialTick);
        }
    }
}
