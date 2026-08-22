package net.okitsu.ysmepicfightcompat.mesh;

import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import javax.annotation.Nullable;

/** Builds complete skin matrices without adding joints to Epic Fight's armature. */
public final class AuxiliaryPoseMatrices {
    private static final OpenMatrix4f IDENTITY = new OpenMatrix4f();

    private final AuxiliaryBoneLayout layout;
    private final ModelPoseRetargeter retargeter;
    private final OpenMatrix4f[] output;
    private final OpenMatrix4f[] toOrigin = new OpenMatrix4f[HumanoidRig.EPIC_JOINT_COUNT];
    private Armature preparedArmature;

    public AuxiliaryPoseMatrices(AuxiliaryBoneLayout layout) {
        this.layout = layout;
        retargeter = new ModelPoseRetargeter(layout);
        output = allocate(layout.totalPoseCount());
    }

    @Nullable
    public OpenMatrix4f[] compose(@Nullable Armature armature, @Nullable OpenMatrix4f[] poses,
                                  @Nullable OpenMatrix4f[] parallelDeltas,
                                  @Nullable OpenMatrix4f[] wholeModelDeltas,
                                  boolean replaceEpicFightPose) {
        if (armature == null || poses == null
                || armature.getJointNumber() < HumanoidRig.EPIC_JOINT_COUNT
                || poses.length < HumanoidRig.EPIC_JOINT_COUNT) {
            return null;
        }
        if (preparedArmature != armature) {
            for (int index = 0; index < toOrigin.length; index++) {
                Joint joint = armature.searchJointById(index);
                if (joint == null) {
                    preparedArmature = null;
                    return null;
                }
                toOrigin[index] = joint.getToOrigin();
            }
            preparedArmature = armature;
        }
        OpenMatrix4f[] retargetedAnchors = poses == armature.getPoseMatrices()
                ? retargeter.retarget(armature, poses) : null;
        return compose(poses, toOrigin, layout, output, parallelDeltas, wholeModelDeltas,
                retargetedAnchors, replaceEpicFightPose);
    }

    static OpenMatrix4f[] compose(OpenMatrix4f[] poses, OpenMatrix4f[] toOrigin,
                                  AuxiliaryBoneLayout layout, OpenMatrix4f[] destination,
                                  @Nullable OpenMatrix4f[] parallelDeltas,
                                  @Nullable OpenMatrix4f[] wholeModelDeltas) {
        return compose(poses, toOrigin, layout, destination, parallelDeltas, wholeModelDeltas,
                null, false);
    }

    static OpenMatrix4f[] compose(OpenMatrix4f[] poses, OpenMatrix4f[] toOrigin,
                                  AuxiliaryBoneLayout layout, OpenMatrix4f[] destination,
                                  @Nullable OpenMatrix4f[] parallelDeltas,
                                  @Nullable OpenMatrix4f[] wholeModelDeltas,
                                  boolean replaceEpicFightPose) {
        return compose(poses, toOrigin, layout, destination, parallelDeltas, wholeModelDeltas,
                null, replaceEpicFightPose);
    }

    static OpenMatrix4f[] compose(OpenMatrix4f[] poses, OpenMatrix4f[] toOrigin,
                                  AuxiliaryBoneLayout layout, OpenMatrix4f[] destination,
                                  @Nullable OpenMatrix4f[] parallelDeltas,
                                  @Nullable OpenMatrix4f[] wholeModelDeltas,
                                  @Nullable OpenMatrix4f[] retargetedAnchors) {
        return compose(poses, toOrigin, layout, destination, parallelDeltas, wholeModelDeltas,
                retargetedAnchors, false);
    }

    static OpenMatrix4f[] compose(OpenMatrix4f[] poses, OpenMatrix4f[] toOrigin,
                                  AuxiliaryBoneLayout layout, OpenMatrix4f[] destination,
                                  @Nullable OpenMatrix4f[] parallelDeltas,
                                  @Nullable OpenMatrix4f[] wholeModelDeltas,
                                  @Nullable OpenMatrix4f[] retargetedAnchors,
                                  boolean replaceEpicFightPose) {
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
            destination[entry.poseIndex()].load(replaceEpicFightPose ? IDENTITY : anchor);
            int auxiliary = entry.auxiliaryIndex();
            if (parallelDeltas != null && auxiliary < parallelDeltas.length) {
                // Hair, tails, and other secondary bones are authored inside their YSM
                // anchor. Apply those deltas before Epic Fight moves the anchor.
                destination[entry.poseIndex()].mulBack(parallelDeltas[auxiliary]);
            }
            if (wholeModelDeltas != null && auxiliary < wholeModelDeltas.length) {
                // Mounted states and roulette clips can move the whole model. Apply their
                // chained model-space delta outside the Epic Fight pose so all parts stay joined.
                destination[entry.poseIndex()].mulFront(wholeModelDeltas[auxiliary]);
            }
        }
        return destination;
    }

    static OpenMatrix4f[] allocate(int count) {
        OpenMatrix4f[] matrices = new OpenMatrix4f[count];
        for (int index = 0; index < count; index++) {
            matrices[index] = new OpenMatrix4f();
        }
        return matrices;
    }
}
