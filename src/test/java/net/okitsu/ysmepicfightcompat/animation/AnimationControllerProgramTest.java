package net.okitsu.ysmepicfightcompat.animation;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimationControllerProgramTest {
    @Test
    void builtinStateIgnoresDeclaredAnimationsForOutputAndCompletion() {
        AnimationControllerProgram program = parsedProgram("""
                {"animation_controllers":{"player.main":{
                  "initial_state":"ysm-builtin",
                  "states":{
                    "ysm-builtin":{
                      "animations":[{"bogus":"v.bogus_calls+=1;return 1;"}],
                      "on_entry":["v.entries+=1;"],
                      "transitions":[{"authored":"v.leave && q.all_animations_finished"}]
                    },
                    "authored":{"animations":["pose"]}
                  }
                }}}
                """);
        TestEnvironment environment = new TestEnvironment();
        AnimationControllerProgram.RuntimeState runtime = new AnimationControllerProgram.RuntimeState();
        assertTrue(program.hasBuiltinControllers());
        assertTrue(program.hasBuiltinController("player.main"));
        assertFalse(program.builtinActive("player.main", runtime));

        List<AnimationControllerProgram.ActiveAnimation> initial = program.select(0, environment, runtime);
        AnimationControllerProgram.BuiltinSlot first = builtinSlot(initial);
        assertEquals(1, initial.size());
        assertTrue(first.builtin());
        assertEquals("ysm-builtin", first.stateName());
        assertEquals(1.0F, first.progress());
        assertTrue(first.animations().isEmpty());
        assertTrue(program.builtinActive("player.main", runtime));
        assertEquals(0.0D, environment.value("v.bogus_calls"));
        assertEquals(1.0D, environment.value("v.entries"));

        environment.writeVariable(ExpressionEngine.slot("v.leave"), 1);
        List<AnimationControllerProgram.ActiveAnimation> authored = program.select(0.1, environment, runtime);
        assertEquals(List.of("pose", ""), authored.stream()
                .map(AnimationControllerProgram.ActiveAnimation::name).toList());
        assertFalse(builtinSlot(authored).builtin());
        assertFalse(program.builtinActive("player.main", runtime));
        assertEquals(0.0D, environment.value("v.bogus_calls"));
    }

    @Test
    void managedSlotsExposeCurrentClipsAndIndependentBlendProgressInBothDirections() {
        AnimationControllerProgram program = parsedProgram("""
                {"animation_controllers":{"player.parallel_0":{
                  "initial_state":"authored",
                  "states":{
                    "authored":{
                      "animations":[{"pose":"v.old_weight_calls+=1;return 1;"}],
                      "transitions":[{"ysm-builtin":"v.phase == 1"}]
                    },
                    "ysm-builtin":{
                      "blend_transition":0.2,
                      "transitions":[{"next":"v.phase == 2"}]
                    },
                    "next":{"animations":["other"],"blend_transition":0.4}
                  }
                }}}
                """);
        TestEnvironment environment = new TestEnvironment();
        AnimationControllerProgram.RuntimeState runtime = new AnimationControllerProgram.RuntimeState();
        assertTrue(program.hasBuiltinController("player.parallel0"));
        List<AnimationControllerProgram.ActiveAnimation> initial = program.select(0, environment, runtime);
        AnimationControllerProgram.BuiltinSlot first = builtinSlot(initial);
        assertFalse(first.builtin());
        assertEquals(1.0F, first.progress());
        assertEquals(List.of(initial.get(0)), first.animations());
        assertEquals(1.0F, initial.get(0).weight());

        environment.writeVariable(ExpressionEngine.slot("v.phase"), 1);
        List<AnimationControllerProgram.ActiveAnimation> entering = program.select(0.1, environment, runtime);
        AnimationControllerProgram.BuiltinSlot builtin = builtinSlot(entering);
        assertEquals(1, entering.size());
        assertTrue(builtin.builtin());
        assertEquals(0.0F, builtin.progress());
        assertTrue(builtin.generation() > first.generation());
        double oldEvaluations = environment.value("v.old_weight_calls");
        assertEquals(0.5F, builtinSlot(program.select(0.2, environment, runtime)).progress(), 0.0001F);
        assertEquals(oldEvaluations, environment.value("v.old_weight_calls"),
                "Previous-state weights must not be reevaluated for pose snapshots");

        environment.writeVariable(ExpressionEngine.slot("v.phase"), 2);
        List<AnimationControllerProgram.ActiveAnimation> leaving = program.select(0.3, environment, runtime);
        AnimationControllerProgram.BuiltinSlot next = builtinSlot(leaving);
        assertFalse(next.builtin());
        assertEquals(0.0F, next.progress());
        assertTrue(next.generation() > builtin.generation());
        assertEquals(List.of("other", ""), leaving.stream()
                .map(AnimationControllerProgram.ActiveAnimation::name).toList());
        assertEquals(1.0F, leaving.get(0).weight(),
                "The slot blends the whole current layer; it must not preweight its clips");
        assertEquals(0.5F, builtinSlot(program.select(0.5, environment, runtime)).progress(), 0.0001F);
        assertEquals(1.0F, builtinSlot(program.select(0.71, environment, runtime)).progress());
        assertEquals(oldEvaluations, environment.value("v.old_weight_calls"));
    }

    @Test
    void preparingBuiltinsThenSelectingAtTheSameTimeExecutesLifecycleOnlyOnce() {
        AnimationControllerProgram program = parsedProgram("""
                {"animation_controllers":{
                  "player.main":{"initial_state":"ysm-builtin","states":{
                    "ysm-builtin":{"on_entry":["v.entries+=1;"],"on_exit":["v.exits+=1;"],
                      "transitions":[{"authored":"v.leave"}]},
                    "authored":{"animations":["pose"],"on_entry":["v.authored_entries+=1;"]}
                  }},
                  "player.parallel_5":{"states":{"default":{
                    "animations":["other"],"on_entry":["v.unmanaged_entries+=1;"]
                  }}}
                }}
                """);
        TestEnvironment environment = new TestEnvironment();
        AnimationControllerProgram.RuntimeState runtime = new AnimationControllerProgram.RuntimeState();
        program.prepareBuiltins(0, environment, runtime, ignored -> true);
        assertEquals(1.0D, environment.value("v.entries"));
        assertEquals(0.0D, environment.value("v.unmanaged_entries"));
        program.selectObserved(0, environment, runtime, ignored -> true);
        program.selectObserved(0, environment, runtime, ignored -> true);
        assertEquals(1.0D, environment.value("v.entries"));
        assertEquals(1.0D, environment.value("v.unmanaged_entries"));

        environment.writeVariable(ExpressionEngine.slot("v.leave"), 1);
        program.prepareBuiltins(0.1, environment, runtime, ignored -> true);
        assertFalse(program.builtinActive("player.main", runtime));
        program.selectObserved(0.1, environment, runtime, ignored -> true);
        program.prepareBuiltins(0.1, environment, runtime, ignored -> true);
        assertEquals(1.0D, environment.value("v.exits"));
        assertEquals(1.0D, environment.value("v.authored_entries"));
        runtime.reset();
        assertFalse(program.builtinActive("player.main", runtime));
        program.prepareBuiltins(0.2, environment, runtime, ignored -> true);
        assertEquals(2.0D, environment.value("v.entries"));
    }

    @Test
    void disabledManagedSlotsRemainObservedWithFrameLocalVariablesButEmitNoOutput() {
        AnimationControllerProgram program = parsedProgram("""
                {"animation_controllers":{"player.main":{"initial_state":"authored","states":{
                  "authored":{
                    "variables":{"weight":{"input":"0.5","remap_curve":{"0":0,"1":2}}},
                    "animations":[{"pose":"variable.weight"}],"on_entry":["v.entries+=1;"]
                  },
                  "ysm-builtin":{}
                }}}}
                """);
        TestEnvironment environment = new TestEnvironment();
        AnimationControllerProgram.RuntimeState runtime = new AnimationControllerProgram.RuntimeState();
        program.prepareBuiltins(0, environment, runtime, ignored -> false);
        AnimationControllerProgram.Selection selection = program.selectObserved(
                0, environment, runtime, ignored -> false);
        assertTrue(selection.outputActive().isEmpty());
        assertEquals(2, selection.allActive().size());
        AnimationControllerProgram.ActiveAnimation marker = selection.allActive().get(1);
        assertEquals("", marker.name());
        assertTrue(marker.instanceKey().endsWith("/slot"));
        assertEquals(1.0D, marker.stateVariables().get(ExpressionEngine.slot("variable.weight")), 0.0001D);
        assertEquals(marker.stateVariables(), marker.builtinSlot().animations().get(0).stateVariables());
        assertEquals(1.0D, environment.value("v.entries"));
        assertFalse(environment.hasVariable(ExpressionEngine.slot("variable.weight")));
    }

    @Test
    void managedEmptyStateChainsRunInOneFrameAndKeepIntermediateLifecycleOrder() {
        AnimationControllerProgram program = parsedProgram("""
                {"animation_controllers":{"player.main":{"initial_state":"first","states":{
                  "first":{"animations":["pose"],"on_entry":["v.order=1;"],
                    "on_exit":["v.order=v.order*10+2;"],"transitions":[{"empty":"1"}]},
                  "empty":{"on_entry":["v.order=v.order*10+3;"],
                    "on_exit":["v.order=v.order*10+4;"],"transitions":[{"ysm-builtin":"1"}]},
                  "ysm-builtin":{"animations":[{"bogus":"v.bogus_calls+=1;return 1;"}],
                    "on_entry":["v.order=v.order*10+5;"],"on_exit":["v.order=v.order*10+6;"],
                    "transitions":[{"last":"q.any_animation_finished && q.all_animations_finished"}]},
                  "last":{"animations":["other"],"on_entry":["v.order=v.order*10+7;"]}
                }}}}
                """);
        TestEnvironment environment = new TestEnvironment();
        AnimationControllerProgram.RuntimeState runtime = new AnimationControllerProgram.RuntimeState();
        program.select(0, environment, runtime);
        List<AnimationControllerProgram.ActiveAnimation> selected = program.select(0.1, environment, runtime);
        assertEquals("last", builtinSlot(selected).stateName());
        assertEquals(1234567.0D, environment.value("v.order"));
        assertEquals(0.0D, environment.value("v.bogus_calls"));
        assertEquals(List.of("other", ""), selected.stream()
                .map(AnimationControllerProgram.ActiveAnimation::name).toList());
    }

    @Test
    void managedEmptyStateCyclesStopBeforeReenteringAVisitedState() {
        AnimationControllerProgram program = parsedProgram("""
                {"animation_controllers":{"player.main":{"initial_state":"first","states":{
                  "first":{"on_entry":["v.first+=1;"],"transitions":[{"second":"1"}]},
                  "second":{"on_entry":["v.second+=1;"],"transitions":[{"ysm-builtin":"1"}]},
                  "ysm-builtin":{"on_entry":["v.builtin+=1;"],"transitions":[{"first":"1"}]}
                }}}}
                """);
        TestEnvironment environment = new TestEnvironment();
        AnimationControllerProgram.RuntimeState runtime = new AnimationControllerProgram.RuntimeState();
        program.select(0, environment, runtime);
        AnimationControllerProgram.BuiltinSlot slot = builtinSlot(program.select(0.1, environment, runtime));
        assertTrue(slot.builtin());
        assertEquals(1.0D, environment.value("v.first"));
        assertEquals(1.0D, environment.value("v.second"));
        assertEquals(1.0D, environment.value("v.builtin"));
        program.prepareBuiltins(0.1, environment, runtime, ignored -> true);
        assertEquals(1.0D, environment.value("v.first"));
    }

    @Test
    void longEmptyStateChainsHaveAFixedPerFrameWorkLimit() {
        Map<String, AnimationController.State> states = new LinkedHashMap<>();
        for (int index = 0; index < 300; index++) {
            states.put("state" + index, state("state" + index, List.of(),
                    index == 299 ? List.of() : List.of(new AnimationController.Transition(
                            "state" + (index + 1), "1")),
                    List.of("v.entries+=1;"), List.of(), null, false));
        }
        states.put("ysm-builtin", state("ysm-builtin", List.of(), List.of(),
                List.of(), List.of(), null, false));
        AnimationController controller = new AnimationController("player.main", "state0", states);
        AnimationControllerProgram program = new AnimationControllerProgram(
                Map.of(controller.name(), controller), Map.of());
        TestEnvironment environment = new TestEnvironment();
        AnimationControllerProgram.RuntimeState runtime = new AnimationControllerProgram.RuntimeState();
        program.select(0, environment, runtime);
        assertEquals("state256", builtinSlot(program.select(0.1, environment, runtime)).stateName());
        assertEquals(257.0D, environment.value("v.entries"));
    }

    @Test
    void unsupportedOrChildControllersNeverDelegateAndTheReservedNameIsExact() {
        for (String name : List.of("player.main.child", "player.parallel_9", "player.parallel_fox",
                "projectile.main", "vehicle.main", "fp.arm.misc", "player.gui_hover")) {
            AnimationController.State builtin = state("ysm-builtin", List.of(
                    new AnimationController.AnimationReference("bogus", "v.bogus_calls+=1;return 1;")),
                    List.of(new AnimationController.Transition("next", "q.all_animations_finished")),
                    List.of(), List.of(), null, false);
            AnimationController.State next = state("next", List.of(
                    new AnimationController.AnimationReference("pose", "1")), List.of(),
                    List.of(), List.of(), null, false);
            AnimationController controller = controller(name, builtin, next);
            AnimationControllerProgram program = new AnimationControllerProgram(
                    Map.of(name, controller), Map.of(
                    "bogus", new AnimationControllerProgram.ClipInfo(100, AnimationClip.Playback.REPEAT),
                    "pose", new AnimationControllerProgram.ClipInfo(1, AnimationClip.Playback.REPEAT)));
            TestEnvironment environment = new TestEnvironment();
            AnimationControllerProgram.RuntimeState runtime = new AnimationControllerProgram.RuntimeState();
            assertFalse(program.hasBuiltinControllers(), name);
            assertFalse(program.hasBuiltinController(name), name);
            assertTrue(program.select(0, environment, runtime).isEmpty(), name);
            assertFalse(program.builtinActive(name, runtime), name);
            assertEquals(0.0D, environment.value("v.bogus_calls"), name);
            List<AnimationControllerProgram.ActiveAnimation> selected = program.select(0.1, environment, runtime);
            assertEquals(1, selected.size(), name);
            assertTrue(selected.get(0).builtinSlot() == null, name);
        }
        AnimationControllerProgram uppercase = parsedProgram("""
                {"animation_controllers":{"player.main":{"states":{
                  "YSM-BUILTIN":{"animations":["pose"]}
                }}}}
                """);
        assertFalse(uppercase.hasBuiltinController("player.main"));
        assertEquals("pose", uppercase.select(0, new TestEnvironment(),
                new AnimationControllerProgram.RuntimeState()).get(0).name());
    }

    @Test
    void parsesOrderedStatesActionsWeightsAndBlendCurve() {
        Map<String, AnimationController> parsed = BedrockAnimationControllerParser.parse(
                JsonParser.parseString("""
                        {"animation_controllers":{"player.parallel_4":{
                          "initial_state":"idle",
                          "states":{
                            "idle":{
                              "animations":["idle_tail",{"ears":"v.ears"}],
                              "transitions":[{"alert":"q.is_sneaking"}],
                              "on_entry":["v.entered=1;"],
                              "on_exit":["v.entered=0;"],
                              "sound_effects":[{"effect":"model.enter"},"model.bell"],
                              "blend_transition":{"0.0":1.0,"0.2":0.0},
                              "blend_via_shortest_path":true
                            },
                            "alert":{"animations":["ears_up"]}
                          }
                        }}}
                        """).getAsJsonObject());

        AnimationController controller = parsed.get("player.parallel_4");
        assertEquals("idle", controller.initialState());
        assertEquals(List.of("idle", "alert"), controller.states().keySet().stream().toList());
        AnimationController.State idle = controller.states().get("idle");
        assertEquals("idle_tail", idle.animations().get(0).name());
        assertEquals("1", idle.animations().get(0).weightExpression());
        assertEquals("v.ears", idle.animations().get(1).weightExpression());
        assertEquals("alert", idle.transitions().get(0).targetState());
        assertEquals("v.entered=1;", idle.onEntry().get(0));
        assertEquals("v.entered=0;", idle.onExit().get(0));
        assertEquals(List.of("model.enter", "model.bell"), idle.soundEffects());
        assertEquals(0.0F, idle.blendTransition().progress(0.0D), 0.0001F);
        assertEquals(0.5F, idle.blendTransition().progress(0.1D), 0.0001F);
        assertEquals(1.0F, idle.blendTransition().progress(0.2D), 0.0001F);
        assertTrue(idle.blendViaShortestPath());
    }

    @Test
    void evaluatesFrameLocalVariablesAndRemapCurves() {
        Map<String, AnimationController> parsed = BedrockAnimationControllerParser.parse(
                JsonParser.parseString("""
                        {"animation_controllers":{"player.parallel_4":{
                          "states":{"default":{
                            "variables":{"speed":{"input":"0.5",
                              "remap_curve":{"0.0":0.0,"1.0":2.0}}},
                            "animations":[{"ears":"variable.speed"}],
                            "particle_effects":[{"effect":"minecraft:flame",
                              "locator":"head","pre_effect_script":"v.started=1",
                              "bind_to_actor":false}]
                          }}
                        }}}
                        """).getAsJsonObject());
        AnimationController controller = parsed.get("player.parallel_4");
        AnimationController.State state = controller.states().get("default");
        assertEquals(1, state.variables().size());
        assertEquals(1.0D, state.variables().get(0).remap(0.5D), 0.0001D);
        assertEquals("minecraft:flame", state.particleEffects().get(0).effect());
        assertEquals("head", state.particleEffects().get(0).locator());

        AnimationControllerProgram program = new AnimationControllerProgram(
                Map.of(controller.name(), controller), Map.of(
                "ears", new AnimationControllerProgram.ClipInfo(
                        1.0F, AnimationClip.Playback.REPEAT)));
        TestEnvironment environment = new TestEnvironment();
        List<AnimationControllerProgram.ActiveAnimation> active = program.select(
                0.0D, environment, new AnimationControllerProgram.RuntimeState());

        assertEquals("player.parallel_4", active.get(0).controllerName());
        assertEquals(1.0F, active.get(0).weight(), 0.0001F);
        assertEquals(1.0D, active.get(0).stateVariables().get(
                ExpressionEngine.slot("variable.speed")), 0.0001D);
        assertTrue(!environment.hasVariable(ExpressionEngine.slot("variable.speed")));
    }

    @Test
    void executesLifecycleActionsOnceAndUsesAnimationCompletionForTransitions() {
        AnimationController.State idle = state("idle", List.of(
                        new AnimationController.AnimationReference("wave", "1")),
                List.of(new AnimationController.Transition(
                        "done", "q.all_animations_finished")),
                List.of("v.idle_entries+=1;"), List.of("v.idle_exits+=1;"),
                new AnimationController.BlendTransition(0.0F, List.of()), false);
        AnimationController.State done = state("done", List.of(
                        new AnimationController.AnimationReference("done_pose", "1")),
                List.of(), List.of("v.done_entries+=1;"), List.of(),
                new AnimationController.BlendTransition(0.2F, List.of()), true);
        AnimationController controller = controller("player.parallel_4", idle, done);
        AnimationControllerProgram program = new AnimationControllerProgram(
                Map.of(controller.name(), controller), Map.of(
                "wave", new AnimationControllerProgram.ClipInfo(
                        1.0F, AnimationClip.Playback.ONCE),
                "done_pose", new AnimationControllerProgram.ClipInfo(
                        1.0F, AnimationClip.Playback.REPEAT)));
        AnimationControllerProgram.RuntimeState runtime =
                new AnimationControllerProgram.RuntimeState();
        TestEnvironment environment = new TestEnvironment();

        List<AnimationControllerProgram.ActiveAnimation> first =
                program.select(0.0D, environment, runtime);
        program.select(0.0D, environment, runtime);
        assertEquals("wave", first.get(0).name());
        assertEquals(1.0D, environment.value("v.idle_entries"), 0.0001D);

        List<AnimationControllerProgram.ActiveAnimation> transitioned =
                program.select(1.0D, environment, runtime);
        assertEquals(1.0D, environment.value("v.idle_exits"), 0.0001D);
        assertEquals(1.0D, environment.value("v.done_entries"), 0.0001D);
        assertEquals(List.of("wave", "done_pose"),
                transitioned.stream().map(
                        AnimationControllerProgram.ActiveAnimation::name).toList());
        assertEquals(0.0F, transitioned.get(1).weight(), 0.0001F);
        assertTrue(transitioned.get(1).blendViaShortestPath());

        List<AnimationControllerProgram.ActiveAnimation> blended =
                program.select(1.1D, environment, runtime);
        assertEquals(0.5F, blended.get(1).weight(), 0.0001F);
        assertEquals(1.0D, environment.value("v.idle_exits"), 0.0001D);

        List<AnimationControllerProgram.ActiveAnimation> finished =
                program.select(1.21D, environment, runtime);
        assertEquals(List.of("done_pose"), finished.stream().map(
                AnimationControllerProgram.ActiveAnimation::name).toList());
        assertEquals(1.0F, finished.get(0).weight(), 0.0001F);
    }

    @Test
    void anyAnimationFinishedTransitionsBeforeTheLongestAnimationEnds() {
        AnimationController.State playing = state("playing", List.of(
                        new AnimationController.AnimationReference("short", "1"),
                        new AnimationController.AnimationReference("long", "1")),
                List.of(new AnimationController.Transition(
                        "done", "q.any_animation_finished")),
                List.of(), List.of(),
                new AnimationController.BlendTransition(0.0F, List.of()), false);
        AnimationController.State done = state("done", List.of(), List.of(),
                List.of("v.done=1;"), List.of(),
                new AnimationController.BlendTransition(0.0F, List.of()), false);
        AnimationController controller = controller("player.parallel_2", playing, done);
        AnimationControllerProgram program = new AnimationControllerProgram(
                Map.of(controller.name(), controller), Map.of(
                "short", new AnimationControllerProgram.ClipInfo(
                        0.5F, AnimationClip.Playback.REPEAT),
                "long", new AnimationControllerProgram.ClipInfo(
                        2.0F, AnimationClip.Playback.REPEAT)));
        AnimationControllerProgram.RuntimeState runtime =
                new AnimationControllerProgram.RuntimeState();
        TestEnvironment environment = new TestEnvironment();

        program.select(0.0D, environment, runtime);
        program.select(0.5D, environment, runtime);

        assertEquals(1.0D, environment.value("v.done"), 0.0001D);
    }

    @Test
    void completionIgnoresZeroWeightAnimationsAndTreatsNoActiveClipsAsFinished() {
        AnimationController.State weighted = state("weighted", List.of(
                        new AnimationController.AnimationReference("short", "1"),
                        new AnimationController.AnimationReference("long", "0")),
                List.of(new AnimationController.Transition(
                        "empty", "q.all_animations_finished")),
                List.of(), List.of(),
                new AnimationController.BlendTransition(0.0F, List.of()), false);
        AnimationController.State empty = state("empty", List.of(),
                List.of(new AnimationController.Transition(
                        "done", "q.any_animation_finished && q.all_animations_finished")),
                List.of(), List.of(),
                new AnimationController.BlendTransition(0.0F, List.of()), false);
        AnimationController.State done = state("done", List.of(), List.of(),
                List.of("v.done=1;"), List.of(),
                new AnimationController.BlendTransition(0.0F, List.of()), false);
        AnimationController controller = controller(
                "player.parallel_2", weighted, empty, done);
        AnimationControllerProgram program = new AnimationControllerProgram(
                Map.of(controller.name(), controller), Map.of(
                "short", new AnimationControllerProgram.ClipInfo(
                        0.5F, AnimationClip.Playback.ONCE),
                "long", new AnimationControllerProgram.ClipInfo(
                        2.0F, AnimationClip.Playback.ONCE)));
        AnimationControllerProgram.RuntimeState runtime =
                new AnimationControllerProgram.RuntimeState();
        TestEnvironment environment = new TestEnvironment();

        program.select(0.0D, environment, runtime);
        program.select(0.5D, environment, runtime);
        program.select(0.6D, environment, runtime);

        assertEquals(1.0D, environment.value("v.done"), 0.0001D);
    }

    @Test
    void neverRunsDedicatedHeldItemControllers() {
        AnimationController.State state = state("default", List.of(), List.of(),
                List.of("v.ran=1;"), List.of(),
                new AnimationController.BlendTransition(0.0F, List.of()), false);
        AnimationController controller = controller("player.pre_hold", state);
        AnimationControllerProgram program = new AnimationControllerProgram(
                Map.of(controller.name(), controller), Map.of());

        assertTrue(program.isEmpty());
    }

    @Test
    void runsOnlyExplicitlyAllowedCustomPropControllers() {
        AnimationController.State state = state("default", List.of(), List.of(),
                List.of("v.ran=1;"), List.of(),
                new AnimationController.BlendTransition(0.0F, List.of()), false);
        AnimationController controller = controller("player.post_swing", state);
        AnimationControllerProgram allowed = new AnimationControllerProgram(
                Map.of(controller.name(), controller), Map.of(),
                Set.of(controller.name()));
        TestEnvironment environment = new TestEnvironment();

        allowed.select(0.0D, environment,
                new AnimationControllerProgram.RuntimeState());

        assertFalse(allowed.isEmpty());
        assertEquals(1.0D, environment.value("v.ran"), 0.0001D);
    }

    private static AnimationController controller(String name,
                                                  AnimationController.State... states) {
        Map<String, AnimationController.State> byName = new LinkedHashMap<>();
        for (AnimationController.State state : states) {
            byName.put(state.name(), state);
        }
        return new AnimationController(name, states[0].name(), byName);
    }

    private static AnimationControllerProgram parsedProgram(String source) {
        Map<String, AnimationController> controllers = BedrockAnimationControllerParser.parse(
                JsonParser.parseString(source).getAsJsonObject());
        return new AnimationControllerProgram(controllers, Map.of(
                "pose", new AnimationControllerProgram.ClipInfo(1, AnimationClip.Playback.REPEAT),
                "other", new AnimationControllerProgram.ClipInfo(1, AnimationClip.Playback.REPEAT),
                "bogus", new AnimationControllerProgram.ClipInfo(100, AnimationClip.Playback.REPEAT)),
                controllers.keySet());
    }

    private static AnimationControllerProgram.BuiltinSlot builtinSlot(
            List<AnimationControllerProgram.ActiveAnimation> active) {
        return active.stream().map(AnimationControllerProgram.ActiveAnimation::builtinSlot)
                .filter(java.util.Objects::nonNull).findFirst().orElseThrow();
    }

    private static AnimationController.State state(
            String name, List<AnimationController.AnimationReference> animations,
            List<AnimationController.Transition> transitions, List<String> onEntry,
            List<String> onExit, AnimationController.BlendTransition blend,
            boolean shortestPath) {
        return new AnimationController.State(name, animations, transitions,
                onEntry, onExit, blend, shortestPath);
    }

    private static final class TestEnvironment implements ExpressionEngine.Environment {
        private final Map<Integer, Double> variables = new HashMap<>();

        private double value(String name) {
            return variables.getOrDefault(ExpressionEngine.slot(name), 0.0D);
        }

        @Override
        public double readVariable(int slot) {
            return variables.getOrDefault(slot, 0.0D);
        }

        @Override
        public boolean hasVariable(int slot) {
            return variables.containsKey(slot);
        }

        @Override
        public void writeVariable(int slot, double value) {
            variables.put(slot, value);
        }

        @Override
        public double readQuery(int slot) {
            return 0.0D;
        }

        @Override
        public double invoke(String name, double[] arguments) {
            return 0.0D;
        }

        @Override
        public double invokeWithText(String name, String[] arguments) {
            return 0.0D;
        }
    }
}
