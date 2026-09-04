package net.okitsu.ysmepicfightcompat.animation;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomaticAnimationSelectorTest {
    @Test
    void officialComparisonUsesTheCachedStacksDamageState() {
        assertTrue(AutomaticAnimationSelector.officialHeldItemMatches(
                false, false, true));
        assertFalse(AutomaticAnimationSelector.officialHeldItemMatches(
                false, true, false));
        assertTrue(AutomaticAnimationSelector.officialHeldItemMatches(
                true, true, false));
        assertFalse(AutomaticAnimationSelector.officialHeldItemMatches(
                true, false, true));
    }

    @Test
    void disabledYsmVehicleRemovesOnlyMountedAnimationSelection() {
        assertFalse(AutomaticAnimationSelector.usesYsmMountedAnimations(
                true, false));
        assertTrue(AutomaticAnimationSelector.usesYsmMountedAnimations(
                true, true));
        assertTrue(AutomaticAnimationSelector.usesYsmMountedAnimations(
                false, false));
    }

    @Test
    void specialLocomotionUsesOfficialClipNames() {
        AutomaticAnimationSelector selector = selector(
                "swim", "swim_stand", "climb", "climbing",
                "ladder_up", "ladder_stillness", "ladder_down", "idle");

        assertEquals("swim", selector.movementClip(MovementAnimationType.SWIM));
        assertEquals("swim_stand",
                selector.movementClip(MovementAnimationType.WATER_IDLE));
        assertEquals("climb",
                selector.movementClip(MovementAnimationType.CRAWL_MOVE));
        assertEquals("climbing",
                selector.movementClip(MovementAnimationType.CRAWL_IDLE));
        assertEquals("ladder_up",
                selector.movementClip(MovementAnimationType.LADDER_UP));
        assertEquals("ladder_stillness",
                selector.movementClip(MovementAnimationType.LADDER_IDLE));
        assertEquals("ladder_down",
                selector.movementClip(MovementAnimationType.LADDER_DOWN));
    }

    @Test
    void missingLadderClipDoesNotFallBackToCrawlAnimation() {
        AutomaticAnimationSelector selector = selector(
                "climb", "climbing", "idle");

        assertEquals("idle",
                selector.movementClip(MovementAnimationType.LADDER_UP));
        assertEquals("idle",
                selector.movementClip(MovementAnimationType.LADDER_IDLE));
        assertEquals("idle",
                selector.movementClip(MovementAnimationType.LADDER_DOWN));
    }

    private static AutomaticAnimationSelector selector(String... clips) {
        Map<String, AutomaticAnimationSelector.ClipInfo> definitions =
                java.util.Arrays.stream(clips).collect(java.util.stream.Collectors.toMap(
                        name -> name,
                        ignored -> new AutomaticAnimationSelector.ClipInfo(1.0F)));
        return new AutomaticAnimationSelector(definitions);
    }
}
