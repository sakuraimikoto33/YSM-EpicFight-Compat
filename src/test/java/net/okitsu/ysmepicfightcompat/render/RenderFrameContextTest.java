package net.okitsu.ysmepicfightcompat.render;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
