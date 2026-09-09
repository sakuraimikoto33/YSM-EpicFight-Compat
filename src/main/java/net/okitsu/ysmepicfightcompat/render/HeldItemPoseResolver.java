package net.okitsu.ysmepicfightcompat.render;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.UseAnim;
import net.okitsu.ysmepicfightcompat.integration.tlm.TouhouMaidRenderBridge;
import net.okitsu.ysmepicfightcompat.mesh.CompatHumanoidMesh;
import net.okitsu.ysmepicfightcompat.mesh.HumanoidRig;
import org.joml.Vector3f;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.model.armature.types.ToolHolderArmature;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

/** Selects the converted model transform used by Epic Fight's ordinary item renderer. */
public final class HeldItemPoseResolver {
    // Official YSM's item layer uses T(0, -1/16, -0.1) * Rx(-90), whereas
    // Epic Fight 20.14's standard Tool correction uses T(0, 0, -0.13) * Rx(-90).
    // Convert between those attachment origins before the item-specific transform.
    private static final OpenMatrix4f YSM_ITEM_ORIGIN_ADJUSTMENT = new OpenMatrix4f()
            .translate(0.0F, -1.0F / 16.0F, 0.13F - 0.1F).unmodifiable();

    private HeldItemPoseResolver() {
    }

    /**
     * Keeps an ordinary item in its physical hand when natural ladder mode is disabled.
     * The patch's persistent hand-parent state is never mutated; only this render lookup
     * is replaced, so Epic Fight still owns all action-time joint changes.
     */
    public static Joint resolveParentJoint(
            LivingEntityPatch<?> patch, InteractionHand hand) {
        if (patch == null || hand == null) {
            return null;
        }
        Joint original = patch.getParentJointOfHand(hand);
        LivingEntity entity = patch.getOriginal();
        if (entity == null) {
            return original;
        }
        if (RenderFrameContext.formHeldItemActive(entity)
                && patch.getArmature() instanceof ToolHolderArmature tools) {
            // This is only a lookup inside one form-owned item draw. Never change
            // the patch's action-time hand-parent state or its gameplay armature.
            return hand == InteractionHand.MAIN_HAND
                    ? tools.rightToolJoint() : tools.leftToolJoint();
        }
        boolean requestedHandHeld =
                RenderFrameContext.keepsLadderItemInHand(entity, hand);
        boolean mainHandHeld = RenderFrameContext.keepsLadderItemInHand(
                entity, InteractionHand.MAIN_HAND);
        if (!usesLadderHandAttachment(hand, requestedHandHeld, mainHandHeld,
                entity.getMainHandItem().getUseAnimation())) {
            return original;
        }
        Armature armature = patch.getArmature();
        if (!(armature instanceof ToolHolderArmature tools)) {
            return original;
        }
        return ladderItemUsesRightTool(hand)
                ? tools.rightToolJoint() : tools.leftToolJoint();
    }

    /**
     * Epic Fight's two-handed bow renderer asks for the OFF_HAND correction while
     * rendering the MAIN_HAND bow. Treat that request as the kept main-hand item;
     * ordinary requests still have to match their own logical hand.
     */
    static boolean usesLadderHandAttachment(
            InteractionHand requestedHand, boolean requestedHandHeld,
            boolean mainHandHeld, UseAnim mainHandUseAnimation) {
        return requestedHandHeld
                || (requestedHand == InteractionHand.OFF_HAND
                && mainHandHeld && mainHandUseAnimation == UseAnim.BOW);
    }

    /** Epic Fight fixes MAIN_HAND to Tool_R and OFF_HAND to Tool_L. */
    static boolean ladderItemUsesRightTool(InteractionHand requestedHand) {
        return requestedHand == InteractionHand.MAIN_HAND;
    }

    /**
     * Resolves the whole correction-matrix expression intercepted in
     * {@code RenderItemBase#getCorrectionMatrix}.
     *
     * <p>A model hand anchor replaces Epic Fight's Tool origin. Its item-layer
     * correction must also be rebased to YSM's post-locator origin; the two renderers
     * use different grip translations. Preserve the live Tool orientation and each
     * item's additional correction, including special weapon poses.</p>
     */
    public static OpenMatrix4f resolveCorrection(
            LivingEntityPatch<?> patch, OpenMatrix4f[] poses,
            OpenMatrix4f itemCorrection, OpenMatrix4f originalPose) {
        int joint = selectedJoint(poses, originalPose);
        if (patch == null || joint < 0) {
            return applyItemCorrection(itemCorrection, originalPose);
        }
        LivingEntity entity = patch.getOriginal();
        CompatHumanoidMesh mesh = RenderFrameContext.currentMeshFor(entity);
        if (mesh == null) {
            return applyItemCorrection(itemCorrection, originalPose);
        }
        int modelToolJoint = RenderFrameContext.hasModelToolAnchor(entity, mesh, joint)
                ? joint : -1;
        if (AttachmentArmatureScope.isDisplayedPoseArray(patch.getArmature(), poses)) {
            // This skeleton is already placed/scaled, including optional entity
            // scale. Only the separate item-origin convention remains to be applied.
            return applyYsmAttachmentItemCorrection(itemCorrection, originalPose, modelToolJoint);
        }
        OpenMatrix4f displayed = RenderFrameContext.displayedAttachmentPose(
                entity, mesh, poses, joint);
        if (displayed != null) {
            return applyYsmAttachmentItemCorrection(itemCorrection, displayed, modelToolJoint);
        }
        if (joint != HumanoidRig.RIGHT_TOOL && joint != HumanoidRig.LEFT_TOOL) {
            return applyItemCorrection(itemCorrection, originalPose);
        }
        float translationScale = TouhouMaidRenderBridge
                .heldItemTranslationScale(mesh);
        OpenMatrix4f authored = RenderFrameContext.authoredHeldItemPose(
                entity, mesh, poses, joint);
        if (authored != null) {
            return applyYsmAttachmentItemCorrection(itemCorrection,
                    scaleToolTranslation(authored, translationScale), joint);
        }
        Vector3f displayedFist = RenderFrameContext.displayedFist(
                entity, mesh, poses, joint);
        if (displayedFist == null) {
            return applyItemCorrection(itemCorrection,
                    scaleToolTranslation(originalPose, translationScale));
        }
        OpenMatrix4f corrected = mesh.heldItemPose(
                patch.getArmature(), poses, joint, displayedFist);
        if (corrected == null) {
            return applyItemCorrection(itemCorrection,
                    scaleToolTranslation(originalPose, translationScale));
        }
        return applyYsmAttachmentItemCorrection(itemCorrection,
                scaleToolTranslation(corrected, translationScale), joint);
    }

    static OpenMatrix4f applyItemCorrection(OpenMatrix4f itemCorrection,
                                            OpenMatrix4f toolPose) {
        // Both Epic Fight call sites discard mulFront's return value and later
        // return their reusable transformHolder receiver. Mutate that receiver in
        // both paths. Pose arrays belong to the body and must never be mutated.
        return itemCorrection.mulFront(toolPose);
    }

    static OpenMatrix4f applyYsmAttachmentItemCorrection(
            OpenMatrix4f itemCorrection, OpenMatrix4f attachmentPose, int joint) {
        if (joint == HumanoidRig.RIGHT_TOOL || joint == HumanoidRig.LEFT_TOOL) {
            // P * (YSM_default * inverse(EF_default)) * itemCorrection.
            // Premultiply in the Tool frame: translating after P would leave the
            // offset in world space; replacing itemCorrection would lose overrides.
            itemCorrection.mulFront(YSM_ITEM_ORIGIN_ADJUSTMENT);
        }
        return applyItemCorrection(itemCorrection, attachmentPose);
    }

    /**
     * Aligns an Epic Fight item origin with a mesh-local uniform scale without
     * scaling the item basis itself. The source pose is never mutated.
     */
    static OpenMatrix4f scaleToolTranslation(OpenMatrix4f toolPose, float scale) {
        if (toolPose == null || scale == 1.0F) {
            return toolPose;
        }
        OpenMatrix4f adjusted = new OpenMatrix4f(toolPose);
        adjusted.m30 *= scale;
        adjusted.m31 *= scale;
        adjusted.m32 *= scale;
        return adjusted;
    }

    static int selectedJoint(OpenMatrix4f[] poses, OpenMatrix4f selectedPose) {
        if (poses == null || selectedPose == null) {
            return -1;
        }
        int selected = -1;
        for (int joint = 0; joint < poses.length; joint++) {
            if (poses[joint] != selectedPose) {
                continue;
            }
            if (selected >= 0) {
                return -1;
            }
            selected = joint;
        }
        return selected;
    }
}
