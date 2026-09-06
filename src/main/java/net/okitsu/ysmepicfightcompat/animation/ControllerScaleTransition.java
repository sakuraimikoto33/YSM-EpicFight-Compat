package net.okitsu.ysmepicfightcompat.animation;

import java.util.Arrays;
import java.util.Objects;

/** Numeric scale history for one Bedrock slot; preceding layers remain live. */
final class ControllerScaleTransition {
    private static final int MAX_TRACKS = 65_536;

    private final int count;
    private final float[] base;
    private final boolean[] basePresent;
    private final Effect source;
    private final Effect incoming;
    private final Effect displayed;
    private long generation;
    private float progress;
    private boolean initialized;
    private boolean collecting;

    ControllerScaleTransition(int count) {
        if (count < 0 || count > MAX_TRACKS) {
            throw new IllegalArgumentException("Scale transition track count is out of bounds");
        }
        this.count = count;
        base = new float[count * 3];
        basePresent = new boolean[count];
        source = new Effect(count);
        incoming = new Effect(count);
        displayed = new Effect(count);
    }

    void begin(long generation, float progress, float[][] values, boolean[] present) {
        validate(values, present);
        if (!initialized || this.generation != generation) source.copyFrom(displayed);
        this.generation = generation;
        this.progress = Float.isFinite(progress) ? Math.max(0.0F, Math.min(1.0F, progress)) : 1.0F;
        initialized = true;
        for (int index = 0; index < count; index++) {
            basePresent[index] = present[index];
            for (int axis = 0; axis < 3; axis++) {
                base[index * 3 + axis] = present[index] ? finite(values[index][axis]) : 1.0F;
            }
        }
        incoming.reset();
        collecting = true;
    }

    /** Record the final scale produced by this slot, not an additional scale factor. */
    void record(int index, float[] evaluatedScale) {
        requireCollecting();
        Objects.checkIndex(index, count);
        if (evaluatedScale == null || evaluatedScale.length < 3) {
            throw new IllegalArgumentException("A scale transition track requires three axes");
        }
        incoming.specified[index] = true;
        incoming.coefficient[index] = 0.0F;
        for (int axis = 0; axis < 3; axis++) {
            incoming.offset[index * 3 + axis] = finite(evaluatedScale[axis]);
        }
    }

    /** Restore the saved live base before applying this slot exactly once. */
    boolean apply(float[][] values, boolean[] present) {
        requireCollecting();
        validate(values, present);
        boolean applied = false;
        for (int index = 0; index < count; index++) {
            System.arraycopy(base, index * 3, values[index], 0, 3);
            present[index] = basePresent[index];
            boolean specified = incoming.specified[index]
                    || progress < 1.0F && source.specified[index];
            displayed.specified[index] = specified;
            if (!specified) {
                displayed.coefficient[index] = 1.0F;
                Arrays.fill(displayed.offset, index * 3, index * 3 + 3, 0.0F);
                continue;
            }
            float coefficient = mix(source.coefficient[index], incoming.coefficient[index], progress);
            displayed.coefficient[index] = coefficient;
            for (int axis = 0; axis < 3; axis++) {
                int component = index * 3 + axis;
                float offset = mix(source.offset[component], incoming.offset[component], progress);
                displayed.offset[component] = offset;
                values[index][axis] = finite((float) ((double) base[component] * coefficient + offset));
            }
            present[index] = true;
            applied = true;
        }
        collecting = false;
        return applied;
    }

    private void validate(float[][] values, boolean[] present) {
        if (values == null || present == null || values.length != count || present.length != count) {
            throw new IllegalArgumentException("Scale transition dimensions do not match");
        }
        for (float[] value : values) {
            if (value == null || value.length < 3) {
                throw new IllegalArgumentException("A scale transition pose requires three axes");
            }
        }
    }

    boolean contributes(int index) {
        return displayed.specified[Objects.checkIndex(index, count)];
    }

    private void requireCollecting() {
        if (!collecting) throw new IllegalStateException("Begin a scale transition before recording or applying it");
    }

    private static float finite(float value) {
        return Float.isFinite(value) ? value : 1.0F;
    }

    private static float mix(float from, float to, float progress) {
        if (progress == 0.0F) return from;
        if (progress == 1.0F) return to;
        return finite((float) ((double) from * (1.0D - progress) + (double) to * progress));
    }

    /** The layer effect is scale = liveBase * coefficient + offset. */
    private static final class Effect {
        private final float[] coefficient;
        private final float[] offset;
        private final boolean[] specified;

        private Effect(int count) {
            coefficient = new float[count];
            offset = new float[count * 3];
            specified = new boolean[count];
            reset();
        }

        private void reset() {
            Arrays.fill(coefficient, 1.0F);
            Arrays.fill(offset, 0.0F);
            Arrays.fill(specified, false);
        }

        private void copyFrom(Effect other) {
            System.arraycopy(other.coefficient, 0, coefficient, 0, coefficient.length);
            System.arraycopy(other.offset, 0, offset, 0, offset.length);
            System.arraycopy(other.specified, 0, specified, 0, specified.length);
        }
    }
}
