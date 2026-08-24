package net.okitsu.ysmepicfightcompat.animation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Per-entity state-machine evaluator for the supported YSM controller subset. */
final class AnimationControllerProgram {
    private static final double EPSILON = 0.0001D;
    private static final int ANY_FINISHED = ExpressionEngine.querySlot(
            "query.any_animation_finished");
    private static final int ALL_FINISHED = ExpressionEngine.querySlot(
            "query.all_animations_finished");

    record ClipInfo(float duration, AnimationClip.Playback playback, boolean renderable) {
        ClipInfo(float duration, AnimationClip.Playback playback) {
            this(duration, playback, true);
        }
    }

    record ActiveAnimation(String controllerName, String instanceKey,
                           String name, double elapsed,
                           float weight, boolean blendViaShortestPath,
                           Map<Integer, Double> stateVariables) {
        ActiveAnimation {
            stateVariables = stateVariables == null ? Map.of() : Map.copyOf(stateVariables);
        }
    }

    static final class RuntimeState {
        private final Map<String, ControllerRuntime> controllers = new LinkedHashMap<>();

        void reset() {
            controllers.clear();
        }
    }

    private record Completion(boolean any, boolean all) {
        private static final Completion NONE = new Completion(false, false);
    }

    private record PreviousState(AnimationController.State state, double enteredAt,
                                 long generation, double transitionStartedAt,
                                 AnimationController.BlendTransition blend) {
    }

    private static final class ControllerRuntime {
        private AnimationController.State current;
        private double enteredAt;
        private long generation;
        private double lastStepAt = Double.NEGATIVE_INFINITY;
        private PreviousState previous;

        private void initialize(AnimationController controller, double now,
                                ControllerEnvironment environment) {
            current = initialState(controller);
            enteredAt = now;
            generation++;
            lastStepAt = now;
            environment.completion(Completion.NONE);
            environment.beginState(current);
            execute(current == null ? List.of() : current.onEntry(), environment);
            environment.playSounds(current == null ? List.of() : current.soundEffects());
            environment.playParticles(current == null
                    ? List.of() : current.particleEffects());
        }
    }

    private static final class ControllerEnvironment implements ExpressionEngine.Environment {
        private final ExpressionEngine.Environment delegate;
        private Completion completion = Completion.NONE;
        private String soundScope = "controller";
        private boolean outputsEnabled = true;
        private final Map<Integer, Double> stateVariables = new LinkedHashMap<>();

        private ControllerEnvironment(ExpressionEngine.Environment delegate) {
            this.delegate = delegate;
        }

        private void completion(Completion value) {
            completion = value == null ? Completion.NONE : value;
        }

        private void soundScope(String scope) {
            soundScope = scope;
            if (delegate instanceof EntityAnimationEnvironment entityEnvironment) {
                entityEnvironment.soundScope(scope);
            }
        }

        private void outputsEnabled(boolean value) {
            outputsEnabled = value;
        }

        private void stopOutputScope() {
            if (delegate instanceof EntityAnimationEnvironment entityEnvironment) {
                entityEnvironment.stopSoundScope(soundScope);
                entityEnvironment.stopParticleScope(soundScope);
            }
        }

        private void playSounds(List<String> effects) {
            if (outputsEnabled
                    && delegate instanceof EntityAnimationEnvironment entityEnvironment) {
                effects.forEach(entityEnvironment::playSoundEffect);
            }
        }

        private void playParticles(List<DeclarativeParticleEffect> effects) {
            if (outputsEnabled
                    && delegate instanceof EntityAnimationEnvironment entityEnvironment) {
                effects.forEach(effect -> entityEnvironment.playParticleEffect(effect, true));
            }
        }

        private void beginState(AnimationController.State state) {
            stateVariables.clear();
            if (state == null) {
                return;
            }
            for (AnimationController.StateVariable variable : state.variables()) {
                int slot = ExpressionEngine.slot(variable.name());
                double input = ExpressionEngine.compile(variable.inputExpression())
                        .evaluate(this);
                double value = variable.remap(input);
                stateVariables.put(slot, Double.isFinite(value) ? value : 0.0D);
            }
        }

        private Map<Integer, Double> stateVariables() {
            return Map.copyOf(stateVariables);
        }

        @Override
        public double readVariable(int slot) {
            return stateVariables.containsKey(slot)
                    ? stateVariables.get(slot) : delegate.readVariable(slot);
        }

        @Override
        public boolean hasVariable(int slot) {
            return stateVariables.containsKey(slot) || delegate.hasVariable(slot);
        }

        @Override
        public void writeVariable(int slot, double value) {
            if (stateVariables.containsKey(slot)) {
                stateVariables.put(slot, Double.isFinite(value) ? value : 0.0D);
            } else {
                delegate.writeVariable(slot, value);
            }
        }

        @Override
        public double readQuery(int slot) {
            if (slot == ANY_FINISHED) {
                return completion.any() ? 1.0D : 0.0D;
            }
            if (slot == ALL_FINISHED) {
                return completion.all() ? 1.0D : 0.0D;
            }
            return delegate.readQuery(slot);
        }

        @Override
        public double invoke(String name, double[] arguments) {
            return delegate.invoke(name, arguments);
        }

        @Override
        public double invokeWithText(String name, String[] arguments) {
            return delegate.invokeWithText(name, arguments);
        }

        @Override
        public double invokeWithMixedArguments(String name, String[] textArguments,
                                               double[] numericArguments) {
            return delegate.invokeWithMixedArguments(name, textArguments, numericArguments);
        }
    }

    private final Map<String, AnimationController> controllers;
    private final Map<String, ClipInfo> clips;

    AnimationControllerProgram(Map<String, AnimationController> controllers,
                               Map<String, ClipInfo> clips) {
        this(controllers, clips, Set.of());
    }

    AnimationControllerProgram(Map<String, AnimationController> controllers,
                               Map<String, ClipInfo> clips,
                               Set<String> allowedHandItemControllers) {
        Set<String> allowed = allowedHandItemControllers == null ? Set.of()
                : allowedHandItemControllers.stream()
                .map(AnimationControllerProgram::normalize)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<String, AnimationController> retained = new LinkedHashMap<>();
        if (controllers != null) {
            controllers.forEach((name, controller) -> {
                if (controller != null && !controller.states().isEmpty()
                        && (!isHandItemController(name)
                        || allowed.contains(normalize(name)))) {
                    retained.putIfAbsent(name, controller);
                }
            });
        }
        this.controllers = Collections.unmodifiableMap(new LinkedHashMap<>(retained));
        this.clips = clips == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(clips));
    }

    boolean isEmpty() {
        return controllers.isEmpty();
    }

    List<ActiveAnimation> select(double now, ExpressionEngine.Environment environment,
                                 RuntimeState runtimeState) {
        return select(now, environment, runtimeState, ignored -> true);
    }

    List<ActiveAnimation> select(double now, ExpressionEngine.Environment environment,
                                 RuntimeState runtimeState,
                                 Predicate<String> outputsEnabled) {
        if (controllers.isEmpty()) {
            return List.of();
        }
        ControllerEnvironment controllerEnvironment = new ControllerEnvironment(environment);
        List<ActiveAnimation> result = new ArrayList<>();
        for (Map.Entry<String, AnimationController> entry : controllers.entrySet()) {
            String controllerName = entry.getKey();
            AnimationController controller = entry.getValue();
            boolean enabled = outputsEnabled == null
                    || outputsEnabled.test(normalize(controllerName));
            controllerEnvironment.outputsEnabled(enabled);
            controllerEnvironment.soundScope("controller/" + controllerName);
            ControllerRuntime runtime = runtimeState.controllers.computeIfAbsent(
                    controllerName, ignored -> new ControllerRuntime());
            if (runtime.current == null) {
                runtime.initialize(controller, now, controllerEnvironment);
            } else if (now > runtime.lastStepAt + EPSILON) {
                advance(controller, runtime, now, controllerEnvironment);
            }
            if (enabled) {
                appendActive(result, controllerName, runtime, now, controllerEnvironment);
            }
        }
        return List.copyOf(result);
    }

    Set<String> activeKeys(List<ActiveAnimation> active) {
        Set<String> result = new LinkedHashSet<>();
        active.forEach(animation -> result.add(animation.instanceKey()));
        return result;
    }

    private void advance(AnimationController controller, ControllerRuntime runtime,
                         double now, ControllerEnvironment environment) {
        runtime.lastStepAt = now;
        if (runtime.previous != null
                && now - runtime.previous.transitionStartedAt()
                >= runtime.previous.blend().duration()) {
            runtime.previous = null;
        }
        environment.beginState(runtime.current);
        Completion completion = completion(runtime.current,
                Math.max(0.0D, now - runtime.enteredAt), environment);
        environment.completion(completion);
        for (AnimationController.Transition transition : runtime.current.transitions()) {
            AnimationController.State target = controller.states().get(transition.targetState());
            if (target == null || !truth(ExpressionEngine.compile(
                    transition.conditionExpression()).evaluate(environment))) {
                continue;
            }
            environment.stopOutputScope();
            execute(runtime.current.onExit(), environment);
            AnimationController.BlendTransition blend = target.blendTransition();
            runtime.previous = blend.duration() > EPSILON
                    ? new PreviousState(runtime.current, runtime.enteredAt, runtime.generation,
                    now, blend) : null;
            runtime.current = target;
            runtime.enteredAt = now;
            runtime.generation++;
            environment.completion(Completion.NONE);
            environment.beginState(target);
            execute(target.onEntry(), environment);
            environment.playSounds(target.soundEffects());
            environment.playParticles(target.particleEffects());
            break;
        }
    }

    private void appendActive(List<ActiveAnimation> result, String controllerName,
                              ControllerRuntime runtime, double now,
                              ControllerEnvironment environment) {
        float incomingWeight = 1.0F;
        if (runtime.previous != null) {
            double transitionElapsed = Math.max(0.0D,
                    now - runtime.previous.transitionStartedAt());
            incomingWeight = runtime.previous.blend().progress(transitionElapsed);
            appendState(result, controllerName, runtime.previous.state(),
                    runtime.previous.generation(),
                    Math.max(0.0D, now - runtime.previous.enteredAt()),
                    1.0F, false, environment);
            if (incomingWeight >= 1.0F) {
                runtime.previous = null;
            }
        }
        appendState(result, controllerName, runtime.current, runtime.generation,
                Math.max(0.0D, now - runtime.enteredAt), incomingWeight,
                runtime.previous != null
                        && runtime.current.blendViaShortestPath(), environment);
    }

    private void appendState(List<ActiveAnimation> result, String controllerName,
                             AnimationController.State state, long generation,
                             double elapsed, float transitionWeight, boolean shortestPath,
                             ControllerEnvironment environment) {
        if (state == null) {
            return;
        }
        environment.beginState(state);
        environment.completion(completion(state, elapsed, environment));
        for (int index = 0; index < state.animations().size(); index++) {
            AnimationController.AnimationReference reference = state.animations().get(index);
            String normalized = normalize(reference.name());
            ClipInfo clip = clips.get(normalized);
            if (clip == null || !clip.renderable()) {
                continue;
            }
            double evaluated = ExpressionEngine.compile(reference.weightExpression())
                    .evaluate(environment);
            float weight = finite(evaluated * transitionWeight);
            String key = "controller/" + controllerName + '/' + state.name() + '/'
                    + generation + '/' + index;
            result.add(new ActiveAnimation(normalize(controllerName), key,
                    normalized, elapsed, weight,
                    shortestPath, environment.stateVariables()));
        }
    }

    private Completion completion(AnimationController.State state, double elapsed,
                                  ControllerEnvironment environment) {
        if (state == null) {
            return Completion.NONE;
        }
        boolean any = false;
        boolean all = true;
        boolean active = false;
        environment.completion(Completion.NONE);
        for (AnimationController.AnimationReference reference : state.animations()) {
            String name = normalize(reference.name());
            ClipInfo clip = clips.get(name);
            if (clip == null || !truth(ExpressionEngine.compile(
                    reference.weightExpression()).evaluate(environment))) {
                continue;
            }
            active = true;
            boolean finished = clip.duration() <= EPSILON ? elapsed > EPSILON
                    : elapsed + EPSILON >= clip.duration();
            any |= finished;
            all &= finished;
        }
        return active ? new Completion(any, all) : new Completion(true, true);
    }

    private static AnimationController.State initialState(AnimationController controller) {
        AnimationController.State explicit = controller.states().get(controller.initialState());
        if (explicit != null) {
            return explicit;
        }
        AnimationController.State defaultState = controller.states().get("default");
        return defaultState != null ? defaultState
                : controller.states().values().stream().findFirst().orElse(null);
    }

    private static void execute(List<String> expressions, ExpressionEngine.Environment environment) {
        expressions.forEach(expression -> ExpressionEngine.compile(expression).evaluate(environment));
    }

    private static boolean truth(double value) {
        return Double.isFinite(value) && Math.abs(value) > EPSILON;
    }

    private static float finite(double value) {
        return Double.isFinite(value) && Math.abs(value) <= Float.MAX_VALUE
                ? (float) value : 0.0F;
    }

    private static String normalize(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    private static boolean isHandItemController(String name) {
        String normalized = normalize(name);
        return normalized.contains("pre_hold") || normalized.contains("post_hold")
                || normalized.contains("pre_use") || normalized.contains("post_use")
                || normalized.contains("pre_swing") || normalized.contains("post_swing");
    }
}
