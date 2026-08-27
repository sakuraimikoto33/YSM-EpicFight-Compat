package net.okitsu.ysmepicfightcompat.render;

import net.okitsu.ysmepicfightcompat.mesh.HumanoidRig;
import org.junit.jupiter.api.Test;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.api.utils.math.Vec3f;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class HeldItemPoseResolverTest {
    @Test
    void identifiesOnlyTheExactRightAndLeftToolPoseEntries() {
        OpenMatrix4f[] poses = matrices();

        assertEquals(HumanoidRig.RIGHT_TOOL,
                HeldItemPoseResolver.selectedToolJoint(
                        poses, poses[HumanoidRig.RIGHT_TOOL]));
        assertEquals(HumanoidRig.LEFT_TOOL,
                HeldItemPoseResolver.selectedToolJoint(
                        poses, poses[HumanoidRig.LEFT_TOOL]));
    }

    @Test
    void leavesHandsBackAttachmentsAndCopiedMatricesUnderEpicFightOwnership() {
        OpenMatrix4f[] poses = matrices();

        assertEquals(-1, HeldItemPoseResolver.selectedToolJoint(
                poses, poses[HumanoidRig.RIGHT_HAND]));
        assertEquals(-1, HeldItemPoseResolver.selectedToolJoint(
                poses, poses[HumanoidRig.CHEST]));
        assertEquals(-1, HeldItemPoseResolver.selectedToolJoint(
                poses, new OpenMatrix4f(poses[HumanoidRig.RIGHT_TOOL])));
    }

    @Test
    void keepsTheItemSpecificCorrectionForTheFallbackToolPose() {
        OpenMatrix4f toolPose = new OpenMatrix4f()
                .translate(2.0F, 3.0F, 4.0F)
                .rotateDeg(90.0F, Vec3f.Z_AXIS);
        OpenMatrix4f itemCorrection = new OpenMatrix4f()
                .translate(5.0F, 7.0F, 11.0F)
                .rotateDeg(35.0F, Vec3f.X_AXIS);
        OpenMatrix4f expected = new OpenMatrix4f(itemCorrection).mulFront(toolPose);

        OpenMatrix4f actual = HeldItemPoseResolver.applyItemCorrection(
                new OpenMatrix4f(itemCorrection), toolPose);

        assertMatrixEquals(expected, actual);
    }

    @Test
    void keepsThePostLocatorItemCorrectionForACompleteAuthoredLocator() {
        OpenMatrix4f locator = new OpenMatrix4f()
                .translate(2.0F, 3.0F, 4.0F)
                .rotateDeg(90.0F, Vec3f.Z_AXIS);
        OpenMatrix4f itemCorrection = new OpenMatrix4f()
                .translate(5.0F, 7.0F, 11.0F)
                .rotateDeg(-90.0F, Vec3f.X_AXIS);

        OpenMatrix4f expected = new OpenMatrix4f(itemCorrection).mulFront(locator);
        OpenMatrix4f receiver = new OpenMatrix4f(itemCorrection);
        OpenMatrix4f actual = HeldItemPoseResolver.applyItemCorrection(
                receiver, locator);

        assertSame(receiver, actual);
        assertMatrixEquals(expected, actual);
        assertMatrixEquals(expected, receiver);
    }

    private static OpenMatrix4f[] matrices() {
        OpenMatrix4f[] result = new OpenMatrix4f[HumanoidRig.EPIC_JOINT_COUNT];
        for (int index = 0; index < result.length; index++) {
            result[index] = new OpenMatrix4f().translate(index, -index, index * 0.5F);
        }
        return result;
    }

    private static void assertMatrixEquals(OpenMatrix4f expected, OpenMatrix4f actual) {
        assertEquals(expected.m00, actual.m00, 0.00001F);
        assertEquals(expected.m01, actual.m01, 0.00001F);
        assertEquals(expected.m02, actual.m02, 0.00001F);
        assertEquals(expected.m10, actual.m10, 0.00001F);
        assertEquals(expected.m11, actual.m11, 0.00001F);
        assertEquals(expected.m12, actual.m12, 0.00001F);
        assertEquals(expected.m20, actual.m20, 0.00001F);
        assertEquals(expected.m21, actual.m21, 0.00001F);
        assertEquals(expected.m22, actual.m22, 0.00001F);
        assertEquals(expected.m30, actual.m30, 0.00001F);
        assertEquals(expected.m31, actual.m31, 0.00001F);
        assertEquals(expected.m32, actual.m32, 0.00001F);
    }
}
