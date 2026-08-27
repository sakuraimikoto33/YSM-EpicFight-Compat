package net.okitsu.ysmepicfightcompat.render;

import net.minecraft.world.entity.LivingEntity;
import net.okitsu.ysmepicfightcompat.mesh.CompatHumanoidMesh;
import net.okitsu.ysmepicfightcompat.mesh.HumanoidRig;
import org.joml.Vector3f;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

/** Selects the converted model transform used by Epic Fight's ordinary item renderer. */
public final class HeldItemPoseResolver {
    private HeldItemPoseResolver() {
    }

    /**
     * Resolves the whole correction-matrix expression intercepted in
     * {@code RenderItemBase#getCorrectionMatrix}.
     *
     * <p>An animated YSM hand locator replaces Epic Fight's Tool joint, but not the
     * item-layer correction that follows it. Official YSM likewise applies the
     * locator hierarchy first and then its third-person item translation and
     * -90-degree X rotation. Keeping Epic Fight's equivalent per-item correction
     * preserves that second stage while making the item follow the authored locator.</p>
     */
    public static OpenMatrix4f resolveCorrection(
            LivingEntityPatch<?> patch, OpenMatrix4f[] poses,
            OpenMatrix4f itemCorrection, OpenMatrix4f originalPose) {
        int joint = selectedToolJoint(poses, originalPose);
        if (patch == null || joint < 0) {
            return applyItemCorrection(itemCorrection, originalPose);
        }
        LivingEntity entity = patch.getOriginal();
        CompatHumanoidMesh mesh = RenderFrameContext.currentMeshFor(entity);
        if (mesh == null) {
            return applyItemCorrection(itemCorrection, originalPose);
        }
        OpenMatrix4f authored = RenderFrameContext.authoredHeldItemPose(
                entity, mesh, poses, joint);
        if (authored != null) {
            return applyItemCorrection(itemCorrection, authored);
        }
        Vector3f displayedFist = RenderFrameContext.displayedFist(
                entity, mesh, poses, joint);
        if (displayedFist == null) {
            return applyItemCorrection(itemCorrection, originalPose);
        }
        OpenMatrix4f corrected = mesh.heldItemPose(
                patch.getArmature(), poses, joint, displayedFist);
        return applyItemCorrection(itemCorrection,
                corrected == null ? originalPose : corrected);
    }

    static OpenMatrix4f applyItemCorrection(OpenMatrix4f itemCorrection,
                                            OpenMatrix4f toolPose) {
        // Both Epic Fight call sites discard mulFront's return value and later
        // return their reusable transformHolder receiver. Mutate that receiver in
        // both paths. For an authored locator, toolPose is the replacement first
        // stage; itemCorrection remains the item-layer second stage.
        return itemCorrection.mulFront(toolPose);
    }

    static int selectedToolJoint(OpenMatrix4f[] poses, OpenMatrix4f selectedPose) {
        if (poses == null || selectedPose == null) {
            return -1;
        }
        if (poses.length > HumanoidRig.RIGHT_TOOL
                && poses[HumanoidRig.RIGHT_TOOL] == selectedPose) {
            return HumanoidRig.RIGHT_TOOL;
        }
        if (poses.length > HumanoidRig.LEFT_TOOL
                && poses[HumanoidRig.LEFT_TOOL] == selectedPose) {
            return HumanoidRig.LEFT_TOOL;
        }
        return -1;
    }
}
