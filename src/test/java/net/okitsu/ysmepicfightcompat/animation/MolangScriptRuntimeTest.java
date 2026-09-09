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
    void leavingABuiltinSlotReleasesOnlyItsPlaybackWithoutReinitializingTheModel() {
        FakeEnvironment environment = environment(Map.of(
                "init@player_init", "v.initializations+=1;",
                "main@player_ctrl_main", "ctrl.set_animation('looping');return ctrl.state_continue;",
                "post@player_ctrl_post_main", "ctrl.set_animation('held');return ctrl.state_continue;"));
        environment.runtime.frame(0, environment);
        environment.runtime.controller("player.main", "", 0, 0, environment);
        environment.runtime.controller("player.post_main", "", 0, 0, environment);
        environment.set("v.saved", 7.0D);
        environment.runtime.deactivateController("player.main");
        environment.runtime.frame(0.5, environment);
        assertEquals(0, environment.runtime.controller("player.main", "", 0, 0.5, environment).elapsed(), EPSILON);
        assertEquals(0.5, environment.runtime.controller("player.post_main", "", 0, 0.5, environment).elapsed(), EPSILON);
        assertEquals(7, environment.number("v.saved"), EPSILON);
        assertEquals(1, environment.number("v.initializations"), EPSILON);
    }

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
    void functionsReturnZeroOnFallthroughButKeepExplicitNestedAndChainedReturns() {
        FakeEnvironment environment = environment(Map.of(
                "assign", "v.saved=7;",
                "text", "'Not a return';",
                "nested", "args[0] ? {loop(2,{return args[1];});};v.saved=9;",
                "chain", "return fn.nested(1,args[0])+fn.assign();",
                "call_without_return", "fn.nested(1,4);"));

        assertEquals(0.0D, number(evaluate("fn.assign()", environment)), EPSILON);
        assertEquals(7.0D, environment.number("v.saved"), EPSILON);
        assertEquals(0.0D, number(evaluate("fn.text()", environment)), EPSILON);
        assertEquals(0.0D, number(evaluate("fn.nested(0,5)", environment)), EPSILON);
        assertEquals(9.0D, environment.number("v.saved"), EPSILON);
        assertEquals(5.0D, number(evaluate("fn.nested(1,5)", environment)), EPSILON);
        assertEquals(6.0D, number(evaluate("fn.chain(6)", environment)), EPSILON);
        assertEquals(0.0D, number(evaluate("fn.call_without_return()", environment)), EPSILON);
    }

    @Test
    void controllerFallthroughDoesNotTreatAssignmentValuesAsPredicates() {
        for (int value = 0; value <= 3; value++) {
            FakeEnvironment environment = environment(Map.of("@player_ctrl_main", """
                    ctrl.set_animation('once');
                    v.saved=v.value;
                    """));
            environment.set("v.value", (double) value);

            assertEquals(MolangScriptRuntime.Output.bypass(),
                    environment.runtime.controller("player.main", "held", 0.4D, 0, environment));
            assertEquals((double) value, environment.number("v.saved"), EPSILON);
            assertEquals(MolangScriptRuntime.Output.bypass(),
                    environment.runtime.controller("player.main", "held", 0.8D, 0.4D, environment));
        }
    }

    @Test
    void onlyAnExplicitControllerReturnUsesAFunctionResultAsItsPredicate() {
        FakeEnvironment environment = environment(Map.of(
                "predicate", "return ctrl.state_continue;",
                "@player_ctrl_main", """
                        ctrl.set_animation('held');
                        v.explicit ? return fn.predicate();
                        fn.predicate();
                        """));
        assertFalse(sample(environment, 0).overridden());
        environment.set("v.explicit", 1.0D);
        assertTrue(sample(environment, 0.2D).visible());
        assertEquals(0.3D, sample(environment, 0.5D).elapsed(), EPSILON);

        FakeEnvironment nullPredicate = environment(Map.of("@player_ctrl_main",
                "ctrl.set_animation('once');return null;"));
        assertEquals(MolangScriptRuntime.Output.bypass(), sample(nullPredicate, 0));
    }

    @Test
    void unreturnedUseHistoryKeepsFallbackClocksAcrossReleaseAndASecondDraw() {
        FakeEnvironment environment = environment(Map.of("@player_ctrl_use", """
                ctrl.set_beginning_transition_length(0.1);
                v.release ? {
                    ctrl.set_animation('once');
                    v.release=0;
                    return ctrl.state_continue;
                };
                v.last_using=v.using;
                """));
        environment.set("v.using", 1.0D);
        MolangScriptRuntime.Output draw = environment.runtime.controller(
                "player.use", "held", 0.1D, 0, environment);
        assertFalse(draw.overridden());
        assertEquals("held", draw.name());
        assertEquals(0.1D, draw.elapsed(), EPSILON);
        assertEquals(1.0F, draw.weight());
        MolangScriptRuntime.Output held = environment.runtime.controller(
                "player.use", "held", 0.6D, 0.5D, environment);
        assertFalse(held.overridden());
        assertEquals(0.6D, held.elapsed(), EPSILON);
        assertEquals(1.0F, held.weight());
        assertEquals(draw.transition().generation(), held.transition().generation());

        environment.set("v.using", 0.0D);
        environment.set("v.release", 1.0D);
        MolangScriptRuntime.Output released = environment.runtime.controller(
                "player.use", "", 0, 0.7D, environment);
        assertTrue(released.visible());
        assertEquals("once", released.name());
        assertEquals(0.0D, released.elapsed(), EPSILON);
        MolangScriptRuntime.Output cleared = environment.runtime.controller(
                "player.use", "", 0, 0.75D, environment);
        assertFalse(cleared.overridden());
        assertEquals("", cleared.name());
        assertEquals(0.0D, environment.number("v.last_using"), EPSILON);

        environment.set("v.using", 1.0D);
        MolangScriptRuntime.Output nextDraw = environment.runtime.controller(
                "player.use", "held", 0.05D, 1.0D, environment);
        assertFalse(nextDraw.overridden());
        assertEquals("held", nextDraw.name());
        assertEquals(0.05D, nextDraw.elapsed(), EPSILON);
        assertEquals(1.0F, nextDraw.weight());
        assertTrue(nextDraw.transition().generation() > cleared.transition().generation());
        assertEquals(1.0D, environment.number("v.last_using"), EPSILON);
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
    void exposesExtraAnimationPlaybackWithoutAnyScriptsOrActiveScriptController() {
        FakeEnvironment environment = environment(Map.of());
        assertTrue(environment.runtime.isEmpty());
        assertFalse(environment.runtime.playingExtraAnimation());
        assertEquals(0.0D, number(environment.runtime.read(
                "ctrl.playing_extra_animation", environment)), EPSILON);

        environment.runtime.playingExtraAnimation(true);
        assertTrue(environment.runtime.playingExtraAnimation());
        assertEquals(1.0D, number(evaluate("ctrl.playing_extra_animation", environment)), EPSILON);
        assertFalse(sample(environment, 0.0D).overridden());
        assertTrue(environment.runtime.playingExtraAnimation());

        environment.runtime.playingExtraAnimation(false);
        assertFalse(environment.runtime.playingExtraAnimation());
        assertEquals(0.0D, number(evaluate("ctrl.playing_extra_animation", environment)), EPSILON);

        environment.runtime.playingExtraAnimation(true);
        environment.runtime.reset();
        assertFalse(environment.runtime.playingExtraAnimation());
        assertEquals(0.0D, number(evaluate("ctrl.playing_extra_animation", environment)), EPSILON);
        assertTrue(environment.runtime.isEmpty());
    }

    @Test
    void extraAnimationPlaybackIsIsolatedBetweenRuntimesIncludingReset() {
        FakeEnvironment first = environment(Map.of());
        FakeEnvironment second = environment(Map.of());
        first.runtime.playingExtraAnimation(true);
        assertEquals(1.0D, number(evaluate("ctrl.playing_extra_animation", first)), EPSILON);
        assertEquals(0.0D, number(evaluate("ctrl.playing_extra_animation", second)), EPSILON);

        second.runtime.playingExtraAnimation(true);
        first.runtime.reset();
        assertFalse(first.runtime.playingExtraAnimation());
        assertTrue(second.runtime.playingExtraAnimation());
        second.runtime.playingExtraAnimation(false);
        assertFalse(first.runtime.playingExtraAnimation());
        assertFalse(second.runtime.playingExtraAnimation());
    }

    @Test
    void extraAnimationPlaybackIsVisibleToEventsAndFunctionsWithoutRepeatingSameFrame() {
        FakeEnvironment environment = environment(Map.of(
                "flag", "return ctrl.playing_extra_animation;",
                "a@player_init", """
                        v.init_count+=1;
                        v.init_direct=ctrl.playing_extra_animation;
                        v.init_function=fn.flag;
                        """,
                "b@player_update", """
                        v.update_count+=1;
                        v.update_direct=ctrl.playing_extra_animation;
                        v.update_function=fn.flag();
                        """,
                "c@sync", """
                        v.sync_count+=1;
                        v.sync_direct=ctrl.playing_extra_animation;
                        v.sync_function=fn.flag;
                        """));
        environment.runtime.playingExtraAnimation(true);
        environment.runtime.enqueueSync(new double[0]);
        environment.runtime.frame(1.0D, environment);
        environment.runtime.frame(1.0D, environment);

        for (String event : List.of("init", "update", "sync")) {
            assertEquals(1.0D, environment.number("v." + event + "_count"), EPSILON, event);
            assertEquals(1.0D, environment.number("v." + event + "_direct"), EPSILON, event);
            assertEquals(1.0D, environment.number("v." + event + "_function"), EPSILON, event);
        }

        environment.runtime.playingExtraAnimation(false);
        assertEquals(0.0D, number(evaluate("fn.flag", environment)), EPSILON);
        environment.runtime.enqueueSync(new double[0]);
        environment.runtime.frame(1.0D, environment);
        assertEquals(1.0D, environment.number("v.update_count"), EPSILON);
        assertEquals(1.0D, environment.number("v.sync_count"), EPSILON);
        assertEquals(1.0D, environment.number("v.update_direct"), EPSILON);
        assertEquals(1.0D, environment.number("v.sync_direct"), EPSILON);

        environment.runtime.frame(1.1D, environment);
        environment.runtime.frame(1.1D, environment);
        assertEquals(1.0D, environment.number("v.init_count"), EPSILON);
        assertEquals(1.0D, environment.number("v.init_direct"), EPSILON);
        for (String event : List.of("update", "sync")) {
            assertEquals(2.0D, environment.number("v." + event + "_count"), EPSILON, event);
            assertEquals(0.0D, environment.number("v." + event + "_direct"), EPSILON, event);
            assertEquals(0.0D, environment.number("v." + event + "_function"), EPSILON, event);
        }
    }

    @Test
    void extraAnimationPredicateCanPauseAndResumeScriptControllerWithoutRestartingItsClock() {
        FakeEnvironment environment = environment(Map.of(
                "flag", "return ctrl.playing_extra_animation;",
                "@player_ctrl_main", """
                        ctrl.set_animation('looping');
                        v.extra_direct=ctrl.playing_extra_animation;
                        v.extra_function=fn.flag;
                        return ctrl.playing_extra_animation ? ctrl.state_pause : ctrl.state_continue;
                        """));
        MolangScriptRuntime.Output started = sample(environment, 0.0D);
        assertTrue(started.visible());
        assertFalse(environment.runtime.playingExtraAnimation());
        assertEquals(0.0D, environment.number("v.extra_direct"), EPSILON);

        environment.runtime.playingExtraAnimation(true);
        MolangScriptRuntime.Output paused = sample(environment, 0.25D);
        assertTrue(paused.overridden());
        assertFalse(paused.visible());
        assertEquals("looping", paused.name());
        assertEquals(0.0F, paused.weight());
        assertEquals(0.25D, paused.elapsed(), EPSILON);
        assertEquals(started.generation(), paused.generation());
        assertEquals(1.0D, environment.number("v.extra_direct"), EPSILON);
        assertEquals(1.0D, environment.number("v.extra_function"), EPSILON);
        MolangScriptRuntime.Output pausedAfterLoop = sample(environment, 1.25D);
        assertFalse(pausedAfterLoop.visible());
        assertEquals(0.25D, pausedAfterLoop.elapsed(), EPSILON);
        assertEquals(started.generation(), pausedAfterLoop.generation());

        environment.runtime.playingExtraAnimation(false);
        MolangScriptRuntime.Output resumed = sample(environment, 1.5D);
        assertTrue(resumed.visible());
        assertEquals("looping", resumed.name());
        assertEquals(0.5D, resumed.elapsed(), EPSILON);
        assertEquals(started.generation(), resumed.generation());
        assertEquals(0.0D, environment.number("v.extra_direct"), EPSILON);
        assertEquals(0.0D, environment.number("v.extra_function"), EPSILON);
    }

    @Test
    void unconfiguredBeginningTransitionsPreserveLegacyOutputs() {
        FakeEnvironment environment = environment(Map.of("@player_ctrl_main", """
                ctrl.set_animation('looping');
                return v.bypass ? ctrl.state_bypass : ctrl.state_continue;
                """));
        MolangScriptRuntime.Output first = sample(environment, 0);
        assertNull(first.transition());
        assertEquals(new MolangScriptRuntime.Output(true, "looping", 0, 1, 1), first);
        assertEquals(0.25D, sample(environment, 0.25D).elapsed(), EPSILON);
        environment.set("v.bypass", 1.0D);
        MolangScriptRuntime.Output bypass = environment.runtime.controller(
                "player.main", "held", 0.75D, 1.0D, environment);
        assertEquals(MolangScriptRuntime.Output.bypass(), bypass);
        assertNull(bypass.transition());
    }

    @Test
    void beginningDurationWorksBeforeOrAfterSetAnimationWithoutChangingPlayback() {
        for (String statements : List.of(
                "ctrl.set_beginning_transition_length(1);ctrl.set_animation('looping');",
                "ctrl.set_animation('looping');ctrl.set_beginning_transition_length(1);")) {
            FakeEnvironment environment = environment(Map.of("@player_ctrl_main",
                    statements + "return ctrl.state_continue;"));
            MolangScriptRuntime.Output first = sample(environment, 0);
            assertTrue(first.visible(), statements);
            assertEquals(1.0F, first.weight());
            assertEquals(0.0F, first.transition().progress());
            assertSame(first, sample(environment, 0));
            MolangScriptRuntime.Output quarter = sample(environment, 0.25D);
            assertEquals(0.25D, quarter.elapsed(), EPSILON);
            assertEquals(0.25F, quarter.transition().progress());
            assertEquals(first.generation(), quarter.generation());
            assertEquals(first.transition().generation(), quarter.transition().generation());
            MolangScriptRuntime.Output completed = sample(environment, 1.25D);
            assertEquals(0.25D, completed.elapsed(), EPSILON);
            assertEquals(1.0F, completed.transition().progress());
            assertEquals(first.transition().generation(), completed.transition().generation());
        }
    }

    @Test
    void repeatedDurationSettersAffectOnlyTheNextClipTransition() {
        FakeEnvironment environment = environment(Map.of("@player_ctrl_main", """
                ctrl.set_beginning_transition_length(v.duration);
                ctrl.set_animation(v.next ? 'held' : 'looping');
                return ctrl.state_continue;
                """));
        environment.set("v.duration", 1.0D);
        MolangScriptRuntime.Output first = sample(environment, 0);
        environment.set("v.duration", 2.0D);
        MolangScriptRuntime.Output halfway = sample(environment, 0.5D);
        assertEquals(0.5F, halfway.transition().progress());
        assertEquals(first.transition().generation(), halfway.transition().generation());
        assertEquals(1.0F, sample(environment, 1.0D).transition().progress());
        environment.set("v.next", 1.0D);
        MolangScriptRuntime.Output changed = sample(environment, 1.25D);
        assertEquals(0.0D, changed.elapsed(), EPSILON);
        assertEquals(0.0F, changed.transition().progress());
        assertTrue(changed.transition().generation() > first.transition().generation());
        MolangScriptRuntime.Output nextQuarter = sample(environment, 1.75D);
        assertEquals(0.5D, nextQuarter.elapsed(), EPSILON);
        assertEquals(0.25F, nextQuarter.transition().progress());
    }

    @Test
    void startingTransitionKeepsPauseStopAndReloadSemanticsSeparate() {
        FakeEnvironment environment = environment(Map.of("@player_ctrl_main", """
                ctrl.set_beginning_transition_length(2);
                v.reload ? {ctrl.indicate_reload;v.reload=0;};
                ctrl.set_animation('looping');
                v.clock=q.anim_time;
                return v.predicate;
                """));
        environment.set("v.predicate", 0.0D);
        MolangScriptRuntime.Output first = sample(environment, 0);
        environment.set("v.predicate", 2.0D);
        MolangScriptRuntime.Output paused = sample(environment, 0.5D);
        assertFalse(paused.visible());
        assertEquals(0.5D, paused.elapsed(), EPSILON);
        assertEquals(0.5D, environment.number("v.clock"), EPSILON);
        assertEquals(0.25F, paused.transition().progress());
        environment.set("v.predicate", 0.0D);
        assertEquals(0.375F, sample(environment, 0.75D).transition().progress());
        environment.set("v.predicate", 1.0D);
        assertEquals(1.0F, sample(environment, 1.0D).weight());
        MolangScriptRuntime.Output stopping = sample(environment, 1.075D);
        assertEquals(0.5D, stopping.weight(), EPSILON);
        assertEquals(first.transition().generation(), stopping.transition().generation());
        assertEquals(0.0F, sample(environment, 1.2D).weight());
        environment.set("v.predicate", 0.0D);
        assertEquals(1.0F, sample(environment, 1.3D).weight());
        environment.set("v.reload", 1.0D);
        MolangScriptRuntime.Output reloaded = sample(environment, 1.5D);
        assertTrue(reloaded.visible());
        assertEquals(0.0D, reloaded.elapsed(), EPSILON);
        assertEquals(0.0F, reloaded.transition().progress());
        assertTrue(reloaded.generation() > first.generation());
        assertTrue(reloaded.transition().generation() > first.transition().generation());
    }

    @Test
    void transitionPauseFlagDistinguishesHiddenPauseFromStopBypassAndReset() {
        FakeEnvironment environment = environment(Map.of("@player_ctrl_main", """
                ctrl.set_beginning_transition_length(2);
                v.reset ? {ctrl.reset;return ctrl.state_continue;};
                ctrl.set_animation('looping');
                return v.predicate;
                """));
        environment.set("v.predicate", 0.0D);
        MolangScriptRuntime.Output started = sample(environment, 0);
        assertFalse(started.transition().paused());
        environment.set("v.predicate", 2.0D);
        MolangScriptRuntime.Output paused = sample(environment, 0.25D);
        assertTrue(paused.transition().paused());
        assertFalse(paused.visible());
        assertEquals(started.transition().generation(), paused.transition().generation());
        assertEquals(0.25D, paused.elapsed(), EPSILON);
        assertTrue(sample(environment, 0.5D).transition().paused());

        environment.set("v.predicate", 1.0D);
        MolangScriptRuntime.Output stopping = sample(environment, 0.75D);
        assertFalse(stopping.transition().paused());
        MolangScriptRuntime.Output stopped = sample(environment, 1.0D);
        assertFalse(stopped.visible());
        assertFalse(stopped.transition().paused());

        environment.set("v.predicate", 3.0D);
        MolangScriptRuntime.Output bypass = sample(environment, 1.25D);
        assertFalse(bypass.overridden());
        assertFalse(bypass.transition().paused());
        environment.set("v.reset", 1.0D);
        MolangScriptRuntime.Output reset = sample(environment, 1.5D);
        assertFalse(reset.visible());
        assertFalse(reset.transition().paused());
        assertTrue(reset.transition().discardPrevious());
        assertFalse(new MolangScriptRuntime.Transition(1, 0.5F, false).paused());
    }

    @Test
    void bypassTransitionsExposeFallbackNamesAndClocksIncludingEmptyFallbacks() {
        FakeEnvironment environment = environment(Map.of("@player_ctrl_main", """
                ctrl.set_beginning_transition_length(0.5);
                v.script ? {ctrl.set_animation('held');return ctrl.state_continue;};
                return ctrl.state_bypass;
                """));
        MolangScriptRuntime.Output initial = environment.runtime.controller(
                "player.main", "looping", 0.4D, 2, environment);
        assertFalse(initial.overridden());
        assertEquals("looping", initial.name());
        assertEquals(0.4D, initial.elapsed(), EPSILON);
        assertEquals(1.0F, initial.weight());
        assertEquals(1.0F, initial.transition().progress());
        MolangScriptRuntime.Output stable = environment.runtime.controller(
                "player.main", "looping", 0.8D, 3, environment);
        assertEquals(initial.transition().generation(), stable.transition().generation());
        environment.set("v.script", 1.0D);
        MolangScriptRuntime.Output scripted = environment.runtime.controller(
                "player.main", "looping", 0.9D, 3.1D, environment);
        assertTrue(scripted.visible());
        assertEquals(0.0F, scripted.transition().progress());
        assertTrue(scripted.transition().generation() > initial.transition().generation());
        environment.set("v.script", 0.0D);
        MolangScriptRuntime.Output returned = environment.runtime.controller(
                "player.main", "looping", 0.2D, 3.5D, environment);
        assertFalse(returned.overridden());
        assertEquals(0.0F, returned.transition().progress());
        assertTrue(returned.transition().generation() > scripted.transition().generation());
        MolangScriptRuntime.Output newFallback = environment.runtime.controller(
                "player.main", "once", 0.1D, 3.75D, environment);
        assertEquals("once", newFallback.name());
        assertEquals(0.0F, newFallback.transition().progress());
        assertTrue(newFallback.transition().generation() > returned.transition().generation());
        MolangScriptRuntime.Output empty = environment.runtime.controller(
                "player.main", "", 0, 4, environment);
        assertEquals("", empty.name());
        assertFalse(empty.overridden());
        assertEquals(0.0F, empty.transition().progress());
        assertTrue(empty.transition().generation() > newFallback.transition().generation());
        MolangScriptRuntime.Output emptyLater = environment.runtime.controller(
                "player.main", null, 0, 4.25D, environment);
        assertEquals(empty.transition().generation(), emptyLater.transition().generation());
        assertEquals(0.5F, emptyLater.transition().progress());
    }

    @Test
    void ignoredScriptClipWritesDuringBypassDoNotRestartFallbackTransitions() {
        FakeEnvironment environment = environment(Map.of("@player_ctrl_main", """
                ctrl.set_beginning_transition_length(1);
                ctrl.set_animation('held');
                return v.bypass ? ctrl.state_bypass : ctrl.state_continue;
                """));
        sample(environment, 0);
        environment.set("v.bypass", 1.0D);
        MolangScriptRuntime.Output returned = environment.runtime.controller(
                "player.main", "looping", 0.2D, 0.25D, environment);
        MolangScriptRuntime.Output sameFallback = environment.runtime.controller(
                "player.main", "looping", 0.4D, 0.5D, environment);
        assertEquals(returned.transition().generation(), sameFallback.transition().generation());
        assertEquals(0.25F, sameFallback.transition().progress());
        assertEquals("looping", sameFallback.name());
        assertEquals(0.4D, sameFallback.elapsed(), EPSILON);
    }

    @Test
    void resetDiscardsPreviousPoseForTheWholeTransitionButNotFollowingClipChanges() {
        FakeEnvironment environment = environment(Map.of("@player_ctrl_main", """
                ctrl.set_beginning_transition_length(1);
                v.reset ? {ctrl.reset;v.reset=0;};
                v.empty ? return ctrl.state_continue;
                ctrl.set_animation(v.next ? 'held' : 'looping');
                return ctrl.state_continue;
                """));
        MolangScriptRuntime.Output first = sample(environment, 0);
        assertFalse(first.transition().discardPrevious());
        environment.set("v.reset", 1.0D);
        MolangScriptRuntime.Output reset = sample(environment, 0.5D);
        assertTrue(reset.transition().discardPrevious());
        assertEquals(0.0D, reset.elapsed(), EPSILON);
        assertEquals(0.0F, reset.transition().progress());
        MolangScriptRuntime.Output later = sample(environment, 0.75D);
        assertTrue(later.transition().discardPrevious());
        assertEquals(reset.transition().generation(), later.transition().generation());
        assertEquals(0.25F, later.transition().progress());
        environment.set("v.next", 1.0D);
        MolangScriptRuntime.Output changed = sample(environment, 1);
        assertFalse(changed.transition().discardPrevious());
        assertTrue(changed.transition().generation() > reset.transition().generation());
        environment.set("v.reset", 1.0D);
        environment.set("v.empty", 1.0D);
        MolangScriptRuntime.Output hidden = sample(environment, 1.25D);
        assertFalse(hidden.visible());
        assertEquals("", hidden.name());
        assertTrue(hidden.transition().discardPrevious());
        assertTrue(hidden.transition().generation() > changed.transition().generation());
    }

    @Test
    void transitionDurationRejectsInvalidArgumentsAndCallsOutsideControllerContext() {
        List<Object[]> invalid = List.of(new Object[0], new Object[]{1.0D, 2.0D},
                new Object[]{"1"}, new Object[]{null}, new Object[]{Boolean.TRUE},
                new Object[]{-1.0D}, new Object[]{Double.NaN},
                new Object[]{Double.POSITIVE_INFINITY}, new Object[]{Double.NEGATIVE_INFINITY});
        for (Object[] arguments : invalid) {
            FakeEnvironment environment = environment(Map.of("@player_ctrl_main", """
                    test.set_transition();ctrl.set_animation('looping');return ctrl.state_continue;
                    """));
            // The seam passes uncleaned Java values while currentControl is active;
            // normal Molang argument evaluation already bounds non-finite values.
            environment.rawTransitionArguments = arguments;
            assertNull(sample(environment, 0).transition());
        }
        FakeEnvironment outside = environment(Map.of("@player_ctrl_main",
                "ctrl.set_animation('looping');return ctrl.state_continue;"));
        assertNull(outside.runtime.invoke("ctrl.set_beginning_transition_length",
                new Object[]{1.0D}, outside));
        assertNull(sample(outside, 0).transition());

        FakeEnvironment retained = environment(Map.of("@player_ctrl_main", """
                test.set_transition();ctrl.set_animation('looping');return ctrl.state_continue;
                """));
        retained.rawTransitionArguments = new Object[]{1.0D};
        sample(retained, 0);
        retained.rawTransitionArguments = new Object[]{Double.NaN};
        assertEquals(0.5F, sample(retained, 0.5D).transition().progress());

        FakeEnvironment zero = environment(Map.of("@player_ctrl_main", """
                ctrl.set_beginning_transition_length(0);
                ctrl.set_animation('looping');return ctrl.state_continue;
                """));
        assertEquals(1.0F, sample(zero, 0).transition().progress());
    }

    @Test
    void transitionDurationIsControllerLocalAndGlobalResetRemovesIt() {
        FakeEnvironment environment = environment(Map.of(
                "@player_ctrl_main", """
                        v.configure ? ctrl.set_beginning_transition_length(1);
                        ctrl.set_animation('looping');return ctrl.state_continue;
                        """,
                "@player_ctrl_pre_main", "ctrl.set_animation('held');return ctrl.state_continue;"));
        environment.set("v.configure", 1.0D);
        assertEquals(0.0F, sample(environment, 0).transition().progress());
        assertNull(environment.runtime.controller("player.pre_main", "", 0, 0, environment).transition());
        environment.set("v.configure", 0.0D);
        assertEquals(0.5F, sample(environment, 0.5D).transition().progress());
        environment.runtime.reset();
        assertNull(sample(environment, 1.0D).transition());
    }

    @Test
    void enablingTransitionDurationWithoutChangingTheSelectionDoesNotStartABlend() {
        FakeEnvironment environment = environment(Map.of("@player_ctrl_main", """
                v.configure ? ctrl.set_beginning_transition_length(1);
                ctrl.set_animation(v.next ? 'held' : 'looping');return ctrl.state_continue;
                """));
        assertNull(sample(environment, 0).transition());
        environment.set("v.configure", 1.0D);
        MolangScriptRuntime.Output enabled = sample(environment, 0.25D);
        assertEquals(1.0F, enabled.transition().progress());
        assertEquals(0.25D, enabled.elapsed(), EPSILON);
        environment.set("v.next", 1.0D);
        assertEquals(0.0F, sample(environment, 0.5D).transition().progress());
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

    @Test
    void pendingSyncSignalTracksAcceptedEventsUntilTheyAreProcessedOrReset() {
        FakeEnvironment environment = environment(Map.of("receive@sync", "v.count+=1;"));
        assertFalse(environment.runtime.hasPendingSyncs());
        environment.runtime.enqueueSync(null);
        environment.runtime.enqueueSync(new double[]{Double.NaN});
        environment.runtime.enqueueSync(new double[MolangScriptRuntime.MAX_SYNC_ARGUMENTS + 1]);
        assertFalse(environment.runtime.hasPendingSyncs());

        environment.runtime.enqueueSync(new double[0]);
        assertTrue(environment.runtime.hasPendingSyncs());
        assertTrue(environment.runtime.hasPendingSyncs());
        environment.runtime.frame(0, environment);
        assertFalse(environment.runtime.hasPendingSyncs());
        assertEquals(1.0D, environment.number("v.count"), EPSILON);

        environment.runtime.enqueueSync(new double[]{2});
        environment.runtime.frame(0, environment);
        assertTrue(environment.runtime.hasPendingSyncs());
        environment.runtime.frame(0.01D, environment);
        assertFalse(environment.runtime.hasPendingSyncs());
        assertEquals(2.0D, environment.number("v.count"), EPSILON);

        environment.runtime.enqueueSync(new double[]{3});
        assertTrue(environment.runtime.hasPendingSyncs());
        environment.runtime.reset();
        assertFalse(environment.runtime.hasPendingSyncs());
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
        private Object[] rawTransitionArguments;

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
            if (name.equals("test.set_transition")) {
                return runtime.invoke("ctrl.set_beginning_transition_length", rawTransitionArguments, this);
            }
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
