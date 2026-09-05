package net.okitsu.ysmepicfightcompat.render;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.okitsu.ysmepicfightcompat.mesh.HumanoidRig;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderFrameContextTest {
    @AfterEach
    void clearContext() {
        RenderFrameContext.clear();
    }

    @Test
    void restoresTheOuterFrameAfterANestedRender() {
        RenderFrameContext.Frame outer = RenderFrameContext.pushThirdPerson(null, 42.5F);
        RenderFrameContext.Frame inner = RenderFrameContext.pushFirstPerson(
                null, Map.of("rightArm", true), false, -15.0F);

        assertSame(inner, RenderFrameContext.current());
        assertEquals(-15.0F, inner.epicModelYaw());
        RenderFrameContext.pop(inner);
        assertSame(outer, RenderFrameContext.current());
        assertEquals(42.5F, outer.epicModelYaw());
        RenderFrameContext.pop(outer);
        assertNull(RenderFrameContext.current());
    }

    @Test
    void copiesFirstPersonVisibilityAndDoesNotClearAnInnerFrameOutOfOrder() {
        Map<String, Boolean> visibility = new HashMap<>();
        visibility.put("leftArm", true);
        RenderFrameContext.Frame outer = RenderFrameContext.pushFirstPerson(
                null, visibility, false);
        visibility.put("leftArm", false);
        RenderFrameContext.Frame inner = RenderFrameContext.pushThirdPerson(null);

        RenderFrameContext.pop(outer);

        assertSame(inner, RenderFrameContext.current());
        assertEquals(Map.of("leftArm", true), outer.visibleParts());
        RenderFrameContext.pop(inner);
        assertNull(RenderFrameContext.current());
    }

    @Test
    void rejectsANonFiniteEpicModelYaw() {
        RenderFrameContext.Frame frame = RenderFrameContext.pushThirdPerson(
                null, Float.NaN);

        assertNull(frame.epicModelYaw());
    }

    @Test
    void ordinaryBowMainhandSuppressionUsesTheOffArmPhysicalSide() {
        assertFalse(RenderFrameContext.physicalRightForLogicalHand(
                InteractionHand.MAIN_HAND, HumanoidArm.RIGHT, true));
        assertTrue(RenderFrameContext.physicalRightForLogicalHand(
                InteractionHand.MAIN_HAND, HumanoidArm.LEFT, true));
        assertTrue(RenderFrameContext.physicalRightForLogicalHand(
                InteractionHand.MAIN_HAND, HumanoidArm.RIGHT, false));
        assertFalse(RenderFrameContext.physicalRightForLogicalHand(
                InteractionHand.MAIN_HAND, HumanoidArm.LEFT, false));
    }

    @Test
    void naturalLadderHidesEpicItemOnlyWhenTheModelReplacesIt() {
        assertTrue(RenderFrameContext.shouldSuppressHeldItem(
                true, true, true));
        assertTrue(RenderFrameContext.shouldSuppressHeldItem(
                true, true, false));
        assertFalse(RenderFrameContext.shouldSuppressHeldItem(
                true, false, true));
        assertFalse(RenderFrameContext.shouldSuppressHeldItem(
                true, false, false));
        assertTrue(RenderFrameContext.shouldSuppressHeldItem(
                false, true, false));
        assertTrue(RenderFrameContext.shouldSuppressHeldItem(
                false, false, true));
        assertFalse(RenderFrameContext.shouldSuppressHeldItem(
                false, false, false));
    }

    @Test
    void defensivelyPublishesTheElytraLocatorForTheExactPoseArray() {
        RenderFrameContext.pushThirdPerson(null);
        OpenMatrix4f[] inputPoses = {new OpenMatrix4f()};
        OpenMatrix4f locator = new OpenMatrix4f().translate(2.0F, 3.0F, 4.0F);
        RenderFrameContext.publishHeldItemPoints(
                null, null, inputPoses, null, null, null, null,
                locator, null, false, false, false, false, Set.of());
        locator.m30 = 99.0F;

        OpenMatrix4f published = RenderFrameContext.elytraLocatorPose(
                null, null, inputPoses);

        assertNotNull(published);
        assertEquals(2.0F, published.m30, 0.0001F);
        published.m31 = 99.0F;
        assertEquals(3.0F, RenderFrameContext.elytraLocatorPose(
                null, null, inputPoses).m31, 0.0001F);
        assertNull(RenderFrameContext.elytraLocatorPose(
                null, null, new OpenMatrix4f[]{new OpenMatrix4f()}));
    }

    @Test
    void refreshesPublishedAttachmentsWhenFramesReuseTheSamePoseArrays() {
        OpenMatrix4f[] inputPoses = new OpenMatrix4f[HumanoidRig.EPIC_JOINT_COUNT];
        OpenMatrix4f[] projectedPoses = new OpenMatrix4f[inputPoses.length];
        for (int joint = 0; joint < inputPoses.length; joint++) {
            inputPoses[joint] = new OpenMatrix4f();
            projectedPoses[joint] = new OpenMatrix4f();
        }
        Vector3f fist = new Vector3f();
        OpenMatrix4f previousPublished = null;
        float previousY = Float.NaN;
        for (float bob : new float[]{0.0F, 0.15F, 0.3F, 0.05F, -0.2F, 0.0F}) {
            RenderFrameContext.Frame frame = RenderFrameContext.pushThirdPerson(null);
            assertNull(RenderFrameContext.displayedAttachmentPose(
                    null, null, inputPoses, HumanoidRig.RIGHT_TOOL));
            inputPoses[HumanoidRig.RIGHT_TOOL].m31 = 1.0F + bob;
            projectedPoses[HumanoidRig.RIGHT_TOOL].m31 = 0.8F + bob;
            fist.set(-0.25F, 0.8F + bob, 0.03F);
            RenderFrameContext.publishHeldItemPoints(
                    null, null, inputPoses, fist, null, null, null,
                    null, projectedPoses, false, false, false, false, Set.of());

            // The composer reuses its output on the next draw. Published data must
            // be a snapshot of this draw, and not a reference to reusable storage.
            projectedPoses[HumanoidRig.RIGHT_TOOL].m31 = 99.0F;
            fist.y = 99.0F;
            OpenMatrix4f published = RenderFrameContext.displayedAttachmentPose(
                    null, null, inputPoses, HumanoidRig.RIGHT_TOOL);
            assertNotNull(published);
            assertEquals(0.8F + bob, published.m31, 0.00001F);
            Vector3f publishedFist = RenderFrameContext.displayedFist(
                    null, null, inputPoses, HumanoidRig.RIGHT_TOOL);
            assertNotNull(publishedFist);
            assertEquals(0.8F + bob, publishedFist.y(), 0.00001F);
            if (previousPublished != null) {
                assertEquals(previousY, previousPublished.m31, 0.00001F);
            }
            previousPublished = published;
            previousY = published.m31;

            // An unbound test frame must not replace the entire layer pose array.
            // Per-joint publication above still exercises frame identity and copies.
            assertSame(inputPoses, RenderFrameContext.resolvePatchedLayerPoses(inputPoses));
            RenderFrameContext.pop(frame);
            assertNull(RenderFrameContext.current());
            assertNull(RenderFrameContext.displayedAttachmentPose(
                    null, null, inputPoses, HumanoidRig.RIGHT_TOOL));
        }
    }

    @Test
    void skinningArrayCopiesRetainTheOriginalLayersAttachmentProvenance() {
        for (int count : new int[]{HumanoidRig.EPIC_JOINT_COUNT,
                HumanoidRig.EPIC_JOINT_COUNT + 4}) {
            OpenMatrix4f[] layerPoses = poses(count);
            // The skinning boundary copies the array, not its matrix elements.
            // The body publishes the copy while patched layers retain the original.
            OpenMatrix4f[] bodyPoses = Arrays.copyOf(layerPoses, count);
            RenderFrameContext.Frame frame = RenderFrameContext.pushThirdPerson(null);
            publishAllAttachments(bodyPoses);

            assertTrue(RenderFrameContext.sameBodyPoseSource(bodyPoses, layerPoses));
            assertTrue(RenderFrameContext.sameBodyPoseSource(layerPoses, bodyPoses));
            assertAllPublishedAttachments(bodyPoses);
            assertAllPublishedAttachments(layerPoses);
            for (int joint = 0; joint < count; joint++) {
                assertSame(layerPoses[joint], bodyPoses[joint]);
            }
            RenderFrameContext.pop(frame);
        }
    }

    @Test
    void equalMatrixValuesDoNotIdentifyTheSameBodyDraw() {
        int count = HumanoidRig.EPIC_JOINT_COUNT + 4;
        OpenMatrix4f[] layerPoses = poses(count);
        OpenMatrix4f[] bodyPoses = Arrays.copyOf(layerPoses, count);
        RenderFrameContext.pushThirdPerson(null);
        publishAllAttachments(bodyPoses);

        // Check an ordinary joint and an addon joint: every source element must
        // belong to this draw, not merely the Tool or the first twenty joints.
        for (int changedJoint : new int[]{HumanoidRig.CHEST, count - 1}) {
            OpenMatrix4f[] unrelated = Arrays.copyOf(layerPoses, count);
            unrelated[changedJoint] = new OpenMatrix4f(layerPoses[changedJoint]);
            assertFalse(RenderFrameContext.sameBodyPoseSource(bodyPoses, unrelated));
            assertNoPublishedAttachments(unrelated);
        }
        OpenMatrix4f[] equalValues = Arrays.stream(layerPoses)
                .map(OpenMatrix4f::new).toArray(OpenMatrix4f[]::new);
        OpenMatrix4f[] shorter = Arrays.copyOf(layerPoses, count - 1);
        assertFalse(RenderFrameContext.sameBodyPoseSource(bodyPoses, equalValues));
        assertFalse(RenderFrameContext.sameBodyPoseSource(bodyPoses, shorter));
        assertFalse(RenderFrameContext.sameBodyPoseSource(null, layerPoses));
        assertFalse(RenderFrameContext.sameBodyPoseSource(bodyPoses, null));
        assertNoPublishedAttachments(equalValues);
        assertNoPublishedAttachments(shorter);
        assertNoPublishedAttachments(null);
        assertAllPublishedAttachments(layerPoses);
    }

    @Test
    void shallowCopiesCannotReuseAnotherRenderFramesPublication() {
        OpenMatrix4f[] oldLayerPoses = poses(HumanoidRig.EPIC_JOINT_COUNT);
        OpenMatrix4f[] oldBodyPoses = oldLayerPoses.clone();
        RenderFrameContext.Frame outer = RenderFrameContext.pushThirdPerson(null);
        publishAllAttachments(oldBodyPoses);
        assertAllPublishedAttachments(oldLayerPoses);

        RenderFrameContext.Frame inner = RenderFrameContext.pushThirdPerson(null);
        OpenMatrix4f[] currentLayerPoses = poses(HumanoidRig.EPIC_JOINT_COUNT);
        publishAllAttachments(currentLayerPoses.clone());
        assertNoPublishedAttachments(oldLayerPoses);
        assertAllPublishedAttachments(currentLayerPoses);
        RenderFrameContext.pop(inner);
        assertAllPublishedAttachments(oldLayerPoses);
        RenderFrameContext.pop(outer);
        assertNoPublishedAttachments(oldLayerPoses);

        // Even an identical reused source array has no publication in a new scope.
        RenderFrameContext.pushThirdPerson(null);
        assertNoPublishedAttachments(oldLayerPoses);
    }

    private static OpenMatrix4f[] poses(int count) {
        OpenMatrix4f[] result = new OpenMatrix4f[count];
        for (int joint = 0; joint < count; joint++) {
            result[joint] = new OpenMatrix4f().translate(joint, joint + 1.0F, -joint);
        }
        return result;
    }

    private static void publishAllAttachments(OpenMatrix4f[] bodyPoses) {
        OpenMatrix4f[] displayed = poses(bodyPoses.length);
        for (OpenMatrix4f joint : displayed) {
            joint.m30 += 100.0F;
        }
        RenderFrameContext.publishHeldItemPoints(
                null, null, bodyPoses,
                new Vector3f(1.0F, 2.0F, 3.0F), new Vector3f(-1.0F, 2.0F, 3.0F),
                new OpenMatrix4f().translate(4.0F, 5.0F, 6.0F),
                new OpenMatrix4f().translate(-4.0F, 5.0F, 6.0F),
                new OpenMatrix4f().translate(7.0F, 8.0F, 9.0F),
                displayed, false, false, false, false, Set.of());
    }

    private static void assertAllPublishedAttachments(OpenMatrix4f[] requested) {
        assertEquals(new Vector3f(1.0F, 2.0F, 3.0F), RenderFrameContext.displayedFist(
                null, null, requested, HumanoidRig.RIGHT_TOOL));
        assertEquals(new Vector3f(-1.0F, 2.0F, 3.0F), RenderFrameContext.displayedFist(
                null, null, requested, HumanoidRig.LEFT_TOOL));
        for (int tool : new int[]{HumanoidRig.RIGHT_TOOL, HumanoidRig.LEFT_TOOL}) {
            OpenMatrix4f authored = RenderFrameContext.authoredHeldItemPose(
                    null, null, requested, tool);
            assertNotNull(authored);
            assertEquals(tool == HumanoidRig.RIGHT_TOOL ? 4.0F : -4.0F, authored.m30);
        }
        OpenMatrix4f elytra = RenderFrameContext.elytraLocatorPose(null, null, requested);
        assertNotNull(elytra);
        assertEquals(8.0F, elytra.m31);
        for (int joint = 0; joint < requested.length; joint++) {
            OpenMatrix4f attachment = RenderFrameContext.displayedAttachmentPose(
                    null, null, requested, joint);
            assertNotNull(attachment, "Missing published joint " + joint);
            assertEquals(100.0F + joint, attachment.m30, 0.00001F,
                    "Return the displayed attachment, not the original Epic Fight joint");
        }
    }

    private static void assertNoPublishedAttachments(OpenMatrix4f[] requested) {
        for (int tool : new int[]{HumanoidRig.RIGHT_TOOL, HumanoidRig.LEFT_TOOL}) {
            assertNull(RenderFrameContext.displayedFist(null, null, requested, tool));
            assertNull(RenderFrameContext.authoredHeldItemPose(null, null, requested, tool));
            assertNull(RenderFrameContext.displayedAttachmentPose(null, null, requested, tool));
        }
        assertNull(RenderFrameContext.elytraLocatorPose(null, null, requested));
    }
}
