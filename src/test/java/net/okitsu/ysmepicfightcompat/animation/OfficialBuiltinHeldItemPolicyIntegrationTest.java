package net.okitsu.ysmepicfightcompat.animation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.world.InteractionHand;
import net.okitsu.ysmepicfightcompat.geometry.BedrockGeometryParser;
import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import net.okitsu.ysmepicfightcompat.mesh.AuxiliaryBoneLayout;
import net.okitsu.ysmepicfightcompat.mesh.HumanoidRig;
import org.junit.jupiter.api.Test;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Optional integration checks against an external official-YSM source tree.
 *
 * <p>The model assets are intentionally not copied into this repository. Set
 * {@code YSM_OFFICIAL_BUILTIN_ROOT} to the directory containing the official
 * {@code wine_fox/05_magical}, {@code wine_fox/21_saint}, and
 * {@code wine_fox/22_elf} model directories to run these checks.</p>
 */
class OfficialBuiltinHeldItemPolicyIntegrationTest {
    @Test
    void magicalStaffReplacesSwordAndBowButNotOrdinaryTools() throws IOException {
        Fixture fixture = load("wine_fox/05_magical");
        CustomHeldItemPolicy policy = CustomHeldItemPolicy.create(
                fixture.geometry(), fixture.animations());

        assertContains(policy, InteractionHand.MAIN_HAND,
                AnimationConditionMatcher.ItemAction.HOLD, ":sword", ":bow");
        assertContains(policy, InteractionHand.MAIN_HAND,
                AnimationConditionMatcher.ItemAction.USE, ":bow");
        assertContains(policy, InteractionHand.MAIN_HAND,
                AnimationConditionMatcher.ItemAction.SWING, ":sword", ":bow");
        assertFalse(policy.selectors(InteractionHand.MAIN_HAND).contains(":pickaxe"));
        assertFalse(policy.selectors(InteractionHand.MAIN_HAND).contains(":shovel"));
        assertTrue(policy.replacementRoots(InteractionHand.MAIN_HAND,
                        AnimationConditionMatcher.ItemAction.HOLD, ":sword")
                .contains("mofazhang"));
        assertTrue(policy.replacementRoots(InteractionHand.MAIN_HAND,
                        AnimationConditionMatcher.ItemAction.HOLD, ":bow")
                .contains("mofazhang"));
        Map<String, Boolean> visibility = DefaultPoseProgram.calculateVisibility(
                fixture.geometry(), fixture.animations());
        assertTrue(Boolean.TRUE.equals(visibility.get("mofazhang")));
        assertFalse(Boolean.TRUE.equals(visibility.get("mofazhang2")));
        assertScaleVisibilityAtEnd(fixture, "hold_mainhand:sword",
                "mofazhang", true);
        assertScaleVisibilityAtEnd(fixture, "hold_mainhand:sword",
                "mofazhang2", false);
        assertScaleVisibilityAtEnd(fixture, "hold_mainhand:bow",
                "mofazhang", true);
        assertScaleVisibilityAtEnd(fixture, "hold_mainhand:bow",
                "mofazhang2", false);
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(fixture.geometry());
        assertEquals(HumanoidRig.RIGHT_HAND,
                HumanoidRig.jointFor(fixture.geometry().bones().get("mofazhang")));
        ParallelAnimationProgram program = program(fixture, layout);
        ParallelAnimationProgram.Frame idle = program.sampleAt(
                0.0D, new NeutralEnvironment());
        assertTrue(idle.hiddenBones().contains("mofazhang"));
        assertFalse(idle.hiddenBones().contains("mofazhang2"));
        ParallelAnimationProgram.Frame held = program.sampleAutomaticAt(
                2.0D, List.of("hold_mainhand:bow"), new NeutralEnvironment());
        assertFalse(held.hiddenBones().contains("mofazhang"));
        assertTrue(held.hiddenBones().contains("mofazhang2"));
        assertToolAttachment(held, layout, "mofazhang");
        assertFalse(held.replaceEpicFightAnchors()[
                layout.entryForBoneName("RightArm").auxiliaryIndex()]);
        assertFalse(held.replaceEpicFightAnchors()[
                layout.entryForBoneName("LeftArm").auxiliaryIndex()]);
        ParallelAnimationProgram.Frame drawing = program.sampleAutomaticAt(
                0.4D, List.of("idle", "hold_mainhand:bow", "use_mainhand:bow"),
                new NeutralEnvironment());
        assertFalse(drawing.hiddenBones().contains("mofazhang"));
        assertTrue(drawing.hiddenBones().contains("mofazhang2"));
        assertFullBodyBowPose(drawing, layout,
                "mofazhang", "AllBody", "RightArm", "LeftArm", "Head");
        assertSuppressesParallelOnlyForProp(drawing, layout,
                "mofazhang", "RightArm", "LeftArm");
        assertMagicCircleContinuesThroughAuthoredBowDuration(program, layout);
        assertOfficialBowAimRespondsToHeadYaw(program, layout, "mofazhang");
        ParallelAnimationProgram.Frame releasing = program.sampleAutomaticAt(
                0.2D, List.of("idle", "hold_mainhand:bow", "swing:bow"),
                new NeutralEnvironment());
        assertFullBodyBowPose(releasing, layout,
                "mofazhang", "AllBody", "RightArm", "LeftArm", "Head");
        ParallelAnimationProgram.Frame attacking = program.sampleAutomaticAt(
                0.2D, List.of("swing:sword"), new NeutralEnvironment());
        assertFalse(attacking.hiddenBones().contains("mofazhang"));
        assertTrue(attacking.hiddenBones().contains("mofazhang2"));
        assertFalse(attacking.replaceEpicFightPose());
        assertFalse(isIdentity(attacking.heldItemDeltas()[
                layout.entryForBoneName("mofazhang").auxiliaryIndex()]));
        assertTrue(attacking.replaceEpicFightAnchors()[
                layout.entryForBoneName("mofazhang").auxiliaryIndex()]);
        assertTrue(attacking.suppressParallelDeltas()[
                layout.entryForBoneName("mofazhang").auxiliaryIndex()]);
        assertFalse(attacking.replaceEpicFightAnchors()[
                layout.entryForBoneName("RightArm").auxiliaryIndex()]);
        assertToolAttachment(attacking, layout, "mofazhang");
    }

    @Test
    void saintWeaponReplacesSwordBowAndSpearOnlyWhileTheirConditionsApply()
            throws IOException {
        Fixture fixture = load("wine_fox/21_saint");
        CustomHeldItemPolicy policy = CustomHeldItemPolicy.create(
                fixture.geometry(), fixture.animations());

        assertContains(policy, InteractionHand.MAIN_HAND,
                AnimationConditionMatcher.ItemAction.HOLD,
                ":sword", ":sword2", ":bow", ":bow2", ":spear");
        assertContains(policy, InteractionHand.MAIN_HAND,
                AnimationConditionMatcher.ItemAction.USE, ":bow", ":spear");
        assertContains(policy, InteractionHand.MAIN_HAND,
                AnimationConditionMatcher.ItemAction.SWING,
                ":sword2", ":bow", ":spear");
        // The ordinary sword clip is intentionally a timeline-only controller trigger.
        // Its HOLD rule keeps Epic Fight's item suppressed while the controller animates
        // the custom prop during the attack.
        AnimationClip swordSwing = fixture.animations().get("swing:sword");
        assertTrue(swordSwing.boneTracks().isEmpty());
        assertFalse(swordSwing.timeline().isEmpty());
        assertTrue(BedrockAnimationParser.isAutomatic("swing:sword"));
        AnimationController attack = fixture.controllers().get("player.post_swing");
        assertNotNull(attack);
        assertTrue(attack.states().get("剑1").animations().stream()
                .anyMatch(reference -> reference.name().equals("sword_idle_attack1")));
        assertFalse(policy.selectors(InteractionHand.MAIN_HAND).contains(":empty"));
        assertFalse(policy.selectors(InteractionHand.MAIN_HAND).contains(":pickaxe"));
        assertTrue(policy.replacementRoots(InteractionHand.MAIN_HAND,
                        AnimationConditionMatcher.ItemAction.HOLD, ":sword")
                .contains("magicstick"));
        assertTrue(policy.replacementRoots(InteractionHand.MAIN_HAND,
                        AnimationConditionMatcher.ItemAction.HOLD, ":spear")
                .contains("magicstick"));
        Map<String, Boolean> visibility = DefaultPoseProgram.calculateVisibility(
                fixture.geometry(), fixture.animations());
        assertTrue(Boolean.TRUE.equals(visibility.get("MagicStick")));
        // MagicStick2 is an animation-only control in this model and must not be
        // mistaken for a renderable geometry bone.
        assertFalse(visibility.containsKey("MagicStick2"));
        assertScaleVisibilityAtEnd(fixture, "hold_mainhand:sword",
                "MagicStick", true);
        assertScaleVisibilityAtEnd(fixture, "hold_mainhand:bow",
                "MagicStick", true);
        assertScaleVisibilityAtEnd(fixture, "hold_mainhand:spear",
                "MagicStick", true);
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(fixture.geometry());
        assertEquals(HumanoidRig.RIGHT_HAND,
                HumanoidRig.jointFor(fixture.geometry().bones().get("MagicStick")));
        ParallelAnimationProgram program = program(fixture, layout);
        ParallelAnimationProgram.Frame held = program.sampleAutomaticAt(
                0.4D, List.of("hold_mainhand:spear"), new NeutralEnvironment());
        assertFalse(held.hiddenBones().contains("magicstick"));
        assertFalse(held.replaceEpicFightPose());
        assertFalse(isIdentity(held.heldItemDeltas()[
                layout.entryForBoneName("MagicStick").auxiliaryIndex()]));
        assertTrue(held.replaceEpicFightAnchors()[
                layout.entryForBoneName("MagicStick").auxiliaryIndex()]);
        assertTrue(held.suppressParallelDeltas()[
                layout.entryForBoneName("MagicStick").auxiliaryIndex()]);
        assertFalse(held.replaceEpicFightAnchors()[
                layout.entryForBoneName("RightArm").auxiliaryIndex()]);
        assertToolAttachment(held, layout, "MagicStick");
        OpenMatrix4f heldOnly = new OpenMatrix4f().load(held.heldItemDeltas()[
                layout.entryForBoneName("MagicStick").auxiliaryIndex()]);
        NeutralEnvironment controllerEnvironment = new NeutralEnvironment();
        controllerEnvironment.writeVariable(
                ExpressionEngine.slot("v.swing_sword"), 1.0D);
        controllerEnvironment.writeVariable(
                ExpressionEngine.slot("v.attack"), 0.0D);
        AnimationControllerProgram.RuntimeState controllerState =
                new AnimationControllerProgram.RuntimeState();
        program.sampleAutomaticAndControllersAt(0.0D,
                List.of("hold_mainhand:sword", "swing:sword"),
                controllerEnvironment, controllerState);
        ParallelAnimationProgram.Frame controlledAttack =
                program.sampleAutomaticAndControllersAt(0.1D,
                        List.of("hold_mainhand:sword", "swing:sword"),
                        controllerEnvironment, controllerState);
        int magicStick = layout.entryForBoneName("MagicStick").auxiliaryIndex();
        assertTrue(controlledAttack.replaceEpicFightAnchors()[magicStick]);
        assertTrue(controlledAttack.suppressParallelDeltas()[magicStick]);
        assertToolAttachment(controlledAttack, layout, "MagicStick");
        assertTrue(matrixDiffers(heldOnly,
                        controlledAttack.heldItemDeltas()[magicStick]),
                "post_swing must animate the custom weapon through its authored parent chain");
        ParallelAnimationProgram.Frame charging = program.sampleAutomaticAt(
                0.4D, List.of("hold_mainhand:spear", "use_mainhand:spear"),
                new NeutralEnvironment());
        assertFalse(charging.hiddenBones().contains("magicstick"));
        assertFalse(charging.replaceEpicFightPose());
        assertFalse(isIdentity(charging.heldItemDeltas()[
                layout.entryForBoneName("MagicStick").auxiliaryIndex()]));
        assertToolAttachment(charging, layout, "MagicStick");
        ParallelAnimationProgram.Frame attacking = program.sampleAutomaticAt(
                0.3D, List.of("swing:spear"), new NeutralEnvironment());
        assertFalse(attacking.hiddenBones().contains("magicstick"));
        assertFalse(attacking.replaceEpicFightPose());
        assertFalse(isIdentity(attacking.heldItemDeltas()[
                layout.entryForBoneName("MagicStick").auxiliaryIndex()]));
        assertToolAttachment(attacking, layout, "MagicStick");
        ParallelAnimationProgram.Frame bowDrawing = program.sampleAutomaticAt(
                0.4D, List.of("idle", "hold_mainhand:bow", "use_mainhand:bow"),
                new NeutralEnvironment());
        assertFullBodyBowPose(bowDrawing, layout,
                "MagicStick", "AllBody", "RightArm", "LeftArm", "Head");
        ParallelAnimationProgram.Frame bowRelease = program.sampleAutomaticAt(
                0.2D, List.of("idle", "hold_mainhand:bow", "swing:bow"),
                new NeutralEnvironment());
        assertFullBodyBowPose(bowRelease, layout,
                "MagicStick", "AllBody", "RightArm", "LeftArm", "Head");
        assertOfficialBowAimRespondsToHeadYaw(program, layout, "MagicStick");
    }

    @Test
    void elfBowMagicEffectDoesNotReplaceEpicFightsBowOrBodyPose()
            throws IOException {
        Fixture fixture = load("wine_fox/22_elf");
        CustomHeldItemPolicy policy = CustomHeldItemPolicy.create(
                fixture.geometry(), fixture.animations());

        assertFalse(policy.selectors(InteractionHand.MAIN_HAND,
                AnimationConditionMatcher.ItemAction.HOLD).contains(":bow"));
        assertFalse(policy.selectors(InteractionHand.MAIN_HAND,
                AnimationConditionMatcher.ItemAction.USE).contains(":bow"));
        assertTrue(policy.epicItemEffectRoots("use_mainhand:bow").contains("shengyin"));
        assertFalse(policy.replacesBodyPose("use_mainhand:bow"));

        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(fixture.geometry());
        ParallelAnimationProgram program = program(fixture, layout);
        ParallelAnimationProgram.Frame drawing = program.sampleAutomaticAt(
                0.5D, List.of("idle", "hold_mainhand:bow", "use_mainhand:bow"),
                new NeutralEnvironment());
        int effect = layout.entryForBoneName("Shengyin").auxiliaryIndex();
        assertFalse(drawing.replaceEpicFightPose());
        assertTrue(drawing.replaceEpicFightAnchors()[effect]);
        assertEquals(HumanoidRig.LEFT_TOOL, drawing.heldItemAnchorJoints()[effect],
                "22_elf's effect must follow Epic Fight's left-hand bow seam");
        assertTrue(drawing.suppressParallelDeltas()[effect],
                "pre_parallel's hidden scale must not erase the active effect");
        assertFalse(isIdentity(drawing.heldItemDeltas()[effect]),
                "22_elf's private bow-use effect must remain animated");
    }

    private static void assertOfficialBowAimRespondsToHeadYaw(
            ParallelAnimationProgram program, AuxiliaryBoneLayout layout,
            String heldItemBone) {
        List<String> clips = List.of("idle", "hold_mainhand:bow", "use_mainhand:bow");
        int upperBody = layout.entryForBoneName("UpBody").auxiliaryIndex();
        int heldItem = layout.entryForBoneName(heldItemBone).auxiliaryIndex();
        ParallelAnimationProgram.Frame centered = program.sampleAutomaticAt(
                2.0D, clips, new NeutralEnvironment().headYaw(0.0D));
        OpenMatrix4f centeredUpperBody = new OpenMatrix4f().load(
                centered.wholeModelDeltas()[upperBody]);
        OpenMatrix4f centeredHeldItem = new OpenMatrix4f().load(
                centered.wholeModelDeltas()[heldItem]);
        ParallelAnimationProgram.Frame aimed = program.sampleAutomaticAt(
                2.0D, clips, new NeutralEnvironment().headYaw(-50.0D));

        assertTrue(matrixDiffers(centeredUpperBody,
                        aimed.wholeModelDeltas()[upperBody]),
                "official one-sided Molang must rotate the complete bow upper body");
        assertTrue(matrixDiffers(centeredHeldItem,
                        aimed.wholeModelDeltas()[heldItem]),
                "the custom bow must follow the same official aiming correction");
    }

    private static void assertMagicCircleContinuesThroughAuthoredBowDuration(
            ParallelAnimationProgram program, AuxiliaryBoneLayout layout) {
        int circle = layout.entryForBoneName("ysmGlowmofazhen").auxiliaryIndex();
        List<String> clips = List.of("idle", "hold_mainhand:bow", "use_mainhand:bow");
        OpenMatrix4f atTen = new OpenMatrix4f().load(program.sampleAutomaticAt(
                10.0D, clips, new NeutralEnvironment()).wholeModelDeltas()[circle]);
        OpenMatrix4f atTenAndHalf = new OpenMatrix4f().load(program.sampleAutomaticAt(
                10.5D, clips, new NeutralEnvironment()).wholeModelDeltas()[circle]);
        assertTrue(matrixDiffers(atTen, atTenAndHalf),
                "05_magical's magic circle must keep rotating during a long draw");

        OpenMatrix4f atFiftyNine = new OpenMatrix4f().load(program.sampleAutomaticAt(
                59.0D, clips, new NeutralEnvironment()).wholeModelDeltas()[circle]);
        OpenMatrix4f atFiftyNineAndHalf = new OpenMatrix4f().load(program.sampleAutomaticAt(
                59.5D, clips, new NeutralEnvironment()).wholeModelDeltas()[circle]);
        assertTrue(matrixDiffers(atFiftyNine, atFiftyNineAndHalf),
                "05_magical's magic circle must not stop before its 60-second clip ends");

        OpenMatrix4f heldAtEnd = new OpenMatrix4f().load(program.sampleAutomaticAt(
                60.0D, clips, new NeutralEnvironment()).wholeModelDeltas()[circle]);
        OpenMatrix4f heldAfterEnd = new OpenMatrix4f().load(program.sampleAutomaticAt(
                61.0D, clips, new NeutralEnvironment()).wholeModelDeltas()[circle]);
        assertFalse(matrixDiffers(heldAtEnd, heldAfterEnd),
                "hold_on_last_frame must retain the final magic-circle pose");
    }

    private static void assertScaleVisibilityAtEnd(Fixture fixture, String animation,
                                                   String bone, boolean visible) {
        AnimationClip clip = fixture.animations().get(animation);
        assertNotNull(clip, () -> "Missing official animation " + animation);
        AnimationClip.BoneTracks tracks = clip.boneTracks().get(bone);
        assertNotNull(tracks, () -> "Missing " + bone + " tracks in " + animation);
        assertNotNull(tracks.scale(), () -> "Missing " + bone + " scale in " + animation);
        AnimationClip.VectorValue value = tracks.scale().keyframes()
                .get(tracks.scale().keyframes().size() - 1).value();
        boolean sampledVisible = true;
        for (int axis = 0; axis < 3; axis++) {
            if (value.expression(axis) != null || Math.abs(value.constant(axis)) < 0.01D) {
                sampledVisible = false;
            }
        }
        boolean actual = sampledVisible;
        assertTrue(actual == visible,
                () -> animation + '/' + bone + " end visibility was " + actual);
    }

    private static void assertContains(CustomHeldItemPolicy policy, InteractionHand hand,
                                       AnimationConditionMatcher.ItemAction action,
                                       String... expected) {
        Set<String> selectors = policy.selectors(hand, action);
        for (String selector : expected) {
            assertTrue(selectors.contains(selector),
                    () -> "Missing " + action + selector + " from " + selectors);
        }
    }

    private static void assertSuppressesParallelOnlyForProp(
            ParallelAnimationProgram.Frame frame, AuxiliaryBoneLayout layout,
            String prop, String... bodyBones) {
        assertTrue(frame.suppressParallelDeltas()[
                layout.entryForBoneName(prop).auxiliaryIndex()]);
        for (String bone : bodyBones) {
            assertFalse(frame.suppressParallelDeltas()[
                            layout.entryForBoneName(bone).auxiliaryIndex()],
                    () -> "Parallel animation should remain active for " + bone);
        }
    }

    private static void assertToolAttachment(ParallelAnimationProgram.Frame frame,
                                             AuxiliaryBoneLayout layout,
                                             String... bones) {
        for (String bone : bones) {
            int auxiliary = layout.entryForBoneName(bone).auxiliaryIndex();
            assertEquals(HumanoidRig.RIGHT_TOOL,
                    frame.heldItemAnchorJoints()[auxiliary],
                    () -> bone + " must follow Epic Fight's right Tool joint");
        }
    }

    private static void assertFullBodyBowPose(ParallelAnimationProgram.Frame frame,
                                              AuxiliaryBoneLayout layout,
                                              String... bones) {
        assertTrue(frame.replaceEpicFightPose(),
                "drawing a model-authored bow must use one complete YSM pose");
        boolean animated = false;
        for (String bone : bones) {
            int auxiliary = layout.entryForBoneName(bone).auxiliaryIndex();
            assertFalse(frame.replaceEpicFightAnchors()[auxiliary],
                    () -> bone + " must not be split onto an Epic Fight limb seam");
            assertEquals(-1, frame.heldItemAnchorJoints()[auxiliary],
                    () -> bone + " must remain in the complete YSM hierarchy");
            animated |= !isIdentity(frame.wholeModelDeltas()[auxiliary]);
        }
        assertTrue(animated, "the complete YSM hierarchy must receive the bow pose");
    }

    private static Fixture load(String modelId) throws IOException {
        String configuredRoot = System.getenv("YSM_OFFICIAL_BUILTIN_ROOT");
        assumeTrue(configuredRoot != null && !configuredRoot.isBlank(),
                "YSM_OFFICIAL_BUILTIN_ROOT is not configured");
        Path builtinRoot = Path.of(configuredRoot).toAbsolutePath().normalize();
        Path modelRoot = builtinRoot.resolve(modelId).normalize();
        assumeTrue(modelRoot.startsWith(builtinRoot) && Files.isDirectory(modelRoot),
                "Official built-in model is unavailable: " + modelRoot);

        JsonObject manifest = json(modelRoot.resolve("ysm.json"));
        JsonObject player = manifest.getAsJsonObject("files").getAsJsonObject("player");
        String geometryPath = player.getAsJsonObject("model").get("main").getAsString();
        GeometryDocument geometry = BedrockGeometryParser.parse(
                Files.readString(confined(modelRoot, geometryPath)));

        Map<String, AnimationClip> animations = new LinkedHashMap<>();
        for (JsonElement location : player.getAsJsonObject("animation").asMap().values()) {
            JsonObject root = json(confined(modelRoot, location.getAsString()));
            JsonObject definitions = root.getAsJsonObject("animations");
            if (definitions == null) {
                continue;
            }
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
                readControllers(modelRoot, declaration, controllers);
            }
        } else {
            readControllers(modelRoot, declarations, controllers);
        }
        return new Fixture(geometry, animations, controllers);
    }

    private static void readControllers(Path modelRoot, JsonElement declaration,
                                        Map<String, AnimationController> target)
            throws IOException {
        if (declaration == null || !declaration.isJsonPrimitive()) {
            return;
        }
        BedrockAnimationControllerParser.parse(json(confined(
                modelRoot, declaration.getAsString()))).forEach(target::putIfAbsent);
    }

    private static ParallelAnimationProgram program(Fixture fixture,
                                                    AuxiliaryBoneLayout layout) {
        ParallelAnimationProgram program = new ParallelAnimationProgram(
                fixture.geometry(), fixture.animations(), fixture.controllers(), layout,
                0.7F, 0.7F);
        assertFalse(program.isEmpty());
        return program;
    }

    private static boolean isIdentity(OpenMatrix4f matrix) {
        return Math.abs(matrix.m00 - 1.0F) < 0.00001F
                && Math.abs(matrix.m11 - 1.0F) < 0.00001F
                && Math.abs(matrix.m22 - 1.0F) < 0.00001F
                && Math.abs(matrix.m33 - 1.0F) < 0.00001F
                && Math.abs(matrix.m01) < 0.00001F
                && Math.abs(matrix.m02) < 0.00001F
                && Math.abs(matrix.m03) < 0.00001F
                && Math.abs(matrix.m10) < 0.00001F
                && Math.abs(matrix.m12) < 0.00001F
                && Math.abs(matrix.m13) < 0.00001F
                && Math.abs(matrix.m20) < 0.00001F
                && Math.abs(matrix.m21) < 0.00001F
                && Math.abs(matrix.m23) < 0.00001F
                && Math.abs(matrix.m30) < 0.00001F
                && Math.abs(matrix.m31) < 0.00001F
                && Math.abs(matrix.m32) < 0.00001F;
    }

    private static boolean matrixDiffers(OpenMatrix4f first, OpenMatrix4f second) {
        return Math.abs(first.m00 - second.m00) > 0.00001F
                || Math.abs(first.m01 - second.m01) > 0.00001F
                || Math.abs(first.m02 - second.m02) > 0.00001F
                || Math.abs(first.m10 - second.m10) > 0.00001F
                || Math.abs(first.m11 - second.m11) > 0.00001F
                || Math.abs(first.m12 - second.m12) > 0.00001F
                || Math.abs(first.m20 - second.m20) > 0.00001F
                || Math.abs(first.m21 - second.m21) > 0.00001F
                || Math.abs(first.m22 - second.m22) > 0.00001F
                || Math.abs(first.m30 - second.m30) > 0.00001F
                || Math.abs(first.m31 - second.m31) > 0.00001F
                || Math.abs(first.m32 - second.m32) > 0.00001F;
    }

    private static JsonObject json(Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    private static Path confined(Path root, String relative) {
        Path result = root.resolve(relative).normalize();
        if (!result.startsWith(root)) {
            throw new IllegalArgumentException("Path escapes model root: " + relative);
        }
        return result;
    }

    private record Fixture(GeometryDocument geometry,
                           Map<String, AnimationClip> animations,
                           Map<String, AnimationController> controllers) {
    }

    private static final class NeutralEnvironment implements ExpressionEngine.Environment {
        private final Map<Integer, Double> variables = new LinkedHashMap<>();
        private double headYaw;

        private NeutralEnvironment headYaw(double value) {
            headYaw = value;
            return this;
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
            return "ysm.head_yaw".equals(ExpressionEngine.slotName(slot))
                    ? headYaw : 0.0D;
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
