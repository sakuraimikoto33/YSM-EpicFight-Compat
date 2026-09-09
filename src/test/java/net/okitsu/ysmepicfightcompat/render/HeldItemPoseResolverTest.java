package net.okitsu.ysmepicfightcompat.render;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.UseAnim;
import net.okitsu.ysmepicfightcompat.mesh.HumanoidRig;
import org.junit.jupiter.api.Test;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.api.utils.math.Vec3f;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeldItemPoseResolverTest {
    @Test
    void followsEpicFightsFixedToolHandsAndRecognizesItsBowProxyRequest() {
        assertTrue(HeldItemPoseResolver.ladderItemUsesRightTool(
                InteractionHand.MAIN_HAND));
        assertFalse(HeldItemPoseResolver.ladderItemUsesRightTool(
                InteractionHand.OFF_HAND));
        assertTrue(HeldItemPoseResolver.usesLadderHandAttachment(
                InteractionHand.MAIN_HAND, true, true, UseAnim.NONE));
        assertTrue(HeldItemPoseResolver.usesLadderHandAttachment(
                InteractionHand.OFF_HAND, true, false, UseAnim.NONE));
        assertTrue(HeldItemPoseResolver.usesLadderHandAttachment(
                InteractionHand.OFF_HAND, false, true, UseAnim.BOW),
                "Epic Fight renders a main-hand bow through its off-hand correction");
        assertFalse(HeldItemPoseResolver.usesLadderHandAttachment(
                InteractionHand.OFF_HAND, false, true, UseAnim.NONE));
    }

    @Test
    void identifiesAnyExactEpicFightAttachmentPoseEntry() {
        OpenMatrix4f[] poses = matrices();

        assertEquals(HumanoidRig.RIGHT_TOOL,
                HeldItemPoseResolver.selectedJoint(
                        poses, poses[HumanoidRig.RIGHT_TOOL]));
        assertEquals(HumanoidRig.LEFT_TOOL,
                HeldItemPoseResolver.selectedJoint(
                        poses, poses[HumanoidRig.LEFT_TOOL]));
        assertEquals(HumanoidRig.RIGHT_HAND,
                HeldItemPoseResolver.selectedJoint(
                        poses, poses[HumanoidRig.RIGHT_HAND]));
        assertEquals(HumanoidRig.CHEST,
                HeldItemPoseResolver.selectedJoint(
                        poses, poses[HumanoidRig.CHEST]));
    }

    @Test
    void rejectsCopiedAndAmbiguousPoseEntries() {
        OpenMatrix4f[] poses = matrices();

        assertEquals(-1, HeldItemPoseResolver.selectedJoint(
                poses, new OpenMatrix4f(poses[HumanoidRig.RIGHT_TOOL])));
        poses[HumanoidRig.LEFT_TOOL] = poses[HumanoidRig.RIGHT_TOOL];
        assertEquals(-1, HeldItemPoseResolver.selectedJoint(
                poses, poses[HumanoidRig.RIGHT_TOOL]));
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
    void mutatesTheEpicFightReceiverWhenComposingAnUnchangedCorrection() {
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

    @Test
    void alignsOrdinarySwordWithTheYsmItemOriginInBothHands() {
        for (int joint : new int[]{HumanoidRig.RIGHT_TOOL, HumanoidRig.LEFT_TOOL}) {
            OpenMatrix4f locator = new OpenMatrix4f().translate(2.0F, 3.0F, 4.0F);
            OpenMatrix4f receiver = epicFightDefaultItemCorrection();

            OpenMatrix4f actual = HeldItemPoseResolver.applyYsmAttachmentItemCorrection(
                    receiver, locator, joint);

            assertSame(receiver, actual, "Epic Fight returns its reusable receiver");
            assertMatrixEquals(new OpenMatrix4f().translate(2.0F, 2.9375F, 3.9F)
                    .rotateDeg(-90.0F, Vec3f.X_AXIS), actual);
            assertMatrixEquals(new OpenMatrix4f().translate(2.0F, 3.0F, 4.0F), locator);
        }
    }

    @Test
    void movesTheGripInTheAnimatedToolFrameAndPreservesItemOverrides() {
        OpenMatrix4f tool = new OpenMatrix4f().translate(2.0F, 3.0F, 4.0F)
                .rotateDeg(90.0F, Vec3f.Z_AXIS).scale(1.5F, 0.75F, 2.0F);
        tool.m10 += 0.12F;
        OpenMatrix4f before = new OpenMatrix4f(tool);
        OpenMatrix4f itemSpecific = new OpenMatrix4f().translate(0.2F, -0.3F, 0.4F)
                .rotateDeg(35.0F, Vec3f.Y_AXIS).scale(0.8F, 1.1F, 1.3F);
        OpenMatrix4f receiver = new OpenMatrix4f(itemSpecific)
                .mulFront(epicFightDefaultItemCorrection());
        OpenMatrix4f expected = new OpenMatrix4f(itemSpecific)
                .mulFront(ysmPostLocatorItemCorrection()).mulFront(tool);

        OpenMatrix4f actual = HeldItemPoseResolver.applyYsmAttachmentItemCorrection(
                receiver, tool, HumanoidRig.RIGHT_TOOL);

        assertSame(receiver, actual);
        assertMatrixEquals(expected, actual);
        assertMatrixEquals(before, tool);
    }

    @Test
    void leavesSheathedAndUnknownAttachmentCorrectionsUnchanged() {
        OpenMatrix4f pose = new OpenMatrix4f().translate(2.0F, 3.0F, 4.0F)
                .rotateDeg(50.0F, Vec3f.Y_AXIS);
        for (int joint : new int[]{HumanoidRig.ROOT, HumanoidRig.CHEST,
                HumanoidRig.RIGHT_HAND, HumanoidRig.LEFT_HAND, -1, 20}) {
            OpenMatrix4f receiver = epicFightDefaultItemCorrection();
            OpenMatrix4f actual = HeldItemPoseResolver.applyYsmAttachmentItemCorrection(
                    receiver, pose, joint);

            assertSame(receiver, actual);
            assertMatrixEquals(epicFightDefaultItemCorrection().mulFront(pose), actual);
        }
    }

    @Test
    void leavesUnownedEpicFightFallbackUntouched() {
        OpenMatrix4f[] poses = matrices();
        OpenMatrix4f tool = poses[HumanoidRig.RIGHT_TOOL];
        OpenMatrix4f receiver = epicFightDefaultItemCorrection();

        OpenMatrix4f actual = HeldItemPoseResolver.resolveCorrection(
                null, poses, receiver, tool);

        assertSame(receiver, actual);
        assertMatrixEquals(epicFightDefaultItemCorrection().mulFront(tool), actual);
    }

    @Test
    void doesNotAccumulateTheAdjustmentAcrossItemDraws() {
        OpenMatrix4f tool = new OpenMatrix4f().translate(2.0F, 3.0F, 4.0F);
        OpenMatrix4f receiver = new OpenMatrix4f();
        OpenMatrix4f expected = ysmPostLocatorItemCorrection().mulFront(tool);
        for (int draw = 0; draw < 3; draw++) {
            receiver.load(epicFightDefaultItemCorrection());
            HeldItemPoseResolver.applyYsmAttachmentItemCorrection(
                    receiver, tool, HumanoidRig.RIGHT_TOOL);
            assertMatrixEquals(expected, receiver);
        }
    }

    private static OpenMatrix4f epicFightDefaultItemCorrection() {
        return new OpenMatrix4f().translate(0.0F, 0.0F, -0.13F)
                .rotateDeg(-90.0F, Vec3f.X_AXIS);
    }

    private static OpenMatrix4f ysmPostLocatorItemCorrection() {
        return new OpenMatrix4f().translate(0.0F, -0.0625F, -0.1F)
                .rotateDeg(-90.0F, Vec3f.X_AXIS);
    }

    @Test
    void scalesOnlyAToolPoseTranslationWithoutMutatingItsSource() {
        OpenMatrix4f source = new OpenMatrix4f()
                .translate(2.0F, 3.0F, 4.0F)
                .rotateDeg(35.0F, Vec3f.Y_AXIS);
        OpenMatrix4f original = new OpenMatrix4f(source);

        OpenMatrix4f adjusted = HeldItemPoseResolver.scaleToolTranslation(
                source, 1.25F);

        assertEquals(original.m00, adjusted.m00, 0.00001F);
        assertEquals(original.m01, adjusted.m01, 0.00001F);
        assertEquals(original.m02, adjusted.m02, 0.00001F);
        assertEquals(original.m10, adjusted.m10, 0.00001F);
        assertEquals(original.m11, adjusted.m11, 0.00001F);
        assertEquals(original.m12, adjusted.m12, 0.00001F);
        assertEquals(original.m20, adjusted.m20, 0.00001F);
        assertEquals(original.m21, adjusted.m21, 0.00001F);
        assertEquals(original.m22, adjusted.m22, 0.00001F);
        assertEquals(original.m30 * 1.25F, adjusted.m30, 0.00001F);
        assertEquals(original.m31 * 1.25F, adjusted.m31, 0.00001F);
        assertEquals(original.m32 * 1.25F, adjusted.m32, 0.00001F);
        assertMatrixEquals(original, source);
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
