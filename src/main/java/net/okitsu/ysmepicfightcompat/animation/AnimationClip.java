package net.okitsu.ysmepicfightcompat.animation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Data needed to evaluate one Bedrock/YSM animation without retaining its source JSON. */
public final class AnimationClip {
    public enum Playback {
        ONCE(0), REPEAT(1), HOLD_LAST_FRAME(3);

        private final int wireValue;

        Playback(int wireValue) {
            this.wireValue = wireValue;
        }

        public int wireValue() {
            return wireValue;
        }

        public static Playback fromWireValue(int value) {
            return switch (value) {
                case 1 -> REPEAT;
                case 3 -> HOLD_LAST_FRAME;
                default -> ONCE;
            };
        }
    }

    public enum Interpolation {
        LINEAR(0), STEP(1), CATMULL_ROM(2);

        private final int wireValue;

        Interpolation(int wireValue) {
            this.wireValue = wireValue;
        }

        public int wireValue() {
            return wireValue;
        }

        public static Interpolation fromWireValue(int value) {
            return switch (value) {
                case 1 -> STEP;
                case 2 -> CATMULL_ROM;
                default -> LINEAR;
            };
        }
    }

    public static final class VectorValue {
        private final String[] expressions = new String[3];
        private final double[] constants = new double[3];

        public void setConstant(int axis, double value) {
            expressions[axis] = null;
            constants[axis] = value;
        }

        public void setExpression(int axis, String source) {
            expressions[axis] = source;
            constants[axis] = 0.0D;
        }

        public String expression(int axis) {
            return expressions[axis];
        }

        public double constant(int axis) {
            return constants[axis];
        }
    }

    public record Keyframe(float time, Interpolation interpolation,
                           VectorValue value, VectorValue incomingValue) {
    }

    public static final class Track {
        private final List<Keyframe> keyframes = new ArrayList<>();

        public List<Keyframe> keyframes() {
            return keyframes;
        }
    }

    public static final class BoneTracks {
        private Track rotation;
        private Track position;
        private Track scale;

        public Track rotation() {
            return rotation;
        }

        public void rotation(Track value) {
            rotation = value;
        }

        public Track position() {
            return position;
        }

        public void position(Track value) {
            position = value;
        }

        public Track scale() {
            return scale;
        }

        public void scale(Track value) {
            scale = value;
        }

        public boolean hasAnyTrack() {
            return rotation != null || position != null || scale != null;
        }
    }

    public record TimelineEvent(float time, List<String> statements) {
        public TimelineEvent {
            statements = List.copyOf(statements);
        }
    }

    private final String name;
    private Playback playback = Playback.ONCE;
    private float duration;
    private final Map<String, BoneTracks> boneTracks = new LinkedHashMap<>();
    private final List<TimelineEvent> timeline = new ArrayList<>();

    public AnimationClip(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    public Playback playback() {
        return playback;
    }

    public void playback(Playback value) {
        playback = value;
    }

    public float duration() {
        return duration;
    }

    public void duration(float value) {
        duration = value;
    }

    public Map<String, BoneTracks> boneTracks() {
        return boneTracks;
    }

    public List<TimelineEvent> timeline() {
        return timeline;
    }
}
