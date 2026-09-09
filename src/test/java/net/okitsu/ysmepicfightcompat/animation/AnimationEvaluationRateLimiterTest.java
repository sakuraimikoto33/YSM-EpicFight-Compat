package net.okitsu.ysmepicfightcompat.animation;

import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import net.okitsu.ysmepicfightcompat.mesh.AuxiliaryBoneLayout;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimationEvaluationRateLimiterTest {
    @Test
    void carriesCadenceAcrossRenderRatesThatAreNotMultiplesOfTheTarget() {
        for (int renderHz : new int[]{75, 144, 165}) {
            assertEquals(600, countEvaluations(60, renderHz, 10), "render Hz=" + renderHz);
        }
        assertEquals(350, countEvaluations(35, 120, 10));
    }

    @Test
    void lowFrameRatesCoalesceMissedDeadlinesWithoutCatchUpBursts() {
        assertEquals(240, countEvaluations(60, 24, 10));
        AnimationEvaluationRateLimiter<String> limiter = new AnimationEvaluationRateLimiter<>();
        limiter.evaluated(0, 60, "world");
        assertTrue(limiter.shouldEvaluate(10, 60, "world", false));
        limiter.evaluated(10, 60, "world");
        assertFalse(limiter.shouldEvaluate(10, 60, "world", false));
        assertFalse(limiter.shouldEvaluate(10.001, 60, "world", false));
        assertTrue(limiter.shouldEvaluate(10 + 1.0 / 60, 60, "world", false));
    }

    @Test
    void disabledLimitAlwaysAdmitsIncludingRepeatedDrawsAtTheSameTime() {
        AnimationEvaluationRateLimiter<String> limiter = new AnimationEvaluationRateLimiter<>();
        for (int index = 0; index < 4; index++) {
            assertTrue(limiter.shouldEvaluate(1, 0, "world", false));
            limiter.evaluated(1, 0, "world");
        }
    }

    @Test
    void contextChangesNeverRestoreAnOlderContextsCachedState() {
        AnimationEvaluationRateLimiter<String> limiter = new AnimationEvaluationRateLimiter<>();
        assertFalse(limiter.currentContextMatches("A"));
        limiter.evaluated(0, 60, "A");
        assertTrue(limiter.currentContextMatches(new String("A")));
        assertFalse(limiter.shouldEvaluate(0.001, 60, "A", false));
        assertTrue(limiter.shouldEvaluate(0.001, 60, "B", false));
        limiter.evaluated(0.001, 60, "B");
        assertFalse(limiter.currentContextMatches("A"));
        assertTrue(limiter.shouldEvaluate(0.002, 60, "A", false));
        limiter.evaluated(0.002, 60, "A");
        assertTrue(limiter.currentContextMatches("A"));
    }

    @Test
    void forcedInputsAreImmediateWithoutMovingAnUnexpiredDeadline() {
        AnimationEvaluationRateLimiter<String> limiter = new AnimationEvaluationRateLimiter<>();
        limiter.evaluated(0, 60, "world");
        assertTrue(limiter.shouldEvaluate(0.005, 60, "world", true));
        limiter.evaluated(0.005, 60, "world");
        assertFalse(limiter.shouldEvaluate(0.006, 60, "world", false));
        assertTrue(limiter.shouldEvaluate(1.0 / 60, 60, "world", false));
    }

    @Test
    void decisionsAndFailedEvaluationsDoNotConsumeTheNextAdmission() {
        AnimationEvaluationRateLimiter<String> limiter = new AnimationEvaluationRateLimiter<>();
        assertTrue(limiter.shouldEvaluate(0, 60, "world", false));
        assertTrue(limiter.shouldEvaluate(0, 60, "world", false));
        limiter.evaluated(0, 60, "world");
        assertTrue(limiter.shouldEvaluate(0.02, 60, "world", false));
        // A failed evaluator never calls evaluated().
        assertTrue(limiter.shouldEvaluate(0.02, 60, "world", false));
        assertTrue(limiter.currentContextMatches("world"));
    }

    @Test
    void rateChangesRewindsAndResetAdmitImmediately() {
        AnimationEvaluationRateLimiter<String> limiter = new AnimationEvaluationRateLimiter<>();
        limiter.evaluated(10, 60, "world");
        assertTrue(limiter.shouldEvaluate(10.001, 30, "world", false));
        limiter.evaluated(10.001, 30, "world");
        assertFalse(limiter.shouldEvaluate(10.002, 30, "world", false));
        assertTrue(limiter.shouldEvaluate(1, 30, "world", false));
        limiter.evaluated(1, 30, "world");
        assertFalse(limiter.shouldEvaluate(1.001, 30, "world", false));
        limiter.reset();
        assertFalse(limiter.currentContextMatches("world"));
        assertTrue(limiter.shouldEvaluate(1.001, 30, "world", false));
    }

    @Test
    void nonFiniteTimeFailsOpenWithoutPoisoningFutureDecisions() {
        for (double now : new double[]{Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            AnimationEvaluationRateLimiter<String> limiter = new AnimationEvaluationRateLimiter<>();
            limiter.evaluated(1, 60, "world");
            assertTrue(limiter.shouldEvaluate(now, 60, "world", false));
            limiter.evaluated(now, 60, "world");
            assertTrue(limiter.shouldEvaluate(1, 60, "world", false));
            limiter.evaluated(1, 60, "world");
            assertFalse(limiter.shouldEvaluate(1.001, 60, "world", false));
        }
    }

    @Test
    void skippedActualProgramEvaluationKeepsSideEffectsAndAdvancesToTrueElapsedTime() {
        ProgramFixture fixture = new ProgramFixture();
        assertTrue(fixture.sample(0, 60));
        ParallelAnimationProgram.Frame previous = fixture.frame;
        assertEquals(1, fixture.environment.calls());
        assertFalse(fixture.sample(0.004, 60));
        assertSame(previous, fixture.frame);
        assertEquals(1, fixture.environment.calls());
        assertEquals(1, fixture.frame.parallelDeltas()[1].m00, 0.00001);

        assertTrue(fixture.sample(0.025, 60));
        assertEquals(2, fixture.environment.calls());
        assertEquals(Math.cos(Math.toRadians(90 * 0.025)),
                fixture.frame.parallelDeltas()[1].m00, 0.00001);
        assertTrue(fixture.sample(0.025, 0));
        assertTrue(fixture.sample(0.025, 0));
        assertEquals(4, fixture.environment.calls());
    }

    private static int countEvaluations(int rateHz, int renderHz, int seconds) {
        AnimationEvaluationRateLimiter<String> limiter = new AnimationEvaluationRateLimiter<>();
        int evaluations = 0;
        for (int index = 0; index < renderHz * seconds; index++) {
            double now = index / (double) renderHz;
            if (limiter.shouldEvaluate(now, rateHz, "world", false)) {
                limiter.evaluated(now, rateHz, "world");
                evaluations++;
            }
        }
        return evaluations;
    }

    private static final class ProgramFixture {
        private final AnimationEvaluationRateLimiter<String> limiter = new AnimationEvaluationRateLimiter<>();
        private final TestEnvironment environment = new TestEnvironment();
        private final ParallelAnimationProgram program;
        private ParallelAnimationProgram.Frame frame;

        private ProgramFixture() {
            GeometryDocument geometry = new GeometryDocument();
            GeometryDocument.Bone head = new GeometryDocument.Bone("head");
            GeometryDocument.Bone ear = new GeometryDocument.Bone("ear");
            ear.parentName("head");
            geometry.add(head);
            geometry.add(ear);
            geometry.linkHierarchy();
            AnimationClip script = new AnimationClip("pre_parallel0");
            AnimationClip.VectorValue sideEffect = new AnimationClip.VectorValue();
            sideEffect.setExpression(0, "v.calls+=1");
            script.boneTracks().put("molang", rotationTrack(sideEffect, null));
            AnimationClip pose = new AnimationClip("parallel0");
            AnimationClip.VectorValue start = new AnimationClip.VectorValue();
            AnimationClip.VectorValue end = new AnimationClip.VectorValue();
            end.setConstant(2, 90);
            pose.boneTracks().put("ear", rotationTrack(start, end));
            program = new ParallelAnimationProgram(geometry,
                    Map.of(script.name(), script, pose.name(), pose),
                    AuxiliaryBoneLayout.create(geometry), 1, 1);
        }

        private boolean sample(double elapsed, int rateHz) {
            if (!limiter.shouldEvaluate(elapsed, rateHz, "world", false)) return false;
            frame = program.sampleAt(elapsed, environment);
            limiter.evaluated(elapsed, rateHz, "world");
            return true;
        }
    }

    private static AnimationClip.BoneTracks rotationTrack(
            AnimationClip.VectorValue start, AnimationClip.VectorValue end) {
        AnimationClip.Track track = new AnimationClip.Track();
        track.keyframes().add(new AnimationClip.Keyframe(0, AnimationClip.Interpolation.LINEAR, start, null));
        if (end != null) {
            track.keyframes().add(new AnimationClip.Keyframe(1, AnimationClip.Interpolation.LINEAR, end, null));
        }
        AnimationClip.BoneTracks tracks = new AnimationClip.BoneTracks();
        tracks.rotation(track);
        return tracks;
    }

    private static final class TestEnvironment implements ExpressionEngine.Environment {
        private final Map<Integer, Double> variables = new HashMap<>();

        double calls() { return readVariable(ExpressionEngine.slot("v.calls")); }
        @Override public double readVariable(int slot) { return variables.getOrDefault(slot, 0.0D); }
        @Override public boolean hasVariable(int slot) { return variables.containsKey(slot); }
        @Override public void writeVariable(int slot, double value) { variables.put(slot, value); }
        @Override public double readQuery(int slot) { return 0; }
        @Override public double invoke(String name, double[] arguments) { return 0; }
        @Override public double invokeWithText(String name, String[] arguments) { return 0; }
    }
}
