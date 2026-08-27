package net.okitsu.ysmepicfightcompat.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.ArrowLayer;
import net.minecraft.client.renderer.entity.layers.BeeStingerLayer;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.entity.layers.ElytraLayer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.PlayerItemInHandLayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.okitsu.ysmepicfightcompat.animation.MovementAnimationType;
import net.okitsu.ysmepicfightcompat.mesh.CombatMeshCache;
import net.okitsu.ysmepicfightcompat.mesh.CompatHumanoidMesh;
import net.okitsu.ysmepicfightcompat.network.ClientMovementAnimationPreferences;
import net.okitsu.ysmepicfightcompat.render.layer.ConvertedArmorLayer;
import net.okitsu.ysmepicfightcompat.render.layer.ConvertedElytraLayer;
import net.okitsu.ysmepicfightcompat.render.layer.ConvertedHeadLayer;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.api.client.model.Meshes;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.client.mesh.HumanoidMesh;
import yesman.epicfight.client.renderer.patched.entity.PHumanoidRenderer;
import yesman.epicfight.client.renderer.patched.layer.PatchedArrowLayer;
import yesman.epicfight.client.renderer.patched.layer.PatchedBeeStingerLayer;
import yesman.epicfight.client.renderer.patched.layer.PatchedCapeLayer;
import yesman.epicfight.client.renderer.patched.layer.PatchedItemInHandLayer;
import yesman.epicfight.client.world.capabilites.entitypatch.player.AbstractClientPlayerPatch;

/** Epic Fight player renderer that accepts official YSM's non-PlayerRenderer delegate. */
@OnlyIn(Dist.CLIENT)
public final class CombatPlayerRenderer extends PHumanoidRenderer<
        AbstractClientPlayer,
        AbstractClientPlayerPatch<AbstractClientPlayer>,
        PlayerModel<AbstractClientPlayer>,
        LivingEntityRenderer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>>,
        HumanoidMesh> {

    public CombatPlayerRenderer(EntityRendererProvider.Context context, EntityType<?> entityType) {
        super(Meshes.BIPED, context, entityType);
        addPatchedLayer(ArrowLayer.class, new PatchedArrowLayer<>(context));
        addPatchedLayer(BeeStingerLayer.class, new PatchedBeeStingerLayer<>());
        addPatchedLayer(CapeLayer.class, new PatchedCapeLayer());
        addPatchedLayer(PlayerItemInHandLayer.class, new PatchedItemInHandLayer<>());
        addPatchedLayerAlways(HumanoidArmorLayer.class,
                new ConvertedArmorLayer<>(Meshes.BIPED, context.getModelManager()));
        addPatchedLayerAlways(CustomHeadLayer.class, new ConvertedHeadLayer<>());
        addPatchedLayerAlways(ElytraLayer.class, new ConvertedElytraLayer<>());
    }

    @Override
    public AssetAccessor<HumanoidMesh> getMeshProvider(
            AbstractClientPlayerPatch<AbstractClientPlayer> patch) {
        AssetAccessor<HumanoidMesh> converted = CombatMeshResolver.forPlayer(patch.getOriginal());
        AssetAccessor<HumanoidMesh> selected = converted == null
                ? super.getMeshProvider(patch) : converted;
        HumanoidMesh mesh = selected.get();
        if (mesh instanceof CompatHumanoidMesh compat) {
            RenderFrameContext.bindMesh(patch.getOriginal(), false, compat);
        }
        return selected;
    }

    @Override
    public void render(AbstractClientPlayer entity,
                       AbstractClientPlayerPatch<AbstractClientPlayer> patch,
                       LivingEntityRenderer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> renderer,
                       MultiBufferSource buffers, PoseStack matrices, int light, float partialTick) {
        boolean epicFightActionActive =
                EpicFightPoseOwnership.actionOwnsPose(entity, patch);
        float epicModelYaw = patch.getAccurateYRot(partialTick);
        float renderedModelYaw = usesOfficialCreativeFlightYaw(
                entity, epicFightActionActive)
                ? officialBodyYaw(entity.yBodyRotO, entity.yBodyRot, partialTick)
                : epicModelYaw;
        RenderFrameContext.Frame scope = RenderFrameContext.pushThirdPerson(
                entity, renderedModelYaw, epicFightActionActive);
        try {
            super.render(entity, patch, renderer, buffers, matrices, light, partialTick);
        } finally {
            RenderFrameContext.pop(scope);
        }
    }

    /**
     * Epic Fight owns the outer model transform even when a configured official-YSM
     * movement clip owns every model bone. Creative-flight clips such as 05_magical's
     * {@code fly} intentionally contain no head-yaw query: official YSM turns them with
     * vanilla's interpolated body yaw. Apply only that missing outer-yaw delta while the
     * converted mesh really owns creative flight. Elytra retains Epic Fight's dedicated
     * flight transform, and actions retain Epic Fight's orientation unchanged.
     */
    @Override
    public void mulPoseStack(
            PoseStack matrices, Armature armature, AbstractClientPlayer entity,
            AbstractClientPlayerPatch<AbstractClientPlayer> patch, float partialTick) {
        super.mulPoseStack(matrices, armature, entity, patch, partialTick);
        RenderFrameContext.Frame frame = RenderFrameContext.current();
        if (frame == null || frame.entity() != entity
                || frame.epicModelYaw() == null
                || !usesOfficialCreativeFlightYaw(
                entity, frame.epicFightActionActive())) {
            return;
        }
        float correction = outerYawCorrection(
                patch.getAccurateYRot(partialTick), frame.epicModelYaw());
        if (Math.abs(correction) > 1.0E-4F) {
            matrices.mulPose(Axis.YP.rotationDegrees(correction));
        }
    }

    private static boolean usesOfficialCreativeFlightYaw(
            AbstractClientPlayer entity, boolean epicFightActionActive) {
        if (epicFightActionActive || !CombatMeshResolver.hasReadyMesh(entity)) {
            return false;
        }
        PlayerSelectionResolver.Selection selection =
                PlayerSelectionResolver.current(entity);
        if (selection == null) {
            return false;
        }
        MovementAnimationType movement =
                ClientMovementAnimationPreferences.remoteMovementOverride(
                        entity, selection.modelId());
        if (movement == null) {
            movement = MovementAnimationType.resolve(entity);
        }
        CompatHumanoidMesh mesh = CombatMeshCache.readyMesh(selection.modelId());
        boolean itemSwitchOwnsPose = mesh != null
                && mesh.itemSwitchOwnsPose(entity);
        return shouldUseOfficialCreativeFlightYaw(
                movement,
                ClientMovementAnimationPreferences.usesYsm(
                        entity, selection.modelId(), movement),
                itemSwitchOwnsPose, epicFightActionActive, true);
    }

    static boolean shouldUseOfficialCreativeFlightYaw(
            MovementAnimationType movement, boolean movementOwnsPose,
            boolean itemSwitchOwnsPose, boolean epicFightActionActive,
            boolean readyMesh) {
        return readyMesh && !epicFightActionActive
                && movement == MovementAnimationType.CREATIVE_FLIGHT
                && (movementOwnsPose || itemSwitchOwnsPose);
    }

    static float officialBodyYaw(float previousBodyYaw, float bodyYaw,
                                 float partialTick) {
        return Mth.rotLerp(partialTick, previousBodyYaw, bodyYaw);
    }

    static float outerYawCorrection(float epicModelYaw, float officialBodyYaw) {
        return Mth.wrapDegrees(epicModelYaw - officialBodyYaw);
    }

    @Override
    protected void prepareModel(HumanoidMesh mesh, AbstractClientPlayer player,
                                AbstractClientPlayerPatch<AbstractClientPlayer> patch,
                                LivingEntityRenderer<AbstractClientPlayer,
                                        PlayerModel<AbstractClientPlayer>> renderer) {
        mesh.initialize();
        setBaseParts(mesh, !player.isSpectator());
        if (player.isSpectator()) {
            mesh.head.setHidden(false);
            mesh.hat.setHidden(false);
            return;
        }
        mesh.hat.setHidden(!player.isModelPartShown(PlayerModelPart.HAT));
        mesh.jacket.setHidden(!player.isModelPartShown(PlayerModelPart.JACKET));
        mesh.leftSleeve.setHidden(!player.isModelPartShown(PlayerModelPart.LEFT_SLEEVE));
        mesh.rightSleeve.setHidden(!player.isModelPartShown(PlayerModelPart.RIGHT_SLEEVE));
        mesh.leftPants.setHidden(!player.isModelPartShown(PlayerModelPart.LEFT_PANTS_LEG));
        mesh.rightPants.setHidden(!player.isModelPartShown(PlayerModelPart.RIGHT_PANTS_LEG));
    }

    private static void setBaseParts(HumanoidMesh mesh, boolean visible) {
        mesh.head.setHidden(!visible);
        mesh.torso.setHidden(!visible);
        mesh.leftArm.setHidden(!visible);
        mesh.rightArm.setHidden(!visible);
        mesh.leftLeg.setHidden(!visible);
        mesh.rightLeg.setHidden(!visible);
        mesh.jacket.setHidden(!visible);
        mesh.leftSleeve.setHidden(!visible);
        mesh.rightSleeve.setHidden(!visible);
        mesh.leftPants.setHidden(!visible);
        mesh.rightPants.setHidden(!visible);
    }
}
