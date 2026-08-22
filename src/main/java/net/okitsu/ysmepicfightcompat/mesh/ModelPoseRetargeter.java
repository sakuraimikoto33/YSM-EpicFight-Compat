package net.okitsu.ysmepicfightcompat.mesh;

import org.joml.Vector3f;
import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import javax.annotation.Nullable;
import java.util.Arrays;

/** Re-evaluates Epic Fight pose deltas around model-specific humanoid bind pivots. */
final class ModelPoseRetargeter {
    private static final int JOINT_COUNT = HumanoidRig.EPIC_JOINT_COUNT;

    private final AuxiliaryBoneLayout layout;
    private final OpenMatrix4f[] referenceLocals = AuxiliaryPoseMatrices.allocate(JOINT_COUNT);
    private final OpenMatrix4f[] targetLocals = AuxiliaryPoseMatrices.allocate(JOINT_COUNT);
    private final OpenMatrix4f[] targetToOrigin = AuxiliaryPoseMatrices.allocate(JOINT_COUNT);
    private final OpenMatrix4f[] targetPoseWorlds = AuxiliaryPoseMatrices.allocate(JOINT_COUNT);
    private final OpenMatrix4f[] targetSkinMatrices = AuxiliaryPoseMatrices.allocate(JOINT_COUNT);
    private final int[] parents = new int[JOINT_COUNT];
    private final int[] traversal = new int[JOINT_COUNT];
    private final OpenMatrix4f identity = new OpenMatrix4f();
    private final OpenMatrix4f referenceBase = new OpenMatrix4f();
    private final OpenMatrix4f referenceBaseInverse = new OpenMatrix4f();
    private final OpenMatrix4f animationDelta = new OpenMatrix4f();
    private final OpenMatrix4f targetBase = new OpenMatrix4f();

    private Armature preparedArmature;
    private int traversalCount;
    private boolean prepared;

    ModelPoseRetargeter(AuxiliaryBoneLayout layout) {
        this.layout = layout;
    }

    @Nullable
    OpenMatrix4f[] retarget(@Nullable Armature armature, @Nullable OpenMatrix4f[] poses) {
        if (!layout.hasJointPivots() || armature == null || poses == null
                || poses.length < JOINT_COUNT) {
            return null;
        }
        if (preparedArmature != armature) {
            prepared = prepare(armature);
            preparedArmature = armature;
        }
        if (!prepared) {
            return null;
        }
        retarget(poses, referenceLocals, targetLocals, targetToOrigin,
                parents, traversal, traversalCount, targetPoseWorlds, targetSkinMatrices,
                identity, referenceBase, referenceBaseInverse, animationDelta, targetBase);
        return targetSkinMatrices;
    }

    private boolean prepare(Armature armature) {
        Arrays.fill(parents, Integer.MIN_VALUE);
        traversalCount = 0;
        boolean[] seen = new boolean[JOINT_COUNT];
        if (armature.rootJoint == null
                || !prepareJoint(armature.rootJoint, -1, new OpenMatrix4f(), seen)) {
            return false;
        }
        if (traversalCount != JOINT_COUNT) {
            return false;
        }
        for (boolean present : seen) {
            if (!present) {
                return false;
            }
        }
        return true;
    }

    private boolean prepareJoint(Joint joint, int parent,
                                 OpenMatrix4f parentTargetBindWorld, boolean[] seen) {
        int id = joint.getId();
        if (id < 0 || id >= JOINT_COUNT || seen[id]) {
            return false;
        }
        seen[id] = true;
        parents[id] = parent;
        traversal[traversalCount++] = id;
        referenceLocals[id].load(joint.getLocalTransform());
        targetLocals[id].load(referenceLocals[id]);

        Vector3f pivot = layout.jointPivot(id);
        if (pivot != null) {
            OpenMatrix4f pivotWorld = translation(pivot);
            if (parent < 0) {
                targetLocals[id].m30 = pivot.x();
                targetLocals[id].m31 = pivot.y();
                targetLocals[id].m32 = pivot.z();
            } else {
                OpenMatrix4f parentInverse = OpenMatrix4f.invert(
                        parentTargetBindWorld, new OpenMatrix4f());
                OpenMatrix4f offset = OpenMatrix4f.mul(
                        parentInverse, pivotWorld, new OpenMatrix4f());
                targetLocals[id].m30 = offset.m30;
                targetLocals[id].m31 = offset.m31;
                targetLocals[id].m32 = offset.m32;
            }
        }

        OpenMatrix4f targetBindWorld = OpenMatrix4f.mul(
                parentTargetBindWorld, targetLocals[id], new OpenMatrix4f());
        OpenMatrix4f.invert(targetBindWorld, targetToOrigin[id]);
        for (Joint child : joint.getSubJoints()) {
            if (!prepareJoint(child, id, targetBindWorld, seen)) {
                return false;
            }
        }
        return true;
    }

    private static OpenMatrix4f translation(Vector3f value) {
        OpenMatrix4f result = new OpenMatrix4f();
        result.m30 = value.x();
        result.m31 = value.y();
        result.m32 = value.z();
        return result;
    }

    static void retarget(OpenMatrix4f[] poses,
                         OpenMatrix4f[] referenceLocals,
                         OpenMatrix4f[] targetLocals,
                         OpenMatrix4f[] targetToOrigin,
                         int[] parents, int[] traversal, int traversalCount,
                         OpenMatrix4f[] targetPoseWorlds, OpenMatrix4f[] targetSkinMatrices,
                         OpenMatrix4f identity, OpenMatrix4f referenceBase,
                         OpenMatrix4f referenceBaseInverse, OpenMatrix4f animationDelta,
                         OpenMatrix4f targetBase) {
        for (int order = 0; order < traversalCount; order++) {
            int joint = traversal[order];
            int parent = parents[joint];
            OpenMatrix4f referenceParentPose = parent < 0 ? identity : poses[parent];
            OpenMatrix4f targetParentPose = parent < 0 ? identity : targetPoseWorlds[parent];

            OpenMatrix4f.mul(referenceParentPose, referenceLocals[joint], referenceBase);
            OpenMatrix4f.invert(referenceBase, referenceBaseInverse);
            OpenMatrix4f.mul(referenceBaseInverse, poses[joint], animationDelta);
            OpenMatrix4f.mul(targetParentPose, targetLocals[joint], targetBase);
            OpenMatrix4f.mul(targetBase, animationDelta, targetPoseWorlds[joint]);
            OpenMatrix4f.mul(targetPoseWorlds[joint], targetToOrigin[joint],
                    targetSkinMatrices[joint]);
        }
    }
}
