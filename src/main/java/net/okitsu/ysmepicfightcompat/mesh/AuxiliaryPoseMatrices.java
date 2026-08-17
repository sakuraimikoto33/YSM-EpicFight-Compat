package net.okitsu.ysmepicfightcompat.mesh;

import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import javax.annotation.Nullable;

/** Builds complete skin matrices without adding joints to Epic Fight's armature. */
public final class AuxiliaryPoseMatrices {
    private final AuxiliaryBoneLayout layout;
    private final OpenMatrix4f[] output;
    private final OpenMatrix4f[] toOrigin = new OpenMatrix4f[HumanoidRig.EPIC_JOINT_COUNT];
    private Armature preparedArmature;

    public AuxiliaryPoseMatrices(AuxiliaryBoneLayout layout) {
        this.layout = layout;
        output = allocate(layout.totalPoseCount());
    }

    @Nullable
    public OpenMatrix4f[] compose(@Nullable Armature armature, @Nullable OpenMatrix4f[] poses,
                                  @Nullable OpenMatrix4f[] auxiliaryDeltas) {
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
        return compose(poses, toOrigin, layout, output, auxiliaryDeltas);
    }

    static OpenMatrix4f[] compose(OpenMatrix4f[] poses, OpenMatrix4f[] toOrigin,
                                  AuxiliaryBoneLayout layout, OpenMatrix4f[] destination,
                                  @Nullable OpenMatrix4f[] auxiliaryDeltas) {
        if (poses.length < HumanoidRig.EPIC_JOINT_COUNT
                || toOrigin.length < HumanoidRig.EPIC_JOINT_COUNT
                || destination.length != layout.totalPoseCount()) {
            throw new IllegalArgumentException("Invalid humanoid pose matrix count");
        }
        for (int index = 0; index < HumanoidRig.EPIC_JOINT_COUNT; index++) {
            destination[index].load(poses[index]).mulBack(toOrigin[index]);
        }
        for (AuxiliaryBoneLayout.Entry entry : layout.entries()) {
            destination[entry.poseIndex()].load(destination[entry.anchorJoint()]);
            if (auxiliaryDeltas != null && entry.auxiliaryIndex() < auxiliaryDeltas.length) {
                destination[entry.poseIndex()].mulBack(auxiliaryDeltas[entry.auxiliaryIndex()]);
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
