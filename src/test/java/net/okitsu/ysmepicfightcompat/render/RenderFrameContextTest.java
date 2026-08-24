package net.okitsu.ysmepicfightcompat.render;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

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
}
