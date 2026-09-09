package net.okitsu.ysmepicfightcompat.animation;

import java.util.List;

/** Immutable sampling data; the parsed model's mutable DTOs are never cached by identity. */
final class CompiledAnimationTrack {
    private final float[] times;
    private final AnimationClip.Interpolation[] interpolations;
    private final double[] values;
    private final ExpressionEngine.Expression[] expressions;
    private final boolean[] hasIncoming;
    private final double[] incomingValues;
    private final ExpressionEngine.Expression[] incomingExpressions;
    private final boolean ordered;
    private final boolean numericOnly;

    static CompiledAnimationTrack compile(AnimationClip.Track track) {
        return track == null ? null : new CompiledAnimationTrack(track.keyframes());
    }

    private CompiledAnimationTrack(List<AnimationClip.Keyframe> keys) {
        int count = keys.size();
        times = new float[count];
        interpolations = new AnimationClip.Interpolation[count];
        values = new double[Math.multiplyExact(count, 3)];
        boolean withExpressions = false;
        boolean withIncoming = false;
        boolean withIncomingExpressions = false;
        for (AnimationClip.Keyframe key : keys) {
            withExpressions |= hasExpressions(key.value());
            withIncoming |= key.incomingValue() != null;
            withIncomingExpressions |= hasExpressions(key.incomingValue());
        }
        expressions = withExpressions ? new ExpressionEngine.Expression[values.length] : null;
        hasIncoming = withIncoming ? new boolean[count] : null;
        incomingValues = withIncoming ? new double[values.length] : null;
        incomingExpressions = withIncomingExpressions
                ? new ExpressionEngine.Expression[values.length] : null;
        numericOnly = !withExpressions && !withIncomingExpressions;
        boolean sorted = true;
        for (int index = 0; index < count; index++) {
            AnimationClip.Keyframe key = keys.get(index);
            times[index] = key.time();
            interpolations[index] = key.interpolation();
            sorted &= !Float.isNaN(key.time())
                    && (index == 0 || key.time() >= times[index - 1]);
            copyVector(key.value(), index, values, expressions);
            if (key.incomingValue() != null) {
                hasIncoming[index] = true;
                copyVector(key.incomingValue(), index, incomingValues, incomingExpressions);
            }
        }
        ordered = sorted;
    }

    private static boolean hasExpressions(AnimationClip.VectorValue value) {
        if (value == null) return false;
        for (int axis = 0; axis < 3; axis++) {
            if (value.expression(axis) != null) return true;
        }
        return false;
    }

    private static void copyVector(AnimationClip.VectorValue source, int index,
                                   double[] constants, ExpressionEngine.Expression[] compiled) {
        for (int axis = 0; axis < 3; axis++) {
            int offset = index * 3 + axis;
            constants[offset] = source.constant(axis);
            String expression = source.expression(axis);
            if (expression != null) compiled[offset] = source.compiledExpression(axis);
        }
    }

    private int upperBound(float time) {
        if (!ordered) {
            // Transfer DTOs need not be sorted. Preserve the old prefix scan's
            // behavior instead of silently reordering authored keys.
            int right = 0;
            while (right < times.length && times[right] <= time) right++;
            return right;
        }
        int low = 0;
        int high = times.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (times[middle] <= time) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    private void sampleUncached(float time, ExpressionEngine.Environment environment,
                                double[] target, Sampler scratch) {
        int right = upperBound(time);
        if (right == 0) {
            evaluateVector(0, false, environment, target);
            return;
        }
        if (right >= times.length) {
            evaluateVector(times.length - 1, false, environment, target);
            return;
        }
        int left = right - 1;
        if (interpolations[right] == AnimationClip.Interpolation.STEP
                || times[right] <= times[left]) {
            evaluateVector(left, false, environment, target);
            return;
        }
        // Keep float division and the original expression evaluation order.
        double alpha = Math.max(0.0D, Math.min(1.0D,
                (time - times[left]) / (times[right] - times[left])));
        evaluateVector(left, false, environment, scratch.p1);
        evaluateVector(right, hasIncoming != null && hasIncoming[right], environment, scratch.p2);
        if (interpolations[right] == AnimationClip.Interpolation.CATMULL_ROM) {
            evaluateVector(Math.max(0, left - 1), false, environment, scratch.p0);
            evaluateVector(Math.min(times.length - 1, right + 1), false, environment, scratch.p3);
            for (int axis = 0; axis < 3; axis++) {
                target[axis] = catmullRom(scratch.p0[axis], scratch.p1[axis],
                        scratch.p2[axis], scratch.p3[axis], alpha);
            }
        } else {
            for (int axis = 0; axis < 3; axis++) {
                target[axis] = scratch.p1[axis]
                        + (scratch.p2[axis] - scratch.p1[axis]) * alpha;
            }
        }
    }

    private void evaluateVector(int index, boolean incoming,
                                ExpressionEngine.Environment environment, double[] target) {
        double[] constants = incoming ? incomingValues : values;
        ExpressionEngine.Expression[] compiled = incoming ? incomingExpressions : expressions;
        for (int axis = 0; axis < 3; axis++) {
            int offset = index * 3 + axis;
            ExpressionEngine.Expression expression = compiled == null ? null : compiled[offset];
            target[axis] = expression == null ? constants[offset] : expression.evaluate(environment);
        }
    }

    private static double catmullRom(double a, double b, double c, double d, double time) {
        double squared = time * time;
        double cubed = squared * time;
        return 0.5D * (2.0D * b + (-a + c) * time
                + (2.0D * a - 5.0D * b + 4.0D * c - d) * squared
                + (-a + 3.0D * b - 3.0D * c + d) * cubed);
    }

    /** Owned by one evaluation scratch, never shared with an active animation worker. */
    static final class Sampler {
        private static final int DEFAULT_CAPACITY = 1_024;
        private final int capacity;
        private final double[] p0 = new double[3];
        private final double[] p1 = new double[3];
        private final double[] p2 = new double[3];
        private final double[] p3 = new double[3];
        private CompiledAnimationTrack[] cachedTracks;
        private int[] cachedTimes;
        private double[] cachedValues;

        Sampler() {
            this(DEFAULT_CAPACITY);
        }

        Sampler(int capacity) {
            if (capacity < 1 || capacity > DEFAULT_CAPACITY
                    || (capacity & (capacity - 1)) != 0) {
                throw new IllegalArgumentException("Sample cache capacity must be a power of two up to 1024");
            }
            this.capacity = capacity;
        }

        /** Returns whether only a previously sampled numeric XYZ value was reused. */
        boolean sample(CompiledAnimationTrack track, float time,
                       ExpressionEngine.Environment environment, double[] target) {
            boolean cacheable = track.numericOnly && track.times.length > 1 && Float.isFinite(time);
            int slot = cacheable ? System.identityHashCode(track) & (capacity - 1) : 0;
            int timeBits = cacheable ? Float.floatToRawIntBits(time) : 0;
            if (cacheable && cachedTracks != null && cachedTracks[slot] == track
                    && cachedTimes[slot] == timeBits) {
                System.arraycopy(cachedValues, slot * 3, target, 0, 3);
                return true;
            }
            track.sampleUncached(time, environment, target, this);
            if (cacheable) {
                if (cachedTracks == null) {
                    cachedTracks = new CompiledAnimationTrack[capacity];
                    cachedTimes = new int[capacity];
                    cachedValues = new double[capacity * 3];
                }
                cachedTracks[slot] = track;
                cachedTimes[slot] = timeBits;
                System.arraycopy(target, 0, cachedValues, slot * 3, 3);
            }
            return false;
        }

        int allocatedCacheCapacity() {
            return cachedTracks == null ? 0 : cachedTracks.length;
        }
    }
}
