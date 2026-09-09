package net.okitsu.ysmepicfightcompat.animation;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CompiledAnimationTrackTest {
    @Test
    void samplesNumericKeysAndReusesOnlyTheExactFloatTime() {
        CompiledAnimationTrack track = CompiledAnimationTrack.compile(numericTrack(
                new float[]{0, 1}, 0, 8));
        CompiledAnimationTrack.Sampler sampler = new CompiledAnimationTrack.Sampler();
        TestEnvironment environment = new TestEnvironment();
        double[] target = new double[3];

        assertFalse(sampler.sample(track, 0.5F, environment, target));
        assertArrayEquals(new double[]{4, 8, -4}, target);
        assertTrue(sampler.sample(track, 0.5F, environment, target));
        assertFalse(sampler.sample(track, Math.nextUp(0.5F), environment, target));
        assertTrue(target[0] > 4);
        assertFalse(sampler.sample(track, 0.5F, environment, target), "Loop/backward time is not a hit");
        assertArrayEquals(new double[]{4, 8, -4}, target);
        assertTrue(sampler.sample(track, 0.5F, environment, target));
        assertFalse(sampler.sample(track, 0.0F, environment, target));
        assertFalse(sampler.sample(track, -0.0F, environment, target), "Time keys preserve signed zero");
        assertTrue(sampler.sample(track, -0.0F, environment, target));
    }

    @Test
    void cacheCopiesValuesAndCollisionsCannotReturnAnotherTracksResult() {
        CompiledAnimationTrack first = CompiledAnimationTrack.compile(numericTrack(
                new float[]{0, 1}, 0, 8));
        CompiledAnimationTrack second = CompiledAnimationTrack.compile(numericTrack(
                new float[]{0, 1}, 20, 28));
        CompiledAnimationTrack.Sampler sampler = new CompiledAnimationTrack.Sampler(1);
        TestEnvironment environment = new TestEnvironment();
        double[] target = new double[3];

        assertFalse(sampler.sample(first, 0.5F, environment, target));
        target[0] = 999;
        assertTrue(sampler.sample(first, 0.5F, environment, target));
        assertEquals(4, target[0]);
        assertFalse(sampler.sample(second, 0.5F, environment, target));
        assertEquals(24, target[0]);
        assertFalse(sampler.sample(first, 0.5F, environment, target));
        assertEquals(4, target[0]);
        assertTrue(sampler.sample(first, 0.5F, environment, target));
    }

    @Test
    void cacheAllocationIsLazyAndBoundedAndEachScratchHasItsOwnCache() {
        CompiledAnimationTrack.Sampler first = new CompiledAnimationTrack.Sampler();
        CompiledAnimationTrack.Sampler second = new CompiledAnimationTrack.Sampler();
        TestEnvironment environment = new TestEnvironment();
        double[] target = new double[3];
        CompiledAnimationTrack oneKey = CompiledAnimationTrack.compile(numericTrack(new float[]{0}, 5));
        assertFalse(first.sample(oneKey, 0, environment, target));
        assertFalse(first.sample(oneKey, 0, environment, target));
        assertEquals(0, first.allocatedCacheCapacity());

        CompiledAnimationTrack shared = CompiledAnimationTrack.compile(numericTrack(new float[]{0, 1}, 0, 8));
        assertFalse(first.sample(shared, 0.5F, environment, target));
        assertTrue(first.sample(shared, 0.5F, environment, target));
        assertFalse(second.sample(shared, 0.5F, environment, target));
        assertTrue(second.sample(shared, 0.5F, environment, target));
        for (int index = 0; index < 2_048; index++) {
            CompiledAnimationTrack track = CompiledAnimationTrack.compile(
                    numericTrack(new float[]{0, 1}, index, index + 2));
            first.sample(track, 0.5F, environment, target);
            assertEquals(index + 1, target[0]);
        }
        assertEquals(1_024, first.allocatedCacheCapacity());
        assertThrows(IllegalArgumentException.class, () -> new CompiledAnimationTrack.Sampler(0));
        assertThrows(IllegalArgumentException.class, () -> new CompiledAnimationTrack.Sampler(3));
        assertThrows(IllegalArgumentException.class, () -> new CompiledAnimationTrack.Sampler(2_048));
    }

    @Test
    void compiledNumericValuesDoNotRetainMutableSourceData() {
        AnimationClip.Track source = numericTrack(new float[]{0, 1}, 0, 8);
        CompiledAnimationTrack compiled = CompiledAnimationTrack.compile(source);
        CompiledAnimationTrack.Sampler sampler = new CompiledAnimationTrack.Sampler();
        double[] target = new double[3];
        TestEnvironment environment = new TestEnvironment();
        assertFalse(sampler.sample(compiled, 0.5F, environment, target));
        source.keyframes().get(0).value().setExpression(0, "v.changed=999");
        source.keyframes().get(1).value().setConstant(0, 999);
        source.keyframes().clear();
        source.keyframes().add(key(100, value(50)));

        assertTrue(sampler.sample(compiled, 0.5F, environment, target));
        assertArrayEquals(new double[]{4, 8, -4}, target);
        assertFalse(sampler.sample(compiled, 0.75F, environment, target));
        assertArrayEquals(new double[]{6, 12, -6}, target);
        assertEquals(0, environment.value("v.changed"));
    }

    @Test
    void compiledExpressionsKeepTheirSourceSnapshotButReadLiveVariables() {
        AnimationClip.VectorValue vector = expression("v.pose");
        AnimationClip.Track source = new AnimationClip.Track();
        source.keyframes().add(key(0, vector));
        source.keyframes().add(key(1, vector));
        CompiledAnimationTrack compiled = CompiledAnimationTrack.compile(source);
        vector.setExpression(0, "v.other");
        TestEnvironment environment = new TestEnvironment();
        environment.put("v.pose", 12);
        environment.put("v.other", 99);
        CompiledAnimationTrack.Sampler sampler = new CompiledAnimationTrack.Sampler();
        double[] target = new double[3];
        assertFalse(sampler.sample(compiled, 0.5F, environment, target));
        assertEquals(12, target[0]);
        environment.put("v.pose", 24);
        assertFalse(sampler.sample(compiled, 0.5F, environment, target));
        assertEquals(24, target[0]);
        assertEquals(0, sampler.allocatedCacheCapacity());
    }

    @Test
    void upperBoundKeepsEndpointDuplicateAndIncomingValueSemantics() {
        AnimationClip.Track source = numericTrack(new float[]{0, 1, 1, 2}, 0, 10, 30, 40);
        CompiledAnimationTrack compiled = CompiledAnimationTrack.compile(source);
        assertSampleX(compiled, -1, 0);
        assertSampleX(compiled, 0, 0);
        assertSampleX(compiled, 0.5F, 5);
        assertSampleX(compiled, 1, 30);
        assertSampleX(compiled, 1.5F, 35);
        assertSampleX(compiled, 2, 40);
        assertSampleX(compiled, 3, 40);

        AnimationClip.Track prePost = numericTrack(new float[]{0, 1}, 0, 100);
        prePost.keyframes().set(1, new AnimationClip.Keyframe(1,
                AnimationClip.Interpolation.LINEAR, value(100), value(10)));
        CompiledAnimationTrack prePostCompiled = CompiledAnimationTrack.compile(prePost);
        assertSampleX(prePostCompiled, 0.5F, 5);
        assertSampleX(prePostCompiled, 1, 100);
        prePost.keyframes().set(1, new AnimationClip.Keyframe(1,
                AnimationClip.Interpolation.STEP, value(100), value(10)));
        assertSampleX(CompiledAnimationTrack.compile(prePost), 0.5F, 0);
    }

    @Test
    void unsortedTransferredKeysRetainTheOriginalLinearPrefixBehavior() {
        CompiledAnimationTrack compiled = CompiledAnimationTrack.compile(numericTrack(
                new float[]{0, 2, 1, 3}, 0, 20, 90, 30));
        assertSampleX(compiled, 1.5F, 15);
        assertSampleX(compiled, 2.5F, 45);
    }

    @Test
    void nonFiniteTimeKeepsExistingEndpointsButNeverEntersTheCache() {
        CompiledAnimationTrack compiled = CompiledAnimationTrack.compile(numericTrack(
                new float[]{0, 1}, 10, 20));
        CompiledAnimationTrack.Sampler sampler = new CompiledAnimationTrack.Sampler();
        double[] target = new double[3];
        for (float time : new float[]{Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY}) {
            assertFalse(sampler.sample(compiled, time, new TestEnvironment(), target));
            assertFalse(sampler.sample(compiled, time, new TestEnvironment(), target));
            assertEquals(time == Float.POSITIVE_INFINITY ? 20 : 10, target[0]);
        }
        assertEquals(0, sampler.allocatedCacheCapacity());
    }

    @Test
    void numericStringsQueriesAndRandomExpressionsAreNotClassifiedAsNumericTracks() {
        for (String expression : new String[]{"0", "q.is_first_person", "math.random(0,1)"}) {
            AnimationClip.Track source = new AnimationClip.Track();
            source.keyframes().add(key(0, expression(expression)));
            source.keyframes().add(key(1, expression(expression)));
            CompiledAnimationTrack compiled = CompiledAnimationTrack.compile(source);
            CompiledAnimationTrack.Sampler sampler = new CompiledAnimationTrack.Sampler();
            TestEnvironment environment = new TestEnvironment();
            double[] target = new double[3];
            assertFalse(sampler.sample(compiled, 0.5F, environment, target));
            environment.query = 1;
            assertFalse(sampler.sample(compiled, 0.5F, environment, target));
            if (expression.startsWith("q.")) assertEquals(1, target[0]);
            if (expression.startsWith("math.")) assertEquals(4, environment.randomCalls);
            assertEquals(0, sampler.allocatedCacheCapacity(), expression);
        }
    }

    @Test
    void expressionsInIncomingOrCatmullNeighborsPreventReuseAndKeepTheirOrder() {
        AnimationClip.Track source = new AnimationClip.Track();
        source.keyframes().add(key(0, expression("v.trace=v.trace*10+1")));
        source.keyframes().add(key(1, expression("v.trace=v.trace*10+2")));
        source.keyframes().add(new AnimationClip.Keyframe(2,
                AnimationClip.Interpolation.CATMULL_ROM, value(30),
                expression("v.trace=v.trace*10+9")));
        source.keyframes().add(key(3, expression("v.trace=v.trace*10+4")));
        CompiledAnimationTrack compiled = CompiledAnimationTrack.compile(source);
        CompiledAnimationTrack.Sampler sampler = new CompiledAnimationTrack.Sampler();
        TestEnvironment environment = new TestEnvironment();
        double[] target = new double[3];
        assertFalse(sampler.sample(compiled, 1.5F, environment, target));
        assertEquals(2914, environment.value("v.trace"));
        assertFalse(sampler.sample(compiled, 1.5F, environment, target));
        assertEquals(29142914, environment.value("v.trace"));
        assertEquals(0, sampler.allocatedCacheCapacity());

        AnimationClip.Track incomingOnly = numericTrack(new float[]{0, 1}, 0, 10);
        incomingOnly.keyframes().set(1, new AnimationClip.Keyframe(1,
                AnimationClip.Interpolation.LINEAR, value(10), expression("v.incoming+=1")));
        compiled = CompiledAnimationTrack.compile(incomingOnly);
        assertFalse(sampler.sample(compiled, 0.5F, environment, target));
        assertFalse(sampler.sample(compiled, 0.5F, environment, target));
        assertEquals(2, environment.value("v.incoming"));
    }

    @Test
    void expressionsStillEvaluateAxesInOrderOnEveryCall() {
        AnimationClip.VectorValue vector = new AnimationClip.VectorValue();
        for (int axis = 0; axis < 3; axis++) {
            vector.setExpression(axis, "v.trace=v.trace*10+" + (axis + 1));
        }
        AnimationClip.Track source = new AnimationClip.Track();
        source.keyframes().add(key(0, vector));
        CompiledAnimationTrack compiled = CompiledAnimationTrack.compile(source);
        CompiledAnimationTrack.Sampler sampler = new CompiledAnimationTrack.Sampler();
        TestEnvironment environment = new TestEnvironment();
        double[] target = new double[3];
        assertFalse(sampler.sample(compiled, 0, environment, target));
        assertArrayEquals(new double[]{1, 12, 123}, target);
        assertFalse(sampler.sample(compiled, 0, environment, target));
        assertEquals(123123, environment.value("v.trace"));
    }

    @Test
    void numericCatmullInterpolationMatchesTheOriginalPolynomial() {
        AnimationClip.Track source = numericTrack(new float[]{0, 1, 2, 3}, 0, 2, 8, 12);
        source.keyframes().set(2, new AnimationClip.Keyframe(2,
                AnimationClip.Interpolation.CATMULL_ROM, value(8), null));
        CompiledAnimationTrack compiled = CompiledAnimationTrack.compile(source);
        assertSampleX(compiled, 1.5F, 4.875);
    }

    private static void assertSampleX(CompiledAnimationTrack track, float time, double expected) {
        double[] target = new double[3];
        new CompiledAnimationTrack.Sampler().sample(track, time, new TestEnvironment(), target);
        assertEquals(expected, target[0], 0.0000001D);
    }

    private static AnimationClip.Track numericTrack(float[] times, double... values) {
        AnimationClip.Track track = new AnimationClip.Track();
        for (int index = 0; index < times.length; index++) track.keyframes().add(key(times[index], value(values[index])));
        return track;
    }

    private static AnimationClip.Keyframe key(float time, AnimationClip.VectorValue value) {
        return new AnimationClip.Keyframe(time, AnimationClip.Interpolation.LINEAR, value, null);
    }

    private static AnimationClip.VectorValue value(double x) {
        AnimationClip.VectorValue value = new AnimationClip.VectorValue();
        value.setConstant(0, x);
        value.setConstant(1, x * 2);
        value.setConstant(2, -x);
        return value;
    }

    private static AnimationClip.VectorValue expression(String expression) {
        AnimationClip.VectorValue value = value(0);
        value.setExpression(0, expression);
        return value;
    }

    private static final class TestEnvironment implements ExpressionEngine.Environment {
        private final Map<Integer, Double> variables = new HashMap<>();
        private double query;
        private int randomCalls;

        void put(String name, double value) { variables.put(ExpressionEngine.slot(name), value); }
        double value(String name) { return readVariable(ExpressionEngine.slot(name)); }
        @Override public double readVariable(int slot) { return variables.getOrDefault(slot, 0.0D); }
        @Override public boolean hasVariable(int slot) { return variables.containsKey(slot); }
        @Override public void writeVariable(int slot, double value) { variables.put(slot, value); }
        @Override public double readQuery(int slot) { return query; }
        @Override public double invoke(String name, double[] arguments) {
            return name.equals("math.random") ? ++randomCalls : 0;
        }
        @Override public double invokeWithText(String name, String[] arguments) { return 0; }
    }
}
