package net.okitsu.ysmepicfightcompat.animation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MolangScriptRuntimeTest {
    private static final double EPSILON = 1.0E-6D;
    private static final Map<String, MolangScriptRuntime.Clip> CLIPS = Map.of(
            "looping", new MolangScriptRuntime.Clip(1.0D, AnimationClip.Playback.REPEAT),
            "once", new MolangScriptRuntime.Clip(1.0D, AnimationClip.Playback.ONCE),
            "held", new MolangScriptRuntime.Clip(1.0D, AnimationClip.Playback.HOLD_LAST_FRAME));

    @Test
    void directFunctionApiStartsAnEvaluationBudgetOnAFreshThread() throws Exception {
        FakeEnvironment environment = environment(Map.of(
                "sum", "return args[0]+args[1];",
                "zero", "return 7;"));
        FutureTask<Object> result = new FutureTask<>(() -> environment.runtime.invoke(
                "fn.sum", new Object[]{5.0D, 7.0D}, environment));
        Thread thread = new Thread(result, "molang-direct-api-test");
        thread.setDaemon(true);
        thread.start();
        assertEquals(12.0D, number(result.get(5, TimeUnit.SECONDS)), EPSILON);

        FutureTask<Object> read = new FutureTask<>(() -> environment.runtime.read(
                "fn.zero", environment));
        Thread reader = new Thread(read, "molang-direct-read-test");
        reader.setDaemon(true);
        reader.start();
        assertEquals(7.0D, number(read.get(5, TimeUnit.SECONDS)), EPSILON);
    }

    @Test
    void callsFunctionsWithTypedArgumentsChainingAndIsolatedTemporaryVariables() {
        FakeEnvironment environment = environment(Map.of(
                "outer", "t.value=10;v.shared=1;fn.inner;return t.value*100+v.shared;",
                "inner", "t.value=99;v.shared+=2;return t.value;",
                "sum", "t.sum=0;for_each(t.arg,args,{t.sum+=t.arg;});return t.sum;",
                "identity", "return args[0];",
                "chain", "return fn.sum(args[0],fn.sum(2,3));"));
        environment.set("t.value", 777.0D);

        assertEquals(1003.0D, number(evaluate("fn.outer", environment)), EPSILON);
        assertEquals(777.0D, environment.number("t.value"), EPSILON);
        assertEquals(3.0D, environment.number("variable.shared"), EPSILON);
        assertEquals(6.0D, number(evaluate("FN.Sum(1,2,3)", environment)), EPSILON);
        assertEquals(9.0D, number(evaluate("fn.chain(4)", environment)), EPSILON);
        assertEquals("Keep Case", evaluate("fn.identity('Keep Case')", environment));
        assertNull(evaluate("fn.identity()", environment));
    }

    @Test
    void boundsRecursionAtThirtyTwoCallsAndRecoversAfterward() {
        FakeEnvironment environment = environment(Map.of("recur", """
                v.calls+=1;
                return args[0]<=0 ? 7 : fn.recur(args[0]-1);
                """));
        assertEquals(7.0D, number(evaluate("fn.recur(31)", environment)), EPSILON);
        assertEquals(32.0D, environment.number("v.calls"), EPSILON);
        environment.set("v.calls", 0.0D);
        assertNull(evaluate("fn.recur(32)", environment));
        assertEquals(32.0D, environment.number("v.calls"), EPSILON);
        assertEquals(7.0D, number(evaluate("fn.recur(0)", environment)), EPSILON);
    }

    @Test
    void initializationUpdateAndQueuedSyncRunOnceAndInOrderPerFrame() {
        FakeEnvironment environment = environment(Map.of(
                "a@player_init", "v.order=v.order*10+1;v.init+=1;v.initial_roaming=v.roaming.flag;",
                "b@player_update", "v.order=v.order*10+2;v.updates+=1;",
                "c@sync", "v.order=v.order*10+3;v.synced=args[0];v.syncs+=1;"));
        environment.set("v.roaming.flag", 9.0D);
        double[] arguments = {42.0D};
        environment.runtime.enqueueSync(arguments);
        arguments[0] = 99.0D;
        environment.runtime.frame(1.0D, environment);
        environment.runtime.frame(1.0D, environment);

        assertEquals(123.0D, environment.number("v.order"), EPSILON);
        assertEquals(1.0D, environment.number("v.init"), EPSILON);
        assertEquals(1.0D, environment.number("v.updates"), EPSILON);
        assertEquals(1.0D, environment.number("v.syncs"), EPSILON);
        assertEquals(9.0D, environment.number("v.initial_roaming"), EPSILON);
        assertEquals(42.0D, environment.number("v.synced"), EPSILON);

        environment.runtime.enqueueSync(new double[]{7});
        environment.runtime.frame(1.0D, environment);
        assertEquals(1.0D, environment.number("v.syncs"), EPSILON);
        environment.runtime.frame(1.1D, environment);
        assertEquals(12323.0D, environment.number("v.order"), EPSILON);
        assertEquals(2.0D, environment.number("v.updates"), EPSILON);
        assertEquals(2.0D, environment.number("v.syncs"), EPSILON);
        environment.runtime.frame(Double.NaN, environment);
        assertEquals(2.0D, environment.number("v.updates"), EPSILON);

        environment.runtime.reset();
        environment.runtime.frame(1.1D, environment);
        assertEquals(2.0D, environment.number("v.init"), EPSILON);
        assertEquals(3.0D, environment.number("v.updates"), EPSILON);
    }

    @Test
    void invalidScriptsDoNotInstallControllerHooksOrEvents() {
        FakeEnvironment environment = environment(Map.of(
                "invalid@player_ctrl_main", "return {",
                "broken@player_init", "v.initialized=1; return (",
                "valid", "return 8;"));
        assertFalse(environment.runtime.hasController("player.main"));
        assertTrue(environment.runtime.controllers().isEmpty());
        environment.runtime.frame(0, environment);
        assertEquals(0.0D, environment.number("v.initialized"), EPSILON);
        assertFalse(environment.runtime.controller("player.main", "once", 0, 0, environment)
                .overridden());
        assertEquals(8.0D, number(evaluate("fn.valid", environment)), EPSILON);
        assertNull(evaluate("fn.invalid", environment));
    }

    @Test
    void exposesOfficialControllerPredicateAndLoopConstants() {
        FakeEnvironment environment = environment(Map.of());
        assertEquals(0.0D, number(evaluate("ctrl.state_continue", environment)), EPSILON);
        assertEquals(1.0D, number(evaluate("ctrl.state_stop", environment)), EPSILON);
        assertEquals(2.0D, number(evaluate("ctrl.state_pause", environment)), EPSILON);
        assertEquals(3.0D, number(evaluate("ctrl.state_bypass", environment)), EPSILON);
        assertEquals(0.0D, number(evaluate("ctrl.play_once", environment)), EPSILON);
        assertEquals(1.0D, number(evaluate("ctrl.loop", environment)), EPSILON);
        assertEquals(3.0D, number(evaluate("ctrl.hold_on_last_frame", environment)), EPSILON);
    }

    @Test
    void repeatingSameClipDoesNotRestartAndExplicitReloadDoes() {
        FakeEnvironment environment = environment(Map.of("@player_ctrl_main", """
                v.invocations+=1;
                v.reload ? {ctrl.indicate_reload;v.reload=0;};
                ctrl.set_animation('once');
                return ctrl.state_continue;
                """));
        MolangScriptRuntime.Output first = sample(environment, 0.0D);
        assertTrue(first.visible());
        assertEquals("once", first.name());
        assertSame(first, sample(environment, 0.0D));
        assertEquals(1.0D, environment.number("v.invocations"), EPSILON);
        MolangScriptRuntime.Output later = sample(environment, 0.4D);
        assertEquals(0.4D, later.elapsed(), EPSILON);
        assertEquals(first.generation(), later.generation());
        assertFalse(sample(environment, 1.1D).visible());
        environment.set("v.reload", 1.0D);
        MolangScriptRuntime.Output reloaded = sample(environment, 1.2D);
        assertTrue(reloaded.visible());
        assertEquals(0.0D, reloaded.elapsed(), EPSILON);
        assertTrue(reloaded.generation() > first.generation());
    }

    @Test
    void pausesOutputWithoutPausingTimeAndStopsWithBlendThenCanReset() {
        FakeEnvironment environment = environment(Map.of("@player_ctrl_main", """
                v.reset ? {ctrl.reset;v.reset=0;};
                ctrl.set_animation('looping');
                return v.predicate;
                """));
        environment.set("v.predicate", 0.0D);
        MolangScriptRuntime.Output first = sample(environment, 0);
        environment.set("v.predicate", 2.0D);
        assertFalse(sample(environment, 0.2D).visible());
        environment.set("v.predicate", 0.0D);
        assertEquals(0.4D, sample(environment, 0.4D).elapsed(), EPSILON);

        environment.set("v.predicate", 1.0D);
        assertEquals(1.0D, sample(environment, 0.5D).weight(), EPSILON);
        assertEquals(0.5D, sample(environment, 0.575D).weight(), EPSILON);
        assertFalse(sample(environment, 0.7D).visible());
        environment.set("v.reset", 1.0D);
        environment.set("v.predicate", 0.0D);
        MolangScriptRuntime.Output reset = sample(environment, 0.8D);
        assertTrue(reset.visible());
        assertEquals(0.0D, reset.elapsed(), EPSILON);
        assertTrue(reset.generation() > first.generation());
    }

    @Test
    void pausedOutputRetainsItsClipClockAndGenerationForSilentTimelineAdvancement() {
        FakeEnvironment environment = environment(Map.of("@player_ctrl_main", """
                ctrl.set_animation('looping');
                return v.paused ? ctrl.state_pause : ctrl.state_continue;
                """));
        MolangScriptRuntime.Output started = sample(environment, 2.0D);
        assertTrue(started.visible());
        environment.set("v.paused", 1.0D);
        MolangScriptRuntime.Output paused = sample(environment, 2.6D);
        assertTrue(paused.overridden());
        assertFalse(paused.visible());
        assertEquals("looping", paused.name());
        assertEquals(0.0F, paused.weight());
        assertEquals(0.6D, paused.elapsed(), EPSILON);
        assertEquals(started.generation(), paused.generation());

        MolangScriptRuntime.Output pausedAfterLoop = sample(environment, 3.2D);
        assertEquals("looping", pausedAfterLoop.name());
        assertEquals(0.0F, pausedAfterLoop.weight());
        assertEquals(0.2D, pausedAfterLoop.elapsed(), EPSILON);
        assertEquals(started.generation(), pausedAfterLoop.generation());
        environment.set("v.paused", 0.0D);
        MolangScriptRuntime.Output resumed = sample(environment, 3.3D);
        assertTrue(resumed.visible());
        assertEquals(0.3D, resumed.elapsed(), EPSILON);
        assertEquals(started.generation(), resumed.generation());
    }

    @Test
    void controllerAnimTimeUsesItsOwnPlaybackClockIncludingNestedFunctionCalls() {
        FakeEnvironment environment = environment(Map.of(
                "clock", "return q.anim_time;",
                "@player_ctrl_main", """
                        ctrl.set_animation('looping');
                        v.main_clock=fn.clock;
                        return v.paused ? ctrl.state_pause : ctrl.state_continue;
                        """,
                "@player_ctrl_pre_main", """
                        ctrl.set_animation('held');
                        v.pre_clock=q.anim_time;
                        return ctrl.state_continue;
                        """));
        environment.set("query.anim_time", 99.0D);
        sample(environment, 10.0D);
        assertEquals(0.0D, environment.number("v.main_clock"), EPSILON);
        environment.runtime.controller("player.pre_main", "", 0, 10.4D, environment);
        assertEquals(0.0D, environment.number("v.pre_clock"), EPSILON);

        environment.set("v.paused", 1.0D);
        sample(environment, 11.25D);
        environment.runtime.controller("player.pre_main", "", 0, 11.25D, environment);
        assertEquals(0.25D, environment.number("v.main_clock"), EPSILON);
        assertEquals(0.85D, environment.number("v.pre_clock"), EPSILON);
        environment.runtime.controller("player.pre_main", "", 0, 11.75D, environment);
        assertEquals(1.0D, environment.number("v.pre_clock"), EPSILON);
        // The controller view must not overwrite the ambient clip context.
        assertEquals(99.0D, number(evaluate("q.anim_time", environment)), EPSILON);
    }

    @Test
    void explicitPlaybackModesAndCompletionQueryMatchTheSelectedClip() {
        FakeEnvironment loop = environment(Map.of("@player_ctrl_main", """
                ctrl.set_animation('once',ctrl.loop);
                v.finished=q.all_animations_finished;
                return ctrl.state_continue;
                """));
        sample(loop, 0);
        MolangScriptRuntime.Output repeating = sample(loop, 1.25D);
        assertTrue(repeating.visible());
        assertEquals(0.25D, repeating.elapsed(), EPSILON);
        assertEquals(0.0D, loop.number("v.finished"), EPSILON);

        FakeEnvironment held = environment(Map.of("@player_ctrl_main", """
                ctrl.set_animation('once',ctrl.hold_on_last_frame);
                v.finished=q.any_animation_finished;
                return ctrl.state_continue;
                """));
        sample(held, 0);
        MolangScriptRuntime.Output heldLast = sample(held, 1.25D);
        assertTrue(heldLast.visible());
        assertEquals(1.0D, heldLast.elapsed(), EPSILON);
        assertEquals(1.0D, held.number("v.finished"), EPSILON);

        FakeEnvironment once = environment(Map.of("@player_ctrl_main", """
                ctrl.set_animation('looping',ctrl.play_once);
                v.finished=q.all_animations_finished;
                return ctrl.state_continue;
                """));
        sample(once, 0);
        assertFalse(sample(once, 1.25D).visible());
        assertEquals(1.0D, once.number("v.finished"), EPSILON);
    }

    @Test
    void bypassRestoresFallbackPlaybackInsteadOfKeepingPreviousLoopOverride() {
        FakeEnvironment environment = environment(Map.of("@player_ctrl_main", """
                v.phase==0 ? {ctrl.set_animation('looping');return ctrl.state_continue;};
                v.phase==1 ? return ctrl.state_bypass;
                return ctrl.state_continue;
                """));
        assertTrue(environment.runtime.controller("player.main", "once", 0, 0, environment)
                .visible());
        environment.set("v.phase", 1.0D);
        assertFalse(environment.runtime.controller("player.main", "once", 0.2D, 0.5D, environment)
                .overridden());
        environment.set("v.phase", 2.0D);
        assertFalse(environment.runtime.controller("player.main", "once", 1.2D, 1.5D, environment)
                .visible());
    }

    @Test
    void preservesNamedAndAnonymousPreControllerSubscriptions() {
        FakeEnvironment environment = environment(Map.of(
                "@player_ctrl_pre_main", "return ctrl.state_bypass;",
                "準備@player_ctrl_pre_parallel_3", "return ctrl.state_bypass;"));
        assertTrue(environment.runtime.hasController("player.pre_main"));
        assertTrue(environment.runtime.hasController("PLAYER.PRE_PARALLEL_3"));
    }

    @Test
    void syncAcceptsAtMostSixteenFiniteNumbersAndDoesNotEchoReceivedCallbacks() {
        FakeEnvironment environment = environment(Map.of("receive@sync",
                "v.received+=1;v.value=args[0];ysm.sync(args[0]);"));
        List<double[]> sent = new ArrayList<>();
        environment.runtime.syncSender(sent::add);
        assertNull(evaluate("ysm.sync(1,2,3)", environment));
        assertArrayEquals(new double[]{1, 2, 3}, sent.get(0));
        String sixteen = String.join(",", java.util.Collections.nCopies(16, "4"));
        evaluate("ysm.sync(" + sixteen + ")", environment);
        assertEquals(16, sent.get(1).length);
        evaluate("ysm.sync(" + sixteen + ",5)", environment);
        evaluate("ysm.sync('1')", environment);
        assertEquals(2, sent.size());
        assertFalse(MolangScriptRuntime.validSync(new double[17]));
        assertFalse(MolangScriptRuntime.validSync(new double[]{Double.NaN}));
        assertFalse(MolangScriptRuntime.validSync(new double[]{Double.POSITIVE_INFINITY}));
        assertFalse(MolangScriptRuntime.validSync(null));
        assertTrue(MolangScriptRuntime.validSync(new double[0]));

        environment.runtime.enqueueSync(new double[]{8});
        environment.runtime.enqueueSync(new double[]{Double.NaN});
        environment.runtime.frame(0, environment);
        assertEquals(1.0D, environment.number("v.received"), EPSILON);
        assertEquals(8.0D, environment.number("v.value"), EPSILON);
        assertEquals(2, sent.size());
        evaluate("ysm.sync(9)", environment);
        assertEquals(3, sent.size());
    }

    @Test
    void limitsQueuedSyncCallbacksAndResetsPendingState() {
        FakeEnvironment environment = environment(Map.of("receive@sync", "v.count+=1;"));
        for (int index = 0; index < 100; index++) environment.runtime.enqueueSync(new double[]{index});
        environment.runtime.frame(0, environment);
        assertEquals(32.0D, environment.number("v.count"), EPSILON);
        environment.runtime.enqueueSync(new double[]{1});
        environment.runtime.reset();
        environment.runtime.frame(1, environment);
        assertEquals(32.0D, environment.number("v.count"), EPSILON);
    }

    private static FakeEnvironment environment(Map<String, String> sources) {
        return new FakeEnvironment(new MolangScriptRuntime(sources, CLIPS));
    }

    private static MolangScriptRuntime.Output sample(FakeEnvironment environment, double now) {
        return environment.runtime.controller("player.main", "", 0, now, environment);
    }

    private static Object evaluate(String source, FakeEnvironment environment) {
        ExpressionEngine.Expression expression = ExpressionEngine.compile(source);
        assertTrue(expression.isValid(), expression::diagnostic);
        return expression.evaluateValue(environment);
    }

    private static double number(Object value) {
        return ExpressionEngine.number(value);
    }

    private static final class FakeEnvironment implements ExpressionEngine.Environment {
        private final MolangScriptRuntime runtime;
        private final Map<Integer, Object> values = new HashMap<>();

        private FakeEnvironment(MolangScriptRuntime runtime) { this.runtime = runtime; }
        private void set(String name, Object value) { values.put(ExpressionEngine.slot(name), value); }
        private double number(String name) { return readVariable(ExpressionEngine.slot(name)); }
        @Override public Object readVariableValue(int slot) { return values.get(slot); }
        @Override public double readVariable(int slot) { return ExpressionEngine.number(values.get(slot)); }
        @Override public boolean hasVariable(int slot) { return values.containsKey(slot); }
        @Override public void writeVariable(int slot, double value) { values.put(slot, value); }
        @Override public void writeVariableValue(int slot, Object value) { values.put(slot, value); }
        @Override public Object readQueryValue(int slot) {
            Object value = runtime.read(ExpressionEngine.slotName(slot), this);
            return value == MolangScriptRuntime.UNHANDLED ? values.get(slot) : value;
        }
        @Override public double readQuery(int slot) { return ExpressionEngine.number(readQueryValue(slot)); }
        @Override public Object invokeValue(String name, Object[] arguments) {
            Object value = runtime.invoke(name, arguments, this);
            return value == MolangScriptRuntime.UNHANDLED ? null : value;
        }
        @Override public double invoke(String name, double[] arguments) {
            Object[] boxed = new Object[arguments.length];
            for (int index = 0; index < arguments.length; index++) boxed[index] = arguments[index];
            return ExpressionEngine.number(invokeValue(name, boxed));
        }
        @Override public double invokeWithText(String name, String[] arguments) {
            return ExpressionEngine.number(invokeValue(name, arguments));
        }
    }
}
