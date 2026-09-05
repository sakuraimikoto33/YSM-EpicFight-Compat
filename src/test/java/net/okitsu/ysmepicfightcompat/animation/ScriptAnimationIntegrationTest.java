package net.okitsu.ysmepicfightcompat.animation;

import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import net.okitsu.ysmepicfightcompat.mesh.AuxiliaryBoneLayout;
import org.junit.jupiter.api.Test;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises script hooks through selection and pose composition, without a Minecraft entity. */
class ScriptAnimationIntegrationTest {
    @Test
    void arbitraryNamedMainClipOwnsTheWholeBodyOnlyWhenMovementIsEnabled() {
        AnimationClip walk = rotationClip("walk", "head", 5);
        AnimationClip authored = rotationClip("authored_pose", "head", 40);
        Map<String, String> sources = Map.of("main@player_ctrl_main",
                "ctrl.set_animation('authored_pose');return ctrl.state_continue;");
        Fixture enabled = fixture(List.of(walk, authored), Map.of(), sources);
        ParallelAnimationProgram.Frame ysm = enabled.sample(selection("walk"), true);

        assertTrue(ysm.replaceEpicFightPose());
        assertRotationZ(40, ysm.wholeModelDeltas()[0]);

        Fixture disabled = fixture(List.of(walk, authored), Map.of(), sources);
        ParallelAnimationProgram.Frame epic = disabled.sample(selection("walk"), false);

        assertFalse(epic.replaceEpicFightPose());
        assertIdentity(epic.wholeModelDeltas()[0]);
        assertIdentity(epic.parallelDeltas()[0]);
    }

    @Test
    void mainHookDoesNotGainFullBodyOwnershipWithoutAnEligibleMovementState() {
        AnimationClip authored = rotationClip("authored_pose", "head", 40);
        Fixture fixture = fixture(List.of(authored), Map.of(), Map.of("main@player_ctrl_main",
                "ctrl.set_animation('authored_pose');return ctrl.state_continue;"));
        AutomaticAnimationSelector.Selection idle = new AutomaticAnimationSelector.Selection(
                List.of(), null, null, Set.of());

        ParallelAnimationProgram.Frame frame = fixture.sample(idle, true);

        assertFalse(frame.replaceEpicFightPose());
        assertIdentity(frame.wholeModelDeltas()[0]);
    }

    @Test
    void bypassKeepsTheOriginalMainSelectionAndPose() {
        AnimationClip walk = rotationClip("walk", "head", 5);
        AnimationClip authored = rotationClip("authored_pose", "head", 40);
        Fixture fixture = fixture(List.of(walk, authored), Map.of(), Map.of("main@player_ctrl_main",
                "ctrl.set_animation('authored_pose');return ctrl.state_bypass;"));
        AutomaticAnimationSelector.Selection selected = selection("walk");
        Map<String, MolangScriptRuntime.Output> outputs = new LinkedHashMap<>();

        AutomaticAnimationSelector.Selection result = fixture.program.selectScriptControllers(
                selected, fixture.scripts, outputs, fixture.environment, 0, 0);

        assertSame(selected.main(), result.main());
        assertEquals(selected.clips(), result.clips());
        assertFalse(outputs.get("player.main").overridden());
        ParallelAnimationProgram.Frame frame = fixture.sample(selected, true);
        assertRotationZ(5, frame.wholeModelDeltas()[0]);
    }

    @Test
    void pauseSuppressesTheOriginalParallelClipWithoutSuppressingOtherSlots() {
        AnimationClip suppressed = scriptClip("parallel0", "v.suppressed+=1");
        AnimationClip retained = scriptClip("parallel1", "v.retained+=1");
        Fixture fixture = fixture(List.of(suppressed, retained), Map.of(),
                Map.of("pause@player_ctrl_parallel_0", "return ctrl.state_pause;"));

        fixture.sample(emptySelection(), false);

        assertEquals(0.0D, fixture.environment.value("v.suppressed"));
        assertEquals(1.0D, fixture.environment.value("v.retained"));
    }

    @Test
    void scriptParallelReplacementsKeepPreMainPostOrderingAndRunOnlyOnce() {
        AnimationClip originalPre = scriptClip("pre_parallel0", "v.order=v.order*10+8");
        AnimationClip originalPost = scriptClip("parallel0", "v.order=v.order*10+9");
        AnimationClip scriptedPre = scriptClip("custom_before", "v.order=v.order*10+1");
        AnimationClip walk = scriptClip("walk", "v.order=v.order*10+2");
        AnimationClip scriptedPost = scriptClip("custom_after", "v.order=v.order*10+3");
        Fixture fixture = fixture(List.of(originalPre, originalPost, scriptedPre, walk, scriptedPost),
                Map.of(), Map.of(
                        "before@player_ctrl_pre_parallel_0",
                        "ctrl.set_animation('custom_before');return ctrl.state_continue;",
                        "after@player_ctrl_parallel_0",
                        "ctrl.set_animation('custom_after');return ctrl.state_continue;"));

        fixture.sample(selection("walk"), false);

        assertEquals(123.0D, fixture.environment.value("v.order"));
    }

    @Test
    void bedrockControllerOwnsItsSlotAndTheConflictingScriptIsNotExecuted() {
        AnimationClip walk = rotationClip("walk", "head", 5);
        AnimationClip scripted = rotationClip("scripted_pose", "head", 40);
        AnimationClip json = rotationClip("json_pose", "head", 20);
        AnimationController controller = controller("player.main", "json_pose",
                List.of("v.json_calls+=1;"));
        Fixture fixture = fixture(List.of(walk, scripted, json),
                Map.of(controller.name(), controller), Map.of("main@player_ctrl_main",
                        "v.script_calls+=1;ctrl.set_animation('scripted_pose');"
                                + "return ctrl.state_continue;"));

        ParallelAnimationProgram.Frame frame = fixture.sample(selection("walk"), true);

        assertEquals(0.0D, fixture.environment.value("v.script_calls"));
        assertEquals(1.0D, fixture.environment.value("v.json_calls"));
        assertRotationZ(20, frame.wholeModelDeltas()[0]);
    }

    @Test
    void guiAndFirstPersonOnlyHooksNeverExecuteInTheWorldControllerPass() {
        Fixture fixture = fixture(List.of(rotationClip("walk", "head", 5)), Map.of(), Map.of(
                "gui@player_ctrl_gui", "v.gui_calls+=1;return ctrl.state_continue;",
                "arm@player_ctrl_fp_arm", "v.arm_calls+=1;return ctrl.state_continue;"));

        ParallelAnimationProgram.Frame frame = fixture.sample(selection("walk"), true);

        assertEquals(0.0D, fixture.environment.value("v.gui_calls"));
        assertEquals(0.0D, fixture.environment.value("v.arm_calls"));
        assertRotationZ(5, frame.wholeModelDeltas()[0]);
    }

    @Test
    void useAndSwingAliasesTargetBothHandsAndArmorNamesTargetEquipmentSlots() {
        Map<String, String> aliases = Map.ofEntries(
                Map.entry("use_mainhand:minecraft:bow", "player.use"),
                Map.entry("use_offhand:minecraft:shield", "player.use"),
                Map.entry("swing_hand#minecraft:swords", "player.swing"),
                Map.entry("swing_offhand", "player.swing"),
                Map.entry("head:default", "player.armor_head"),
                Map.entry("chest:default", "player.armor_chest"),
                Map.entry("legs:default", "player.armor_legs"),
                Map.entry("feet:default", "player.armor_feet"));
        aliases.forEach((name, expected) -> {
            AutomaticAnimationSelector.ActiveClip active = active(name);
            assertEquals(expected, ParallelAnimationProgram.scriptChannel(active, null), name);
            assertTrue(ParallelAnimationProgram.supportsScriptController(expected), name);
        });
        assertTrue(ParallelAnimationProgram.supportsScriptController("player.parallel_0"));
        assertTrue(ParallelAnimationProgram.supportsScriptController("player.pre_parallel_7"));
        assertFalse(ParallelAnimationProgram.supportsScriptController("player.parallel8"));
        assertFalse(ParallelAnimationProgram.supportsScriptController("player.gui"));
    }

    @Test
    void usePauseRemovesBothHandVariantsButLeavesTheMainState() {
        AnimationClip walk = rotationClip("walk", "head", 5);
        Fixture fixture = fixture(List.of(walk), Map.of(),
                Map.of("use@player_ctrl_use", "return ctrl.state_pause;"));
        AutomaticAnimationSelector.ActiveClip main = active("walk");
        AutomaticAnimationSelector.Selection selected = new AutomaticAnimationSelector.Selection(
                List.of(main, active("use_mainhand:minecraft:bow"), active("use_offhand:minecraft:shield")),
                main, MovementAnimationType.WALK, Set.of());

        AutomaticAnimationSelector.Selection result = fixture.program.selectScriptControllers(
                selected, fixture.scripts, new LinkedHashMap<>(), fixture.environment, 0, 0);

        assertEquals(List.of(main), result.clips());
        assertSame(main, result.main());
    }

    @Test
    void disabledOutputGateKeepsObservationWithoutEmittingAScriptPose() {
        Fixture fixture = fixture(List.of(rotationClip("authored_pose", "ear", 15)), Map.of(),
                Map.of("hold@player_ctrl_hold_mainhand",
                        "ctrl.set_animation('authored_pose');return ctrl.state_continue;"));
        Map<String, MolangScriptRuntime.Output> outputs = new LinkedHashMap<>();
        fixture.program.selectScriptControllers(emptySelection(), fixture.scripts, outputs,
                fixture.environment, 0, 0);

        AnimationControllerProgram.Selection result = fixture.program.mergeScriptControllers(
                new AnimationControllerProgram.Selection(List.of(), List.of()), outputs, ignored -> false);

        assertTrue(result.outputActive().isEmpty());
        assertEquals(1, result.allActive().size());
        assertEquals("authored_pose", result.allActive().get(0).name());
    }

    private static Fixture fixture(List<AnimationClip> clips,
                                   Map<String, AnimationController> controllers,
                                   Map<String, String> functions) {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone head = new GeometryDocument.Bone("head");
        GeometryDocument.Bone ear = new GeometryDocument.Bone("ear");
        ear.parentName("head");
        ear.pivot(0, 1, 0);
        geometry.add(head);
        geometry.add(ear);
        geometry.linkHierarchy();
        Map<String, AnimationClip> animations = new LinkedHashMap<>();
        Map<String, MolangScriptRuntime.Clip> metadata = new LinkedHashMap<>();
        clips.forEach(clip -> {
            animations.put(clip.name(), clip);
            metadata.put(clip.name(), new MolangScriptRuntime.Clip(1, AnimationClip.Playback.REPEAT));
        });
        ParallelAnimationProgram program = new ParallelAnimationProgram(
                "script_test", geometry, animations, controllers, functions,
                AuxiliaryBoneLayout.create(geometry), 1, 1);
        MolangScriptRuntime scripts = new MolangScriptRuntime(functions, metadata);
        return new Fixture(program, scripts, new HostEnvironment(scripts));
    }

    private record Fixture(ParallelAnimationProgram program, MolangScriptRuntime scripts,
                           HostEnvironment environment) {
        ParallelAnimationProgram.Frame sample(AutomaticAnimationSelector.Selection selection,
                                               boolean movementEnabled) {
            return program.sampleScriptControllersAt(0, selection, environment, scripts,
                    new AnimationControllerProgram.RuntimeState(), movementEnabled);
        }
    }

    private static AutomaticAnimationSelector.Selection selection(String mainName) {
        AutomaticAnimationSelector.ActiveClip main = active(mainName);
        return new AutomaticAnimationSelector.Selection(
                List.of(main), main, MovementAnimationType.WALK, Set.of());
    }

    private static AutomaticAnimationSelector.Selection emptySelection() {
        return new AutomaticAnimationSelector.Selection(List.of(), null, null, Set.of());
    }

    private static AutomaticAnimationSelector.ActiveClip active(String name) {
        return new AutomaticAnimationSelector.ActiveClip(name, 0, false);
    }

    private static AnimationClip rotationClip(String name, String bone, double degrees) {
        AnimationClip clip = new AnimationClip(name);
        clip.playback(AnimationClip.Playback.REPEAT);
        AnimationClip.VectorValue value = new AnimationClip.VectorValue();
        value.setConstant(0, 0);
        value.setConstant(1, 0);
        value.setConstant(2, degrees);
        clip.boneTracks().put(bone, tracks(value));
        return clip;
    }

    private static AnimationClip scriptClip(String name, String expression) {
        AnimationClip clip = new AnimationClip(name);
        clip.playback(AnimationClip.Playback.REPEAT);
        AnimationClip.VectorValue value = new AnimationClip.VectorValue();
        value.setExpression(0, expression);
        value.setConstant(1, 0);
        value.setConstant(2, 0);
        clip.boneTracks().put("ear", tracks(value));
        return clip;
    }

    private static AnimationClip.BoneTracks tracks(AnimationClip.VectorValue value) {
        AnimationClip.Track rotation = new AnimationClip.Track();
        rotation.keyframes().add(new AnimationClip.Keyframe(
                0, AnimationClip.Interpolation.LINEAR, value, null));
        AnimationClip.BoneTracks tracks = new AnimationClip.BoneTracks();
        tracks.rotation(rotation);
        return tracks;
    }

    private static AnimationController controller(String name, String clip, List<String> onEntry) {
        AnimationController.State initial = new AnimationController.State("default",
                List.of(new AnimationController.AnimationReference(clip, "1")),
                List.of(), onEntry, List.of(),
                new AnimationController.BlendTransition(0, List.of()), false);
        return new AnimationController(name, "default", Map.of("default", initial));
    }

    private static void assertIdentity(OpenMatrix4f actual) {
        assertRotationZ(0, actual);
        assertEquals(0, actual.m30, 0.0001F);
        assertEquals(0, actual.m31, 0.0001F);
        assertEquals(0, actual.m32, 0.0001F);
    }

    private static void assertRotationZ(double degrees, OpenMatrix4f actual) {
        assertNotNull(actual);
        double radians = Math.toRadians(degrees);
        assertEquals(Math.cos(radians), actual.m00, 0.0001D);
        assertEquals(Math.sin(radians), actual.m01, 0.0001D);
        assertEquals(-Math.sin(radians), actual.m10, 0.0001D);
        assertEquals(Math.cos(radians), actual.m11, 0.0001D);
        assertEquals(1, actual.m22, 0.0001D);
    }

    private static final class HostEnvironment implements MolangScriptRuntime.Host {
        private final Map<Integer, Object> values = new HashMap<>();
        private final MolangScriptRuntime scripts;

        private HostEnvironment(MolangScriptRuntime scripts) {
            this.scripts = scripts;
        }

        double value(String name) {
            return readVariable(ExpressionEngine.slot(name));
        }

        @Override public MolangScriptRuntime scripts() { return scripts; }
        @Override public boolean hasVariable(int slot) { return values.containsKey(slot); }
        @Override public Object readVariableValue(int slot) { return values.getOrDefault(slot, 0.0D); }
        @Override public double readVariable(int slot) { return ExpressionEngine.number(readVariableValue(slot)); }
        @Override public void writeVariable(int slot, double value) { writeVariableValue(slot, value); }
        @Override public void writeVariableValue(int slot, Object value) {
            values.put(slot, ExpressionEngine.boundedValue(value));
        }
        @Override public Object readQueryValue(int slot) {
            Object value = scripts.read(ExpressionEngine.slotName(slot), this);
            return value == MolangScriptRuntime.UNHANDLED ? 0.0D : value;
        }
        @Override public double readQuery(int slot) { return ExpressionEngine.number(readQueryValue(slot)); }
        @Override public Object invokeValue(String name, Object[] arguments) {
            Object value = scripts.invoke(name, arguments, this);
            return value == MolangScriptRuntime.UNHANDLED ? 0.0D : value;
        }
        @Override public double invoke(String name, double[] arguments) { return 0; }
        @Override public double invokeWithText(String name, String[] arguments) { return 0; }
    }
}
