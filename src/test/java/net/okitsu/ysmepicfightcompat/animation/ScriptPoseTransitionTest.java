package net.okitsu.ysmepicfightcompat.animation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptPoseTransitionTest {
    private static final float EPSILON = 1.0E-6F;

    @Test
    void optionalShortestRotationWrapsAnglesWithoutWrappingPositions() {
        ScriptPoseTransition transition = new ScriptPoseTransition(1);
        transition.begin(1, 1, false);
        transition.rotation(0, vector((float) Math.toRadians(170)), 1, false);
        transition.position(0, vector(170), 1, false);
        new Pose(1).apply(transition);
        transition.begin(2, 0.5F, false, true);
        transition.rotation(0, vector((float) Math.toRadians(-170)), 1, false);
        transition.position(0, vector(-170), 1, false);
        Pose result = new Pose(1);
        result.apply(transition);
        assertEquals(Math.PI, result.rotations[0][0], EPSILON);
        assertEquals(0, result.positions[0][0], EPSILON);
        transition.begin(2, 1, false, true);
        transition.rotation(0, vector((float) Math.toRadians(-170)), 1, false);
        Pose ending = new Pose(1);
        ending.apply(transition);
        assertEquals(Math.toRadians(-170), ending.rotations[0][0], EPSILON);
    }

    @Test
    void additiveAndOverrideRotationsPreserveTheirDifferentLayerEffects() {
        ScriptPoseTransition additive = new ScriptPoseTransition(1);
        additive.begin(1, 1.0F, false);
        additive.rotation(0, vector(20), 0.5F, true);
        Pose added = new Pose(1).rotation(0, 10);
        assertTrue(added.apply(additive));
        assertEquals(20.0F, added.rotations[0][0], EPSILON);

        ScriptPoseTransition override = new ScriptPoseTransition(1);
        override.begin(1, 1.0F, false);
        override.rotation(0, vector(20), 0.5F, false);
        Pose replaced = new Pose(1).rotation(0, 10);
        assertTrue(replaced.apply(override));
        assertEquals(15.0F, replaced.rotations[0][0], EPSILON);
    }

    @Test
    void parallelPositionReplacesRatherThanAddingToEarlierPosition() {
        ScriptPoseTransition parallel = new ScriptPoseTransition(1);
        parallel.begin(1, 1.0F, false);
        parallel.position(0, vector(20), 0.5F, true);
        Pose parallelPose = new Pose(1).position(0, 10);
        assertTrue(parallelPose.apply(parallel));
        assertEquals(10.0F, parallelPose.positions[0][0], EPSILON);

        ScriptPoseTransition override = new ScriptPoseTransition(1);
        override.begin(1, 1.0F, false);
        override.position(0, vector(20), 0.5F, false);
        Pose overridePose = new Pose(1).position(0, 10);
        assertTrue(overridePose.apply(override));
        assertEquals(15.0F, overridePose.positions[0][0], EPSILON);
    }

    @Test
    void firstFrameKeepsIncomingOwnershipWithoutChangingTheEarlierPose() {
        ScriptPoseTransition transition = new ScriptPoseTransition(2);
        transition.begin(1, 0.0F, false);
        transition.rotation(0, vector(60), 1.0F, false);
        transition.position(1, vector(3), 1.0F, false);
        Pose pose = new Pose(2).rotation(0, 12);
        // Unset tracks have a zero base, even if scratch storage still contains a value.
        pose.positions[1][0] = 99;
        assertTrue(pose.apply(transition));
        assertEquals(12.0F, pose.rotations[0][0], EPSILON);
        assertTrue(pose.hasRotation[0]);
        assertEquals(0.0F, pose.positions[1][0], EPSILON);
        assertTrue(pose.hasPosition[1]);
        assertFalse(pose.hasRotation[1]);
        assertFalse(pose.hasPosition[0]);
        assertTrue(transition.hasRotation(0));
        assertTrue(transition.hasPosition(1));
        assertFalse(transition.hasRotation(1));
        assertFalse(transition.hasPosition(0));

        transition.begin(1, 1.0F, false);
        transition.rotation(0, vector(60), 0.0F, false);
        Pose zeroWeight = new Pose(2);
        assertTrue(zeroWeight.apply(transition));
        assertEquals(0.0F, zeroWeight.rotations[0][0], EPSILON);
        assertTrue(zeroWeight.hasRotation[0]);
        assertTrue(transition.hasRotation(0));
        assertFalse(transition.hasPosition(1));
    }

    @Test
    void keepsTheSourceEffectFrozenWhileEarlierLayersContinueMoving() {
        ScriptPoseTransition transition = new ScriptPoseTransition(1);
        transition.begin(1, 1.0F, false);
        transition.rotation(0, vector(10), 1.0F, true);
        Pose initial = new Pose(1).rotation(0, 2);
        initial.apply(transition);
        assertEquals(12.0F, initial.rotations[0][0], EPSILON);

        transition.begin(2, 0.25F, false);
        transition.rotation(0, vector(30), 1.0F, true);
        Pose quarter = new Pose(1).rotation(0, 100);
        quarter.apply(transition);
        assertEquals(115.0F, quarter.rotations[0][0], EPSILON);

        transition.begin(2, 0.5F, false);
        transition.rotation(0, vector(30), 1.0F, true);
        Pose half = new Pose(1).rotation(0, 200);
        half.apply(transition);
        assertEquals(220.0F, half.rotations[0][0], EPSILON);
    }

    @Test
    void blendsOverrideCoefficientsWithoutDoubleApplyingOrFreezingEarlierLayers() {
        ScriptPoseTransition transition = new ScriptPoseTransition(1);
        transition.begin(1, 1.0F, false);
        transition.rotation(0, vector(10), 0.5F, false);
        new Pose(1).rotation(0, 20).apply(transition);

        transition.begin(2, 0.5F, false);
        transition.rotation(0, vector(30), 0.5F, false);
        Pose next = new Pose(1).rotation(0, 100);
        next.apply(transition);
        // Both endpoint effects keep half of the current base: 100 * .5 + lerp(5, 15, .5).
        assertEquals(60.0F, next.rotations[0][0], EPSILON);
    }

    @Test
    void interruptedTransitionStartsFromTheLastDisplayedEffect() {
        ScriptPoseTransition transition = new ScriptPoseTransition(1);
        transition.begin(1, 1.0F, false);
        transition.rotation(0, vector(100), 1.0F, false);
        new Pose(1).apply(transition);

        transition.begin(2, 0.25F, false);
        transition.rotation(0, vector(200), 1.0F, false);
        Pose interrupted = new Pose(1);
        interrupted.apply(transition);
        assertEquals(125.0F, interrupted.rotations[0][0], EPSILON);

        transition.begin(3, 0.0F, false);
        transition.rotation(0, vector(300), 1.0F, false);
        Pose first = new Pose(1);
        first.apply(transition);
        assertEquals(125.0F, first.rotations[0][0], EPSILON);

        transition.begin(3, 0.5F, false);
        transition.rotation(0, vector(300), 1.0F, false);
        Pose half = new Pose(1);
        half.apply(transition);
        assertEquals(212.5F, half.rotations[0][0], EPSILON);
    }

    @Test
    void missingIncomingTracksFadeToIdentityAndReleaseOnlyTheirOwnOwnership() {
        ScriptPoseTransition transition = new ScriptPoseTransition(2);
        transition.begin(1, 1.0F, false);
        transition.rotation(0, vector(12), 1.0F, false);
        transition.position(1, vector(8), 1.0F, false);
        new Pose(2).apply(transition);

        transition.begin(2, 0.5F, false);
        Pose halfway = new Pose(2).rotation(0, 4).position(1, 2);
        assertTrue(halfway.apply(transition));
        assertEquals(8.0F, halfway.rotations[0][0], EPSILON);
        assertEquals(5.0F, halfway.positions[1][0], EPSILON);
        assertTrue(halfway.hasRotation[0]);
        assertTrue(halfway.hasPosition[1]);
        assertTrue(transition.hasRotation(0));
        assertTrue(transition.hasPosition(1));

        transition.begin(2, 1.0F, false);
        Pose finished = new Pose(2).rotation(0, 4);
        finished.positions[1][0] = 42;
        assertFalse(finished.apply(transition));
        assertEquals(4.0F, finished.rotations[0][0], EPSILON);
        assertTrue(finished.hasRotation[0]);
        assertEquals(42.0F, finished.positions[1][0], EPSILON);
        assertFalse(finished.hasPosition[1]);
        // The earlier rotation remains present, but does not belong to this layer.
        assertFalse(transition.hasRotation(0));
        assertFalse(transition.hasPosition(1));
    }

    @Test
    void repeatedTrackWritesComposeInOriginalOrderAndDoNotRetainTargetArrays() {
        ScriptPoseTransition transition = new ScriptPoseTransition(1);
        transition.begin(1, 1.0F, false);
        float[] target = vector(10);
        transition.rotation(0, target, 1.0F, true);
        target[0] = 99;
        transition.rotation(0, vector(20), 0.5F, false);
        transition.position(0, vector(1), 0.5F, true);
        transition.position(0, vector(3), 0.5F, false);
        Pose pose = new Pose(1).rotation(0, 4).position(0, 100);
        pose.apply(transition);
        assertEquals(17.0F, pose.rotations[0][0], EPSILON);
        assertEquals(1.75F, pose.positions[0][0], EPSILON);
    }

    @Test
    void explicitDiscardReplacesTheFrozenSourceWithIdentityWithinTheSameGeneration() {
        ScriptPoseTransition transition = new ScriptPoseTransition(1);
        transition.begin(1, 1.0F, false);
        transition.rotation(0, vector(30), 1.0F, false);
        new Pose(1).apply(transition);
        transition.begin(2, 0.5F, false);
        transition.rotation(0, vector(60), 1.0F, false);
        new Pose(1).apply(transition);

        transition.begin(2, 0.5F, true);
        transition.rotation(0, vector(60), 1.0F, false);
        Pose pose = new Pose(1).rotation(0, 4);
        pose.apply(transition);
        assertEquals(32.0F, pose.rotations[0][0], EPSILON);
    }

    @Test
    void stopWeightAttenuatesBothEntryEndpointsAndReturnsToTheCurrentBaseAtZero() {
        ScriptPoseTransition transition = new ScriptPoseTransition(1);
        transition.begin(1, 1.0F, false);
        transition.rotation(0, vector(20), 1.0F, false);
        transition.position(0, vector(20), 1.0F, true);
        new Pose(1).apply(transition);

        transition.begin(2, 0.5F, false);
        transition.rotation(0, vector(60), 1.0F, false);
        transition.position(0, vector(60), 1.0F, true);
        Pose stopping = new Pose(1).rotation(0, 10).position(0, 10);
        assertTrue(stopping.apply(transition, 0.5F));
        assertEquals(25.0F, stopping.rotations[0][0], EPSILON);
        assertEquals(25.0F, stopping.positions[0][0], EPSILON);

        transition.begin(2, 0.75F, false);
        transition.rotation(0, vector(60), 1.0F, false);
        transition.position(0, vector(60), 1.0F, true);
        Pose stopped = new Pose(1).rotation(0, 12).position(0, 14);
        assertTrue(stopped.apply(transition, 0.0F));
        assertEquals(12.0F, stopped.rotations[0][0], EPSILON);
        assertEquals(14.0F, stopped.positions[0][0], EPSILON);
    }

    @Test
    void stopWeightAlsoAttenuatesTheOutgoingAdditiveContribution() {
        ScriptPoseTransition transition = new ScriptPoseTransition(1);
        transition.begin(1, 1.0F, false);
        transition.rotation(0, vector(10), 1.0F, true);
        new Pose(1).apply(transition);

        transition.begin(2, 0.5F, false);
        transition.rotation(0, vector(30), 1.0F, true);
        Pose stopping = new Pose(1).rotation(0, 100);
        stopping.apply(transition, 0.5F);
        assertEquals(110.0F, stopping.rotations[0][0], EPSILON);
    }

    @Test
    void newGenerationUsesTheAttenuatedDisplayedEffectWithoutFreezingItsBase() {
        ScriptPoseTransition transition = new ScriptPoseTransition(1);
        transition.begin(1, 1.0F, false);
        transition.rotation(0, vector(20), 1.0F, false);
        new Pose(1).apply(transition);

        transition.begin(2, 0.5F, false);
        transition.rotation(0, vector(60), 1.0F, false);
        Pose stopping = new Pose(1).rotation(0, 10);
        stopping.apply(transition, 0.5F);
        assertEquals(25.0F, stopping.rotations[0][0], EPSILON);

        transition.begin(3, 0.0F, false);
        transition.rotation(0, vector(100), 1.0F, false);
        Pose switched = new Pose(1).rotation(0, 50);
        switched.apply(transition);
        // The source is the displayed effect (.5 * base + 20), not the value 25.
        assertEquals(45.0F, switched.rotations[0][0], EPSILON);
    }

    @Test
    void outputWeightIsFiniteAndClampedWithoutChangingTheEntryClock() {
        ScriptPoseTransition transition = new ScriptPoseTransition(1);
        transition.begin(1, 0.5F, false);
        transition.rotation(0, vector(20), 1.0F, true);
        Pose full = new Pose(1).rotation(0, 4);
        full.apply(transition, 2.0F);
        assertEquals(14.0F, full.rotations[0][0], EPSILON);
        for (float invalid : new float[]{-1.0F, Float.NaN, Float.POSITIVE_INFINITY}) {
            transition.begin(1, 0.5F, false);
            transition.rotation(0, vector(20), 1.0F, true);
            Pose muted = new Pose(1).rotation(0, 4);
            muted.apply(transition, invalid);
            assertEquals(4.0F, muted.rotations[0][0], EPSILON);
        }
    }

    @Test
    void blendsAllThreeAxesWithoutChangingCallerOwnedTrackValues() {
        ScriptPoseTransition transition = new ScriptPoseTransition(1);
        transition.begin(1, 0.5F, false);
        float[] rotation = {2, 4, 6};
        float[] position = {6, 4, 2};
        transition.rotation(0, rotation, 1.0F, false);
        transition.position(0, position, 1.0F, false);
        Pose pose = new Pose(1);
        pose.apply(transition);
        assertArrayEquals(new float[]{1, 2, 3}, pose.rotations[0], EPSILON);
        assertArrayEquals(new float[]{3, 2, 1}, pose.positions[0], EPSILON);
        assertArrayEquals(new float[]{2, 4, 6}, rotation, EPSILON);
        assertArrayEquals(new float[]{6, 4, 2}, position, EPSILON);
    }

    @Test
    void appendingDisplayedEffectsComposesOnceInTheDestinationsExistingOrder() {
        ScriptPoseTransition inner = new ScriptPoseTransition(1);
        inner.begin(1, 1.0F, false);
        inner.rotation(0, vector(20), 0.5F, false);
        inner.position(0, vector(8), 0.5F, true);
        new Pose(1).rotation(0, 999).position(0, 999).apply(inner);

        ScriptPoseTransition outer = new ScriptPoseTransition(1);
        outer.begin(1, 1.0F, false);
        outer.rotation(0, vector(10), 1.0F, true);
        outer.position(0, vector(12), 0.5F, false);
        inner.appendDisplayedTo(outer);
        outer.rotation(0, vector(3), 1.0F, true);
        outer.position(0, vector(20), 0.5F, false);

        Pose pose = new Pose(1).rotation(0, 4).position(0, 100);
        assertTrue(pose.apply(outer));
        // ((base + 10) * .5 + 10) + 3: only the effect, never the sampled 999 base.
        assertEquals(20.0F, pose.rotations[0][0], EPSILON);
        // The inner parallel position replaces all earlier positions before the final override.
        assertEquals(12.0F, pose.positions[0][0], EPSILON);
    }

    @Test
    void outerEntryBlendsTheInnerDisplayedEffectAgainstLiveEarlierLayers() {
        ScriptPoseTransition inner = new ScriptPoseTransition(1);
        inner.begin(1, 0.5F, false);
        inner.rotation(0, vector(20), 1.0F, true);
        inner.position(0, vector(20), 1.0F, false);
        new Pose(1).rotation(0, 100).position(0, 100).apply(inner);

        ScriptPoseTransition outer = new ScriptPoseTransition(1);
        outer.begin(1, 0.5F, false);
        inner.appendDisplayedTo(outer);
        Pose first = new Pose(1).rotation(0, 200).position(0, 200);
        first.apply(outer);
        assertEquals(205.0F, first.rotations[0][0], EPSILON);
        assertEquals(155.0F, first.positions[0][0], EPSILON);

        outer.begin(1, 0.5F, false);
        inner.appendDisplayedTo(outer);
        Pose moved = new Pose(1).rotation(0, 300).position(0, 300);
        moved.apply(outer);
        assertEquals(305.0F, moved.rotations[0][0], EPSILON);
        assertEquals(230.0F, moved.positions[0][0], EPSILON);
    }

    @Test
    void appendingDoesNotMutateTheSourcesDisplayedOrFrozenTransitionEffects() {
        ScriptPoseTransition inner = new ScriptPoseTransition(1);
        inner.begin(1, 1.0F, false);
        inner.rotation(0, vector(40), 1.0F, false);
        inner.position(0, vector(8), 1.0F, false);
        new Pose(1).apply(inner);
        inner.begin(2, 0.25F, false);
        inner.rotation(0, vector(80), 1.0F, false);
        inner.position(0, vector(24), 1.0F, false);
        new Pose(1).apply(inner);

        ScriptPoseTransition outer = new ScriptPoseTransition(1);
        outer.begin(1, 1.0F, false);
        inner.appendDisplayedTo(outer);
        outer.rotation(0, vector(999), 1.0F, false);
        outer.position(0, vector(999), 1.0F, false);
        new Pose(1).apply(outer);

        outer.begin(1, 1.0F, false);
        inner.appendDisplayedTo(outer);
        Pose copiedAgain = new Pose(1);
        copiedAgain.apply(outer);
        assertEquals(50.0F, copiedAgain.rotations[0][0], EPSILON);
        assertEquals(12.0F, copiedAgain.positions[0][0], EPSILON);

        inner.begin(2, 0.5F, false);
        inner.rotation(0, vector(80), 1.0F, false);
        inner.position(0, vector(24), 1.0F, false);
        Pose continued = new Pose(1);
        continued.apply(inner);
        assertEquals(60.0F, continued.rotations[0][0], EPSILON);
        assertEquals(16.0F, continued.positions[0][0], EPSILON);

        inner.begin(3, 0.0F, false);
        inner.rotation(0, vector(100), 1.0F, false);
        inner.position(0, vector(40), 1.0F, false);
        Pose interrupted = new Pose(1);
        interrupted.apply(inner);
        assertEquals(60.0F, interrupted.rotations[0][0], EPSILON);
        assertEquals(16.0F, interrupted.positions[0][0], EPSILON);
    }

    @Test
    void appendingPreservesDeclaredIdentityAndUnrelatedDestinationTracks() {
        ScriptPoseTransition inner = new ScriptPoseTransition(2);
        inner.begin(1, 0.0F, false);
        inner.rotation(0, vector(60), 1.0F, false);
        inner.position(1, vector(3), 1.0F, false);
        new Pose(2).apply(inner);

        ScriptPoseTransition outer = new ScriptPoseTransition(2);
        outer.begin(1, 1.0F, false);
        outer.rotation(1, vector(2), 1.0F, true);
        outer.position(0, vector(4), 1.0F, true);
        inner.appendDisplayedTo(outer);
        Pose pose = new Pose(2);
        assertTrue(pose.apply(outer));
        assertArrayEquals(new boolean[]{true, true}, pose.hasRotation);
        assertArrayEquals(new boolean[]{true, true}, pose.hasPosition);
        assertEquals(0.0F, pose.rotations[0][0], EPSILON);
        assertEquals(2.0F, pose.rotations[1][0], EPSILON);
        assertEquals(4.0F, pose.positions[0][0], EPSILON);
        assertEquals(0.0F, pose.positions[1][0], EPSILON);
    }

    @Test
    void appendingValidatesTheDestinationBeforeChangingAnyCollectedTrack() {
        ScriptPoseTransition inner = new ScriptPoseTransition(1);
        inner.begin(1, 1.0F, false);
        inner.rotation(0, vector(100), 1.0F, false);
        new Pose(1).apply(inner);
        ScriptPoseTransition mismatched = new ScriptPoseTransition(2);
        mismatched.begin(1, 1.0F, false);
        mismatched.rotation(0, vector(7), 1.0F, true);
        assertThrows(IllegalArgumentException.class, () -> inner.appendDisplayedTo(mismatched));
        Pose unchanged = new Pose(2).rotation(0, 3);
        unchanged.apply(mismatched);
        assertEquals(10.0F, unchanged.rotations[0][0], EPSILON);
        assertFalse(unchanged.hasRotation[1]);
        assertThrows(IllegalStateException.class,
                () -> inner.appendDisplayedTo(new ScriptPoseTransition(1)));
        assertThrows(NullPointerException.class, () -> inner.appendDisplayedTo(null));

        ScriptPoseTransition empty = new ScriptPoseTransition(0);
        ScriptPoseTransition destination = new ScriptPoseTransition(0);
        destination.begin(1, 1.0F, false);
        empty.appendDisplayedTo(destination);
        assertFalse(new Pose(0).apply(destination));
        assertThrows(IllegalStateException.class, () -> empty.appendDisplayedTo(destination));
    }

    @Test
    void boundsStorageAndRequiresOneApplyPerSample() {
        assertThrows(IllegalArgumentException.class, () -> new ScriptPoseTransition(-1));
        assertThrows(IllegalArgumentException.class, () -> new ScriptPoseTransition(65_537));
        ScriptPoseTransition transition = new ScriptPoseTransition(0);
        Pose pose = new Pose(0);
        assertThrows(IllegalStateException.class, () -> pose.apply(transition));
        transition.begin(1, 1.0F, false);
        assertFalse(pose.apply(transition));
        assertThrows(IllegalStateException.class, () -> pose.apply(transition));
    }

    private static float[] vector(float value) {
        return new float[]{value, 0, 0};
    }

    private static final class Pose {
        private final float[][] rotations;
        private final boolean[] hasRotation;
        private final float[][] positions;
        private final boolean[] hasPosition;

        private Pose(int count) {
            rotations = new float[count][3];
            hasRotation = new boolean[count];
            positions = new float[count][3];
            hasPosition = new boolean[count];
        }

        private Pose rotation(int index, float value) {
            rotations[index][0] = value;
            hasRotation[index] = true;
            return this;
        }

        private Pose position(int index, float value) {
            positions[index][0] = value;
            hasPosition[index] = true;
            return this;
        }

        private boolean apply(ScriptPoseTransition transition) {
            return transition.apply(rotations, hasRotation, positions, hasPosition);
        }

        private boolean apply(ScriptPoseTransition transition, float outputWeight) {
            return transition.apply(rotations, hasRotation, positions, hasPosition, outputWeight);
        }
    }
}
