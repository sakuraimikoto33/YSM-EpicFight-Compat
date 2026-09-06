package net.okitsu.ysmepicfightcompat.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.LivingEntity;
import net.okitsu.ysmepicfightcompat.integration.tlm.TouhouMaidRenderBridge;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.mesh.CompatHumanoidMesh;
import net.okitsu.ysmepicfightcompat.render.LayerBufferFlush;
import net.okitsu.ysmepicfightcompat.render.ModelLayerOrder;
import net.okitsu.ysmepicfightcompat.render.RenderFrameContext;
import net.okitsu.ysmepicfightcompat.render.AttachmentArmatureScope;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.api.client.model.SkinnedMesh;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.client.renderer.patched.entity.PatchedLivingEntityRenderer;
import yesman.epicfight.client.renderer.patched.layer.PatchedLayer;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

import java.util.concurrent.atomic.AtomicBoolean;

/** Creates the converted-mesh scope inherited by EFTLM's maid renderer. */
@Mixin(value = PatchedLivingEntityRenderer.class, remap = false)
public abstract class PatchedLivingEntityRendererMixin {
    @Unique
    private static final AtomicBoolean ysmCompat$unknownLayerBuffers = new AtomicBoolean();
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

    @Shadow(remap = false)
    protected abstract void renderLayer(
            LivingEntityRenderer<LivingEntity, EntityModel<LivingEntity>> renderer,
            LivingEntityPatch<?> patch, LivingEntity entity, OpenMatrix4f[] poses,
            MultiBufferSource buffers, PoseStack matrices, int light, float partialTick);

    /** Only the normal body invoke is in render; decoration draws are in its lambda. */
    @Redirect(method = RENDER, at = @At(value = "INVOKE", target =
            "Lyesman/epicfight/api/client/model/SkinnedMesh;draw(" +
                    "Lcom/mojang/blaze3d/vertex/PoseStack;" +
                    "Lnet/minecraft/client/renderer/MultiBufferSource;" +
                    "Lnet/minecraft/client/renderer/RenderType;IFFFFI" +
                    "Lyesman/epicfight/api/model/Armature;" +
                    "[Lyesman/epicfight/api/utils/math/OpenMatrix4f;)V",
            remap = false), require = 1, expect = 1, remap = false)
    private void ysmCompat$drawBodyWithModelLayerOrder(
            SkinnedMesh mesh, PoseStack bodyMatrices, MultiBufferSource bodyBuffers,
            RenderType type, int bodyLight, float red, float green, float blue,
            float alpha, int overlay, Armature armature, OpenMatrix4f[] poses,
            LivingEntity entity, LivingEntityPatch<?> patch,
            LivingEntityRenderer<LivingEntity, EntityModel<LivingEntity>> renderer,
            MultiBufferSource buffers, PoseStack matrices, int light, float partialTick) {
        ModelLayerOrder order = null;
        if (mesh instanceof CompatHumanoidMesh converted) {
            Runnable earlyLayers = null;
            if (converted.renderLayersFirst() && !entity.isSpectator()) {
                if (LayerBufferFlush.supports(buffers)) {
                    earlyLayers = () -> {
                        renderLayer(renderer, patch, entity, poses, buffers,
                                matrices, light, partialTick);
                        // Compute skinning draws immediately. Finish the supplied
                        // layer buffers first, instead of merely changing enqueue order.
                        LayerBufferFlush.flush(buffers);
                    };
                } else if (ysmCompat$unknownLayerBuffers.compareAndSet(false, true)) {
                    CompatMod.LOG.warn("YSM-EF Compat: cannot flush layer buffer {}; " +
                                    "preserving Epic Fight's layer order",
                            buffers.getClass().getName());
                }
            }
            order = RenderFrameContext.beginBodyDraw(entity, converted, earlyLayers);
        }
        try {
            mesh.draw(bodyMatrices, bodyBuffers, type, bodyLight, red, green, blue,
                    alpha, overlay, armature, poses);
        } finally {
            if (order != null) {
                order.finishBody();
            }
        }
    }

    @Redirect(method = RENDER, at = @At(value = "INVOKE", target =
            "Lyesman/epicfight/client/renderer/patched/entity/PatchedLivingEntityRenderer;" +
                    RENDER_LAYER, remap = false), require = 1, expect = 1, remap = false)
    private void ysmCompat$renderRemainingLayers(
            PatchedLivingEntityRenderer<?, ?, ?, ?, ?> owner,
            LivingEntityRenderer<LivingEntity, EntityModel<LivingEntity>> renderer,
            LivingEntityPatch<?> patch, LivingEntity entity, OpenMatrix4f[] poses,
            MultiBufferSource buffers, PoseStack matrices, int light, float partialTick) {
        if (!RenderFrameContext.layersAlreadyRendered(entity)) {
            renderLayer(renderer, patch, entity, poses, buffers, matrices, light, partialTick);
        }
    }

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
