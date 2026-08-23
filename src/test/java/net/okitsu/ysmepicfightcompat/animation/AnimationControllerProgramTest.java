package net.okitsu.ysmepicfightcompat.animation;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimationControllerProgramTest {
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

    private static AnimationController controller(String name,
                                                  AnimationController.State... states) {
        Map<String, AnimationController.State> byName = new LinkedHashMap<>();
        for (AnimationController.State state : states) {
            byName.put(state.name(), state);
        }
        return new AnimationController(name, states[0].name(), byName);
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
