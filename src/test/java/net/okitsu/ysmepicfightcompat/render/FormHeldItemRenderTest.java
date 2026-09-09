package net.okitsu.ysmepicfightcompat.render;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.okitsu.ysmepicfightcompat.mesh.HumanoidRig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FormHeldItemRenderTest {
    @Test
    void visibleFormSupersedesOnlyTheInactivePrimaryLocatorsSuppression() {
        publish(poses(), HumanoidArm.RIGHT, 1);
        assertTrue(RenderFrameContext.hasVisibleFormHeldItem(null, InteractionHand.MAIN_HAND));
        assertFalse(RenderFrameContext.shouldSuppressHeldItem(false, false, true, true, false));
        assertTrue(RenderFrameContext.shouldSuppressHeldItem(false, true, true, true, false));
        assertTrue(RenderFrameContext.shouldSuppressHeldItem(false, false, false, false, true));
        assertTrue(RenderFrameContext.shouldSuppressHeldItem(false, false, true, false, false));
        assertFalse(RenderFrameContext.shouldSuppressHeldItem(true, false, true, true, true));
        assertTrue(RenderFrameContext.shouldSuppressHeldItem(true, true, true, true, false));
    }

    @AfterEach
    void clear() {
        RenderFrameContext.clear();
    }

    @Test
    void logicalHandsHaveIndependentPerItemToolsIncludingTheBowOffHandProxy() {
        Armature armature = armature();
        OpenMatrix4f[] original = poses();
        publish(original, HumanoidArm.RIGHT, 1);

        try (RenderFrameContext.FormHeldItemDraw main = RenderFrameContext.openFormHeldItem(
                null, InteractionHand.MAIN_HAND, armature, original)) {
            assertNotNull(main);
            assertTrue(RenderFrameContext.formHeldItemActive(null));
            assertToolPair(main.poses(), 2, 3, 4);
            assertNotSame(main.poses()[HumanoidRig.RIGHT_TOOL], main.poses()[HumanoidRig.LEFT_TOOL]);
            assertTrue(AttachmentArmatureScope.isDisplayedPoseArray(armature, main.poses()));
            assertEquals(2, AttachmentArmatureScope.resolveJointPose(armature,
                    armature.searchJointById(HumanoidRig.LEFT_TOOL), new OpenMatrix4f()).m30);
            assertEquals(0, original[HumanoidRig.RIGHT_TOOL].m30);
            try (RenderFrameContext.FormHeldItemDraw off = RenderFrameContext.openFormHeldItem(
                    null, InteractionHand.OFF_HAND, armature, original)) {
                assertNotNull(off);
                assertToolPair(off.poses(), -2, 5, 6);
                assertToolPair(main.poses(), 2, 3, 4);
            }
            assertEquals(2, AttachmentArmatureScope.resolveJointPose(armature,
                    armature.searchJointById(HumanoidRig.LEFT_TOOL), new OpenMatrix4f()).m30);
        }
        assertFalse(RenderFrameContext.formHeldItemActive(null));
        assertFalse(AttachmentArmatureScope.suppressPoseWrite(armature));
        assertEquals(0, original[HumanoidRig.LEFT_TOOL].m30);
    }

    @Test
    void dominantArmMappingOccursOnceRatherThanSwappingEpicToolSlots() {
        Armature armature = armature();
        OpenMatrix4f[] original = poses();
        publish(original, HumanoidArm.LEFT, 1);
        try (RenderFrameContext.FormHeldItemDraw main = RenderFrameContext.openFormHeldItem(
                null, InteractionHand.MAIN_HAND, armature, original)) {
            assertNotNull(main);
            assertToolPair(main.poses(), -2, 5, 6);
        }
        try (RenderFrameContext.FormHeldItemDraw off = RenderFrameContext.openFormHeldItem(
                null, InteractionHand.OFF_HAND, armature, original)) {
            assertNotNull(off);
            assertToolPair(off.poses(), 2, 3, 4);
        }
    }

    @Test
    void meshLocalTranslationCompensationIsPublishedOnceWithoutScalingTheItemBasis() {
        Armature armature = armature();
        OpenMatrix4f[] original = poses();
        publish(original, HumanoidArm.RIGHT, 0.75F);
        try (RenderFrameContext.FormHeldItemDraw main = RenderFrameContext.openFormHeldItem(
                null, InteractionHand.MAIN_HAND, armature, original)) {
            assertNotNull(main);
            assertToolPair(main.poses(), 1.5F, 2.25F, 3);
            assertEquals(1, main.poses()[HumanoidRig.RIGHT_TOOL].m00);
            assertTrue(AttachmentArmatureScope.isDisplayedPoseArray(armature, main.poses()));
            OpenMatrix4f[] reread = AttachmentArmatureScope.resolvePoseMatrices(
                    armature, original, false);
            assertToolPair(reread, 1.5F, 2.25F, 3);
            assertTrue(AttachmentArmatureScope.isDisplayedPoseArray(armature, reread));
        }
    }

    @Test
    void snapshotsAreDefensiveAndDoNotLeakIntoNestedEntityOrPreviewFrames() {
        Armature armature = armature();
        OpenMatrix4f[] original = poses();
        publish(original, HumanoidArm.RIGHT, 1);
        try (RenderFrameContext.FormHeldItemDraw main = RenderFrameContext.openFormHeldItem(
                null, InteractionHand.MAIN_HAND, armature, original)) {
            assertNotNull(main);
            main.poses()[HumanoidRig.RIGHT_TOOL].m30 = 99;
            RenderFrameContext.Frame inner = RenderFrameContext.pushThirdPerson(null);
            assertFalse(RenderFrameContext.formHeldItemActive(null));
            assertNull(RenderFrameContext.openFormHeldItem(
                    null, InteractionHand.MAIN_HAND, armature, original));
            RenderFrameContext.pop(inner);
            assertTrue(RenderFrameContext.formHeldItemActive(null));
        }
        try (RenderFrameContext.FormHeldItemDraw again = RenderFrameContext.openFormHeldItem(
                null, InteractionHand.MAIN_HAND, armature, original)) {
            assertNotNull(again);
            assertToolPair(again.poses(), 2, 3, 4);
        }
    }

    @Test
    void unchangedNonToolMatricesRemainCopiesAndGameplayInputIsNeverWritten() {
        Armature armature = armature();
        OpenMatrix4f[] original = poses();
        original[HumanoidRig.CHEST].translate(7, 8, 9);
        publish(original, HumanoidArm.RIGHT, 1);
        try (RenderFrameContext.FormHeldItemDraw item = RenderFrameContext.openFormHeldItem(
                null, InteractionHand.MAIN_HAND, armature, original)) {
            assertNotNull(item);
            assertEquals(7, item.poses()[HumanoidRig.CHEST].m30);
            assertNotSame(original[HumanoidRig.CHEST], item.poses()[HumanoidRig.CHEST]);
            item.poses()[HumanoidRig.CHEST].m30 = 99;
            assertEquals(7, original[HumanoidRig.CHEST].m30);
        }
    }

    @Test
    void hiddenFormHandIsLogicalAndDoesNotResurrectAtTheOrdinaryGrip() {
        OpenMatrix4f[] original = poses();
        publish(original, HumanoidArm.LEFT, 1);
        RenderFrameContext.publishFormHeldItemPoints(null, null, HumanoidArm.LEFT,
                null, null, true, false, 1);
        assertFalse(RenderFrameContext.formHidesHeldItem(null, InteractionHand.MAIN_HAND));
        assertTrue(RenderFrameContext.formHidesHeldItem(null, InteractionHand.OFF_HAND));
        assertNull(RenderFrameContext.openFormHeldItem(
                null, InteractionHand.OFF_HAND, armature(), original));
    }

    @Test
    void republicationClearsOldFormAttachmentsWhenTheFormOrMeshChanges() {
        OpenMatrix4f[] original = poses();
        publish(original, HumanoidArm.RIGHT, 1);
        publishBase(original, false);
        assertNull(RenderFrameContext.openFormHeldItem(
                null, InteractionHand.MAIN_HAND, armature(), original));
        assertFalse(RenderFrameContext.formHidesHeldItem(null, InteractionHand.MAIN_HAND));
    }

    @Test
    void firstPersonAndStowedMovementKeepFormAttachmentsDisabled() {
        OpenMatrix4f[] original = poses();
        RenderFrameContext.pushFirstPerson(null, Map.of(), true);
        publishBase(original, false);
        RenderFrameContext.publishFormHeldItemPoints(null, null, HumanoidArm.RIGHT,
                new OpenMatrix4f(), new OpenMatrix4f(), true, true, 1);
        assertNull(RenderFrameContext.openFormHeldItem(
                null, InteractionHand.MAIN_HAND, armature(), original));
        RenderFrameContext.clear();
        publish(original, HumanoidArm.RIGHT, 1);
        publishBase(original, true);
        RenderFrameContext.publishFormHeldItemPoints(null, null, HumanoidArm.RIGHT,
                new OpenMatrix4f(), new OpenMatrix4f(), true, true, 1);
        assertNull(RenderFrameContext.openFormHeldItem(
                null, InteractionHand.MAIN_HAND, armature(), original));
        assertFalse(RenderFrameContext.formHidesHeldItem(null, InteractionHand.MAIN_HAND));
    }

    @Test
    void stowedRunningFrameRestoresBothFormAttachmentsWhenMovementEnds() {
        Armature armature = armature();
        OpenMatrix4f[] original = poses();
        publish(original, HumanoidArm.RIGHT, 1);
        // YSM Epic ParCool running and natural ladder poses publish the same policy.
        publishBase(original, true);
        RenderFrameContext.publishFormHeldItemPoints(null, null, HumanoidArm.RIGHT,
                new OpenMatrix4f().translate(2, 3, 4),
                new OpenMatrix4f().translate(-2, 5, 6), true, true, 1);

        for (InteractionHand hand : InteractionHand.values()) {
            assertNull(RenderFrameContext.openFormHeldItem(null, hand, armature, original));
            assertFalse(RenderFrameContext.hasVisibleFormHeldItem(null, hand));
            assertFalse(RenderFrameContext.formHidesHeldItem(null, hand));
        }
        assertFalse(AttachmentArmatureScope.suppressPoseWrite(armature));

        // Reusing the same body frame must release the policy as soon as running ends.
        publishBase(original, false);
        RenderFrameContext.publishFormHeldItemPoints(null, null, HumanoidArm.RIGHT,
                new OpenMatrix4f().translate(2, 3, 4),
                new OpenMatrix4f().translate(-2, 5, 6), false, false, 1);
        for (InteractionHand hand : InteractionHand.values()) {
            assertTrue(RenderFrameContext.hasVisibleFormHeldItem(null, hand));
            try (RenderFrameContext.FormHeldItemDraw item = RenderFrameContext.openFormHeldItem(
                    null, hand, armature, original)) {
                assertNotNull(item);
                if (hand == InteractionHand.MAIN_HAND) {
                    assertToolPair(item.poses(), 2, 3, 4);
                } else {
                    assertToolPair(item.poses(), -2, 5, 6);
                }
            }
        }
        assertFalse(AttachmentArmatureScope.suppressPoseWrite(armature));
    }

    @Test
    void unrelatedPoseArraysAndMalformedArmaturesDoNotOpenFormScopes() {
        OpenMatrix4f[] original = poses();
        publish(original, HumanoidArm.RIGHT, 1);
        assertNull(RenderFrameContext.openFormHeldItem(
                null, InteractionHand.MAIN_HAND, armature(), poses()));
        assertNull(RenderFrameContext.openFormHeldItem(
                null, InteractionHand.MAIN_HAND, null, original));
        assertFalse(RenderFrameContext.formHeldItemActive(null));
    }

    private static void publish(OpenMatrix4f[] original, HumanoidArm mainArm, float scale) {
        RenderFrameContext.pushThirdPerson(null, null, true);
        publishBase(original, false);
        OpenMatrix4f right = new OpenMatrix4f().translate(2, 3, 4);
        OpenMatrix4f left = new OpenMatrix4f().translate(-2, 5, 6);
        RenderFrameContext.publishFormHeldItemPoints(null, null, mainArm,
                right, left, false, false, scale);
        right.m30 = left.m30 = 999;
    }

    private static void publishBase(OpenMatrix4f[] original, boolean suppressHeldItemPose) {
        RenderFrameContext.publishHeldItemPoints(null, null, original, null, null,
                null, null, null, null, false, false, false, suppressHeldItemPose, Set.of());
    }

    private static Armature armature() {
        String[] names = {"Root", "Thigh_R", "Leg_R", "Knee_R", "Thigh_L", "Leg_L", "Knee_L",
                "Torso", "Chest", "Head", "Shoulder_R", "Arm_R", "Hand_R", "Tool_R", "Elbow_R",
                "Shoulder_L", "Arm_L", "Hand_L", "Tool_L", "Elbow_L"};
        Map<String, Joint> joints = new LinkedHashMap<>();
        Joint root = new Joint(names[0], 0, new OpenMatrix4f());
        joints.put(names[0], root);
        for (int index = 1; index < names.length; index++) {
            Joint joint = new Joint(names[index], index, new OpenMatrix4f());
            root.addSubJoints(joint);
            joints.put(names[index], joint);
        }
        Armature result = new Armature("form-item-test", names.length, root, joints);
        root.initOriginTransform(new OpenMatrix4f());
        return result;
    }

    private static OpenMatrix4f[] poses() {
        OpenMatrix4f[] result = new OpenMatrix4f[20];
        java.util.Arrays.setAll(result, index -> new OpenMatrix4f());
        return result;
    }

    private static void assertToolPair(OpenMatrix4f[] poses, float x, float y, float z) {
        for (int joint : new int[]{HumanoidRig.RIGHT_TOOL, HumanoidRig.LEFT_TOOL}) {
            assertEquals(x, poses[joint].m30, 0.00001F);
            assertEquals(y, poses[joint].m31, 0.00001F);
            assertEquals(z, poses[joint].m32, 0.00001F);
        }
    }
}
