package net.okitsu.ysmepicfightcompat.render;

import net.minecraft.world.entity.LivingEntity;
import net.okitsu.ysmepicfightcompat.mesh.CompatHumanoidMesh;
import net.okitsu.ysmepicfightcompat.mesh.HumanoidRig;
import org.joml.Vector3f;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

/** Selects a converted model's held-item pose without replacing Epic Fight item transforms. */
public final class HeldItemPoseResolver {
    private HeldItemPoseResolver() {
    }

    public static OpenMatrix4f resolve(LivingEntityPatch<?> patch,
                                       OpenMatrix4f[] poses, OpenMatrix4f originalPose) {
        int joint = selectedToolJoint(poses, originalPose);
        if (patch == null || joint < 0) {
            return originalPose;
        }
        LivingEntity entity = patch.getOriginal();
        CompatHumanoidMesh mesh = RenderFrameContext.currentMeshFor(entity);
        if (mesh == null) {
            return originalPose;
        }
        Vector3f displayedFist = RenderFrameContext.displayedFist(
                entity, mesh, poses, joint);
        if (displayedFist == null) {
            return originalPose;
        }
        OpenMatrix4f corrected = mesh.heldItemPose(
                patch.getArmature(), poses, joint, displayedFist);
        return corrected == null ? originalPose : corrected;
    }

    /** Keeps Epic Fight's item-specific correction behind the selected Tool pose. */
    public static OpenMatrix4f applyItemCorrection(OpenMatrix4f itemCorrection,
                                                   OpenMatrix4f toolPose) {
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
