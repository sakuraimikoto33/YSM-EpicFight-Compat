package net.okitsu.ysmepicfightcompat.render.layer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.world.entity.LivingEntity;
import net.okitsu.ysmepicfightcompat.render.CombatMeshResolver;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.client.mesh.HumanoidMesh;
import yesman.epicfight.client.renderer.patched.layer.PatchedLayer;
import yesman.epicfight.client.renderer.patched.layer.WearableItemLayer;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

/** Keeps biped armor off arbitrary converted bodies, while retaining fallback rendering. */
public final class ConvertedArmorLayer<E extends LivingEntity, P extends LivingEntityPatch<E>,
        M extends HumanoidModel<E>, H extends HumanoidMesh>
        extends PatchedLayer<E, P, M, HumanoidArmorLayer<E, M, M>> {
    private final WearableItemLayer<E, P, M, H> fallback;

    public ConvertedArmorLayer(AssetAccessor<H> mesh, ModelManager models) {
        fallback = new WearableItemLayer<>(mesh, false, models);
    }

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
    protected void renderLayer(P patch, E entity, HumanoidArmorLayer<E, M, M> layer,
                               PoseStack matrices, MultiBufferSource buffers, int light,
                               OpenMatrix4f[] poses, float bob, float yaw, float pitch,
                               float partialTick) {
    }
}
