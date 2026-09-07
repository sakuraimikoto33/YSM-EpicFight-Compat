package net.okitsu.ysmepicfightcompat.animation;

import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import net.okitsu.ysmepicfightcompat.mesh.AuxiliaryBoneLayout;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Synthetic rigs only: native visual ownership must never change the humanoid lane. */
class NonHumanoidAnimationProgramTest {
    @Test
    void anOrdinaryHumanoidDoesNotAllocateOrPublishANativeLane() {
        GeometryDocument geometry = humanoid();
        AnimationClip clip = clip("parallel0");
        tracks(clip, "Root").position(constant(16, 0, 0));
        tracks(clip, "RightArm").rotation(constant(0, 0, 40));
        Fixture fixture = fixture(geometry, List.of(clip));

        ParallelAnimationProgram.Frame frame = fixture.program.sampleAt(0, fixture.environment);

        assertNull(frame.authoredDeltas());
        assertMatrix(new Matrix4f(), fixture.parallel(frame, "Root"));
        assertMatrix(new Matrix4f(), fixture.parallel(frame, "RightArm"));
        assertFalse(frame.replaceEpicFightPose());
    }

    @Test
    void aRigWithoutAnatomicalAliasesKeepsItsRootAndConnectedChildMotion() {
        GeometryDocument geometry = new GeometryDocument();
        bone(geometry, "Root", "");
        bone(geometry, "Core", "Root");
        bone(geometry, "Tentacle", "Core");
        geometry.linkHierarchy();
        AnimationClip clip = clip("parallel0");
        tracks(clip, "Root").position(constant(16, 0, 0));
        tracks(clip, "Core").rotation(constant(0, 0, 30));
        tracks(clip, "Tentacle").position(constant(0, 16, 0));
        Fixture fixture = fixture(geometry, List.of(clip));

        ParallelAnimationProgram.Frame frame = fixture.program.sampleAt(0, fixture.environment);

        assertMatrix(new Matrix4f().translation(-1, 0, 0)
                        .rotateZ(radians(30)).translate(0, 1, 0),
                fixture.authored(frame, "Tentacle"));
        assertFalse(frame.replaceEpicFightPose());
    }

    @Test
    void aSharedRootIsSampledOnceButItsNativeTransformDoesNotReachTheHumanoidLane() {
        AnimationClip clip = clip("parallel0");
        tracks(clip, "Root").position(expression("v.root_calls+=1;return 16;", "0", "0"));
        tracks(clip, "AllBody2").rotation(constant(0, 0, 30));
        tracks(clip, "Head2").position(constant(0, 16, 0));
        Fixture fixture = fixture(mixedRig(), List.of(clip));

        ParallelAnimationProgram.Frame frame = fixture.program.sampleAt(0, fixture.environment);

        assertEquals(1, fixture.environment.value("v.root_calls"));
        assertMatrix(new Matrix4f().translation(-1, 0, 0)
                        .rotateZ(radians(30)).translate(0, 1, 0),
                fixture.authored(frame, "RightHandLocator2"));
        assertMatrix(new Matrix4f(), fixture.parallel(frame, "Root"));
        assertMatrix(new Matrix4f(), fixture.parallel(frame, "RightArm"));
        assertFalse(frame.replaceEpicFightPose());
        assertTrue(frame.itemSwitchHands().isEmpty());
    }

    @Test
    void aLaterMainLayerCanRestoreAnInitiallyHiddenNativeBranchInTheSameHierarchy() {
        AnimationClip pre = clip("pre_parallel0");
        tracks(pre, "AlternateVisual").scale(constant(0, 0, 0));
        AnimationClip main = clip("walk");
        tracks(main, "AlternateVisual").scale(constant(1, 1, 1));
        tracks(main, "AllBody2").rotation(constant(0, 0, 25));
        Fixture fixture = fixture(mixedRig(), List.of(pre, main));

        ParallelAnimationProgram.Frame frame = fixture.program.sampleAutomaticAt(
                0, List.of("walk"), "walk", fixture.environment);

        assertMatrix(new Matrix4f().rotateZ(radians(25)),
                fixture.authored(frame, "RightHandLocator2"));
        assertFalse(frame.hiddenBones().contains("RightHandLocator2"));
        assertFalse(frame.replaceEpicFightPose());
    }

    @Test
    void anOrdinaryHeldLayerKeepsTheMouthPoseWithoutChangingHumanBonesOrVisibility() {
        AnimationClip main = clip("walk");
        tracks(main, "Root").position(constant(16, 0, 0));
        tracks(main, "Head2").rotation(constant(0, 0, 15));
        AnimationClip hold = clip("hold_mainhand");
        tracks(hold, "Root").position(expression("v.hold_calls+=1;return 32;", "0", "0"));
        tracks(hold, "Head2").rotation(constant(0, 0, 45));
        tracks(hold, "RightArm").scale(constant(0, 0, 0));
        tracks(hold, "HumanAccessory").rotation(expression("v.human_leak+=1;return 0;", "0", "70"));
        Fixture fixture = fixture(mixedRig(), List.of(main, hold));

        ParallelAnimationProgram.Frame frame = fixture.program.sampleAutomaticAt(
                0, List.of("walk", "hold_mainhand"), "walk",
                Set.of("hold_mainhand"), fixture.environment);

        assertMatrix(new Matrix4f().translation(-2, 0, 0).rotateZ(radians(45)),
                fixture.authored(frame, "RightHandLocator2"));
        assertMatrix(new Matrix4f(), fixture.parallel(frame, "HumanAccessory"));
        assertFalse(frame.hiddenBones().contains("RightArm"));
        assertEquals(1, fixture.environment.value("v.hold_calls"));
        assertEquals(1, fixture.environment.value("v.human_leak"),
                "Input expressions still run once; only their humanoid pose writes are excluded");
        assertFalse(frame.replaceEpicFightPose());
    }

    @Test
    void selectedNativeLocomotionLoopsAfterAnOnceClipExpiresWithoutRevivingHumanAccessories() {
        AnimationClip main = new AnimationClip("walk");
        main.duration(1);
        tracks(main, "Head2").rotation(linear(1, 0, 80));
        tracks(main, "HumanAccessory").rotation(constant(0, 0, 70));
        Fixture fixture = fixture(mixedRig(), List.of(main));

        ParallelAnimationProgram.Frame frame = fixture.program.sampleAutomaticAt(
                3.25, List.of("walk"), "walk", fixture.environment);

        assertMatrix(new Matrix4f().rotateZ(radians(20)),
                fixture.authored(frame, "RightHandLocator2"));
        assertMatrix(new Matrix4f(), fixture.parallel(frame, "HumanAccessory"));
        assertFalse(frame.replaceEpicFightPose());
        assertNull(frame.movementPoseKey());
    }

    @Test
    void aContinuedNativeClipKeepsVariablesAssignedOnSuppressedHumanChannels() {
        AnimationClip main = new AnimationClip("walk");
        main.duration(1);
        tracks(main, "Head").position(expression("v.phase+=1;return 16;", "0", "0"));
        tracks(main, "LeftArm2").rotation(expression("0", "0", "v.phase*15"));
        Fixture fixture = fixture(mixedRig(), List.of(main));
        fixture.program.sampleAutomaticAt(0, List.of("walk"), "walk", fixture.environment);

        ParallelAnimationProgram.Frame continued = fixture.program.sampleAutomaticAt(
                2.1, List.of("walk"), "walk", fixture.environment);

        assertEquals(2, fixture.environment.value("v.phase"));
        assertMatrix(new Matrix4f().rotateZ(radians(30)),
                fixture.authored(continued, "LeftArm2"));
        assertMatrix(new Matrix4f(), fixture.parallel(continued, "Head"));
        assertFalse(continued.replaceEpicFightPose());
    }

    @Test
    void anExpiredOrdinaryHoldRetainsItsAuthoredEndpoint() {
        AnimationClip hold = new AnimationClip("hold_mainhand");
        hold.duration(1);
        tracks(hold, "Head2").rotation(linear(1, 0, 60));
        Fixture fixture = fixture(mixedRig(), List.of(hold));

        ParallelAnimationProgram.Frame frame = fixture.program.sampleAutomaticAt(
                4, List.of("hold_mainhand"), fixture.environment);

        assertMatrix(new Matrix4f().rotateZ(radians(60)),
                fixture.authored(frame, "RightHandLocator2"));
        assertFalse(frame.replaceEpicFightPose());
    }

    @Test
    void nativeOnlyBoneSamplingSuppressesEffectsWithoutMutingLaterOrdinarySampling() {
        AnimationClip hold = clip("hold_mainhand");
        tracks(hold, "Head").position(expression(
                "v.phase+=1;ysm.play_sound('test');ysm.particle('smoke');return 16;", "0", "0"));
        tracks(hold, "LeftArm2").rotation(expression("0", "0", "v.phase*15"));
        Fixture fixture = fixture(mixedRig(), List.of(hold));

        ParallelAnimationProgram.Frame nativeOnly = fixture.program.sampleAutomaticAt(
                0, List.of("hold_mainhand"), null, Set.of("hold_mainhand"), fixture.environment);

        assertEquals(0, fixture.environment.effectCalls);
        assertEquals(1, fixture.environment.value("v.phase"));
        assertMatrix(new Matrix4f().rotateZ(radians(15)), fixture.authored(nativeOnly, "LeftArm2"));

        fixture.program.sampleAutomaticAt(0.1, List.of("hold_mainhand"), fixture.environment);

        assertEquals(2, fixture.environment.effectCalls);
        assertEquals(2, fixture.environment.value("v.phase"));
    }

    @Test
    void nativeOnlyTracksDoNotExtendTheHumanoidEquipWindowOrClaimItsLadderArms() {
        AnimationClip hold = new AnimationClip("hold_mainhand");
        tracks(hold, "RightArm").rotation(linear(0.2F, 0, 10));
        tracks(hold, "Head2").rotation(linear(8, 0, 60));
        AnimationClip ladder = clip("ladder_up");
        tracks(ladder, "LeftArm2").rotation(constant(0, 0, 45));
        Fixture fixture = fixture(mixedRig(), List.of(hold, ladder));

        assertEquals(0.2F, fixture.program.itemSwitchDuration("hold_mainhand"), 0.0001F);
        assertFalse(fixture.program.supportsNaturalLadderPose(
                "ladder_up", MovementAnimationType.LADDER_UP));
    }

    @Test
    void authoredOnlyScaleUsesTheCurrentAncestorHierarchyAndClearsOnTheNextFrame() {
        AnimationClip hold = clip("hold_mainhand");
        tracks(hold, "Root").scale(expression("v.visible", "v.visible", "v.visible"));
        Fixture fixture = fixture(mixedRig(), List.of(hold));
        fixture.environment.set("v.visible", 0);

        ParallelAnimationProgram.Frame hidden = fixture.program.sampleAutomaticAt(
                0, List.of("hold_mainhand"), null,
                Set.of("hold_mainhand"), fixture.environment);

        assertTrue(hidden.hiddenBones().contains("RightHandLocator2"));
        assertFalse(hidden.hiddenBones().contains("RightHandLocator"));
        assertMatrix(new Matrix4f().scale(0), fixture.authored(hidden, "RightHandLocator2"));
        fixture.environment.set("v.visible", 1);
        ParallelAnimationProgram.Frame visible = fixture.program.sampleAutomaticAt(
                0.1, List.of("hold_mainhand"), null,
                Set.of("hold_mainhand"), fixture.environment);
        assertFalse(visible.hiddenBones().contains("RightHandLocator2"));
        assertMatrix(new Matrix4f(), fixture.authored(visible, "RightHandLocator2"));
    }

    @Test
    void aMirroredNativeBranchIsVisibleWhileAZeroScaledBranchIsHidden() {
        AnimationClip parallel = clip("parallel0");
        tracks(parallel, "AlternateVisual").scale(expression("v.x_scale", "1", "1"));
        Fixture fixture = fixture(mixedRig(), List.of(parallel));
        fixture.environment.set("v.x_scale", -1);

        ParallelAnimationProgram.Frame reflected = fixture.program.sampleAt(0, fixture.environment);

        assertFalse(reflected.hiddenBones().contains("AlternateVisual"));
        assertFalse(reflected.hiddenBones().contains("RightHandLocator2"));
        assertMatrix(new Matrix4f().scale(-1, 1, 1),
                fixture.authored(reflected, "RightHandLocator2"));
        fixture.environment.set("v.x_scale", 0);
        assertTrue(fixture.program.sampleAt(0.1, fixture.environment)
                .hiddenBones().contains("RightHandLocator2"));
    }

    @Test
    void aBuiltinControllerBlendsNativePoseAndScaleWithoutReexecutingTheDepartingProvider() {
        AnimationClip nativeClip = countedRotation("parallel0", "Head2", 0, "v.native_calls");
        tracks(nativeClip, "Head2").scale(constant(0, 0, 0));
        AnimationClip custom = countedRotation("custom_pose", "Head2", 90, "v.custom_calls");
        tracks(custom, "Head2").scale(constant(1, 1, 1));
        AnimationController controller = builtinController();
        Fixture fixture = fixture(mixedRig(), List.of(nativeClip, custom),
                Map.of(controller.name(), controller), Map.of());

        assertMatrix(new Matrix4f().scale(0),
                fixture.authored(fixture.sample(0, false), "Head2"));
        fixture.environment.set("v.custom", 1);
        fixture.sample(1, false);
        ParallelAnimationProgram.Frame halfway = fixture.sample(1.1, false);
        assertMatrix(new Matrix4f().rotateZ(radians(45)).scale(0.5F),
                fixture.authored(halfway, "Head2"));
        assertFalse(halfway.hiddenBones().contains("RightHandLocator2"));
        assertEquals(1, fixture.environment.value("v.native_calls"));
        assertEquals(2, fixture.environment.value("v.custom_calls"));
        assertMatrix(new Matrix4f().rotateZ(radians(90)),
                fixture.authored(fixture.sample(1.2, false), "Head2"));
        fixture.environment.set("v.custom", 0);
        ParallelAnimationProgram.Frame returned = fixture.sample(2, false);
        assertMatrix(new Matrix4f().scale(0), fixture.authored(returned, "Head2"));
        assertTrue(returned.hiddenBones().contains("RightHandLocator2"));
        assertEquals(2, fixture.environment.value("v.native_calls"));
    }

    @Test
    void nativeScriptTransitionHistorySurvivesAHumanoidOwnershipChange() {
        AnimationClip oldPose = countedRotation("pose_a", "Head2", 30, "v.old_calls");
        AnimationClip newPose = countedRotation("pose_b", "Head2", 70, "v.new_calls");
        Map<String, String> functions = Map.of("pose@player_ctrl_main", """
                ctrl.set_beginning_transition_length(0.2);
                ctrl.set_animation(v.next_pose ? 'pose_b' : 'pose_a');
                return ctrl.state_continue;
                """);
        Fixture fixture = fixture(mixedRig(), List.of(clip("walk"), oldPose, newPose),
                Map.of(), functions);

        fixture.sample(0, true);
        assertMatrix(new Matrix4f().rotateZ(radians(30)),
                fixture.authored(fixture.sample(0.2, true), "Head2"));
        fixture.environment.set("v.next_pose", 1);
        ParallelAnimationProgram.Frame beginning = fixture.sample(1, false);
        assertFalse(beginning.replaceEpicFightPose());
        assertMatrix(new Matrix4f().rotateZ(radians(30)), fixture.authored(beginning, "Head2"));
        assertMatrix(new Matrix4f().rotateZ(radians(50)),
                fixture.authored(fixture.sample(1.1, false), "Head2"));
        assertMatrix(new Matrix4f().rotateZ(radians(70)),
                fixture.authored(fixture.sample(1.2, false), "Head2"));
        assertEquals(2, fixture.environment.value("v.old_calls"));
        assertEquals(3, fixture.environment.value("v.new_calls"));
    }

    private static Fixture fixture(GeometryDocument geometry, List<AnimationClip> clips) {
        return fixture(geometry, clips, Map.of(), Map.of());
    }

    private static Fixture fixture(GeometryDocument geometry, List<AnimationClip> clips,
                                   Map<String, AnimationController> controllers,
                                   Map<String, String> functions) {
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry);
        Map<String, AnimationClip> animations = new LinkedHashMap<>();
        Map<String, MolangScriptRuntime.Clip> metadata = new LinkedHashMap<>();
        for (AnimationClip clip : clips) {
            animations.put(clip.name(), clip);
            metadata.put(clip.name(), new MolangScriptRuntime.Clip(
                    Math.max(1, clip.duration()), clip.playback()));
        }
        MolangScriptRuntime scripts = new MolangScriptRuntime(functions, metadata);
        return new Fixture(new ParallelAnimationProgram("synthetic_rig", geometry,
                animations, controllers, functions, layout, 1, 1), layout,
                scripts, new Environment(scripts), new AnimationControllerProgram.RuntimeState());
    }

    private record Fixture(ParallelAnimationProgram program, AuxiliaryBoneLayout layout,
                           MolangScriptRuntime scripts, Environment environment,
                           AnimationControllerProgram.RuntimeState controllers) {
        private OpenMatrix4f authored(ParallelAnimationProgram.Frame frame, String name) {
            assertNotNull(frame.authoredDeltas());
            return frame.authoredDeltas()[layout.entryForBoneName(name).auxiliaryIndex()];
        }

        private OpenMatrix4f parallel(ParallelAnimationProgram.Frame frame, String name) {
            return frame.parallelDeltas()[layout.entryForBoneName(name).auxiliaryIndex()];
        }

        private ParallelAnimationProgram.Frame sample(double time, boolean movementEnabled) {
            AutomaticAnimationSelector.ActiveClip main =
                    new AutomaticAnimationSelector.ActiveClip("walk", time, false);
            AutomaticAnimationSelector.Selection selected = new AutomaticAnimationSelector.Selection(
                    List.of(main), main, MovementAnimationType.WALK, Set.of());
            return program.sampleScriptControllersAt(time, selected, environment,
                    scripts, controllers, movementEnabled);
        }
    }

    private static GeometryDocument humanoid() {
        GeometryDocument geometry = new GeometryDocument();
        bone(geometry, "Root", "");
        body(geometry, "Root", "", false);
        bone(geometry, "HumanAccessory", "Head");
        geometry.linkHierarchy();
        return geometry;
    }

    private static GeometryDocument mixedRig() {
        GeometryDocument geometry = humanoid();
        bone(geometry, "AlternateVisual", "Root");
        body(geometry, "AlternateVisual", "2", true);
        geometry.linkHierarchy();
        return geometry;
    }

    private static void body(GeometryDocument geometry, String parent,
                             String suffix, boolean mouthHeld) {
        bone(geometry, "AllBody" + suffix, parent);
        bone(geometry, "UpperBody" + suffix, "AllBody" + suffix);
        bone(geometry, "Head" + suffix, "UpperBody" + suffix);
        bone(geometry, "DownBody" + suffix, "AllBody" + suffix);
        if (mouthHeld) bone(geometry, "MouthMount" + suffix, "Head" + suffix);
        for (String side : List.of("Left", "Right")) {
            bone(geometry, side + "Arm" + suffix, "UpperBody" + suffix);
            bone(geometry, side + "Leg" + suffix, "DownBody" + suffix);
            bone(geometry, side + "Hand" + suffix,
                    (mouthHeld ? "MouthMount" : side + "Arm") + suffix);
            bone(geometry, side + "HandLocator" + suffix, side + "Hand" + suffix);
        }
    }

    private static void bone(GeometryDocument geometry, String name, String parent) {
        GeometryDocument.Bone bone = new GeometryDocument.Bone(name);
        bone.parentName(parent);
        geometry.add(bone);
    }

    private static AnimationClip clip(String name) {
        AnimationClip clip = new AnimationClip(name);
        clip.playback(AnimationClip.Playback.REPEAT);
        return clip;
    }

    private static AnimationClip countedRotation(String name, String bone,
                                                 double degrees, String counter) {
        AnimationClip clip = clip(name);
        tracks(clip, bone).rotation(expression(counter + "+=1;return 0;", "0",
                Double.toString(degrees)));
        return clip;
    }

    private static AnimationClip.BoneTracks tracks(AnimationClip clip, String bone) {
        return clip.boneTracks().computeIfAbsent(bone, ignored -> new AnimationClip.BoneTracks());
    }

    private static AnimationClip.Track constant(double x, double y, double z) {
        AnimationClip.VectorValue value = new AnimationClip.VectorValue();
        value.setConstant(0, x);
        value.setConstant(1, y);
        value.setConstant(2, z);
        return track(value);
    }

    private static AnimationClip.Track expression(String x, String y, String z) {
        AnimationClip.VectorValue value = new AnimationClip.VectorValue();
        value.setExpression(0, x);
        value.setExpression(1, y);
        value.setExpression(2, z);
        return track(value);
    }

    private static AnimationClip.Track linear(float endTime, double start, double end) {
        AnimationClip.Track track = constant(0, 0, start);
        AnimationClip.VectorValue value = new AnimationClip.VectorValue();
        value.setConstant(2, end);
        track.keyframes().add(new AnimationClip.Keyframe(
                endTime, AnimationClip.Interpolation.LINEAR, value, null));
        return track;
    }

    private static AnimationClip.Track track(AnimationClip.VectorValue value) {
        AnimationClip.Track track = new AnimationClip.Track();
        track.keyframes().add(new AnimationClip.Keyframe(
                0, AnimationClip.Interpolation.LINEAR, value, null));
        return track;
    }

    private static AnimationController builtinController() {
        AnimationController.BlendTransition blend =
                new AnimationController.BlendTransition(0.2F, List.of());
        AnimationController.State builtin = new AnimationController.State("ysm-builtin",
                List.of(), List.of(new AnimationController.Transition("custom", "v.custom")),
                List.of(), List.of(), blend, false);
        AnimationController.State custom = new AnimationController.State("custom",
                List.of(new AnimationController.AnimationReference("custom_pose", "1")),
                List.of(new AnimationController.Transition("ysm-builtin", "!v.custom")),
                List.of(), List.of(), blend, false);
        return new AnimationController("player.parallel0", "ysm-builtin",
                Map.of("ysm-builtin", builtin, "custom", custom));
    }

    private static float radians(double degrees) {
        return (float) Math.toRadians(degrees);
    }

    private static void assertMatrix(Matrix4f expected, OpenMatrix4f actual) {
        assertNotNull(actual);
        assertArrayEquals(expected.get(new float[16]), new float[]{
                actual.m00, actual.m01, actual.m02, actual.m03,
                actual.m10, actual.m11, actual.m12, actual.m13,
                actual.m20, actual.m21, actual.m22, actual.m23,
                actual.m30, actual.m31, actual.m32, actual.m33}, 0.0001F);
    }

    private static final class Environment implements MolangScriptRuntime.Host {
        private final Map<Integer, Object> variables = new HashMap<>();
        private final MolangScriptRuntime scripts;
        private int effectCalls;

        private Environment(MolangScriptRuntime scripts) {
            this.scripts = scripts;
        }

        private double value(String name) { return readVariable(ExpressionEngine.slot(name)); }
        private void set(String name, double value) { writeVariable(ExpressionEngine.slot(name), value); }
        @Override public MolangScriptRuntime scripts() { return scripts; }
        @Override public boolean hasVariable(int slot) { return variables.containsKey(slot); }
        @Override public Object readVariableValue(int slot) { return variables.getOrDefault(slot, 0.0D); }
        @Override public double readVariable(int slot) { return ExpressionEngine.number(readVariableValue(slot)); }
        @Override public void writeVariable(int slot, double value) { writeVariableValue(slot, value); }
        @Override public void writeVariableValue(int slot, Object value) {
            variables.put(slot, ExpressionEngine.boundedValue(value));
        }
        @Override public Object readQueryValue(int slot) {
            Object value = scripts.read(ExpressionEngine.slotName(slot), this);
            return value == MolangScriptRuntime.UNHANDLED ? 0.0D : value;
        }
        @Override public double readQuery(int slot) { return ExpressionEngine.number(readQueryValue(slot)); }
        @Override public Object invokeValue(String name, Object[] arguments) {
            if (name.equals("ysm.play_sound") || name.equals("ysm.particle")) {
                effectCalls++;
                return 1.0D;
            }
            Object value = scripts.invoke(name, arguments, this);
            return value == MolangScriptRuntime.UNHANDLED ? 0.0D : value;
        }
        @Override public double invoke(String name, double[] arguments) { return 0; }
        @Override public double invokeWithText(String name, String[] arguments) { return 0; }
    }
}
