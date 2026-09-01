package net.okitsu.ysmepicfightcompat.render.layer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.ElytraLayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.okitsu.ysmepicfightcompat.mesh.CompatHumanoidMesh;
import net.okitsu.ysmepicfightcompat.render.RenderFrameContext;
import yesman.epicfight.api.utils.math.MathUtils;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.client.renderer.patched.layer.PatchedElytraLayer;
import yesman.epicfight.client.renderer.patched.layer.PatchedLayer;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

/** Fits vanilla elytra geometry to the active converted YSM model. */
public final class ConvertedElytraLayer<E extends LivingEntity, P extends LivingEntityPatch<E>,
        M extends EntityModel<E>> extends PatchedLayer<E, P, M, ElytraLayer<E, M>> {
    private static final float VANILLA_ELYTRA_Z_OFFSET = 0.125F;
    private final PatchedElytraLayer<E, P, M> fallback = new PatchedElytraLayer<>();

    enum RenderPath {
        FALLBACK,
        HIDE,
        LOCATOR
    }

    @Override
    protected void renderLayer(P patch, E entity, ElytraLayer<E, M> layer,
                               PoseStack matrices, MultiBufferSource buffers, int light,
                               OpenMatrix4f[] poses, float bob, float yaw, float pitch,
                               float partialTick) {
        CompatHumanoidMesh mesh = RenderFrameContext.currentMeshFor(entity);
        OpenMatrix4f locatorPose = mesh == null ? null
                : RenderFrameContext.elytraLocatorPose(entity, mesh, poses);
        RenderPath path = renderPath(mesh != null, locatorPose != null);
        if (path == RenderPath.FALLBACK) {
            fallback.renderLayer(entity, patch, layer, matrices, buffers, light, poses,
                    bob, yaw, pitch, partialTick);
            return;
        }
        if (path == RenderPath.HIDE) {
            return;
        }
        if (!layer.shouldRender(entity.getItemBySlot(EquipmentSlot.CHEST), entity)) {
            return;
        }

        matrices.pushPose();
        try {
            applyLocatorTransform(matrices, locatorPose);
            layer.render(matrices, buffers, light, entity,
                    entity.walkAnimation.position(), entity.walkAnimation.speed(),
                    partialTick, bob, yaw, pitch);
        } finally {
            matrices.popPose();
        }
    }

    static RenderPath renderPath(boolean convertedMesh, boolean locatorAvailable) {
        if (!convertedMesh) {
            return RenderPath.FALLBACK;
        }
        return locatorAvailable ? RenderPath.LOCATOR : RenderPath.HIDE;
    }

    static void applyLocatorTransform(PoseStack matrices, OpenMatrix4f locatorPose) {
        MathUtils.mulStack(matrices, locatorPose);
        // The converted locator already carries the model's final position and scale.
        // Keep the elytra root on that exact origin and only adapt ElytraModel's axes.
        matrices.mulPose(Axis.ZP.rotationDegrees(180.0F));
        // Official YSM renders ElytraModel directly; vanilla ElytraLayer adds this
        // offset internally, so cancel it immediately before delegating.
        matrices.translate(0.0F, 0.0F, -VANILLA_ELYTRA_Z_OFFSET);
    }
}
