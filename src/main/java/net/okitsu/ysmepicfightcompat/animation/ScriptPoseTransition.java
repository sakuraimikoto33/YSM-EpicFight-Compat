package net.okitsu.ysmepicfightcompat.animation;

import java.util.Arrays;
import java.util.Objects;

/**
 * Blends one script channel's rotation/position effect, never an already composed pose.
 * Each track is an affine operation {@code base * coefficient + offset}. Keeping only
 * that operation lets earlier layers keep moving without applying their old pose twice.
 * Values use the caller's units; scales, visibility, clocks and script output are not owned here.
 */
final class ScriptPoseTransition {
    private static final int MAX_BONES = 65_536;

    private final int boneCount;
    private final Track rotation;
    private final Track position;
    private long generation;
    private boolean initialized;
    private boolean collecting;
    private float progress;
    private boolean shortestRotation;

    ScriptPoseTransition(int boneCount) {
        if (boneCount < 0 || boneCount > MAX_BONES) {
            throw new IllegalArgumentException("Invalid transition bone count: " + boneCount);
        }
        this.boneCount = boneCount;
        rotation = new Track(boneCount);
        position = new Track(boneCount);
    }

    /** Start one sample. The source stays frozen until a new generation or an explicit discard. */
    void begin(long generation, float progress, boolean discardPrevious) {
        begin(generation, progress, discardPrevious, false);
    }

    void begin(long generation, float progress, boolean discardPrevious, boolean shortestRotation) {
        if (!initialized || this.generation != generation || discardPrevious) {
            rotation.freezeSource(discardPrevious);
            position.freezeSource(discardPrevious);
        }
        this.generation = generation;
        this.shortestRotation = shortestRotation;
        initialized = true;
        this.progress = Float.isFinite(progress) ? Math.max(0.0F, Math.min(1.0F, progress)) : 1.0F;
        rotation.incoming.reset();
        position.incoming.reset();
        collecting = true;
    }

    void rotation(int index, float[] target, float weight, boolean additive) {
        requireCollecting();
        float finiteWeight = finite(weight);
        rotation.record(index, target, additive ? 1.0F : 1.0F - finiteWeight, finiteWeight);
    }

    void position(int index, float[] target, float weight, boolean parallel) {
        requireCollecting();
        float finiteWeight = finite(weight);
        // Existing parallel position tracks replace the previous position with their
        // weighted value, unlike parallel rotation tracks, which add to it.
        position.record(index, target, parallel ? 0.0F : 1.0F - finiteWeight, finiteWeight);
    }

    /**
     * Apply once after collecting this channel, before subsequent layers. A declared
     * incoming track retains ownership even at zero progress/weight. An outgoing-only
     * track releases ownership at progress one without altering the earlier layer's flags.
     */
    boolean apply(float[][] rotations, boolean[] hasRotation,
                  float[][] positions, boolean[] hasPosition) {
        return apply(rotations, hasRotation, positions, hasPosition, 1.0F);
    }

    /** Attenuate the completed entry blend toward identity, for an independent STOP fade. */
    boolean apply(float[][] rotations, boolean[] hasRotation,
                  float[][] positions, boolean[] hasPosition, float outputWeight) {
        requireCollecting();
        if (rotations.length != boneCount || hasRotation.length != boneCount
                || positions.length != boneCount || hasPosition.length != boneCount) {
            throw new IllegalArgumentException("Transition pose dimensions do not match");
        }
        float boundedWeight = Math.max(0.0F, Math.min(1.0F, finite(outputWeight)));
        boolean appliedRotation = rotation.apply(rotations, hasRotation, progress, boundedWeight, shortestRotation);
        boolean appliedPosition = position.apply(positions, hasPosition, progress, boundedWeight, false);
        collecting = false;
        return appliedRotation || appliedPosition;
    }

    /** Whether this layer contributed the rotation track in the most recent apply. */
    boolean hasRotation(int index) {
        return rotation.displayed.specified[index];
    }

    /** Whether this layer contributed the position track in the most recent apply. */
    boolean hasPosition(int index) {
        return position.displayed.specified[index];
    }

    /**
     * Append this layer's last displayed effect after the destination's collected
     * effects, without resampling a pose or changing this layer's transition history.
     * The destination must have begun a sample and use the same bone layout size.
     */
    void appendDisplayedTo(ScriptPoseTransition destination) {
        Objects.requireNonNull(destination, "Transition destination");
        if (destination.boneCount != boneCount) {
            throw new IllegalArgumentException("Transition bone counts do not match");
        }
        destination.requireCollecting();
        rotation.appendDisplayedTo(destination.rotation);
        position.appendDisplayedTo(destination.position);
    }

    private void requireCollecting() {
        if (!collecting) {
            throw new IllegalStateException("Begin a transition sample before collecting or applying it");
        }
    }

    private static float finite(float value) {
        return Float.isFinite(value) ? value : 0.0F;
    }

    private static float mix(float from, float to, float progress) {
        if (progress == 0.0F) return from;
        if (progress == 1.0F) return to;
        return finite((float) ((double) from * (1.0D - progress) + (double) to * progress));
    }

    private static final class Effect {
        private final float[] coefficient;
        private final float[] offset;
        private final boolean[] specified;

        private Effect(int boneCount) {
            coefficient = new float[boneCount];
            offset = new float[boneCount * 3];
            specified = new boolean[boneCount];
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

    private static final class Track {
        private final Effect source;
        private final Effect incoming;
        private final Effect displayed;

        private Track(int boneCount) {
            source = new Effect(boneCount);
            incoming = new Effect(boneCount);
            displayed = new Effect(boneCount);
        }

        private void freezeSource(boolean discardPrevious) {
            if (discardPrevious) source.reset();
            else source.copyFrom(displayed);
        }

        private void record(int index, float[] target, float coefficient, float weight) {
            Objects.checkIndex(index, incoming.specified.length);
            if (target.length < 3) {
                throw new IllegalArgumentException("A transition track requires three axes");
            }
            // Compose repeated writes to the same bone in their original evaluation order.
            incoming.coefficient[index] = finite(coefficient * incoming.coefficient[index]);
            for (int axis = 0; axis < 3; axis++) {
                int component = index * 3 + axis;
                incoming.offset[component] = finite((float) (
                        (double) coefficient * incoming.offset[component]
                                + (double) finite(target[axis]) * weight));
            }
            incoming.specified[index] = true;
        }

        private void appendDisplayedTo(Track destination) {
            for (int index = 0; index < displayed.specified.length; index++) {
                if (!displayed.specified[index]) {
                    continue;
                }
                float coefficient = displayed.coefficient[index];
                destination.incoming.coefficient[index] = finite(
                        coefficient * destination.incoming.coefficient[index]);
                for (int axis = 0; axis < 3; axis++) {
                    int component = index * 3 + axis;
                    destination.incoming.offset[component] = finite((float) (
                            (double) coefficient * destination.incoming.offset[component]
                                    + displayed.offset[component]));
                }
                destination.incoming.specified[index] = true;
            }
        }

        private boolean apply(float[][] values, boolean[] present, float progress, float outputWeight,
                              boolean shortestRotation) {
            boolean applied = false;
            for (int index = 0; index < incoming.specified.length; index++) {
                boolean specified = incoming.specified[index]
                        || progress < 1.0F && source.specified[index];
                displayed.specified[index] = specified;
                if (!specified) {
                    displayed.coefficient[index] = 1.0F;
                    Arrays.fill(displayed.offset, index * 3, index * 3 + 3, 0.0F);
                    continue;
                }
                if (values[index].length < 3) {
                    throw new IllegalArgumentException("A transition pose requires three axes");
                }
                float coefficient = mix(1.0F,
                        mix(source.coefficient[index], incoming.coefficient[index], progress), outputWeight);
                displayed.coefficient[index] = coefficient;
                for (int axis = 0; axis < 3; axis++) {
                    int component = index * 3 + axis;
                    float base = present[index] ? finite(values[index][axis]) : 0.0F;
                    float incomingOffset = incoming.offset[component];
                    if (shortestRotation && progress > 0.0F && progress < 1.0F) {
                        double from = (double) base * source.coefficient[index] + source.offset[component];
                        double to = (double) base * incoming.coefficient[index] + incomingOffset;
                        double difference = Math.IEEEremainder(to - from, Math.PI * 2.0D);
                        incomingOffset = finite((float) (from + difference
                                - (double) base * incoming.coefficient[index]));
                    }
                    float offset = mix(0.0F,
                            mix(source.offset[component], incomingOffset, progress), outputWeight);
                    displayed.offset[component] = offset;
                    values[index][axis] = finite((float) ((double) base * coefficient + offset));
                }
                present[index] = true;
                applied = true;
            }
            return applied;
        }
    }
}
