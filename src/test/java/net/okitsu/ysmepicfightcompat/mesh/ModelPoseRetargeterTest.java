package net.okitsu.ysmepicfightcompat.mesh;

import org.junit.jupiter.api.Test;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.api.utils.math.Vec3f;
import yesman.epicfight.api.utils.math.Vec4f;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelPoseRetargeterTest {
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
}
