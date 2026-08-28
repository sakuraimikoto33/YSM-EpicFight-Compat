package net.okitsu.ysmepicfightcompat.animation;

import org.junit.jupiter.api.Test;

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
}
