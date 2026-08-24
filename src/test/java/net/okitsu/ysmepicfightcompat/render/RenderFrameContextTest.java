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
        RenderFrameContext.Frame outer = RenderFrameContext.pushThirdPerson(null);
        RenderFrameContext.Frame inner = RenderFrameContext.pushFirstPerson(
                null, Map.of("rightArm", true), false);

        assertSame(inner, RenderFrameContext.current());
        RenderFrameContext.pop(inner);
        assertSame(outer, RenderFrameContext.current());
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
}
