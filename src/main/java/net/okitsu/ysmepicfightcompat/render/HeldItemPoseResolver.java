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
     * <p>An animated YSM hand locator replaces Epic Fight's Tool joint, but not the
     * item-layer correction that follows it. Official YSM likewise applies the
     * locator hierarchy first and then its third-person item translation and
     * -90-degree X rotation. Keeping Epic Fight's equivalent per-item correction
     * preserves that second stage while making the item follow the authored locator.</p>
     */
    public static OpenMatrix4f resolveCorrection(
            LivingEntityPatch<?> patch, OpenMatrix4f[] poses,
            OpenMatrix4f itemCorrection, OpenMatrix4f originalPose) {
        int joint = selectedJoint(poses, originalPose);
        if (patch == null || joint < 0) {
            return applyItemCorrection(itemCorrection, originalPose);
        }
        if (AttachmentArmatureScope.isDisplayedPoseArray(patch.getArmature(), poses)) {
            // An add-on has read the scoped final skeleton rather than the layer
            // argument. It is already placed/scaled, including optional entity scale.
            return applyItemCorrection(itemCorrection, originalPose);
        }
        LivingEntity entity = patch.getOriginal();
        CompatHumanoidMesh mesh = RenderFrameContext.currentMeshFor(entity);
        if (mesh == null) {
            return applyItemCorrection(itemCorrection, originalPose);
        }
        OpenMatrix4f displayed = RenderFrameContext.displayedAttachmentPose(
                entity, mesh, poses, joint);
        if (displayed != null) {
            return applyItemCorrection(itemCorrection, displayed);
        }
        if (joint != HumanoidRig.RIGHT_TOOL && joint != HumanoidRig.LEFT_TOOL) {
            return applyItemCorrection(itemCorrection, originalPose);
        }
        float translationScale = TouhouMaidRenderBridge
                .heldItemTranslationScale(mesh);
        OpenMatrix4f authored = RenderFrameContext.authoredHeldItemPose(
                entity, mesh, poses, joint);
        if (authored != null) {
            return applyItemCorrection(itemCorrection,
                    scaleToolTranslation(authored, translationScale));
        }
        Vector3f displayedFist = RenderFrameContext.displayedFist(
                entity, mesh, poses, joint);
        if (displayedFist == null) {
            return applyItemCorrection(itemCorrection,
                    scaleToolTranslation(originalPose, translationScale));
        }
        OpenMatrix4f corrected = mesh.heldItemPose(
                patch.getArmature(), poses, joint, displayedFist);
        return applyItemCorrection(itemCorrection,
                scaleToolTranslation(corrected == null ? originalPose : corrected,
                        translationScale));
    }

    static OpenMatrix4f applyItemCorrection(OpenMatrix4f itemCorrection,
                                            OpenMatrix4f toolPose) {
        // Both Epic Fight call sites discard mulFront's return value and later
        // return their reusable transformHolder receiver. Mutate that receiver in
        // both paths. For an authored locator, toolPose is the replacement first
        // stage; itemCorrection remains the item-layer second stage.
        return itemCorrection.mulFront(toolPose);
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
