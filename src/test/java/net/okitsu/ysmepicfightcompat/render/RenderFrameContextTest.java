package net.okitsu.ysmepicfightcompat.render;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

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
}
