package net.okitsu.ysmepicfightcompat.mesh;

import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
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
    private final OpenMatrix4f[] toOrigin = new OpenMatrix4f[HumanoidRig.EPIC_JOINT_COUNT];
    private final Vector3f[] referenceBindOrigins = new Vector3f[HumanoidRig.EPIC_JOINT_COUNT];
    private final OpenMatrix4f heldItemHandSkin = new OpenMatrix4f();
    private final OpenMatrix4f heldItemToolSkin = new OpenMatrix4f();
    private final OpenMatrix4f heldItemOutput = new OpenMatrix4f();
    private final OpenMatrix4f bindWorldScratch = new OpenMatrix4f();
    private final Vec4f heldItemReferencePoint = new Vec4f();
    private final Vec4f heldItemHandPoint = new Vec4f();
    private final Vec4f heldItemToolPoint = new Vec4f();
    private final Vec4f rightDisplayedPoint = new Vec4f();
    private final Vec4f leftDisplayedPoint = new Vec4f();
    private Armature preparedArmature;

    public AuxiliaryPoseMatrices(AuxiliaryBoneLayout layout) {
        this.layout = layout;
        retargeter = new ModelPoseRetargeter(layout);
        output = allocate(layout.totalPoseCount());
        blendScratch = new BlendScratch(layout.entries().size());
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
        return compose(poses, toOrigin, layout, output, parallelDeltas, wholeModelDeltas,
                heldItemDeltas, retargetedAnchors, replaceEpicFightPose,
                replaceEpicFightAnchors, suppressParallelDeltas,
                heldItemAnchorJoints, fullBodyBlendSource, fullBodyBlendWeight,
                blendScratch);
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
            OpenMatrix4f.invert(toOrigin[index], bindWorldScratch);
            Vector3f bindOrigin = new Vector3f(
                    bindWorldScratch.m30, bindWorldScratch.m31, bindWorldScratch.m32);
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
            boolean replaceAnchor = replaceEpicFightPose
                    || replaceEpicFightAnchors != null
                    && auxiliary < replaceEpicFightAnchors.length
                    && replaceEpicFightAnchors[auxiliary];
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
            if (replaceAnchor && heldItemDeltas != null
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
}
