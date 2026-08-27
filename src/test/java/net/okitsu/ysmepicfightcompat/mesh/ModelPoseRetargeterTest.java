package net.okitsu.ysmepicfightcompat.mesh;

import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.animation.JointTransform;
import yesman.epicfight.api.animation.Pose;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.api.utils.math.Vec3f;
import yesman.epicfight.api.utils.math.Vec4f;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelPoseRetargeterTest {
    @Test
    void keepsTheAuthoredShoulderSeamConnectedOnAnExtendedRotatedMaidArmature() {
        GeometryDocument geometry = magicalRightArmGeometry();
        float modelScale = 0.65F;
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(
                geometry, modelScale, modelScale);

        Armature armature = extendedRotatedMaidArmature();
        Pose attack = new Pose();
        attack.putJointData("Chest", JointTransform.rotation(new Quaternionf()
                .rotateXYZ(radians(-18.0F), radians(22.0F), radians(9.0F))));
        attack.putJointData("Shoulder_R", JointTransform.rotation(new Quaternionf()
                .rotateXYZ(radians(58.0F), radians(-31.0F), radians(27.0F))));
        attack.putJointData("Arm_R", JointTransform.rotation(new Quaternionf()
                .rotateXYZ(radians(-36.0F), radians(44.0F), radians(73.0F))));
        armature.setPose(attack);

        OpenMatrix4f[] retargeted = new ModelPoseRetargeter(layout).retarget(
                armature, armature.getPoseMatrices());
        assertNotNull(retargeted);

        GeometryDocument.Bone rightArm = geometry.bones().get("RightArm");
        Vector3f authoredShoulder = new Vector3f(
                rightArm.pivotX() * modelScale,
                rightArm.pivotY() * modelScale,
                rightArm.pivotZ() * modelScale);
        // Skin matrices on both sides of an authored YSM joint must map the same bind
        // point to the same animated point. This verifies the visible body/arm seam,
        // rather than merely checking that retargeting returned an array.
        assertPointEquals(transform(retargeted[HumanoidRig.CHEST], authoredShoulder),
                transform(retargeted[HumanoidRig.RIGHT_ARM], authoredShoulder));
    }

    @Test
    void acceptsOptionalJointsAppendedToTheHumanoidArmature() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone rootBone = new GeometryDocument.Bone("Torso");
        rootBone.pivot(0.0F, 12.0F, 0.0F);
        GeometryDocument.Bone rightArmBone = new GeometryDocument.Bone("RightArm");
        rightArmBone.parentName("Torso");
        rightArmBone.pivot(-3.0F, 22.0F, 0.0F);
        geometry.add(rootBone);
        geometry.add(rightArmBone);
        geometry.linkHierarchy();
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry);
        assertTrue(layout.hasJointPivots());

        Map<String, Joint> joints = new LinkedHashMap<>();
        Joint root = new Joint("joint_0", 0, new OpenMatrix4f());
        Joint[] core = new Joint[HumanoidRig.EPIC_JOINT_COUNT];
        core[0] = root;
        joints.put(root.getName(), root);
        for (int id = 1; id < HumanoidRig.EPIC_JOINT_COUNT; id++) {
            Joint joint = new Joint("joint_" + id, id, new OpenMatrix4f());
            core[id] = joint;
            root.addSubJoints(joint);
            joints.put(joint.getName(), joint);
        }
        Joint optional = new Joint("optional_maid_joint",
                HumanoidRig.EPIC_JOINT_COUNT,
                new OpenMatrix4f().translate(2.0F, 3.0F, 4.0F));
        Joint nestedOptional = new Joint("nested_optional_maid_joint",
                HumanoidRig.EPIC_JOINT_COUNT + 1, new OpenMatrix4f());
        core[HumanoidRig.RIGHT_TOOL].addSubJoints(optional);
        optional.addSubJoints(nestedOptional);
        joints.put(optional.getName(), optional);
        joints.put(nestedOptional.getName(), nestedOptional);
        Armature armature = new Armature("extended_humanoid",
                HumanoidRig.EPIC_JOINT_COUNT + 2, root, joints);
        armature.bakeOriginMatrices();

        OpenMatrix4f[] retargeted = new ModelPoseRetargeter(layout).retarget(
                armature, armature.getPoseMatrices());

        assertNotNull(retargeted,
                "optional integration joints must not disable humanoid bind retargeting");
    }

    @Test
    void rejectsCanonicalHumanoidJointNestedBelowAnExtension() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone torso = new GeometryDocument.Bone("Torso");
        torso.pivot(0.0F, 12.0F, 0.0F);
        geometry.add(torso);
        geometry.linkHierarchy();
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry);
        assertTrue(layout.hasJointPivots());

        Map<String, Joint> joints = new LinkedHashMap<>();
        Joint root = new Joint("joint_0", 0, new OpenMatrix4f());
        joints.put(root.getName(), root);
        for (int id = 1; id < HumanoidRig.EPIC_JOINT_COUNT; id++) {
            Joint joint = new Joint("joint_" + id, id, new OpenMatrix4f());
            root.addSubJoints(joint);
            joints.put(joint.getName(), joint);
        }
        Joint extension = new Joint("extension", HumanoidRig.EPIC_JOINT_COUNT,
                new OpenMatrix4f());
        Joint duplicateCore = new Joint("duplicate_core", HumanoidRig.RIGHT_ARM,
                new OpenMatrix4f());
        extension.addSubJoints(duplicateCore);
        root.addSubJoints(extension);
        joints.put(extension.getName(), extension);
        joints.put(duplicateCore.getName(), duplicateCore);
        Armature armature = new Armature("malformed_extended_humanoid",
                HumanoidRig.EPIC_JOINT_COUNT + 1, root, joints);
        armature.bakeOriginMatrices();

        assertNull(new ModelPoseRetargeter(layout).retarget(
                armature, armature.getPoseMatrices()));
    }

    @Test
    void keepsNestedCentralJointsConnectedWhileReapplyingLocalAnimation() {
        OpenMatrix4f[] referenceLocals = AuxiliaryPoseMatrices.allocate(3);
        referenceLocals[1].translate(0.0F, 1.0F, 0.0F);
        referenceLocals[2].translate(0.0F, 1.0F, 0.0F);
        OpenMatrix4f[] targetLocals = AuxiliaryPoseMatrices.allocate(3);
        targetLocals[1].translate(0.0F, 2.0F, 0.0F);
        targetLocals[2].translate(0.0F, 3.0F, 0.0F);
        OpenMatrix4f[] targetToOrigin = AuxiliaryPoseMatrices.allocate(3);
        OpenMatrix4f targetChestBind = new OpenMatrix4f(targetLocals[1]);
        OpenMatrix4f targetHeadBind = OpenMatrix4f.mul(
                targetChestBind, targetLocals[2], null);
        OpenMatrix4f.invert(targetChestBind, targetToOrigin[1]);
        OpenMatrix4f.invert(targetHeadBind, targetToOrigin[2]);

        OpenMatrix4f rootRotation = new OpenMatrix4f().rotateDeg(40.0F, Vec3f.Z_AXIS);
        OpenMatrix4f chestRotation = new OpenMatrix4f().rotateDeg(25.0F, Vec3f.X_AXIS);
        OpenMatrix4f headRotation = new OpenMatrix4f().rotateDeg(-15.0F, Vec3f.Y_AXIS);
        OpenMatrix4f[] poses = AuxiliaryPoseMatrices.allocate(3);
        poses[0].load(rootRotation);
        OpenMatrix4f referenceChestBase = OpenMatrix4f.mul(
                poses[0], referenceLocals[1], null);
        OpenMatrix4f.mul(referenceChestBase, chestRotation, poses[1]);
        OpenMatrix4f referenceHeadBase = OpenMatrix4f.mul(
                poses[1], referenceLocals[2], null);
        OpenMatrix4f.mul(referenceHeadBase, headRotation, poses[2]);
        OpenMatrix4f[] targetPoseWorlds = AuxiliaryPoseMatrices.allocate(3);
        OpenMatrix4f[] targetSkinMatrices = AuxiliaryPoseMatrices.allocate(3);

        ModelPoseRetargeter.retarget(poses, referenceLocals, targetLocals, targetToOrigin,
                new int[]{-1, 0, 1}, new int[]{0, 1, 2}, 3,
                targetPoseWorlds, targetSkinMatrices,
                new OpenMatrix4f(), new OpenMatrix4f(), new OpenMatrix4f(),
                new OpenMatrix4f(), new OpenMatrix4f());

        OpenMatrix4f expectedChest = OpenMatrix4f.mul(rootRotation, targetLocals[1], null);
        expectedChest.mulBack(chestRotation);
        OpenMatrix4f expectedHead = OpenMatrix4f.mul(expectedChest, targetLocals[2], null);
        expectedHead.mulBack(headRotation);
        assertMatrixEquals(rootRotation, targetPoseWorlds[0]);
        assertMatrixEquals(expectedChest, targetPoseWorlds[1]);
        assertMatrixEquals(expectedHead, targetPoseWorlds[2]);
        assertMatrixEquals(OpenMatrix4f.mul(expectedHead, targetToOrigin[2], null),
                targetSkinMatrices[2]);
    }

    @Test
    void reappliesTheReferenceLocalRotationAroundTheTargetBindPivot() {
        OpenMatrix4f[] referenceLocals = AuxiliaryPoseMatrices.allocate(2);
        referenceLocals[1].translate(1.0F, 2.0F, 0.0F);
        OpenMatrix4f[] targetLocals = AuxiliaryPoseMatrices.allocate(2);
        targetLocals[1].translate(3.0F, 4.0F, 0.0F);
        OpenMatrix4f[] targetToOrigin = AuxiliaryPoseMatrices.allocate(2);
        OpenMatrix4f.invert(targetLocals[1], targetToOrigin[1]);
        OpenMatrix4f rotation = new OpenMatrix4f().rotateDeg(90.0F, Vec3f.Z_AXIS);
        OpenMatrix4f[] poses = AuxiliaryPoseMatrices.allocate(2);
        OpenMatrix4f.mul(referenceLocals[1], rotation, poses[1]);
        OpenMatrix4f[] targetPoseWorlds = AuxiliaryPoseMatrices.allocate(2);
        OpenMatrix4f[] targetSkinMatrices = AuxiliaryPoseMatrices.allocate(2);

        ModelPoseRetargeter.retarget(poses, referenceLocals, targetLocals, targetToOrigin,
                new int[]{-1, 0}, new int[]{0, 1}, 2,
                targetPoseWorlds, targetSkinMatrices,
                new OpenMatrix4f(), new OpenMatrix4f(), new OpenMatrix4f(),
                new OpenMatrix4f(), new OpenMatrix4f());

        OpenMatrix4f expectedPose = OpenMatrix4f.mul(targetLocals[1], rotation, null);
        OpenMatrix4f expectedSkin = OpenMatrix4f.mul(expectedPose, targetToOrigin[1], null);
        assertMatrixEquals(expectedPose, targetPoseWorlds[1]);
        assertMatrixEquals(expectedSkin, targetSkinMatrices[1]);
        Vec4f pivotAfterRotation = OpenMatrix4f.transform(targetSkinMatrices[1],
                new Vec4f(3.0F, 4.0F, 0.0F, 1.0F), new Vec4f());
        assertEquals(3.0F, pivotAfterRotation.x, 0.00001F);
        assertEquals(4.0F, pivotAfterRotation.y, 0.00001F);
        assertEquals(0.0F, pivotAfterRotation.z, 0.00001F);
    }

    @Test
    void reproducesTheOriginalSkinMatricesWhenBindPivotsAreUnchanged() {
        OpenMatrix4f[] referenceLocals = AuxiliaryPoseMatrices.allocate(2);
        referenceLocals[1].translate(1.0F, 2.0F, 0.0F);
        OpenMatrix4f[] targetLocals = AuxiliaryPoseMatrices.allocate(2);
        targetLocals[1].load(referenceLocals[1]);
        OpenMatrix4f[] targetToOrigin = AuxiliaryPoseMatrices.allocate(2);
        OpenMatrix4f.invert(targetLocals[1], targetToOrigin[1]);
        OpenMatrix4f[] poses = AuxiliaryPoseMatrices.allocate(2);
        OpenMatrix4f rotation = new OpenMatrix4f().rotateDeg(-35.0F, Vec3f.X_AXIS);
        OpenMatrix4f.mul(referenceLocals[1], rotation, poses[1]);
        OpenMatrix4f[] targetPoseWorlds = AuxiliaryPoseMatrices.allocate(2);
        OpenMatrix4f[] targetSkinMatrices = AuxiliaryPoseMatrices.allocate(2);

        ModelPoseRetargeter.retarget(poses, referenceLocals, targetLocals, targetToOrigin,
                new int[]{-1, 0}, new int[]{0, 1}, 2,
                targetPoseWorlds, targetSkinMatrices,
                new OpenMatrix4f(), new OpenMatrix4f(), new OpenMatrix4f(),
                new OpenMatrix4f(), new OpenMatrix4f());

        assertMatrixEquals(OpenMatrix4f.mul(poses[1], targetToOrigin[1], null),
                targetSkinMatrices[1]);
    }

    private static void assertMatrixEquals(OpenMatrix4f expected, OpenMatrix4f actual) {
        assertEquals(expected.m00, actual.m00, 0.00001F);
        assertEquals(expected.m01, actual.m01, 0.00001F);
        assertEquals(expected.m02, actual.m02, 0.00001F);
        assertEquals(expected.m03, actual.m03, 0.00001F);
        assertEquals(expected.m10, actual.m10, 0.00001F);
        assertEquals(expected.m11, actual.m11, 0.00001F);
        assertEquals(expected.m12, actual.m12, 0.00001F);
        assertEquals(expected.m13, actual.m13, 0.00001F);
        assertEquals(expected.m20, actual.m20, 0.00001F);
        assertEquals(expected.m21, actual.m21, 0.00001F);
        assertEquals(expected.m22, actual.m22, 0.00001F);
        assertEquals(expected.m23, actual.m23, 0.00001F);
        assertEquals(expected.m30, actual.m30, 0.00001F);
        assertEquals(expected.m31, actual.m31, 0.00001F);
        assertEquals(expected.m32, actual.m32, 0.00001F);
        assertEquals(expected.m33, actual.m33, 0.00001F);
    }

    private static GeometryDocument magicalRightArmGeometry() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone torso = new GeometryDocument.Bone("Torso");
        torso.pivot(0.0F, 0.65F, 0.0F);
        GeometryDocument.Bone chest = new GeometryDocument.Bone("Chest");
        chest.parentName("Torso");
        chest.pivot(0.0F, 1.15F, 0.0F);
        GeometryDocument.Bone armControl = new GeometryDocument.Bone("Arm");
        armControl.parentName("Chest");
        armControl.pivot(-0.03254F, 1.626655F, 0.092526F);
        GeometryDocument.Bone rightArm = new GeometryDocument.Bone("RightArm");
        rightArm.parentName("Arm");
        // Converted from the authored 05_magical RightArm pivot and bind rotation.
        rightArm.pivot(0.139023F, 1.626655F, 0.092526F);
        rightArm.rotation(0.0F, 0.0F, radians(18.0F));
        rightArm.faces().add(new GeometryDocument.Face(new Vector3f[]{
                new Vector3f(0.065875F, 1.685718F, -0.008724F),
                new Vector3f(0.065875F, 1.685718F, 0.193776F),
                new Vector3f(0.237460F, 1.685718F, 0.193776F),
                new Vector3f(0.237460F, 1.685718F, -0.008724F)
        }, new float[][]{{0.0F, 0.0F}, {0.0F, 1.0F}, {1.0F, 1.0F}, {1.0F, 0.0F}},
                new Vector3f(0.0F, 1.0F, 0.0F)));
        geometry.add(torso);
        geometry.add(chest);
        geometry.add(armControl);
        geometry.add(rightArm);
        geometry.linkHierarchy();
        return geometry;
    }

    private static Armature extendedRotatedMaidArmature() {
        Map<String, Joint> joints = new LinkedHashMap<>();
        Joint root = joint(joints, "Root", HumanoidRig.ROOT,
                new OpenMatrix4f().translate(-0.000005F, 0.000946F, 0.856268F)
                        .rotateDeg(90.0F, Vec3f.X_AXIS));
        Joint rightThigh = joint(joints, "Thigh_R", HumanoidRig.RIGHT_THIGH,
                new OpenMatrix4f().translate(0.125015F, 0.044020F, 0.0F));
        Joint rightLeg = joint(joints, "Leg_R", HumanoidRig.RIGHT_LEG,
                new OpenMatrix4f().translate(0.0F, 0.374726F, 0.0F));
        Joint rightKnee = joint(joints, "Knee_R", 3,
                new OpenMatrix4f().translate(0.0F, 0.371789F, 0.0F));
        Joint leftThigh = joint(joints, "Thigh_L", HumanoidRig.LEFT_THIGH,
                new OpenMatrix4f().translate(-0.125006F, 0.044020F, 0.0F));
        Joint leftLeg = joint(joints, "Leg_L", HumanoidRig.LEFT_LEG,
                new OpenMatrix4f().translate(0.0F, 0.374726F, 0.0F));
        Joint leftKnee = joint(joints, "Knee_L", 6,
                new OpenMatrix4f().translate(0.0F, 0.371789F, 0.0F));
        Joint torso = joint(joints, "Torso", HumanoidRig.TORSO,
                new OpenMatrix4f().translate(0.0F, 0.05F, 0.0F));
        Joint chest = joint(joints, "Chest", HumanoidRig.CHEST,
                new OpenMatrix4f().translate(0.0F, 0.207703F, 0.0F));
        Joint head = joint(joints, "Head", HumanoidRig.HEAD,
                new OpenMatrix4f().translate(0.0F, 0.447225F, 0.0F));
        Joint rightShoulder = joint(joints, "Shoulder_R", HumanoidRig.RIGHT_SHOULDER,
                new OpenMatrix4f().translate(0.011692F, 0.448644F, 0.0F)
                        .rotateDeg(67.0F, Vec3f.Z_AXIS)
                        .rotateDeg(-39.0F, Vec3f.Y_AXIS));
        Joint rightArm = joint(joints, "Arm_R", HumanoidRig.RIGHT_ARM,
                new OpenMatrix4f().translate(0.0F, 0.309528F, 0.0F)
                        .rotateDeg(-58.0F, Vec3f.X_AXIS)
                        .rotateDeg(42.0F, Vec3f.Z_AXIS));
        Joint rightHand = joint(joints, "Hand_R", HumanoidRig.RIGHT_HAND,
                new OpenMatrix4f().translate(0.0F, 0.3F, 0.0F)
                        .rotateDeg(9.4F, Vec3f.Y_AXIS));
        Joint rightTool = joint(joints, "Tool_R", HumanoidRig.RIGHT_TOOL,
                new OpenMatrix4f().translate(0.0F, 0.3F, 0.0F)
                        .rotateDeg(180.0F, Vec3f.X_AXIS));
        Joint rightElbow = joint(joints, "Elbow_R", HumanoidRig.RIGHT_ELBOW,
                new OpenMatrix4f().translate(0.073746F, 0.356449F, -0.013714F));
        Joint leftShoulder = joint(joints, "Shoulder_L", HumanoidRig.LEFT_SHOULDER,
                new OpenMatrix4f().translate(-0.011682F, 0.448644F, 0.0F));
        Joint leftArm = joint(joints, "Arm_L", HumanoidRig.LEFT_ARM,
                new OpenMatrix4f().translate(0.0F, 0.309528F, 0.0F));
        Joint leftHand = joint(joints, "Hand_L", HumanoidRig.LEFT_HAND,
                new OpenMatrix4f().translate(0.0F, 0.3F, 0.0F));
        Joint leftTool = joint(joints, "Tool_L", HumanoidRig.LEFT_TOOL,
                new OpenMatrix4f().translate(0.0F, 0.3F, 0.0F));
        Joint leftElbow = joint(joints, "Elbow_L", HumanoidRig.LEFT_ELBOW,
                new OpenMatrix4f().translate(-0.073755F, 0.356449F, -0.013715F));

        root.addSubJoints(rightThigh, leftThigh, torso);
        rightThigh.addSubJoints(rightLeg, rightKnee);
        leftThigh.addSubJoints(leftLeg, leftKnee);
        torso.addSubJoints(chest);
        chest.addSubJoints(head, rightShoulder, leftShoulder);
        rightShoulder.addSubJoints(rightArm);
        rightArm.addSubJoints(rightHand, rightElbow);
        rightHand.addSubJoints(rightTool);
        leftShoulder.addSubJoints(leftArm);
        leftArm.addSubJoints(leftHand, leftElbow);
        leftHand.addSubJoints(leftTool);
        Joint optional = joint(joints, "Claw_R", HumanoidRig.EPIC_JOINT_COUNT,
                new OpenMatrix4f().translate(0.03F, 0.27F, 0.01F));
        rightHand.addSubJoints(optional);

        Armature armature = new Armature("eftlm_like_extended_humanoid",
                HumanoidRig.EPIC_JOINT_COUNT + 1, root, joints);
        armature.bakeOriginMatrices();
        return armature;
    }

    private static Joint joint(Map<String, Joint> joints, String name, int id,
                               OpenMatrix4f local) {
        Joint joint = new Joint(name, id, local);
        joints.put(name, joint);
        return joint;
    }

    private static Vec4f transform(OpenMatrix4f matrix, Vector3f point) {
        return OpenMatrix4f.transform(matrix,
                new Vec4f(point.x(), point.y(), point.z(), 1.0F), new Vec4f());
    }

    private static void assertPointEquals(Vec4f expected, Vec4f actual) {
        assertEquals(expected.x, actual.x, 0.00001F);
        assertEquals(expected.y, actual.y, 0.00001F);
        assertEquals(expected.z, actual.z, 0.00001F);
        assertEquals(expected.w, actual.w, 0.00001F);
    }

    private static float radians(float degrees) {
        return (float) Math.toRadians(degrees);
    }
}
