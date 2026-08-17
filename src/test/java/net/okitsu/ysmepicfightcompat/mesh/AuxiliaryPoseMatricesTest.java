package net.okitsu.ysmepicfightcompat.mesh;

import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import org.junit.jupiter.api.Test;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuxiliaryPoseMatricesTest {
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

        AuxiliaryPoseMatrices.compose(poses, toOrigin, layout, output, null);

        for (int index = 0; index < HumanoidRig.EPIC_JOINT_COUNT; index++) {
            assertMatrixEquals(new OpenMatrix4f(poses[index]).mulBack(toOrigin[index]),
                    output[index]);
        }
        AuxiliaryBoneLayout.Entry earEntry = layout.entries().get(0);
        assertEquals(HumanoidRig.HEAD, earEntry.anchorJoint());
        assertMatrixEquals(output[HumanoidRig.HEAD], output[earEntry.poseIndex()]);
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
