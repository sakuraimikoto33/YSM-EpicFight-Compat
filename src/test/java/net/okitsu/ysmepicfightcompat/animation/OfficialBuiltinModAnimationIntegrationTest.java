package net.okitsu.ysmepicfightcompat.animation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.okitsu.ysmepicfightcompat.geometry.BedrockGeometryParser;
import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import net.okitsu.ysmepicfightcompat.mesh.AuxiliaryBoneLayout;
import net.okitsu.ysmepicfightcompat.mesh.AuxiliaryPoseMatrices;
import net.okitsu.ysmepicfightcompat.mesh.HumanoidRig;
import org.junit.jupiter.api.Test;
import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in official 2.6.5 default assets, supplied through YSM_OFFICIAL_BUILTIN_ROOT.
 * Only manifest-declared files are read; no source assets are copied. Expressions,
 * sampled poses, and parsed fixtures remain in memory and are never written or exported.
 */
class OfficialBuiltinModAnimationIntegrationTest {
    private static final float MODEL_SCALE = 0.7F;
    private static final double FRAME_TIME = 10.0D;

    @Test
    void allFortyNineOfficialModClipsAreAutomaticAndOnlyAuthoredPosesTakeWholeBodyOwnership()
            throws IOException {
        Fixture fixture = load();
        List<String> parkour = clips(fixture, "parcool:");
        List<String> riding = clips(fixture, "swem:");
        assertEquals(38, parkour.size(), "Official 2.6.5 ParCool clip count");
        assertEquals(11, riding.size(), "Official 2.6.5 SWEM clip count");
        Map<String, ModAnimationType> expected = new LinkedHashMap<>();
        parkour.forEach(name -> expected.put(name, ModAnimationType.PARCOOL));
        riding.forEach(name -> expected.put(name, ModAnimationType.SWEM));
        assertEquals(49, expected.size());
        List<String> placeholders = expected.keySet().stream()
                .filter(name -> !hasMatchingAuthoredPose(fixture, name)).toList();
        assertEquals(List.of("parcool:backward_wall_jump"), placeholders,
                "The official default has one empty placeholder and 48 authored poses");

        AuxiliaryPoseMatrices composer = new AuxiliaryPoseMatrices(fixture.layout);
        Armature armature = armature();
        OpenMatrix4f[] epic = identityPoses();
        for (Map.Entry<String, ModAnimationType> entry : expected.entrySet()) {
            String clip = entry.getKey();
            ModAnimationType type = entry.getValue();
            assertEquals(type, ModAnimationClips.type(clip), clip);
            assertTrue(BedrockAnimationParser.isAutomatic(clip), clip);
            boolean authoredPose = hasMatchingAuthoredPose(fixture, clip);
            assertEquals(authoredPose, fixture.program.supportsModAnimation(clip), clip);
            for (double time : new double[]{0.0D, 0.2D, 1.25D}) {
                for (boolean looking : new boolean[]{false, true}) {
                    String context = clip + " at " + time + " looking=" + looking;
                    ParallelAnimationProgram.Frame frame = fixture.program.sampleModAnimationAt(
                            FRAME_TIME, new ModAnimationSample(type, clip, time, 1L),
                            environment(looking ? 27.0D : 0.0D,
                                    looking ? -13.0D : 0.0D, looking ? 4.0D : 0.0D));
                    if (authoredPose) {
                        assertTrue(frame.replaceEpicFightPose(), context);
                        assertEquals("mod:" + type + ':' + clip, frame.movementPoseKey(), context);
                    } else {
                        // Recognition alone cannot turn an empty default placeholder
                        // into a pose owner. Models with actual override tracks may own it.
                        assertFalse(frame.replaceEpicFightPose(), context);
                        assertNull(frame.movementPoseKey(), context);
                    }
                    assertFalse(frame.naturalLadderPose(), context);
                    assertTrue(frame.itemSwitchHands().isEmpty(), context);
                    assertFinite(frame.parallelDeltas(), fixture.layout.entries().size(), context);
                    assertFinite(frame.wholeModelDeltas(), fixture.layout.entries().size(), context);
                    assertFinite(compose(composer, armature, epic, frame),
                            fixture.layout.totalPoseCount(), context);
                }
            }
        }
    }

    @Test
    void expressionOnlyHangingClipsAdvanceWithNativeElapsedWithoutADeclaredDuration()
            throws IOException {
        Fixture fixture = load();
        AuxiliaryPoseMatrices composer = new AuxiliaryPoseMatrices(fixture.layout);
        Armature armature = armature();
        OpenMatrix4f[] epic = identityPoses();
        for (String clip : List.of("parcool:hang", "parcool:hang_vertical")) {
            AnimationClip animation = fixture.animations.get(clip);
            assertNotNull(animation, clip);
            assertEquals(0.0F, animation.duration(), clip);
            assertEquals(AnimationClip.Playback.REPEAT, animation.playback(), clip);

            // Hold the outer clock and all inputs fixed. SnapshotExpressionEnvironment
            // receives clipTime from the program, so this cannot accidentally supply
            // a progressing query.anim_time around a broken zero-duration time clamp.
            ParallelAnimationProgram.Frame start = fixture.program.sampleModAnimationAt(
                    FRAME_TIME, new ModAnimationSample(ModAnimationType.PARCOOL, clip, 0.0D, 1L),
                    environment(0.0D, 0.0D, 4.0D));
            float[] before = copyValues(compose(composer, armature, epic, start));
            ParallelAnimationProgram.Frame progressed = fixture.program.sampleModAnimationAt(
                    FRAME_TIME, new ModAnimationSample(ModAnimationType.PARCOOL, clip, 0.2D, 1L),
                    environment(0.0D, 0.0D, 4.0D));
            OpenMatrix4f[] after = compose(composer, armature, epic, progressed);
            assertFinite(after, fixture.layout.totalPoseCount(), clip);
            assertTrue(differs(before, after), clip + " must retain native expression-time progression");
        }
    }

    @Test
    void officialRidingPoseStillRespondsToFinalCameraHeadQueries() throws IOException {
        Fixture fixture = load();
        AuxiliaryPoseMatrices composer = new AuxiliaryPoseMatrices(fixture.layout);
        Armature armature = armature();
        OpenMatrix4f[] epic = identityPoses();
        ModAnimationSample riding = new ModAnimationSample(
                ModAnimationType.SWEM, "swem:idle", 0.0D, 1L);
        ParallelAnimationProgram.Frame neutral = fixture.program.sampleModAnimationAt(
                FRAME_TIME, riding, environment(0.0D, 0.0D, 0.0D));
        float[] before = copyValues(compose(composer, armature, epic, neutral));
        ParallelAnimationProgram.Frame looking = fixture.program.sampleModAnimationAt(
                FRAME_TIME, riding, environment(32.0D, -14.0D, 0.0D));
        OpenMatrix4f[] after = compose(composer, armature, epic, looking);
        assertFinite(after, fixture.layout.totalPoseCount(), "SWEM final camera head");
        assertTrue(differs(before, after), "Whole-body SWEM poses must not erase head queries");
    }

    private static List<String> clips(Fixture fixture, String prefix) {
        // Count the declared namespace, not just the accepted whitelist. An unknown
        // or accidentally omitted official clip must not silently evade coverage.
        return fixture.animations.keySet().stream().filter(name -> name.startsWith(prefix))
                .sorted().toList();
    }

    private static boolean hasMatchingAuthoredPose(Fixture fixture, String clip) {
        return fixture.animations.get(clip).boneTracks().entrySet().stream()
                .anyMatch(entry -> entry.getValue().hasAnyTrack()
                        && fixture.layout.entryForBoneName(entry.getKey()) != null);
    }

    private static Fixture load() throws IOException {
        String configuredRoot = System.getenv("YSM_OFFICIAL_BUILTIN_ROOT");
        assumeTrue(configuredRoot != null && !configuredRoot.isBlank(),
                "YSM_OFFICIAL_BUILTIN_ROOT is not configured");
        Path builtinRoot = Path.of(configuredRoot).toAbsolutePath().normalize().toRealPath();
        assertTrue(Files.isDirectory(builtinRoot), "Official built-in root must be a directory");
        Path modelRoot = confined(builtinRoot, "default");
        JsonObject player = json(confined(modelRoot, "ysm.json"))
                .getAsJsonObject("files").getAsJsonObject("player");
        GeometryDocument geometry = BedrockGeometryParser.parse(Files.readString(confined(
                modelRoot, player.getAsJsonObject("model").get("main").getAsString())));
        assertNotNull(geometry);

        Map<String, AnimationClip> animations = new LinkedHashMap<>();
        for (JsonElement declaration : player.getAsJsonObject("animation").asMap().values()) {
            JsonObject definitions = json(confined(modelRoot, declaration.getAsString()))
                    .getAsJsonObject("animations");
            if (definitions == null) continue;
            definitions.entrySet().forEach(entry -> {
                if (entry.getValue().isJsonObject()) {
                    animations.putIfAbsent(entry.getKey(), BedrockAnimationParser.parse(
                            entry.getKey(), entry.getValue().getAsJsonObject()));
                }
            });
        }
        Map<String, AnimationController> controllers = new LinkedHashMap<>();
        JsonElement declarations = player.get("animation_controllers");
        if (declarations != null && declarations.isJsonArray()) {
            for (JsonElement declaration : declarations.getAsJsonArray()) {
                readController(modelRoot, declaration, controllers);
            }
        } else {
            readController(modelRoot, declarations, controllers);
        }
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry, MODEL_SCALE, MODEL_SCALE);
        ParallelAnimationProgram program = new ParallelAnimationProgram(geometry,
                animations, controllers, layout, MODEL_SCALE, MODEL_SCALE);
        assertFalse(program.isEmpty());
        return new Fixture(animations, layout, program);
    }

    private static void readController(Path modelRoot, JsonElement declaration,
                                       Map<String, AnimationController> target) throws IOException {
        if (declaration != null && declaration.isJsonPrimitive()) {
            BedrockAnimationControllerParser.parse(json(confined(modelRoot,
                    declaration.getAsString()))).forEach(target::putIfAbsent);
        }
    }

    private static Path confined(Path root, String relative) throws IOException {
        Path declared = Path.of(relative);
        if (declared.isAbsolute()) {
            throw new IllegalArgumentException("Fixture declaration must be relative");
        }
        Path normalized = root.resolve(declared).normalize();
        if (!normalized.startsWith(root)) {
            throw new IllegalArgumentException("Fixture path escapes root");
        }
        Path resolved = normalized.toRealPath();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Fixture link escapes root");
        }
        return resolved;
    }

    private static JsonObject json(Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    private static SnapshotExpressionEnvironment environment(double headYaw,
                                                               double headPitch,
                                                               double groundSpeed) {
        Map<Integer, Double> variables = Map.of(
                ExpressionEngine.slot("v.player_size"), 1.0D,
                ExpressionEngine.slot("v.bv"), 1.0D);
        Map<Integer, Double> queries = Map.of(
                ExpressionEngine.querySlot("ysm.head_yaw"), headYaw,
                ExpressionEngine.querySlot("ysm.head_pitch"), headPitch,
                ExpressionEngine.querySlot("query.ground_speed"), groundSpeed,
                ExpressionEngine.querySlot("query.is_on_ground"), 1.0D,
                ExpressionEngine.querySlot("query.life_time"), FRAME_TIME,
                ExpressionEngine.querySlot("query.delta_time"), 0.05D);
        ExpressionEngine.Environment source = new ExpressionEngine.Environment() {
            @Override public double readVariable(int slot) { return variables.getOrDefault(slot, 0.0D); }
            @Override public boolean hasVariable(int slot) { return variables.containsKey(slot); }
            @Override public void writeVariable(int slot, double value) { }
            @Override public double readQuery(int slot) { return queries.getOrDefault(slot, 0.0D); }
            @Override public double invoke(String name, double[] arguments) { return 0.0D; }
            @Override public double invokeWithText(String name, String[] arguments) { return 0.0D; }
        };
        return SnapshotExpressionEnvironment.capture(source, variables.keySet(), queries.keySet());
    }

    private static OpenMatrix4f[] compose(AuxiliaryPoseMatrices composer, Armature armature,
                                          OpenMatrix4f[] epic, ParallelAnimationProgram.Frame frame) {
        return composer.compose(armature, epic, frame.parallelDeltas(), frame.wholeModelDeltas(),
                frame.heldItemDeltas(), frame.replaceEpicFightPose(), frame.replaceEpicFightAnchors(),
                frame.suppressParallelDeltas(), frame.heldItemAnchorJoints(),
                frame.fullBodyBlendSource(), frame.fullBodyBlendWeight(), null, frame.authoredDeltas());
    }

    private static void assertFinite(OpenMatrix4f[] matrices, int expectedLength, String context) {
        assertNotNull(matrices, context);
        assertEquals(expectedLength, matrices.length, context);
        for (int index = 0; index < matrices.length; index++) {
            assertNotNull(matrices[index], context + " pose " + index);
            for (float value : values(matrices[index])) {
                assertTrue(Float.isFinite(value), context + " non-finite pose " + index);
            }
        }
    }

    private static float[] copyValues(OpenMatrix4f[] matrices) {
        assertNotNull(matrices);
        float[] copy = new float[matrices.length * 16];
        for (int index = 0; index < matrices.length; index++) {
            System.arraycopy(values(matrices[index]), 0, copy, index * 16, 16);
        }
        return copy;
    }

    private static boolean differs(float[] before, OpenMatrix4f[] after) {
        for (int index = 0; index < after.length; index++) {
            float[] current = values(after[index]);
            for (int component = 0; component < current.length; component++) {
                if (Math.abs(before[index * 16 + component] - current[component]) > 0.00001F) {
                    return true;
                }
            }
        }
        return false;
    }

    private static float[] values(OpenMatrix4f matrix) {
        return new float[]{matrix.m00, matrix.m01, matrix.m02, matrix.m03,
                matrix.m10, matrix.m11, matrix.m12, matrix.m13,
                matrix.m20, matrix.m21, matrix.m22, matrix.m23,
                matrix.m30, matrix.m31, matrix.m32, matrix.m33};
    }

    private static OpenMatrix4f[] identityPoses() {
        OpenMatrix4f[] poses = new OpenMatrix4f[HumanoidRig.EPIC_JOINT_COUNT];
        Arrays.setAll(poses, index -> new OpenMatrix4f());
        return poses;
    }

    private static Armature armature() {
        String[] names = {"Root", "Thigh_R", "Leg_R", "Knee_R", "Thigh_L", "Leg_L", "Knee_L",
                "Torso", "Chest", "Head", "Shoulder_R", "Arm_R", "Hand_R", "Tool_R", "Elbow_R",
                "Shoulder_L", "Arm_L", "Hand_L", "Tool_L", "Elbow_L"};
        Map<String, Joint> joints = new LinkedHashMap<>();
        Joint root = new Joint(names[0], 0, new OpenMatrix4f());
        joints.put(names[0], root);
        for (int index = 1; index < names.length; index++) {
            Joint joint = new Joint(names[index], index, new OpenMatrix4f());
            root.addSubJoints(joint);
            joints.put(names[index], joint);
        }
        Armature result = new Armature("official-mod-animation-test", names.length, root, joints);
        root.initOriginTransform(new OpenMatrix4f());
        return result;
    }

    private record Fixture(Map<String, AnimationClip> animations,
                           AuxiliaryBoneLayout layout, ParallelAnimationProgram program) { }
}
