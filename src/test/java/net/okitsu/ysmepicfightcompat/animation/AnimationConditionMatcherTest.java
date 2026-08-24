package net.okitsu.ysmepicfightcompat.animation;

import net.minecraft.world.item.UseAnim;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimationConditionMatcherTest {
    @Test
    void bowReboundReturnsToHoldInsteadOfStartingSwingBow() {
        assertFalse(AnimationConditionMatcher.shouldTreatReboundAsSwing(UseAnim.BOW));
        assertTrue(AnimationConditionMatcher.shouldTreatReboundAsSwing(UseAnim.SPEAR));
        assertTrue(AnimationConditionMatcher.shouldTreatReboundAsSwing(UseAnim.NONE));
    }
}
