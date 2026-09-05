package net.okitsu.ysmepicfightcompat.animation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Per-model, per-entity script state. Never executes code outside the bounded Molang VM. */
public final class MolangScriptRuntime {
    public interface Host extends ExpressionEngine.Environment {
        MolangScriptRuntime scripts();
    }

    static MolangScriptRuntime scripts(ExpressionEngine.Environment environment) {
        return environment instanceof Host host ? host.scripts() : null;
    }
    public static final Object UNHANDLED = new Object();
    public static final int MAX_CALL_DEPTH = 32;
    public static final int MAX_SYNC_ARGUMENTS = 16;
    private static final int MAX_PENDING_SYNCS = 32;
    private static final double STOP_SECONDS = 0.15D;
    private static final int CONTINUE = 0, STOP = 1, PAUSE = 2, BYPASS = 3;

    public record Clip(double duration, AnimationClip.Playback playback) { }
    public record Output(boolean overridden, String name, double elapsed,
                         float weight, long generation) {
        static Output bypass() { return new Output(false, "", 0, 0, 0); }
        static Output hidden() { return new Output(true, "", 0, 0, 0); }
        public boolean visible() { return overridden && !name.isEmpty() && weight > 0; }
    }

    private static final class Control {
        String clip = "";
        AnimationClip.Playback playback = AnimationClip.Playback.ONCE;
        double startedAt;
        double stopStartedAt = Double.NaN;
        long generation;
        boolean reload;
        double sampledAt = Double.NaN;
        Output output = Output.bypass();
    }

    private final Map<String, ExpressionEngine.Expression> functions = new LinkedHashMap<>();
    private final Map<String, List<ExpressionEngine.Expression>> events = new LinkedHashMap<>();
    private final Map<String, ExpressionEngine.Expression> hooks = new LinkedHashMap<>();
    private final Map<String, Control> controls = new HashMap<>();
    private final Map<String, Clip> clips;
    private final ArrayDeque<double[]> pendingSyncs = new ArrayDeque<>();
    private Consumer<double[]> syncSender = ignored -> { };
    private boolean initialized;
    private boolean handlingSync;
    private double lastFrame = Double.NaN;
    private int depth;
    private Control currentControl;
    private double controlTime;

    public MolangScriptRuntime(Map<String, String> sources, Map<String, Clip> clips) {
        this.clips = Map.copyOf(clips);
        sources.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String key = normalize(entry.getKey());
            if (key.endsWith(".molang")) key = key.substring(0, key.length() - 7);
            ExpressionEngine.Expression expression = ExpressionEngine.compile(entry.getValue());
            if (!expression.isValid()) return;
            int at = key.indexOf('@');
            String name = at < 0 ? key : key.substring(0, at);
            if (!name.isEmpty()) functions.putIfAbsent("fn." + name, expression);
            if (at >= 0) {
                String event = key.substring(at + 1);
                if (Set.of("player_init", "player_update", "sync").contains(event)) {
                    events.computeIfAbsent(event, ignored -> new ArrayList<>()).add(expression);
                } else if (event.startsWith("player_ctrl_")) {
                    hooks.putIfAbsent("player." + event.substring(12), expression);
                }
            }
        });
    }

    public boolean isEmpty() { return functions.isEmpty() && hooks.isEmpty() && events.isEmpty(); }
    public Set<String> controllers() { return Set.copyOf(hooks.keySet()); }
    public boolean hasController(String channel) { return hooks.containsKey(normalize(channel)); }
    public void syncSender(Consumer<double[]> sender) {
        syncSender = sender == null ? ignored -> { } : sender;
    }

    /** Packets are queued; initialization and the frame update always precede sync callbacks. */
    public void enqueueSync(double[] arguments) {
        if (!validSync(arguments) || pendingSyncs.size() >= MAX_PENDING_SYNCS) return;
        pendingSyncs.addLast(arguments.clone());
    }

    public static boolean validSync(double[] arguments) {
        if (arguments == null || arguments.length > MAX_SYNC_ARGUMENTS) return false;
        for (double value : arguments) if (!Double.isFinite(value)) return false;
        return true;
    }

    public void frame(double now, ExpressionEngine.Environment environment) {
        if (!Double.isFinite(now) || now == lastFrame) return;
        lastFrame = now;
        try (ExpressionEngine.EvaluationScope ignored = ExpressionEngine.beginEvaluation()) {
            if (!initialized) {
                initialized = true;
                event("player_init", new Object[0], environment);
            }
            event("player_update", new Object[0], environment);
            int count = pendingSyncs.size();
            handlingSync = true;
            for (int i = 0; i < count; i++) {
                double[] packet = pendingSyncs.removeFirst();
                Object[] arguments = new Object[packet.length];
                for (int j = 0; j < packet.length; j++) arguments[j] = packet[j];
                event("sync", arguments, environment);
            }
        } catch (ExpressionEngine.EvaluationLimitException ignored) {
            // A hostile model exhausts only this frame, not the client render loop.
        } finally {
            handlingSync = false;
        }
    }

    private void event(String event, Object[] arguments, ExpressionEngine.Environment environment) {
        for (ExpressionEngine.Expression expression : events.getOrDefault(event, List.of())) {
            call(expression, arguments, environment);
        }
    }

    public Object read(String name, ExpressionEngine.Environment environment) {
        String key = normalize(name);
        if (key.startsWith("fn.")) return invoke(key, new Object[0], environment);
        return switch (key) {
            case "ctrl.state_continue" -> (double) CONTINUE;
            case "ctrl.state_stop" -> (double) STOP;
            case "ctrl.state_pause" -> (double) PAUSE;
            case "ctrl.state_bypass" -> (double) BYPASS;
            case "ctrl.loop" -> 1.0D;
            case "ctrl.play_once" -> 0.0D;
            case "ctrl.hold_on_last_frame" -> 3.0D;
            case "ctrl.reset", "ctrl.indicate_reload" -> invoke(key, new Object[0], environment);
            default -> UNHANDLED;
        };
    }

    public Object invoke(String name, Object[] arguments, ExpressionEngine.Environment environment) {
        String key = normalize(name);
        if (key.startsWith("fn.")) {
            ExpressionEngine.Expression expression = functions.get(key);
            return expression == null ? null : call(expression, arguments, environment);
        }
        if (key.equals("ysm.sync")) {
            if (handlingSync || arguments.length > MAX_SYNC_ARGUMENTS) return null;
            double[] values = new double[arguments.length];
            for (int i = 0; i < values.length; i++) {
                if (!(arguments[i] instanceof Number value) || !Double.isFinite(value.doubleValue())) return null;
                values[i] = value.doubleValue();
            }
            syncSender.accept(values);
            return null;
        }
        if (!key.equals("ctrl.set_animation") && !key.equals("ctrl.reset")
                && !key.equals("ctrl.indicate_reload")) return UNHANDLED;
        if (currentControl == null) return null;
        if (key.equals("ctrl.indicate_reload")) {
            currentControl.reload = true;
        } else if (key.equals("ctrl.reset")) {
            currentControl.clip = "";
            currentControl.reload = true;
            currentControl.stopStartedAt = Double.NaN;
        } else if (arguments.length >= 1 && arguments.length <= 2 && arguments[0] instanceof String value) {
            String clip = normalize(value);
            Clip info = clips.get(clip);
            if (info == null) return null;
            AnimationClip.Playback playback = info.playback();
            if (arguments.length == 2) {
                int flag = (int) ExpressionEngine.number(arguments[1]);
                if (flag != 0 && flag != 1 && flag != 3) return null;
                playback = AnimationClip.Playback.fromWireValue(flag);
            }
            if (!clip.equals(currentControl.clip) || currentControl.reload) {
                currentControl.clip = clip;
                currentControl.startedAt = controlTime;
                currentControl.generation++;
                currentControl.stopStartedAt = Double.NaN;
                currentControl.playback = playback;
                currentControl.reload = false;
            }
        }
        return null;
    }

    private Object call(ExpressionEngine.Expression function, Object[] arguments,
                        ExpressionEngine.Environment environment) {
        if (depth >= MAX_CALL_DEPTH) return null;
        depth++;
        try (ExpressionEngine.EvaluationScope ignored = ExpressionEngine.beginEvaluation()) {
            ExpressionEngine.consumeOperations(1);
            return function.evaluateValue(new FunctionEnvironment(environment, arguments));
        } finally {
            depth--;
        }
    }

    public Output controller(String channel, String fallback, double fallbackElapsed,
                             double now, ExpressionEngine.Environment environment) {
        String key = normalize(channel);
        ExpressionEngine.Expression hook = hooks.get(key);
        if (hook == null) return Output.bypass();
        Control state = controls.computeIfAbsent(key, ignored -> new Control());
        if (state.sampledAt == now) return state.output;
        state.sampledAt = now;
        Control previous = currentControl;
        double previousTime = controlTime;
        currentControl = state;
        controlTime = now;
        if (state.clip.isEmpty() && fallback != null && clips.containsKey(fallback)) {
            state.clip = fallback;
            state.playback = clips.get(fallback).playback();
            state.startedAt = now - Math.max(0, fallbackElapsed);
        }
        int predicate = BYPASS;
        try (ExpressionEngine.EvaluationScope ignored = ExpressionEngine.beginEvaluation()) {
            Object result = call(hook, new Object[0], environment);
            if (result instanceof Number value) predicate = value.intValue();
        } catch (ExpressionEngine.EvaluationLimitException ignored) {
            predicate = BYPASS;
        } finally {
            currentControl = previous;
            controlTime = previousTime;
        }
        if (predicate == BYPASS || predicate < CONTINUE || predicate > BYPASS) {
            state.clip = fallback == null ? "" : fallback;
            Clip fallbackInfo = clips.get(state.clip);
            state.playback = fallbackInfo == null ? AnimationClip.Playback.ONCE : fallbackInfo.playback();
            state.startedAt = now - Math.max(0, fallbackElapsed);
            state.stopStartedAt = Double.NaN;
            state.output = Output.bypass();
        } else if (state.clip.isEmpty()) {
            state.output = Output.hidden();
        } else {
            if (predicate == STOP && Double.isNaN(state.stopStartedAt)) state.stopStartedAt = now;
            if (predicate == CONTINUE) state.stopStartedAt = Double.NaN;
            float weight = Double.isNaN(state.stopStartedAt) ? 1.0F
                    : (float) Math.max(0, 1 - (now - state.stopStartedAt) / STOP_SECONDS);
            if (predicate == PAUSE) weight = 0.0F;
            Clip clip = clips.get(state.clip);
            double elapsed = Math.max(0, now - state.startedAt);
            double duration = clip == null ? 0 : clip.duration();
            if (duration > 0) {
                if (state.playback == AnimationClip.Playback.REPEAT) elapsed %= duration;
                else if (state.playback == AnimationClip.Playback.HOLD_LAST_FRAME) elapsed = Math.min(duration, elapsed);
                else if (elapsed > duration) weight = 0;
            }
            state.output = new Output(true, state.clip, elapsed, weight, state.generation);
        }
        return state.output;
    }

    public void reset() {
        controls.clear();
        pendingSyncs.clear();
        initialized = false;
        lastFrame = Double.NaN;
        currentControl = null;
        depth = 0;
    }

    private final class FunctionEnvironment implements ExpressionEngine.Environment {
        private final ExpressionEngine.Environment delegate;
        private final Object[] arguments;
        private final Map<Integer, Object> temporaries = new HashMap<>();
        private FunctionEnvironment(ExpressionEngine.Environment delegate, Object[] arguments) {
            // A function invocation has its own temporary namespace, even under recursion.
            this.delegate = delegate instanceof FunctionEnvironment parent ? parent.delegate : delegate;
            this.arguments = arguments.clone();
        }
        private boolean temporary(int slot) {
            String name = ExpressionEngine.slotName(slot);
            return name.startsWith("t.") || name.startsWith("temp.");
        }
        @Override public Object[] arguments() { return arguments; }
        @Override public Object readVariableValue(int slot) {
            return temporary(slot) ? temporaries.get(slot) : delegate.readVariableValue(slot);
        }
        @Override public void writeVariableValue(int slot, Object value) {
            if (temporary(slot)) temporaries.put(slot, ExpressionEngine.boundedValue(value));
            else delegate.writeVariableValue(slot, value);
        }
        @Override public double readVariable(int slot) { return ExpressionEngine.number(readVariableValue(slot)); }
        @Override public boolean hasVariable(int slot) {
            return temporary(slot) ? temporaries.containsKey(slot) : delegate.hasVariable(slot);
        }
        @Override public void writeVariable(int slot, double value) { writeVariableValue(slot, value); }
        @Override public Object readQueryValue(int slot) {
            String name = ExpressionEngine.slotName(slot);
            if (currentControl != null && name.equals("query.anim_time")) {
                double elapsed = Math.max(0.0D, controlTime - currentControl.startedAt);
                Clip info = clips.get(currentControl.clip);
                if (info == null) return 0.0D;
                if (info.duration() > 0.0D) {
                    elapsed = currentControl.playback == AnimationClip.Playback.REPEAT
                            ? elapsed % info.duration() : Math.min(elapsed, info.duration());
                }
                return elapsed;
            }
            if (currentControl != null && (name.equals("query.all_animations_finished")
                    || name.equals("query.any_animation_finished"))) {
                Clip info = clips.get(currentControl.clip);
                return info != null && currentControl.playback != AnimationClip.Playback.REPEAT
                        && controlTime - currentControl.startedAt >= info.duration() ? 1.0D : 0.0D;
            }
            Object value = read(name, this);
            return value == UNHANDLED ? delegate.readQueryValue(slot) : value;
        }
        @Override public double readQuery(int slot) { return ExpressionEngine.number(readQueryValue(slot)); }
        @Override public Object invokeValue(String name, Object[] args) {
            Object value = MolangScriptRuntime.this.invoke(name, args, this);
            return value == UNHANDLED ? delegate.invokeValue(name, args) : value;
        }
        @Override public double invoke(String name, double[] args) {
            Object[] values = new Object[args.length];
            for (int i = 0; i < args.length; i++) values[i] = args[i];
            return ExpressionEngine.number(invokeValue(name, values));
        }
        @Override public double invokeWithText(String name, String[] args) {
            return ExpressionEngine.number(invokeValue(name, args));
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
