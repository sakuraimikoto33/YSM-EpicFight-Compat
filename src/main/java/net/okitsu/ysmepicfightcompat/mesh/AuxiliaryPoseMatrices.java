package net.okitsu.ysmepicfightcompat.mesh;

import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.api.utils.math.Vec4f;

import javax.annotation.Nullable;

/** Builds complete skin matrices without adding joints to Epic Fight's armature. */
public final class AuxiliaryPoseMatrices {
    private static final OpenMatrix4f IDENTITY = new OpenMatrix4f();
    private static final float SINGULAR_DETERMINANT_EPSILON = 1.0E-12F;
    private static final float DECOMPOSITION_DETERMINANT_EPSILON = 1.0E-10F;

    private final AuxiliaryBoneLayout layout;
    private final ModelPoseRetargeter retargeter;
    private final OpenMatrix4f[] output;
    private final BlendScratch blendScratch;
    private final OpenMatrix4f[] completeBlendSource;
    private final OpenMatrix4f[] toOrigin = new OpenMatrix4f[HumanoidRig.EPIC_JOINT_COUNT];
    private final OpenMatrix4f[] referenceBindWorlds =
            allocate(HumanoidRig.EPIC_JOINT_COUNT);
    private final Quaternionf[] referenceBindRotations =
            quaternions(HumanoidRig.EPIC_JOINT_COUNT);
    private final Vector3f[] referenceBindOrigins = new Vector3f[HumanoidRig.EPIC_JOINT_COUNT];
    private final OpenMatrix4f heldItemHandSkin = new OpenMatrix4f();
    private final OpenMatrix4f heldItemToolSkin = new OpenMatrix4f();
    private final OpenMatrix4f heldItemOutput = new OpenMatrix4f();
    private final OpenMatrix4f rightAuthoredItemOutput = new OpenMatrix4f();
    private final OpenMatrix4f leftAuthoredItemOutput = new OpenMatrix4f();
    private final OpenMatrix4f elytraLocatorOutput = new OpenMatrix4f();
    private OpenMatrix4f[] attachmentOutput = allocate(HumanoidRig.EPIC_JOINT_COUNT);
    private final Matrix4f authoredItemScratch = new Matrix4f();
    private final Matrix4f authoredItemScale = new Matrix4f();
    private final Matrix4f attachmentModelScale = new Matrix4f();
    private final Matrix4f attachmentModelScaleInverse = new Matrix4f();
    private final Matrix4f attachmentDeltaFrame = new Matrix4f();
    private final Matrix4f attachmentSourceFrame = new Matrix4f();
    private final Matrix4f attachmentOriginalFrame = new Matrix4f();
    private final Matrix4f attachmentResultFrame = new Matrix4f();
    private final LocalTransform attachmentSourceTransform = new LocalTransform();
    private final LocalTransform attachmentOriginalTransform = new LocalTransform();
    private final LocalTransform attachmentBindTransform = new LocalTransform();
    private final Quaternionf attachmentHandRotation = new Quaternionf();
    private final Quaternionf attachmentHandToDisplayedRotation = new Quaternionf();
    private final Quaternionf attachmentGripRotation = new Quaternionf();
    private final Vector3f attachmentGripOffset = new Vector3f();
    private final Matrix3f attachmentPolarFrame = new Matrix3f();
    private final Matrix3f attachmentPolarInverse = new Matrix3f();
    private final Matrix3f attachmentPolarPrevious = new Matrix3f();
    private final Vec4f heldItemReferencePoint = new Vec4f();
    private final Vec4f heldItemHandPoint = new Vec4f();
    private final Vec4f heldItemToolPoint = new Vec4f();
    private final Vec4f rightDisplayedPoint = new Vec4f();
    private final Vec4f leftDisplayedPoint = new Vec4f();
    private final Vec4f attachmentPoint = new Vec4f();
    private Armature preparedArmature;
    // Ownership metadata only: never changes the evaluated/blended body matrices.
    private float rightEpicGripWeight = 1.0F;
    private float leftEpicGripWeight = 1.0F;

    public AuxiliaryPoseMatrices(AuxiliaryBoneLayout layout) {
        this.layout = layout;
        retargeter = new ModelPoseRetargeter(layout);
        output = allocate(layout.totalPoseCount());
        blendScratch = new BlendScratch(layout.entries().size());
        completeBlendSource = new OpenMatrix4f[layout.entries().size()];
    }

    @Nullable
    public OpenMatrix4f[] compose(@Nullable Armature armature, @Nullable OpenMatrix4f[] poses,
                                  @Nullable OpenMatrix4f[] parallelDeltas,
                                  @Nullable OpenMatrix4f[] wholeModelDeltas,
                                  @Nullable OpenMatrix4f[] heldItemDeltas,
                                  boolean replaceEpicFightPose,
                                  @Nullable boolean[] replaceEpicFightAnchors,
                                  @Nullable boolean[] suppressParallelDeltas,
                                  @Nullable int[] heldItemAnchorJoints) {
        return compose(armature, poses, parallelDeltas, wholeModelDeltas, heldItemDeltas,
                replaceEpicFightPose, replaceEpicFightAnchors, suppressParallelDeltas,
                heldItemAnchorJoints, null, 0.0F);
    }

    /**
     * Composes the normal Epic Fight/YSM pose, then blends a captured full-body YSM skin
     * toward that completed result. The source contains one skin matrix per auxiliary entry.
     */
    @Nullable
    public OpenMatrix4f[] compose(@Nullable Armature armature, @Nullable OpenMatrix4f[] poses,
                                  @Nullable OpenMatrix4f[] parallelDeltas,
                                  @Nullable OpenMatrix4f[] wholeModelDeltas,
                                  @Nullable OpenMatrix4f[] heldItemDeltas,
                                  boolean replaceEpicFightPose,
                                  @Nullable boolean[] replaceEpicFightAnchors,
                                  @Nullable boolean[] suppressParallelDeltas,
                                  @Nullable int[] heldItemAnchorJoints,
                                  @Nullable OpenMatrix4f[] fullBodyBlendSource,
                                  float fullBodyBlendWeight) {
        if (armature == null || poses == null
                || armature.getJointNumber() < HumanoidRig.EPIC_JOINT_COUNT
                || poses.length < HumanoidRig.EPIC_JOINT_COUNT) {
            return null;
        }
        if (!prepareArmature(armature)) {
            return null;
        }
        // Mesh.draw normally receives Armature#getPoseMatrices() directly, but copied pose
        // arrays are valid too. Custom props depend on the model-specific Tool skin, so do
        // not silently demote an otherwise valid pose array to the generic Hand anchor.
        OpenMatrix4f[] retargetedAnchors = retargeter.retarget(armature, poses);
        OpenMatrix4f[] complete = compose(poses, toOrigin, layout, output, parallelDeltas, wholeModelDeltas,
                heldItemDeltas, retargetedAnchors, replaceEpicFightPose,
                replaceEpicFightAnchors, suppressParallelDeltas,
                heldItemAnchorJoints, fullBodyBlendSource, fullBodyBlendWeight,
                blendScratch);
        float endingWeight = validBlendSource(fullBodyBlendSource)
                ? unitWeight(fullBodyBlendWeight) : 0.0F;
        rightEpicGripWeight = rawEpicGripWeight(HumanoidRig.RIGHT_TOOL,
                replaceEpicFightPose, replaceEpicFightAnchors, heldItemAnchorJoints)
                * (1.0F - endingWeight);
        leftEpicGripWeight = rawEpicGripWeight(HumanoidRig.LEFT_TOOL,
                replaceEpicFightPose, replaceEpicFightAnchors, heldItemAnchorJoints)
                * (1.0F - endingWeight);
        return complete;
    }

    private float rawEpicGripWeight(int tool, boolean fullBody,
                                   @Nullable boolean[] replaced, @Nullable int[] anchors) {
        if (fullBody) {
            return 0.0F;
        }
        int auxiliary = layout.toolAnchorPoseIndex(tool) - HumanoidRig.EPIC_JOINT_COUNT;
        boolean replacedHand = replaced != null && auxiliary >= 0
                && auxiliary < replaced.length && replaced[auxiliary];
        boolean stillAttachedToEpic = anchors != null && auxiliary >= 0
                && auxiliary < anchors.length && anchors[auxiliary] >= 0;
        return replacedHand && !stillAttachedToEpic ? 0.0F : 1.0F;
    }

    float epicGripWeight(int tool) {
        return tool == HumanoidRig.RIGHT_TOOL ? rightEpicGripWeight : leftEpicGripWeight;
    }

    private boolean validBlendSource(@Nullable OpenMatrix4f[] source) {
        if (source == null || source.length != layout.entries().size()) {
            return false;
        }
        for (OpenMatrix4f matrix : source) {
            if (matrix == null || !finite(matrix)) {
                return false;
            }
        }
        return true;
    }

    private static float unitWeight(float weight) {
        return Float.isFinite(weight) ? Math.max(0.0F, Math.min(1.0F, weight)) : 0.0F;
    }

    /** Places Epic Fight's selected Tool pose at an exact point published by the body draw. */
    @Nullable
    public OpenMatrix4f heldItemPose(@Nullable Armature armature,
                                    @Nullable OpenMatrix4f[] poses, int joint,
                                    @Nullable Vector3f displayedFist) {
        if (armature == null || poses == null || poses.length < HumanoidRig.EPIC_JOINT_COUNT
                || displayedFist == null || !finite(displayedFist)
                || !prepareArmature(armature)) {
            return null;
        }
        int hand = joint == HumanoidRig.RIGHT_TOOL ? HumanoidRig.RIGHT_HAND
                : joint == HumanoidRig.LEFT_TOOL ? HumanoidRig.LEFT_HAND : -1;
        Vector3f referenceToolOrigin = joint >= 0 && joint < referenceBindOrigins.length
                ? referenceBindOrigins[joint] : null;
        if (hand < 0 || poses[hand] == null || poses[joint] == null
                || referenceToolOrigin == null) {
            return null;
        }
        heldItemHandSkin.load(poses[hand]).mulBack(toOrigin[hand]);
        heldItemToolSkin.load(poses[joint]).mulBack(toOrigin[joint]);
        return placeAtDisplayedFist(poses[joint], heldItemHandSkin, heldItemToolSkin,
                referenceToolOrigin, displayedFist, heldItemOutput,
                heldItemReferencePoint, heldItemHandPoint, heldItemToolPoint);
    }

    /** Resolves the authored fist point through the exact final skin used for model drawing. */
    @Nullable
    public Vector3f displayedFist(@Nullable OpenMatrix4f[] complete, int joint) {
        Vector3f bindPoint = layout.jointPivot(joint);
        int poseIndex = layout.toolAnchorPoseIndex(joint);
        if (complete == null || bindPoint == null || !finite(bindPoint)
                || poseIndex < 0 || poseIndex >= complete.length
                || complete[poseIndex] == null) {
            return null;
        }
        Vec4f point = joint == HumanoidRig.RIGHT_TOOL
                ? rightDisplayedPoint : joint == HumanoidRig.LEFT_TOOL
                ? leftDisplayedPoint : null;
        if (point == null) {
            return null;
        }
        point.set(bindPoint.x(), bindPoint.y(), bindPoint.z(), 1.0F);
        OpenMatrix4f.transform(complete[poseIndex], point, point);
        return finite(point) ? new Vector3f(point.x, point.y, point.z) : null;
    }

    /**
     * Reconstructs the full official-YSM locator frame used by its item layer.
     *
     * <p>The completed private pose is a skin matrix. Multiplying it by the scaled
     * locator bind world recovers the authored current world, and retaining the final
     * pivot translation matches YSM's locator traversal. Rotation and scale (including
     * an authored zero scale used to delay item appearance) therefore reach Epic
     * Fight's ordinary item renderer instead of being reduced to a single point.</p>
     */
    @Nullable
    public OpenMatrix4f authoredHeldItemPose(@Nullable OpenMatrix4f[] complete, int joint) {
        AuxiliaryBoneLayout.Entry locator = layout.toolLocatorEntry(joint);
        OpenMatrix4f destination = joint == HumanoidRig.RIGHT_TOOL
                ? rightAuthoredItemOutput : joint == HumanoidRig.LEFT_TOOL
                ? leftAuthoredItemOutput : null;
        return authoredLocatorPose(complete, locator, destination);
    }

    /** Reconstructs the animated official-YSM frame ending at {@code ElytraLocator}. */
    @Nullable
    public OpenMatrix4f elytraLocatorPose(@Nullable OpenMatrix4f[] complete) {
        return authoredLocatorPose(
                complete, layout.elytraLocatorEntry(), elytraLocatorOutput);
    }

    @Nullable
    private OpenMatrix4f authoredLocatorPose(
            @Nullable OpenMatrix4f[] complete,
            @Nullable AuxiliaryBoneLayout.Entry locator,
            @Nullable OpenMatrix4f destination) {
        if (complete == null || locator == null || destination == null
                || locator.poseIndex() < 0 || locator.poseIndex() >= complete.length
                || complete[locator.poseIndex()] == null) {
            return null;
        }
        authoredItemScale.scaling(layout.horizontalScale(), layout.verticalScale(),
                layout.horizontalScale());
        load(authoredItemScratch, complete[locator.poseIndex()])
                .mul(authoredItemScale)
                .mul(locator.bindWorld())
                .translate(locator.bone().pivotX(), locator.bone().pivotY(),
                        locator.bone().pivotZ());
        store(destination, authoredItemScratch);
        return finite(destination) ? destination : null;
    }

    /**
     * Projects the final rendered YSM skeleton back to Epic Fight's fixed joint order.
     *
     * <p>Epic Fight renders patched layers after the converted body and otherwise gives
     * those layers its original pose array. Each projected entry keeps Epic Fight's
     * scale/shear contract, but takes its position and proper rotation from the exact
     * YSM bone that was just drawn. Ordinary Tool poses retain the live Epic Fight
     * grip only to the extent that Epic Fight still owns the displayed hand. Only an
     * explicit item switch imports independent locator motion. Missing or ambiguous
     * model controls fail open to the original Epic Fight pose.</p>
     */
    @Nullable
    public OpenMatrix4f[] displayedAttachmentPoses(
            @Nullable Armature armature,
            @Nullable OpenMatrix4f[] complete,
            @Nullable OpenMatrix4f[] originalPoses,
            float translationScale,
            boolean rightItemSwitch,
            boolean leftItemSwitch) {
        if (complete == null || originalPoses == null
                || originalPoses.length < HumanoidRig.EPIC_JOINT_COUNT
                || armature == null || !prepareArmature(armature)) {
            return null;
        }
        float safeTranslationScale = Float.isFinite(translationScale)
                && translationScale > 1.0E-7F ? translationScale : 1.0F;
        if (attachmentOutput.length != originalPoses.length) {
            attachmentOutput = allocate(originalPoses.length);
        }
        attachmentModelScale.scaling(layout.horizontalScale(), layout.verticalScale(),
                layout.horizontalScale());
        attachmentModelScaleInverse.scaling(1.0F / layout.horizontalScale(),
                1.0F / layout.verticalScale(), 1.0F / layout.horizontalScale());
        for (int joint = 0; joint < originalPoses.length; joint++) {
            OpenMatrix4f original = originalPoses[joint];
            if (original == null || !finite(original)) {
                return null;
            }
            OpenMatrix4f destination = attachmentOutput[joint].load(original);
            if (joint >= HumanoidRig.EPIC_JOINT_COUNT) {
                continue;
            }

            boolean locatorOwned = joint == HumanoidRig.RIGHT_TOOL && rightItemSwitch
                    || joint == HumanoidRig.LEFT_TOOL && leftItemSwitch;
            boolean toolJoint = joint == HumanoidRig.RIGHT_TOOL
                    || joint == HumanoidRig.LEFT_TOOL;
            if (locatorOwned && layout.toolLocatorEntry(joint) == null) {
                locatorOwned = false;
            }
            if (toolJoint && !locatorOwned) {
                OpenMatrix4f grip = heldItemPoseFollowingDisplayedHand(
                        armature, complete, originalPoses, joint);
                if (grip != null) {
                    destination.load(grip);
                    destination.m30 *= safeTranslationScale;
                    destination.m31 *= safeTranslationScale;
                    destination.m32 *= safeTranslationScale;
                }
                continue;
            }
            AuxiliaryBoneLayout.Entry source = locatorOwned
                    ? layout.toolLocatorEntry(joint) : layout.attachmentEntry(joint);
            Vector3f pivot = layout.attachmentPivot(joint);
            if (source == null || pivot == null
                    || source.poseIndex() < 0 || source.poseIndex() >= complete.length
                    || complete[source.poseIndex()] == null
                    || !finite(complete[source.poseIndex()])) {
                continue;
            }
            attachmentPoint.set(pivot.x(), pivot.y(), pivot.z(), 1.0F);
            OpenMatrix4f.transform(complete[source.poseIndex()], attachmentPoint,
                    attachmentPoint);
            if (!finite(attachmentPoint)) {
                continue;
            }

            boolean originalAffine = decomposeAffine(
                    load(attachmentOriginalFrame, original),
                    attachmentOriginalTransform);
            if (!originalAffine || !attachmentOriginalTransform.rotationValid) {
                continue;
            }
            attachmentOriginalTransform.updateResidual();
            if (locatorOwned) {
                if (!displayedLocatorLinear(complete[source.poseIndex()])) {
                    continue;
                }
                attachmentSourceFrame.rotation(referenceBindRotations[joint])
                        .mul(attachmentOriginalTransform.residual);
                attachmentResultFrame.set(attachmentDeltaFrame)
                        .mul(attachmentSourceFrame);
            } else {
                Quaternionf sourceRotation = displayedAttachmentRotation(
                        complete[source.poseIndex()], joint);
                if (sourceRotation == null) {
                    attachmentResultFrame.rotation(referenceBindRotations[joint])
                            .mul(attachmentOriginalTransform.residual);
                } else {
                    attachmentResultFrame.rotation(sourceRotation)
                            .mul(attachmentOriginalTransform.residual);
                }
            }
            attachmentResultFrame.m30(attachmentPoint.x * safeTranslationScale);
            attachmentResultFrame.m31(attachmentPoint.y * safeTranslationScale);
            attachmentResultFrame.m32(attachmentPoint.z * safeTranslationScale);
            store(destination, attachmentResultFrame);
        }
        return attachmentOutput;
    }

    @Nullable
    private OpenMatrix4f heldItemPoseFollowingDisplayedHand(
            Armature armature, OpenMatrix4f[] complete, OpenMatrix4f[] originalPoses,
            int joint) {
        Vector3f fist = displayedFist(complete, joint);
        float epicWeight = epicGripWeight(joint);
        if (epicWeight == 0.0F && fist != null) {
            // Do not even decompose the hidden EF pose at this endpoint. An addon
            // may collapse its Tool/Hand to zero scale while YSM draws a normal hand.
            int handIndex = layout.toolAnchorPoseIndex(joint);
            attachmentSourceFrame.set(load(authoredItemScratch, complete[handIndex]))
                    .mul(attachmentModelScale);
            if (toolRotation(attachmentSourceFrame, attachmentSourceTransform.rotation)) {
                attachmentResultFrame.rotation(attachmentSourceTransform.rotation)
                        .mul(load(attachmentOriginalFrame, referenceBindWorlds[joint]));
                attachmentResultFrame.m30(fist.x()).m31(fist.y()).m32(fist.z());
                store(heldItemOutput, attachmentResultFrame);
                return heldItemOutput;
            }
        }
        OpenMatrix4f grip = heldItemPose(armature, originalPoses, joint, fist);
        if (grip == null || fist == null) {
            return null;
        }
        // Start with the 0.3.0 grip: preserve the complete live Tool matrix and its
        // independent offset from the hand. Move that grip only by the rotation
        // actually added to the physical hand, never by a sibling locator's motion.
        // heldItemPose has already prepared heldItemHandSkin from the same input.
        attachmentSourceFrame.set(load(authoredItemScratch, heldItemHandSkin))
                .mul(attachmentModelScale);
        if (!toolRotation(attachmentSourceFrame, attachmentHandRotation)) {
            return grip;
        }
        int handPoseIndex = layout.toolAnchorPoseIndex(joint);
        attachmentSourceFrame.set(load(authoredItemScratch, complete[handPoseIndex]))
                .mul(attachmentModelScale);
        if (!toolRotation(attachmentSourceFrame, attachmentSourceTransform.rotation)) {
            return grip;
        }
        attachmentHandToDisplayedRotation.set(attachmentHandRotation).conjugate()
                .premul(attachmentSourceTransform.rotation).normalize();
        attachmentGripOffset.set(grip.m30 - fist.x(), grip.m31 - fist.y(),
                grip.m32 - fist.z());
        attachmentHandToDisplayedRotation.transform(attachmentGripOffset);
        attachmentResultFrame.rotation(attachmentHandToDisplayedRotation)
                .mul(load(attachmentOriginalFrame, originalPoses[joint]));
        if (epicWeight < 1.0F) {
            // The body already contains its complete YSM/transition pose. Only blend
            // the grip relative to that hand: live EF Tool motion must not continue
            // independently while YSM is in charge (including addon idle Tool tracks).
            if (!splitGrip(load(attachmentOriginalFrame, originalPoses[joint]),
                    attachmentOriginalTransform)
                    || !splitGrip(load(attachmentOriginalFrame, referenceBindWorlds[joint]),
                    attachmentBindTransform)) {
                return grip;
            }
            attachmentGripRotation.set(attachmentHandRotation).conjugate()
                    .mul(attachmentOriginalTransform.rotation);
            attachmentBindTransform.rotation.slerp(attachmentGripRotation, epicWeight);
            attachmentGripRotation.set(attachmentSourceTransform.rotation)
                    .mul(attachmentBindTransform.rotation);
            lerpLinear(attachmentBindTransform.residual, attachmentOriginalTransform.residual,
                    epicWeight, attachmentDeltaFrame);
            attachmentResultFrame.rotation(attachmentGripRotation).mul(attachmentDeltaFrame);
            attachmentGripOffset.mul(epicWeight);
        }
        attachmentResultFrame.m30(fist.x() + attachmentGripOffset.x())
                .m31(fist.y() + attachmentGripOffset.y())
                .m32(fist.z() + attachmentGripOffset.z());
        store(grip, attachmentResultFrame);
        return grip;
    }

    private boolean splitGrip(Matrix4f frame, LocalTransform destination) {
        if (!decomposeAffine(frame, destination)
                || !toolRotation(frame, destination.rotation)) {
            return false;
        }
        destination.updateResidual();
        return true;
    }

    @Nullable
    private Quaternionf displayedAttachmentRotation(OpenMatrix4f complete, int joint) {
        // The displayed frame is C*S, not S^-1*C*S. The latter describes the
        // unscaled authored model and tilts attachments when height != width.
        attachmentSourceFrame.set(load(authoredItemScratch, complete))
                .mul(attachmentModelScale);
        if (toolRotation(attachmentSourceFrame, attachmentSourceTransform.rotation)) {
            attachmentSourceTransform.rotation.mul(referenceBindRotations[joint]);
            return attachmentSourceTransform.rotation;
        }
        return null;
    }

    /** Removes both left and right scale/shear without tilting the Tool's grip. */
    private boolean toolRotation(Matrix4f frame, Quaternionf destination) {
        attachmentPolarFrame.set(frame);
        float determinant = attachmentPolarFrame.determinant();
        if (!Float.isFinite(determinant)
                || Math.abs(determinant) <= SINGULAR_DETERMINANT_EPSILON) {
            return false;
        }
        if (determinant < 0.0F) {
            // Reflections cannot be represented by a proper rotation. Keep the sign
            // in the original Tool residual and use a deterministic proper frame.
            attachmentPolarFrame.m20(-attachmentPolarFrame.m20())
                    .m21(-attachmentPolarFrame.m21()).m22(-attachmentPolarFrame.m22());
        }
        for (int iteration = 0; iteration < 12; iteration++) {
            attachmentPolarPrevious.set(attachmentPolarFrame);
            attachmentPolarInverse.set(attachmentPolarFrame).invert().transpose();
            double norm = squaredNorm(attachmentPolarFrame);
            double inverseNorm = squaredNorm(attachmentPolarInverse);
            float balancingScale = (float) Math.sqrt(Math.sqrt(inverseNorm / norm));
            if (!Float.isFinite(balancingScale) || balancingScale <= 0.0F) {
                return false;
            }
            attachmentPolarFrame.scale(balancingScale)
                    .add(attachmentPolarInverse.scale(1.0F / balancingScale)).scale(0.5F);
            if (attachmentPolarFrame.equals(attachmentPolarPrevious, 1.0E-6F)) {
                attachmentPolarFrame.getNormalizedRotation(destination).normalize();
                return finite(destination);
            }
        }
        return false;
    }

    private static double squaredNorm(Matrix3f matrix) {
        return (double) matrix.m00() * matrix.m00() + (double) matrix.m01() * matrix.m01()
                + (double) matrix.m02() * matrix.m02() + (double) matrix.m10() * matrix.m10()
                + (double) matrix.m11() * matrix.m11() + (double) matrix.m12() * matrix.m12()
                + (double) matrix.m20() * matrix.m20() + (double) matrix.m21() * matrix.m21()
                + (double) matrix.m22() * matrix.m22();
    }

    private boolean displayedLocatorLinear(OpenMatrix4f complete) {
        attachmentDeltaFrame.set(attachmentModelScaleInverse)
                .mul(load(authoredItemScratch, complete))
                .mul(attachmentModelScale);
        attachmentDeltaFrame.m03(0.0F).m13(0.0F).m23(0.0F)
                .m30(0.0F).m31(0.0F).m32(0.0F).m33(1.0F);
        return finite(attachmentDeltaFrame);
    }

    /**
     * Blends a completed pose from an earlier render into the current completed pose.
     *
     * <p>Only the private pose slots used by this YSM mesh are changed. Epic Fight's
     * leading armature slots stay live, while the model's authored hierarchy is blended
     * in local space so connected children do not separate during an ownership change.</p>
     */
    public void blendFromComplete(@Nullable OpenMatrix4f[] destination,
                                  @Nullable OpenMatrix4f[] source,
                                  float sourceWeight) {
        if (destination == null || source == null
                || destination.length != layout.totalPoseCount()
                || source.length != layout.totalPoseCount()) {
            return;
        }
        for (AuxiliaryBoneLayout.Entry entry : layout.entries()) {
            completeBlendSource[entry.auxiliaryIndex()] = source[entry.poseIndex()];
        }
        applyFullBodyBlend(layout, destination, completeBlendSource,
                sourceWeight, blendScratch);
    }

    /** Carries grip ownership through the body's existing transition, with no new clock. */
    void blendFromComplete(OpenMatrix4f[] destination, OpenMatrix4f[] source,
                           float sourceWeight, float sourceRightGrip, float sourceLeftGrip) {
        if (destination == null || source == null
                || destination.length != layout.totalPoseCount()
                || source.length != layout.totalPoseCount()) {
            return;
        }
        blendFromComplete(destination, source, sourceWeight);
        if (validBlendSource(completeBlendSource)) {
            float weight = unitWeight(sourceWeight);
            rightEpicGripWeight = lerp(rightEpicGripWeight, sourceRightGrip, weight);
            leftEpicGripWeight = lerp(leftEpicGripWeight, sourceLeftGrip, weight);
        }
    }

    private boolean prepareArmature(Armature armature) {
        if (armature.getJointNumber() < HumanoidRig.EPIC_JOINT_COUNT) {
            return false;
        }
        if (preparedArmature == armature) {
            return true;
        }
        for (int index = 0; index < toOrigin.length; index++) {
            Joint joint = armature.searchJointById(index);
            if (joint == null) {
                preparedArmature = null;
                return false;
            }
            toOrigin[index] = joint.getToOrigin();
            OpenMatrix4f.invert(toOrigin[index], referenceBindWorlds[index]);
            if (!finite(referenceBindWorlds[index])
                    || !decomposeAffine(
                    load(attachmentSourceFrame, referenceBindWorlds[index]),
                    attachmentBindTransform)
                    || !attachmentBindTransform.rotationValid) {
                preparedArmature = null;
                return false;
            }
            referenceBindRotations[index].set(attachmentBindTransform.rotation);
            Vector3f bindOrigin = new Vector3f(
                    referenceBindWorlds[index].m30, referenceBindWorlds[index].m31,
                    referenceBindWorlds[index].m32);
            if (!finite(bindOrigin)) {
                preparedArmature = null;
                return false;
            }
            referenceBindOrigins[index] = bindOrigin;
        }
        preparedArmature = armature;
        return true;
    }

    @Nullable
    static OpenMatrix4f placeAtDisplayedFist(
            OpenMatrix4f selectedPose, OpenMatrix4f handSkin, OpenMatrix4f toolSkin,
            Vector3f referenceToolOrigin, Vector3f displayedFist,
            OpenMatrix4f destination, Vec4f referencePoint,
            Vec4f handPoint, Vec4f toolPoint) {
        referencePoint.set(referenceToolOrigin.x(), referenceToolOrigin.y(),
                referenceToolOrigin.z(), 1.0F);
        OpenMatrix4f.transform(handSkin, referencePoint, handPoint);
        OpenMatrix4f.transform(toolSkin, referencePoint, toolPoint);
        float x = displayedFist.x() + toolPoint.x - handPoint.x;
        float y = displayedFist.y() + toolPoint.y - handPoint.y;
        float z = displayedFist.z() + toolPoint.z - handPoint.z;
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
            return null;
        }
        destination.load(selectedPose);
        destination.m30 = x;
        destination.m31 = y;
        destination.m32 = z;
        return destination;
    }

    private static boolean finite(Vector3f value) {
        return Float.isFinite(value.x()) && Float.isFinite(value.y())
                && Float.isFinite(value.z());
    }

    private static boolean finite(Vec4f value) {
        return Float.isFinite(value.x) && Float.isFinite(value.y)
                && Float.isFinite(value.z) && Float.isFinite(value.w);
    }

    static OpenMatrix4f[] compose(OpenMatrix4f[] poses, OpenMatrix4f[] toOrigin,
                                  AuxiliaryBoneLayout layout, OpenMatrix4f[] destination,
                                  @Nullable OpenMatrix4f[] parallelDeltas,
                                  @Nullable OpenMatrix4f[] wholeModelDeltas) {
        return compose(poses, toOrigin, layout, destination, parallelDeltas, wholeModelDeltas,
                null, null, false, null, null, null);
    }

    static OpenMatrix4f[] compose(OpenMatrix4f[] poses, OpenMatrix4f[] toOrigin,
                                  AuxiliaryBoneLayout layout, OpenMatrix4f[] destination,
                                  @Nullable OpenMatrix4f[] parallelDeltas,
                                  @Nullable OpenMatrix4f[] wholeModelDeltas,
                                  boolean replaceEpicFightPose) {
        return compose(poses, toOrigin, layout, destination, parallelDeltas, wholeModelDeltas,
                null, null, replaceEpicFightPose, null, null, null);
    }

    static OpenMatrix4f[] compose(OpenMatrix4f[] poses, OpenMatrix4f[] toOrigin,
                                  AuxiliaryBoneLayout layout, OpenMatrix4f[] destination,
                                  @Nullable OpenMatrix4f[] parallelDeltas,
                                  @Nullable OpenMatrix4f[] wholeModelDeltas,
                                  @Nullable OpenMatrix4f[] retargetedAnchors) {
        return compose(poses, toOrigin, layout, destination, parallelDeltas, wholeModelDeltas,
                null, retargetedAnchors, false, null, null, null);
    }

    static OpenMatrix4f[] compose(OpenMatrix4f[] poses, OpenMatrix4f[] toOrigin,
                                  AuxiliaryBoneLayout layout, OpenMatrix4f[] destination,
                                  @Nullable OpenMatrix4f[] parallelDeltas,
                                  @Nullable OpenMatrix4f[] wholeModelDeltas,
                                  @Nullable OpenMatrix4f[] retargetedAnchors,
                                  boolean replaceEpicFightPose) {
        return compose(poses, toOrigin, layout, destination, parallelDeltas,
                wholeModelDeltas, null, retargetedAnchors, replaceEpicFightPose,
                null, null, null);
    }

    static OpenMatrix4f[] compose(OpenMatrix4f[] poses, OpenMatrix4f[] toOrigin,
                                  AuxiliaryBoneLayout layout, OpenMatrix4f[] destination,
                                  @Nullable OpenMatrix4f[] parallelDeltas,
                                  @Nullable OpenMatrix4f[] wholeModelDeltas,
                                  @Nullable OpenMatrix4f[] heldItemDeltas,
                                  @Nullable OpenMatrix4f[] retargetedAnchors,
                                  boolean replaceEpicFightPose,
                                  @Nullable boolean[] replaceEpicFightAnchors,
                                  @Nullable boolean[] suppressParallelDeltas,
                                  @Nullable int[] heldItemAnchorJoints) {
        return compose(poses, toOrigin, layout, destination, parallelDeltas,
                wholeModelDeltas, heldItemDeltas, retargetedAnchors,
                replaceEpicFightPose, replaceEpicFightAnchors, suppressParallelDeltas,
                heldItemAnchorJoints, null, 0.0F, null);
    }

    static OpenMatrix4f[] compose(OpenMatrix4f[] poses, OpenMatrix4f[] toOrigin,
                                  AuxiliaryBoneLayout layout, OpenMatrix4f[] destination,
                                  @Nullable OpenMatrix4f[] parallelDeltas,
                                  @Nullable OpenMatrix4f[] wholeModelDeltas,
                                  @Nullable OpenMatrix4f[] heldItemDeltas,
                                  @Nullable OpenMatrix4f[] retargetedAnchors,
                                  boolean replaceEpicFightPose,
                                  @Nullable boolean[] replaceEpicFightAnchors,
                                  @Nullable boolean[] suppressParallelDeltas,
                                  @Nullable int[] heldItemAnchorJoints,
                                  @Nullable OpenMatrix4f[] fullBodyBlendSource,
                                  float fullBodyBlendWeight) {
        BlendScratch scratch = fullBodyBlendSource == null || fullBodyBlendWeight <= 0.0F
                ? null : new BlendScratch(layout.entries().size());
        return compose(poses, toOrigin, layout, destination, parallelDeltas,
                wholeModelDeltas, heldItemDeltas, retargetedAnchors,
                replaceEpicFightPose, replaceEpicFightAnchors, suppressParallelDeltas,
                heldItemAnchorJoints, fullBodyBlendSource, fullBodyBlendWeight, scratch);
    }

    private static OpenMatrix4f[] compose(OpenMatrix4f[] poses, OpenMatrix4f[] toOrigin,
                                          AuxiliaryBoneLayout layout,
                                          OpenMatrix4f[] destination,
                                          @Nullable OpenMatrix4f[] parallelDeltas,
                                          @Nullable OpenMatrix4f[] wholeModelDeltas,
                                          @Nullable OpenMatrix4f[] heldItemDeltas,
                                          @Nullable OpenMatrix4f[] retargetedAnchors,
                                          boolean replaceEpicFightPose,
                                          @Nullable boolean[] replaceEpicFightAnchors,
                                          @Nullable boolean[] suppressParallelDeltas,
                                          @Nullable int[] heldItemAnchorJoints,
                                          @Nullable OpenMatrix4f[] fullBodyBlendSource,
                                          float fullBodyBlendWeight,
                                          @Nullable BlendScratch blendScratch) {
        if (poses.length < HumanoidRig.EPIC_JOINT_COUNT
                || toOrigin.length < HumanoidRig.EPIC_JOINT_COUNT
                || destination.length != layout.totalPoseCount()) {
            throw new IllegalArgumentException("Invalid humanoid pose matrix count");
        }
        for (int index = 0; index < HumanoidRig.EPIC_JOINT_COUNT; index++) {
            destination[index].load(poses[index]).mulBack(toOrigin[index]);
        }
        for (AuxiliaryBoneLayout.Entry entry : layout.entries()) {
            OpenMatrix4f anchor = retargetedAnchors != null
                    && entry.anchorJoint() < retargetedAnchors.length
                    && retargetedAnchors[entry.anchorJoint()] != null
                    ? retargetedAnchors[entry.anchorJoint()]
                    : destination[entry.anchorJoint()];
            // A mounted YSM state is already a complete pose. Starting from the bind skin
            // matrix prevents Epic Fight's own riding pose from moving the limbs a second time.
            int auxiliary = entry.auxiliaryIndex();
            boolean selectivelyReplaceAnchor = replaceEpicFightAnchors != null
                    && auxiliary < replaceEpicFightAnchors.length
                    && replaceEpicFightAnchors[auxiliary];
            boolean replaceAnchor = replaceEpicFightPose || selectivelyReplaceAnchor;
            // Custom YSM-held props need their authored path below a live Tool joint while
            // the surrounding body remains under Epic Fight. Full-body custom-bow poses do
            // not use this selective path at all, so shoulders cannot be split at a seam.
            int heldAnchorJoint = heldItemAnchorJoints != null
                    && auxiliary < heldItemAnchorJoints.length
                    ? heldItemAnchorJoints[auxiliary] : -1;
            OpenMatrix4f attachmentAnchor = null;
            if (heldAnchorJoint >= 0
                    && heldAnchorJoint < HumanoidRig.EPIC_JOINT_COUNT) {
                if (retargetedAnchors != null
                        && heldAnchorJoint < retargetedAnchors.length
                        && retargetedAnchors[heldAnchorJoint] != null) {
                    attachmentAnchor = retargetedAnchors[heldAnchorJoint];
                } else if (heldAnchorJoint == HumanoidRig.RIGHT_TOOL
                        || heldAnchorJoint == HumanoidRig.LEFT_TOOL) {
                    // A missing private Tool retarget should not move a prop to the
                    // identity Tool pose. Keep it spatially safe at its normal hand seam.
                    attachmentAnchor = anchor;
                } else {
                    attachmentAnchor = destination[heldAnchorJoint];
                }
            }
            destination[entry.poseIndex()].load(replaceEpicFightPose ? IDENTITY
                    : replaceAnchor && attachmentAnchor != null
                    ? attachmentAnchor : replaceAnchor ? IDENTITY : anchor);
            if (selectivelyReplaceAnchor && heldItemDeltas != null
                    && auxiliary < heldItemDeltas.length) {
                // Selective YSM animation is relative to the chosen live Epic Fight seam.
                // Post-multiplication keeps Tool translation and rotation outside the
                // authored prop/upper-body delta instead of making the hand orbit it.
                destination[entry.poseIndex()].mulBack(heldItemDeltas[auxiliary]);
            }
            boolean suppressParallel = suppressParallelDeltas != null
                    && auxiliary < suppressParallelDeltas.length
                    && suppressParallelDeltas[auxiliary];
            if (!suppressParallel && parallelDeltas != null
                    && auxiliary < parallelDeltas.length) {
                // Hair, tails, and other secondary bones live below the authored bow/head
                // path. Applying them last preserves that local relationship when the bow
                // owns one connected upper-body island.
                destination[entry.poseIndex()].mulBack(parallelDeltas[auxiliary]);
            }
            if (wholeModelDeltas != null && auxiliary < wholeModelDeltas.length) {
                // Mounted states and roulette clips can move the whole model. Apply their
                // chained model-space delta outside every local pose so all parts stay joined.
                destination[entry.poseIndex()].mulFront(wholeModelDeltas[auxiliary]);
            }
        }
        applyFullBodyBlend(layout, destination, fullBodyBlendSource,
                fullBodyBlendWeight, blendScratch);
        return destination;
    }

    private static void applyFullBodyBlend(
            AuxiliaryBoneLayout layout, OpenMatrix4f[] destination,
            @Nullable OpenMatrix4f[] source, float sourceWeight,
            @Nullable BlendScratch suppliedScratch) {
        if (source == null || !Float.isFinite(sourceWeight) || sourceWeight <= 0.0F) {
            return;
        }
        int count = layout.entries().size();
        if (source.length != count) {
            return;
        }
        for (OpenMatrix4f matrix : source) {
            if (matrix == null || !finite(matrix)) {
                // The source is an optimization snapshot, never an authority. A malformed
                // snapshot must leave the already-composed live Epic Fight target intact.
                return;
            }
        }
        if (sourceWeight >= 1.0F) {
            for (AuxiliaryBoneLayout.Entry entry : layout.entries()) {
                destination[entry.poseIndex()].load(source[entry.auxiliaryIndex()]);
            }
            return;
        }

        BlendScratch scratch = suppliedScratch == null
                ? new BlendScratch(count) : suppliedScratch;
        if (scratch.sourceWorld.length != count) {
            return;
        }
        float horizontalScale = layout.horizontalScale();
        float verticalScale = layout.verticalScale();
        scratch.modelScale.scaling(horizontalScale, verticalScale, horizontalScale);
        scratch.modelScaleInverse.scaling(1.0F / horizontalScale,
                1.0F / verticalScale, 1.0F / horizontalScale);
        for (AuxiliaryBoneLayout.Entry entry : layout.entries()) {
            int auxiliary = entry.auxiliaryIndex();
            // pose.output is S * skin * S^-1 while the baked rest vertex is
            // S * bindWorld. Recover the authored, unscaled model-space world so parent
            // locals and their Bedrock pivots remain in one coordinate system.
            scratch.sourceWorld[auxiliary].set(scratch.modelScaleInverse)
                    .mul(load(scratch.sourceSkin, source[auxiliary]))
                    .mul(scratch.modelScale)
                    .mul(entry.bindWorld());
            scratch.targetWorld[auxiliary].set(scratch.modelScaleInverse)
                    .mul(load(scratch.targetSkin, destination[entry.poseIndex()]))
                    .mul(scratch.modelScale)
                    .mul(entry.bindWorld());
            if (!finite(scratch.sourceWorld[auxiliary])
                    || !finite(scratch.targetWorld[auxiliary])) {
                return;
            }
        }

        float targetWeight = 1.0F - sourceWeight;
        for (AuxiliaryBoneLayout.Entry entry : layout.entries()) {
            int auxiliary = entry.auxiliaryIndex();
            int parent = entry.parentAuxiliaryIndex();
            boolean sourceLocalValid = relativeLocal(scratch.sourceWorld[auxiliary],
                    parent, scratch.sourceWorld, scratch.sourceLocal, scratch.inverse);
            boolean targetLocalValid = relativeLocal(scratch.targetWorld[auxiliary],
                    parent, scratch.targetWorld, scratch.targetLocal, scratch.inverse);

            if (!sourceLocalValid && targetLocalValid) {
                scratch.sourceLocal.set(scratch.targetLocal);
                sourceLocalValid = true;
            } else if (!targetLocalValid && sourceLocalValid) {
                scratch.targetLocal.set(scratch.sourceLocal);
                targetLocalValid = true;
            } else if (!sourceLocalValid) {
                scratch.sourceLocal.set(entry.bindLocal());
                scratch.targetLocal.set(entry.bindLocal());
                sourceLocalValid = true;
                targetLocalValid = true;
            }

            GeometryDocument.Bone bone = entry.bone();
            centerAtPivot(scratch.sourceLocal, bone, scratch.sourceCentered);
            centerAtPivot(scratch.targetLocal, bone, scratch.targetCentered);
            centerAtPivot(entry.bindLocal(), bone, scratch.fallbackCentered);
            boolean sourceDecomposed = sourceLocalValid
                    && decomposeAffine(scratch.sourceCentered, scratch.source);
            boolean targetDecomposed = targetLocalValid
                    && decomposeAffine(scratch.targetCentered, scratch.target);
            boolean fallbackDecomposed = decomposeAffine(
                    scratch.fallbackCentered, scratch.fallback);
            if (!sourceDecomposed && targetDecomposed) {
                scratch.source.set(scratch.target);
            } else if (!targetDecomposed && sourceDecomposed) {
                scratch.target.set(scratch.source);
            } else if (!sourceDecomposed) {
                if (!fallbackDecomposed) {
                    scratch.fallback.identity();
                }
                scratch.source.set(scratch.fallback);
                scratch.target.set(scratch.fallback);
            }
            if (!scratch.source.rotationValid) {
                scratch.source.rotation.set(scratch.target.rotationValid
                        ? scratch.target.rotation : scratch.fallback.rotation);
            }
            if (!scratch.target.rotationValid) {
                scratch.target.rotation.set(scratch.source.rotationValid
                        ? scratch.source.rotation : scratch.fallback.rotation);
            }
            scratch.source.updateResidual();
            scratch.target.updateResidual();

            scratch.blended.translation.set(scratch.source.translation)
                    .lerp(scratch.target.translation, targetWeight);
            scratch.blended.rotation.set(scratch.source.rotation);
            if (scratch.blended.rotation.dot(scratch.target.rotation) < 0.0F) {
                scratch.target.rotation.set(-scratch.target.rotation.x,
                        -scratch.target.rotation.y, -scratch.target.rotation.z,
                        -scratch.target.rotation.w);
            }
            scratch.blended.rotation.slerp(scratch.target.rotation, targetWeight)
                    .normalize();
            lerpLinear(scratch.source.residual, scratch.target.residual,
                    targetWeight, scratch.blended.residual);
            scratch.blendedCentered.translation(scratch.blended.translation)
                    .rotate(scratch.blended.rotation)
                    .mul(scratch.blended.residual);
            scratch.blendedLocal.translation(
                            bone.pivotX(), bone.pivotY(), bone.pivotZ())
                    .mul(scratch.blendedCentered)
                    .translate(-bone.pivotX(), -bone.pivotY(), -bone.pivotZ());
            if (parent >= 0) {
                scratch.blendedWorld[auxiliary]
                        .set(scratch.blendedWorld[parent]).mul(scratch.blendedLocal);
            } else {
                scratch.blendedWorld[auxiliary].set(scratch.blendedLocal);
            }
            scratch.output.set(scratch.modelScale)
                    .mul(scratch.blendedWorld[auxiliary])
                    .mul(entry.bindWorldInverse())
                    .mul(scratch.modelScaleInverse);
            if (!finite(scratch.output)) {
                return;
            }
            scratch.blendedSkin[auxiliary].set(scratch.output);
        }
        // Publish transactionally only after every local hierarchy entry is valid. This
        // preserves the normal target if an internal snapshot overflows during recovery.
        for (AuxiliaryBoneLayout.Entry entry : layout.entries()) {
            store(destination[entry.poseIndex()],
                    scratch.blendedSkin[entry.auxiliaryIndex()]);
        }
    }

    private static boolean relativeLocal(Matrix4f world, int parent,
                                         Matrix4f[] candidateWorlds,
                                         Matrix4f destination, Matrix4f inverse) {
        if (parent < 0) {
            destination.set(world);
            return finite(destination);
        }
        Matrix4f parentWorld = candidateWorlds[parent];
        float determinant = parentWorld.determinant();
        if (!Float.isFinite(determinant)
                || Math.abs(determinant) <= SINGULAR_DETERMINANT_EPSILON) {
            return false;
        }
        inverse.set(parentWorld).invert();
        destination.set(inverse).mul(world);
        return finite(destination);
    }

    private static Matrix4f centerAtPivot(Matrix4f matrix, GeometryDocument.Bone bone,
                                          Matrix4f destination) {
        return destination.translation(-bone.pivotX(), -bone.pivotY(), -bone.pivotZ())
                .mul(matrix)
                .translate(bone.pivotX(), bone.pivotY(), bone.pivotZ());
    }

    /**
     * Splits an affine transform into a proper rotation and a complete linear residual.
     * Unlike a TRS scale vector, the residual retains reflection signs and shear, so a
     * value immediately below sourceWeight=1 remains continuous with the exact endpoint.
     */
    private static boolean decomposeAffine(Matrix4f matrix, LocalTransform destination) {
        if (!finite(matrix)) {
            return false;
        }
        destination.translation.set(matrix.m30(), matrix.m31(), matrix.m32());
        destination.linear.set(
                matrix.m00(), matrix.m01(), matrix.m02(), 0.0F,
                matrix.m10(), matrix.m11(), matrix.m12(), 0.0F,
                matrix.m20(), matrix.m21(), matrix.m22(), 0.0F,
                0.0F, 0.0F, 0.0F, 1.0F);
        if (!finite(destination.translation) || !finite(destination.linear)) {
            destination.rotation.identity();
            destination.rotationValid = false;
            return false;
        }
        float determinant = determinant3x3(destination.linear);
        destination.rotationValid = Float.isFinite(determinant)
                && Math.abs(determinant) > DECOMPOSITION_DETERMINANT_EPSILON;
        if (destination.rotationValid) {
            destination.linear.getUnnormalizedRotation(destination.rotation).normalize();
            destination.rotationValid = finite(destination.rotation);
        }
        if (!destination.rotationValid) {
            destination.rotation.identity();
        }
        return true;
    }

    private static float determinant3x3(Matrix4f matrix) {
        return matrix.m00() * (matrix.m11() * matrix.m22() - matrix.m12() * matrix.m21())
                - matrix.m10() * (matrix.m01() * matrix.m22()
                - matrix.m02() * matrix.m21())
                + matrix.m20() * (matrix.m01() * matrix.m12()
                - matrix.m02() * matrix.m11());
    }

    private static void lerpLinear(Matrix4f source, Matrix4f target, float amount,
                                   Matrix4f destination) {
        destination.set(
                lerp(source.m00(), target.m00(), amount),
                lerp(source.m01(), target.m01(), amount),
                lerp(source.m02(), target.m02(), amount), 0.0F,
                lerp(source.m10(), target.m10(), amount),
                lerp(source.m11(), target.m11(), amount),
                lerp(source.m12(), target.m12(), amount), 0.0F,
                lerp(source.m20(), target.m20(), amount),
                lerp(source.m21(), target.m21(), amount),
                lerp(source.m22(), target.m22(), amount), 0.0F,
                0.0F, 0.0F, 0.0F, 1.0F);
    }

    private static float lerp(float source, float target, float amount) {
        return source + (target - source) * amount;
    }

    private static Matrix4f load(Matrix4f destination, OpenMatrix4f source) {
        return destination.set(
                source.m00, source.m01, source.m02, source.m03,
                source.m10, source.m11, source.m12, source.m13,
                source.m20, source.m21, source.m22, source.m23,
                source.m30, source.m31, source.m32, source.m33);
    }

    private static void store(OpenMatrix4f destination, Matrix4f source) {
        destination.m00 = source.m00();
        destination.m01 = source.m01();
        destination.m02 = source.m02();
        destination.m03 = source.m03();
        destination.m10 = source.m10();
        destination.m11 = source.m11();
        destination.m12 = source.m12();
        destination.m13 = source.m13();
        destination.m20 = source.m20();
        destination.m21 = source.m21();
        destination.m22 = source.m22();
        destination.m23 = source.m23();
        destination.m30 = source.m30();
        destination.m31 = source.m31();
        destination.m32 = source.m32();
        destination.m33 = source.m33();
    }

    private static boolean finite(OpenMatrix4f matrix) {
        return Float.isFinite(matrix.m00) && Float.isFinite(matrix.m01)
                && Float.isFinite(matrix.m02) && Float.isFinite(matrix.m03)
                && Float.isFinite(matrix.m10) && Float.isFinite(matrix.m11)
                && Float.isFinite(matrix.m12) && Float.isFinite(matrix.m13)
                && Float.isFinite(matrix.m20) && Float.isFinite(matrix.m21)
                && Float.isFinite(matrix.m22) && Float.isFinite(matrix.m23)
                && Float.isFinite(matrix.m30) && Float.isFinite(matrix.m31)
                && Float.isFinite(matrix.m32) && Float.isFinite(matrix.m33);
    }

    private static boolean finite(Matrix4f matrix) {
        return Float.isFinite(matrix.m00()) && Float.isFinite(matrix.m01())
                && Float.isFinite(matrix.m02()) && Float.isFinite(matrix.m03())
                && Float.isFinite(matrix.m10()) && Float.isFinite(matrix.m11())
                && Float.isFinite(matrix.m12()) && Float.isFinite(matrix.m13())
                && Float.isFinite(matrix.m20()) && Float.isFinite(matrix.m21())
                && Float.isFinite(matrix.m22()) && Float.isFinite(matrix.m23())
                && Float.isFinite(matrix.m30()) && Float.isFinite(matrix.m31())
                && Float.isFinite(matrix.m32()) && Float.isFinite(matrix.m33());
    }

    private static boolean finite(Quaternionf value) {
        return Float.isFinite(value.x) && Float.isFinite(value.y)
                && Float.isFinite(value.z) && Float.isFinite(value.w);
    }

    private static final class LocalTransform {
        private final Vector3f translation = new Vector3f();
        private final Quaternionf rotation = new Quaternionf();
        private final Quaternionf inverseRotation = new Quaternionf();
        private final Matrix4f linear = new Matrix4f();
        private final Matrix4f residual = new Matrix4f();
        private boolean rotationValid = true;

        private void set(LocalTransform source) {
            translation.set(source.translation);
            rotation.set(source.rotation);
            linear.set(source.linear);
            residual.set(source.residual);
            rotationValid = source.rotationValid;
        }

        private void identity() {
            translation.zero();
            rotation.identity();
            linear.identity();
            residual.identity();
            rotationValid = true;
        }

        private void updateResidual() {
            inverseRotation.set(rotation).conjugate();
            residual.rotation(inverseRotation).mul(linear);
        }
    }

    private static final class BlendScratch {
        private final Matrix4f[] sourceWorld;
        private final Matrix4f[] targetWorld;
        private final Matrix4f[] blendedWorld;
        private final Matrix4f[] blendedSkin;
        private final Matrix4f sourceLocal = new Matrix4f();
        private final Matrix4f targetLocal = new Matrix4f();
        private final Matrix4f sourceCentered = new Matrix4f();
        private final Matrix4f targetCentered = new Matrix4f();
        private final Matrix4f fallbackCentered = new Matrix4f();
        private final Matrix4f blendedCentered = new Matrix4f();
        private final Matrix4f blendedLocal = new Matrix4f();
        private final Matrix4f inverse = new Matrix4f();
        private final Matrix4f output = new Matrix4f();
        private final Matrix4f sourceSkin = new Matrix4f();
        private final Matrix4f targetSkin = new Matrix4f();
        private final Matrix4f modelScale = new Matrix4f();
        private final Matrix4f modelScaleInverse = new Matrix4f();
        private final LocalTransform source = new LocalTransform();
        private final LocalTransform target = new LocalTransform();
        private final LocalTransform blended = new LocalTransform();
        private final LocalTransform fallback = new LocalTransform();

        private BlendScratch(int count) {
            sourceWorld = matrices(count);
            targetWorld = matrices(count);
            blendedWorld = matrices(count);
            blendedSkin = matrices(count);
        }

        private static Matrix4f[] matrices(int count) {
            Matrix4f[] result = new Matrix4f[count];
            for (int index = 0; index < count; index++) {
                result[index] = new Matrix4f();
            }
            return result;
        }
    }

    static OpenMatrix4f[] allocate(int count) {
        OpenMatrix4f[] matrices = new OpenMatrix4f[count];
        for (int index = 0; index < count; index++) {
            matrices[index] = new OpenMatrix4f();
        }
        return matrices;
    }

    private static Quaternionf[] quaternions(int count) {
        Quaternionf[] values = new Quaternionf[count];
        for (int index = 0; index < count; index++) {
            values[index] = new Quaternionf();
        }
        return values;
    }
}
