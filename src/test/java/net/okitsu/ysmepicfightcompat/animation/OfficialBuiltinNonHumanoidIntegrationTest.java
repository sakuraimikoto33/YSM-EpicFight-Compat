package net.okitsu.ysmepicfightcompat.animation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.okitsu.ysmepicfightcompat.geometry.BedrockGeometryParser;
import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import net.okitsu.ysmepicfightcompat.mesh.AuxiliaryBoneLayout;
import net.okitsu.ysmepicfightcompat.mesh.AuxiliaryPoseMatrices;
import net.okitsu.ysmepicfightcompat.mesh.HandLocatorSelection;
import net.okitsu.ysmepicfightcompat.mesh.HumanoidRig;
import org.junit.jupiter.api.Test;
import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.api.utils.math.Vec3f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** External, opt-in official assets only; no source model or extracted data is copied. */
class OfficialBuiltinNonHumanoidIntegrationTest {
    @Test
    void finalBodyCompositionSelectsTheVisibleMouthWithoutFollowingEpicLimbs() throws IOException {
        Fixture fixture = load();
        AuxiliaryPoseMatrices composer = new AuxiliaryPoseMatrices(fixture.layout);
        Armature armature = armature();
        OpenMatrix4f[] epic = new OpenMatrix4f[20];
        java.util.Arrays.setAll(epic, index -> new OpenMatrix4f());
        for (boolean animal : new boolean[]{false, true, false}) {
            ParallelAnimationProgram.Frame frame = fixture.program.sampleAutomaticAt(
                    0.2, List.of("walk"), "walk", new Environment(0.2, animal));
            OpenMatrix4f[] complete = compose(composer, armature, epic, frame);
            assertNotNull(complete);
            for (int tool : new int[]{HumanoidRig.RIGHT_TOOL, HumanoidRig.LEFT_TOOL}) {
                String name = tool == HumanoidRig.RIGHT_TOOL
                        ? "RightHandLocator" : "LeftHandLocator";
                HandLocatorSelection selected = composer.selectFormHandLocator(
                        complete, frame.hiddenBones(), tool);
                assertEquals(HandLocatorSelection.Status.VISIBLE, selected.status());
                assertEquals(name + (animal ? "2" : ""), selected.locator().bone().name());
                OpenMatrix4f itemPose = composer.formHeldItemPose(complete, selected);
                if (animal) {
                    assertNotNull(itemPose);
                    float[] expected = values(itemPose);
                    epic[HumanoidRig.HEAD].translate(20, 30, 40).rotateDeg(85, Vec3f.X_AXIS);
                    epic[HumanoidRig.RIGHT_ARM].translate(-50, 60, -70).rotateDeg(-120, Vec3f.Y_AXIS);
                    complete = compose(composer, armature, epic, frame);
                    assertArrayEquals(expected, values(composer.formHeldItemPose(complete,
                            composer.selectFormHandLocator(complete, frame.hiddenBones(), tool))),
                            0.0001F, name);
                    assertEquals(epic[HumanoidRig.HEAD].m30, complete[HumanoidRig.HEAD].m30);
                } else {
                    assertNull(itemPose, "Human form keeps Epic Fight's ordinary grip");
                }
            }
        }
    }

    @Test
    void nativeFoxLocomotionMatchesTheAuthoredHierarchyWithoutOwningTheHumanoidPose()
            throws IOException {
        Fixture fixture = load();
        for (String bone : List.of("FOX", "Head2", "LeftArm2", "RightArm2",
                "LeftLeg2", "RightLeg2", "LeftHandLocator2", "RightHandLocator2")) {
            assertTrue(fixture.layout.rigBindings().usesAuthoredPose(
                    fixture.geometry.bones().get(bone)), bone);
        }
        assertFalse(fixture.layout.rigBindings().usesAuthoredPose(
                fixture.geometry.bones().get("AllBody")));

        for (double time : new double[]{0.1, 0.35, 1.25}) {
            ParallelAnimationProgram.Frame nativeFrame = fixture.program.sampleAutomaticAt(
                    time, List.of("walk"), "walk", new Environment(time, true));
            assertFalse(nativeFrame.replaceEpicFightPose());
            assertTrue(nativeFrame.itemSwitchHands().isEmpty());
            assertNotNull(nativeFrame.authoredDeltas());
            Map<String, float[]> nativeMatrices = new LinkedHashMap<>();
            for (String bone : List.of("LeftArm2", "RightArm2", "LeftLeg2", "RightLeg2",
                    "Head2", "LeftHandLocator2", "RightHandLocator2")) {
                int index = fixture.layout.entryForBoneName(bone).auxiliaryIndex();
                nativeMatrices.put(bone, values(nativeFrame.authoredDeltas()[index]));
            }

            // The established full-body composition is an independent numeric
            // reference. Its human camera post does not belong to the animal branch.
            ParallelAnimationProgram.Frame fullBody = fixture.program.sampleMovementAt(
                    time, List.of("walk"), "walk", MovementAnimationType.WALK,
                    new Environment(time, true), new AnimationControllerProgram.RuntimeState());
            assertTrue(fullBody.replaceEpicFightPose());
            nativeMatrices.forEach((bone, expected) -> {
                int index = fixture.layout.entryForBoneName(bone).auxiliaryIndex();
                OpenMatrix4f reference = new OpenMatrix4f(fullBody.parallelDeltas()[index]);
                reference.mulFront(fullBody.wholeModelDeltas()[index]);
                assertArrayEquals(expected, values(reference), 0.0001F, bone + " at " + time);
            });
        }
    }

    @Test
    void changingTheAuthoredFormUpdatesBothLocatorBranchesInTheSameFrame() throws IOException {
        Fixture fixture = load();
        ParallelAnimationProgram.Frame humanoid = fixture.program.sampleAutomaticAt(
                0.1, List.of("walk"), "walk", new Environment(0.1, false));
        assertTrue(humanoid.hiddenBones().contains("RightHandLocator2"));
        assertTrue(humanoid.hiddenBones().contains("LeftHandLocator2"));
        assertFalse(humanoid.hiddenBones().contains("RightHandLocator"));

        ParallelAnimationProgram.Frame animal = fixture.program.sampleAutomaticAt(
                0.2, List.of("walk"), "walk", new Environment(0.2, true));
        assertFalse(animal.hiddenBones().contains("RightHandLocator2"));
        assertFalse(animal.hiddenBones().contains("LeftHandLocator2"));
        assertTrue(animal.hiddenBones().contains("RightHandLocator"));
        assertTrue(animal.hiddenBones().contains("LeftHandLocator"));
        assertFalse(animal.replaceEpicFightPose());
    }

    private static Fixture load() throws IOException {
        String configuredRoot = System.getenv("YSM_OFFICIAL_BUILTIN_ROOT");
        assumeTrue(configuredRoot != null && !configuredRoot.isBlank(),
                "YSM_OFFICIAL_BUILTIN_ROOT is not configured");
        Path builtinRoot = Path.of(configuredRoot).toAbsolutePath().normalize();
        Path modelRoot = confined(builtinRoot, "wine_fox/19_nine_tailed");
        assumeTrue(Files.isDirectory(modelRoot), "Official non-humanoid fixture is unavailable");
        JsonObject player = player(modelRoot);
        GeometryDocument geometry = BedrockGeometryParser.parse(Files.readString(confined(
                modelRoot, player.getAsJsonObject("model").get("main").getAsString())));
        assertNotNull(geometry);
        Map<String, AnimationClip> animations = new LinkedHashMap<>();
        readAnimations(modelRoot, player, animations);
        Path primaryRoot = confined(builtinRoot, "default");
        readAnimations(primaryRoot, player(primaryRoot), animations);
        Map<String, AnimationController> controllers = new LinkedHashMap<>();
        JsonElement declarations = player.get("animation_controllers");
        if (declarations != null && declarations.isJsonArray()) {
            for (JsonElement declaration : declarations.getAsJsonArray()) {
                readController(modelRoot, declaration, controllers);
            }
        } else {
            readController(modelRoot, declarations, controllers);
        }
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry, 0.7F, 0.7F);
        ParallelAnimationProgram program = new ParallelAnimationProgram(geometry,
                animations, controllers, layout, 0.7F, 0.7F);
        return new Fixture(geometry, layout, program);
    }

    private static JsonObject player(Path modelRoot) throws IOException {
        return json(modelRoot.resolve("ysm.json")).getAsJsonObject("files")
                .getAsJsonObject("player");
    }

    private static void readAnimations(Path modelRoot, JsonObject player,
                                       Map<String, AnimationClip> target) throws IOException {
        for (JsonElement declaration : player.getAsJsonObject("animation").asMap().values()) {
            JsonObject definitions = json(confined(modelRoot, declaration.getAsString()))
                    .getAsJsonObject("animations");
            if (definitions == null) continue;
            definitions.entrySet().forEach(entry -> {
                if (entry.getValue().isJsonObject()) {
                    target.putIfAbsent(entry.getKey(), BedrockAnimationParser.parse(
                            entry.getKey(), entry.getValue().getAsJsonObject()));
                }
            });
        }
    }

    private static void readController(Path modelRoot, JsonElement declaration,
                                       Map<String, AnimationController> target) throws IOException {
        if (declaration != null && declaration.isJsonPrimitive()) {
            BedrockAnimationControllerParser.parse(json(confined(modelRoot,
                    declaration.getAsString()))).forEach(target::putIfAbsent);
        }
    }

    private static Path confined(Path root, String relative) {
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root)) throw new IllegalArgumentException("Fixture path escapes root");
        return path;
    }

    private static JsonObject json(Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    private static float[] values(OpenMatrix4f matrix) {
        return new float[]{matrix.m00, matrix.m01, matrix.m02, matrix.m03,
                matrix.m10, matrix.m11, matrix.m12, matrix.m13,
                matrix.m20, matrix.m21, matrix.m22, matrix.m23,
                matrix.m30, matrix.m31, matrix.m32, matrix.m33};
    }

    private static OpenMatrix4f[] compose(AuxiliaryPoseMatrices composer, Armature armature,
                                          OpenMatrix4f[] epic, ParallelAnimationProgram.Frame frame) {
        return composer.compose(armature, epic, frame.parallelDeltas(), frame.wholeModelDeltas(),
                frame.heldItemDeltas(), frame.replaceEpicFightPose(), frame.replaceEpicFightAnchors(),
                frame.suppressParallelDeltas(), frame.heldItemAnchorJoints(),
                frame.fullBodyBlendSource(), frame.fullBodyBlendWeight(), null, frame.authoredDeltas());
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
        Armature result = new Armature("official-form-test", names.length, root, joints);
        root.initOriginTransform(new OpenMatrix4f());
        return result;
    }

    private record Fixture(GeometryDocument geometry, AuxiliaryBoneLayout layout,
                           ParallelAnimationProgram program) { }

    private static final class Environment implements ExpressionEngine.Environment {
        private final Map<Integer, Double> variables = new LinkedHashMap<>();
        private final double time;
        private final SnapshotExpressionEnvironment arithmetic;

        private Environment(double time, boolean animal) {
            this.time = time;
            writeVariable(ExpressionEngine.slot("v.roaming.a"), animal ? 1 : 0);
            writeVariable(ExpressionEngine.slot("v.roaming.b"), 0);
            writeVariable(ExpressionEngine.slot("v.player_size"), 1);
            arithmetic = SnapshotExpressionEnvironment.capture(this, Set.of(), Set.of());
        }

        @Override public double readVariable(int slot) { return variables.getOrDefault(slot, 0.0D); }
        @Override public boolean hasVariable(int slot) { return variables.containsKey(slot); }
        @Override public void writeVariable(int slot, double value) { variables.put(slot, value); }
        @Override public double readQuery(int slot) {
            return ExpressionEngine.slotName(slot).equals("query.anim_time") ? time : 0;
        }
        @Override public double invoke(String name, double[] arguments) {
            return arithmetic.invoke(name, arguments);
        }
        @Override public double invokeWithText(String name, String[] arguments) { return 0; }
    }
}
