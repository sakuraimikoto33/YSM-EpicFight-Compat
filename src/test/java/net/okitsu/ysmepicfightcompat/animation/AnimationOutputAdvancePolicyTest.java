package net.okitsu.ysmepicfightcompat.animation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimationOutputAdvancePolicyTest {
    @Test
    void cappedCurrentAndPreviousTickDrawsReserveUpdatesForRendering() {
        for (int rate : new int[]{1, 30, 60, 240}) {
            assertFalse(ParallelAnimationProgram.shouldAdvanceOutputs(rate, 20, 19, 20));
            assertFalse(ParallelAnimationProgram.shouldAdvanceOutputs(rate, 20, 19, 19));
            assertFalse(ParallelAnimationProgram.shouldAdvanceOutputs(rate, 20, 10, 19),
                    "Reused draws must remain visible even when the last evaluation is older");
        }
    }

    @Test
    void cappedOffscreenOutputsResumeAfterOneMissedRenderTick() {
        for (int rate : new int[]{1, 30, 60, 240}) {
            assertFalse(ParallelAnimationProgram.shouldAdvanceOutputs(rate, 21, 20, 20));
            assertTrue(ParallelAnimationProgram.shouldAdvanceOutputs(rate, 22, 20, 20));
            assertFalse(ParallelAnimationProgram.shouldAdvanceOutputs(rate, 22, 22, 20));
            assertTrue(ParallelAnimationProgram.shouldAdvanceOutputs(rate, 23, 22, 20));
        }
    }

    @Test
    void anEvaluationAlreadyInThisOrAFutureTickSuppressesEveryMode() {
        for (int rate : new int[]{-1, 0, 30, 240}) {
            for (int renderTick : new int[]{Integer.MIN_VALUE, 18, 19, 20, 21, Integer.MAX_VALUE}) {
                assertFalse(ParallelAnimationProgram.shouldAdvanceOutputs(rate, 20, 20, renderTick));
                assertFalse(ParallelAnimationProgram.shouldAdvanceOutputs(rate, 20, 21, renderTick));
            }
        }
    }

    @Test
    void unlimitedModePreservesExactlyTheOriginalEvaluationTickGuard() {
        int[] ticks = {Integer.MIN_VALUE, -1, 0, 1, 20, Integer.MAX_VALUE};
        for (int rate : new int[]{-1, 0}) {
            for (int tick : ticks) {
                for (int evaluated : ticks) {
                    for (int rendered : ticks) {
                        assertEquals(evaluated < tick,
                                ParallelAnimationProgram.shouldAdvanceOutputs(rate, tick, evaluated, rendered));
                    }
                }
            }
        }
    }

    @Test
    void neverRenderedAndLargeTickGapsDoNotOverflowIntoTheVisibleWindow() {
        assertTrue(ParallelAnimationProgram.shouldAdvanceOutputs(
                30, 0, Integer.MIN_VALUE, Integer.MIN_VALUE));
        assertTrue(ParallelAnimationProgram.shouldAdvanceOutputs(
                30, Integer.MAX_VALUE, Integer.MAX_VALUE - 1, Integer.MIN_VALUE));
        assertTrue(ParallelAnimationProgram.shouldAdvanceOutputs(
                30, Integer.MAX_VALUE, Integer.MAX_VALUE - 1, -2));
        assertFalse(ParallelAnimationProgram.shouldAdvanceOutputs(
                30, Integer.MAX_VALUE, Integer.MAX_VALUE - 1, Integer.MAX_VALUE - 1));
    }

    @Test
    void aNegativeRenderAgeFailsOpenWhenTheEvaluationTickStillNeedsAdvancing() {
        assertTrue(ParallelAnimationProgram.shouldAdvanceOutputs(30, 20, 19, 21));
        assertTrue(ParallelAnimationProgram.shouldAdvanceOutputs(
                30, Integer.MIN_VALUE + 1, Integer.MIN_VALUE, Integer.MAX_VALUE));
        assertFalse(ParallelAnimationProgram.shouldAdvanceOutputs(30, 20, 21, 21));
    }
}
