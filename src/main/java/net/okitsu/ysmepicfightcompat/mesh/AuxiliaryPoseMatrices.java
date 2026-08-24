package net.okitsu.ysmepicfightcompat.mesh;

import org.joml.Vector3f;
import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.api.utils.math.Vec4f;

import javax.annotation.Nullable;

/** Builds complete skin matrices without adding joints to Epic Fight's armature. */
public final class AuxiliaryPoseMatrices {
    private static final OpenMatrix4f IDENTITY = new OpenMatrix4f();

    private final AuxiliaryBoneLayout layout;
    private final ModelPoseRetargeter retargeter;
    private final OpenMatrix4f[] output;
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
        if (!prepareArmature(armature)) {
            return null;
        }
        OpenMatrix4f[] retargetedAnchors = poses == armature.getPoseMatrices()
                ? retargeter.retarget(armature, poses) : null;
        return compose(poses, toOrigin, layout, output, parallelDeltas, wholeModelDeltas,
                retargetedAnchors, replaceEpicFightPose);
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
