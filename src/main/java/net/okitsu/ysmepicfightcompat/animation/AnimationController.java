package net.okitsu.ysmepicfightcompat.animation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable official-YSM/Bedrock animation-controller input. */
public final class AnimationController {
    public record AnimationReference(String name, String weightExpression) {
        public AnimationReference {
            name = name == null ? "" : name;
            weightExpression = weightExpression == null || weightExpression.isBlank()
                    ? "1" : weightExpression;
        }
    }

    public record Transition(String targetState, String conditionExpression) {
        public Transition {
            targetState = targetState == null ? "" : targetState;
            conditionExpression = conditionExpression == null ? "" : conditionExpression;
        }
    }

    public record BlendPoint(float time, float value) {
    }

    public static final class BlendTransition {
        private final float duration;
        private final List<BlendPoint> curve;

        public BlendTransition(float duration, List<BlendPoint> curve) {
            this.duration = Float.isFinite(duration) && duration > 0.0F ? duration : 0.0F;
            this.curve = curve == null ? List.of() : List.copyOf(curve);
        }

        public float duration() {
            return curve.isEmpty() ? duration : Math.max(0.0F, curve.get(curve.size() - 1).time());
        }

        public float fixedDuration() {
            return duration;
        }

        public List<BlendPoint> curve() {
            return curve;
        }

        public float progress(double elapsed) {
            float length = duration();
            if (length <= 0.0F || elapsed >= length) {
                return 1.0F;
            }
            if (elapsed <= 0.0D) {
                return curve.isEmpty() ? 0.0F : curveProgress(curve.get(0).value());
            }
            if (curve.isEmpty()) {
                return finiteUnit((float) (elapsed / length));
            }
            BlendPoint left = curve.get(0);
            if (elapsed <= left.time()) {
                return curveProgress(left.value());
            }
            for (int index = 1; index < curve.size(); index++) {
                BlendPoint right = curve.get(index);
                if (elapsed <= right.time()) {
                    float width = right.time() - left.time();
                    float alpha = width <= 0.0F ? 1.0F
                            : (float) ((elapsed - left.time()) / width);
                    return curveProgress(
                            left.value() + (right.value() - left.value()) * alpha);
                }
                left = right;
            }
            return curveProgress(left.value());
        }

        private static float curveProgress(float value) {
            // Bedrock/YSM curve values describe how much of the previous state remains.
            // The evaluator needs the complementary weight for the state being entered.
            return finiteUnit(1.0F - value);
        }

        private static float finiteUnit(float value) {
            return Float.isFinite(value) ? Math.max(0.0F, Math.min(1.0F, value)) : 0.0F;
        }
    }

    public static final class State {
        private final String name;
        private final List<AnimationReference> animations;
        private final List<Transition> transitions;
        private final List<String> onEntry;
        private final List<String> onExit;
        private final List<String> soundEffects;
        private final BlendTransition blendTransition;
        private final boolean blendViaShortestPath;

        public State(String name, List<AnimationReference> animations,
                     List<Transition> transitions, List<String> onEntry,
                     List<String> onExit, BlendTransition blendTransition,
                     boolean blendViaShortestPath) {
            this(name, animations, transitions, onEntry, onExit, List.of(),
                    blendTransition, blendViaShortestPath);
        }

        public State(String name, List<AnimationReference> animations,
                     List<Transition> transitions, List<String> onEntry,
                     List<String> onExit, List<String> soundEffects,
                     BlendTransition blendTransition,
                     boolean blendViaShortestPath) {
            this.name = name == null ? "" : name;
            this.animations = animations == null ? List.of() : List.copyOf(animations);
            this.transitions = transitions == null ? List.of() : List.copyOf(transitions);
            this.onEntry = onEntry == null ? List.of() : List.copyOf(onEntry);
            this.onExit = onExit == null ? List.of() : List.copyOf(onExit);
            this.soundEffects = soundEffects == null ? List.of() : List.copyOf(soundEffects);
            this.blendTransition = blendTransition == null
                    ? new BlendTransition(0.0F, List.of()) : blendTransition;
            this.blendViaShortestPath = blendViaShortestPath;
        }

        public String name() {
            return name;
        }

        public List<AnimationReference> animations() {
            return animations;
        }

        public List<Transition> transitions() {
            return transitions;
        }

        public List<String> onEntry() {
            return onEntry;
        }

        public List<String> onExit() {
            return onExit;
        }

        public List<String> soundEffects() {
            return soundEffects;
        }

        public BlendTransition blendTransition() {
            return blendTransition;
        }

        public boolean blendViaShortestPath() {
            return blendViaShortestPath;
        }
    }

    private final String name;
    private final String initialState;
    private final Map<String, State> states;

    public AnimationController(String name, String initialState, Map<String, State> states) {
        this.name = name == null ? "" : name;
        this.initialState = initialState == null ? "" : initialState;
        this.states = states == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(states));
    }

    public String name() {
        return name;
    }

    public String initialState() {
        return initialState;
    }

    public Map<String, State> states() {
        return states;
    }
}
