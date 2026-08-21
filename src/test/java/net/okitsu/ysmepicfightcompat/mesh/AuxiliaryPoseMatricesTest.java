package net.okitsu.ysmepicfightcompat.mesh;

import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import org.junit.jupiter.api.Test;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.api.utils.math.Vec3f;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuxiliaryPoseMatricesTest {
    @Test
    void appliesWholeBodyRouletteDeltaOutsideTheEpicHeadPose() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone head = new GeometryDocument.Bone("head");
        geometry.add(head);
        geometry.linkHierarchy();
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry);
        OpenMatrix4f[] poses = AuxiliaryPoseMatrices.allocate(HumanoidRig.EPIC_JOINT_COUNT);
        OpenMatrix4f[] toOrigin = AuxiliaryPoseMatrices.allocate(HumanoidRig.EPIC_JOINT_COUNT);
        poses[HumanoidRig.HEAD].translate(0.5F, 1.5F, -0.25F)
                .rotateDeg(30.0F, Vec3f.Y_AXIS);
        OpenMatrix4f[] output = AuxiliaryPoseMatrices.allocate(layout.totalPoseCount());
        OpenMatrix4f[] modelDeltas = AuxiliaryPoseMatrices.allocate(1);
        modelDeltas[0].translate(0.0F, 0.75F, 0.0F)
                .rotateDeg(90.0F, Vec3f.X_AXIS)
                .translate(0.0F, -0.75F, 0.0F);

        AuxiliaryPoseMatrices.compose(poses, toOrigin, layout, output,
                null, modelDeltas);

        OpenMatrix4f epicHead = new OpenMatrix4f(poses[HumanoidRig.HEAD])
                .mulBack(toOrigin[HumanoidRig.HEAD]);
        assertMatrixEquals(epicHead, output[HumanoidRig.HEAD]);
        assertMatrixEquals(new OpenMatrix4f(epicHead).mulFront(modelDeltas[0]),
                output[layout.entries().get(0).poseIndex()]);
    }

    @Test
    void appliesHairParallelDeltaInsideTheEpicHeadPose() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone head = new GeometryDocument.Bone("head");
        GeometryDocument.Bone hair = new GeometryDocument.Bone("hair");
        hair.parentName("head");
        geometry.add(head);
        geometry.add(hair);
        geometry.linkHierarchy();
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry);
        OpenMatrix4f[] poses = AuxiliaryPoseMatrices.allocate(HumanoidRig.EPIC_JOINT_COUNT);
        OpenMatrix4f[] toOrigin = AuxiliaryPoseMatrices.allocate(HumanoidRig.EPIC_JOINT_COUNT);
        poses[HumanoidRig.HEAD].rotateDeg(25.0F, Vec3f.X_AXIS);
        OpenMatrix4f[] output = AuxiliaryPoseMatrices.allocate(layout.totalPoseCount());
        OpenMatrix4f[] modelDeltas = AuxiliaryPoseMatrices.allocate(layout.entries().size());
        AuxiliaryBoneLayout.Entry hairEntry = layout.entries().get(1);
        modelDeltas[hairEntry.auxiliaryIndex()].translate(0.0F, 1.0F, 0.0F)
                .rotateDeg(-15.0F, Vec3f.Z_AXIS)
                .translate(0.0F, -1.0F, 0.0F);

        AuxiliaryPoseMatrices.compose(poses, toOrigin, layout, output,
                modelDeltas, null);

        OpenMatrix4f epicHead = new OpenMatrix4f(poses[HumanoidRig.HEAD]);
        assertMatrixEquals(new OpenMatrix4f(epicHead)
                        .mulBack(modelDeltas[hairEntry.auxiliaryIndex()]),
                output[hairEntry.poseIndex()]);
    }

    @Test
    void keepsParallelTailMotionInsideAWholeBodyRoulettePose() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone body = new GeometryDocument.Bone("body");
        GeometryDocument.Bone tail = new GeometryDocument.Bone("tail");
        tail.parentName("body");
        geometry.add(body);
        geometry.add(tail);
        geometry.linkHierarchy();
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry);
        OpenMatrix4f[] poses = AuxiliaryPoseMatrices.allocate(HumanoidRig.EPIC_JOINT_COUNT);
        OpenMatrix4f[] toOrigin = AuxiliaryPoseMatrices.allocate(HumanoidRig.EPIC_JOINT_COUNT);
        poses[HumanoidRig.TORSO].rotateDeg(20.0F, Vec3f.Y_AXIS);
        OpenMatrix4f[] output = AuxiliaryPoseMatrices.allocate(layout.totalPoseCount());
        OpenMatrix4f[] parallelDeltas = AuxiliaryPoseMatrices.allocate(layout.entries().size());
        OpenMatrix4f[] rouletteDeltas = AuxiliaryPoseMatrices.allocate(layout.entries().size());
        AuxiliaryBoneLayout.Entry tailEntry = layout.entries().get(1);
        parallelDeltas[tailEntry.auxiliaryIndex()].translate(0.0F, 0.5F, 0.0F)
                .rotateDeg(15.0F, Vec3f.Z_AXIS)
                .translate(0.0F, -0.5F, 0.0F);
        rouletteDeltas[tailEntry.auxiliaryIndex()].translate(0.0F, 1.0F, 0.0F)
                .rotateDeg(70.0F, Vec3f.X_AXIS)
                .translate(0.0F, -1.0F, 0.0F);

        AuxiliaryPoseMatrices.compose(poses, toOrigin, layout, output,
                parallelDeltas, rouletteDeltas);

        OpenMatrix4f expected = new OpenMatrix4f(poses[HumanoidRig.TORSO])
                .mulBack(parallelDeltas[tailEntry.auxiliaryIndex()])
                .mulFront(rouletteDeltas[tailEntry.auxiliaryIndex()]);
        assertMatrixEquals(expected, output[tailEntry.poseIndex()]);
    }

    @Test
    void preservesEpicSkinMatricesAndCopiesTheAnchorForAuxiliaryBones() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone head = new GeometryDocument.Bone("head");
        GeometryDocument.Bone ear = new GeometryDocument.Bone("ear");
        ear.parentName("head");
        geometry.add(head);
        geometry.add(ear);
        geometry.linkHierarchy();
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry);
        OpenMatrix4f[] poses = translatedMatrices(HumanoidRig.EPIC_JOINT_COUNT, 2.0F);
        OpenMatrix4f[] toOrigin = translatedMatrices(HumanoidRig.EPIC_JOINT_COUNT, 3.0F);
        OpenMatrix4f[] output = AuxiliaryPoseMatrices.allocate(layout.totalPoseCount());

        AuxiliaryPoseMatrices.compose(poses, toOrigin, layout, output, null, null);

        for (int index = 0; index < HumanoidRig.EPIC_JOINT_COUNT; index++) {
            assertMatrixEquals(new OpenMatrix4f(poses[index]).mulBack(toOrigin[index]),
                    output[index]);
        }
        AuxiliaryBoneLayout.Entry headEntry = layout.entries().get(0);
        assertEquals(HumanoidRig.HEAD, headEntry.anchorJoint());
        assertMatrixEquals(output[HumanoidRig.HEAD], output[headEntry.poseIndex()]);
        AuxiliaryBoneLayout.Entry earEntry = layout.entries().get(1);
        assertEquals(HumanoidRig.HEAD, earEntry.anchorJoint());
        assertMatrixEquals(output[HumanoidRig.HEAD], output[earEntry.poseIndex()]);
    }

    @Test
    void usesRetargetedAnchorsOnlyForPrivateYsmPoses() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone rightArm = new GeometryDocument.Bone("rightArm");
        geometry.add(rightArm);
        geometry.linkHierarchy();
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry);
        OpenMatrix4f[] poses = translatedMatrices(HumanoidRig.EPIC_JOINT_COUNT, 1.0F);
        OpenMatrix4f[] toOrigin = AuxiliaryPoseMatrices.allocate(HumanoidRig.EPIC_JOINT_COUNT);
        OpenMatrix4f[] output = AuxiliaryPoseMatrices.allocate(layout.totalPoseCount());
        OpenMatrix4f[] retargeted = AuxiliaryPoseMatrices.allocate(HumanoidRig.EPIC_JOINT_COUNT);
        retargeted[HumanoidRig.RIGHT_ARM].translate(40.0F, 50.0F, 60.0F);

        AuxiliaryPoseMatrices.compose(poses, toOrigin, layout, output,
                null, null, retargeted);

        AuxiliaryBoneLayout.Entry armEntry = layout.entries().get(0);
        assertMatrixEquals(poses[HumanoidRig.RIGHT_ARM], output[HumanoidRig.RIGHT_ARM]);
        assertMatrixEquals(retargeted[HumanoidRig.RIGHT_ARM], output[armEntry.poseIndex()]);
    }

    private static OpenMatrix4f[] translatedMatrices(int count, float multiplier) {
        OpenMatrix4f[] result = AuxiliaryPoseMatrices.allocate(count);
        for (int index = 0; index < count; index++) {
            result[index].translate(index * multiplier, multiplier, -multiplier);
        }
        return result;
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
}
