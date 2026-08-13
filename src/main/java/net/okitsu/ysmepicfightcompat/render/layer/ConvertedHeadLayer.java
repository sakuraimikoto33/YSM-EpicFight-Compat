package net.okitsu.ysmepicfightcompat.render.layer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HeadedModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.LivingEntity;
import net.okitsu.ysmepicfightcompat.render.CombatMeshResolver;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.client.renderer.patched.layer.PatchedHeadLayer;
import yesman.epicfight.client.renderer.patched.layer.PatchedLayer;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

/** Prevents vanilla head equipment from being attached to incompatible YSM proportions. */
public final class ConvertedHeadLayer<E extends LivingEntity, P extends LivingEntityPatch<E>,
        M extends EntityModel<E> & HeadedModel>
        extends PatchedLayer<E, P, M, CustomHeadLayer<E, M>> {
    private final PatchedHeadLayer<E, P, M> fallback = new PatchedHeadLayer<>();

    @Override
    public void renderLayer(E entity, P patch, RenderLayer<E, M> layer, PoseStack matrices,
                            MultiBufferSource buffers, int light, OpenMatrix4f[] poses,
                            float bob, float yaw, float pitch, float partialTick) {
        if (!(entity instanceof AbstractClientPlayer player)
                || !CombatMeshResolver.hasReadyMesh(player)) {
            fallback.renderLayer(entity, patch, layer, matrices, buffers, light, poses,
                    bob, yaw, pitch, partialTick);
        }
    }

    @Override
    protected void renderLayer(P patch, E entity, CustomHeadLayer<E, M> layer,
                               PoseStack matrices, MultiBufferSource buffers, int light,
                               OpenMatrix4f[] poses, float bob, float yaw, float pitch,
                               float partialTick) {
    }
}
