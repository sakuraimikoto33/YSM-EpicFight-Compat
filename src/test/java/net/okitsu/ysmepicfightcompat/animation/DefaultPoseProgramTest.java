package net.okitsu.ysmepicfightcompat.animation;

import com.google.gson.JsonParser;
import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import net.okitsu.ysmepicfightcompat.mesh.HumanoidRig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultPoseProgramTest {
    @Test
    void zeroScaleOnAParentHidesItsDescendantsInTheDefaultForm() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone body = new GeometryDocument.Bone("body");
        GeometryDocument.Bone tail = new GeometryDocument.Bone("tail");
        tail.parentName("body");
        geometry.add(body);
        geometry.add(tail);
        geometry.linkHierarchy();
        AnimationClip clip = BedrockAnimationParser.parse("parallel.default",
                JsonParser.parseString("""
                        {"bones":{"body":{"scale":[0,0,0]}}}
                        """).getAsJsonObject());

        DefaultPoseProgram program = new DefaultPoseProgram(
                geometry, Map.of(clip.name(), clip));

        assertEquals(2, program.hiddenBoneCount());
    }

    @Test
    void blendWeightIsAppliedFromIdentityBeforeVisibilityIsCalculated() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone tail = new GeometryDocument.Bone("tail");
        geometry.add(tail);
        geometry.linkHierarchy();
        AnimationClip clip = BedrockAnimationParser.parse("parallel0",
                JsonParser.parseString("""
                        {"blend_weight":0.5,"bones":{"tail":{"scale":0}}}
                        """).getAsJsonObject());

        DefaultPoseProgram program = new DefaultPoseProgram(
                geometry, Map.of(clip.name(), clip));

        assertEquals(0, program.hiddenBoneCount());
    }

    @Test
    void evaluatesScriptTracksBeforeLaterVisibilityExpressions() {
        GeometryDocument geometry = new GeometryDocument();
        geometry.add(new GeometryDocument.Bone("tail"));
        geometry.linkHierarchy();
        AnimationClip script = BedrockAnimationParser.parse("pre_parallel0",
                JsonParser.parseString("""
                        {"bones":{"molang":{"rotation":["v.hide_tail=1",0,0]}}}
                        """).getAsJsonObject());
        AnimationClip visibility = BedrockAnimationParser.parse("parallel0",
                JsonParser.parseString("""
                        {"bones":{"tail":{"scale":"1-v.hide_tail"}}}
                        """).getAsJsonObject());

        DefaultPoseProgram program = new DefaultPoseProgram(
                geometry, Map.of(script.name(), script, visibility.name(), visibility));

        assertEquals(1, program.hiddenBoneCount());
    }

    @Test
    void firstPersonGroupsTreatArmAndSleeveAsOneVisibleJointFamily() {
        Map<String, Boolean> arms = Map.of(
                "rightArm", false, "rightSleeve", true,
                "head", true, "hat", false);

        assertTrue(DefaultPoseProgram.isJointVisible(HumanoidRig.RIGHT_HAND, arms, false));
        assertTrue(DefaultPoseProgram.isJointVisible(HumanoidRig.HEAD, arms, false));
        assertFalse(DefaultPoseProgram.isJointVisible(HumanoidRig.LEFT_ARM, arms, false));
        assertFalse(DefaultPoseProgram.isJointVisible(HumanoidRig.CHEST, Map.of(), false));
        assertTrue(DefaultPoseProgram.isJointVisible(99, Map.of(), true));
    }

    @Test
    void parserKeepsOnlyAutomaticallyPlayedAnimationNames() {
        assertTrue(BedrockAnimationParser.isAutomatic("pre_parallel.forms"));
        assertTrue(BedrockAnimationParser.isAutomatic("hold_mainhand:minecraft:bow"));
        assertTrue(BedrockAnimationParser.isAutomatic("hold_offhand$minecraft:shield"));
        assertTrue(BedrockAnimationParser.isAutomatic("swing#minecraft:swords"));
        assertTrue(BedrockAnimationParser.isAutomatic("passenger#minecraft:raiders"));
        assertTrue(BedrockAnimationParser.isAutomatic("head:default"));
        assertTrue(BedrockAnimationParser.isAutomatic("attacked"));
        assertTrue(BedrockAnimationParser.isAutomatic("swing_offhand"));
        assertFalse(BedrockAnimationParser.isAutomatic("manual.wave"));
        assertFalse(BedrockAnimationParser.isAutomatic("vehicle_preview"));

        assertTrue(BedrockAnimationParser.isHandItemAnimation(
                "hold_mainhand:minecraft:bow"));
        assertTrue(BedrockAnimationParser.isHandItemAnimation(
                "hold_offhand$minecraft:shield"));
        assertTrue(BedrockAnimationParser.isHandItemAnimation(
                "swing#minecraft:swords"));
        assertTrue(BedrockAnimationParser.isHandItemAnimation("use_mainhand"));
        assertFalse(BedrockAnimationParser.isHandItemAnimation("head:default"));
        assertFalse(BedrockAnimationParser.isHandItemAnimation(
                "vehicle$minecraft:boat"));
    }

    @Test
    void parserKeepsMolangBlendWeight() {
        AnimationClip clip = BedrockAnimationParser.parse("pre_parallel2",
                JsonParser.parseString("""
                        {"blend_weight":"0.75*math.sin(query.anim_time*20)+1.5"}
                        """).getAsJsonObject());

        assertEquals("0.75*math.sin(query.anim_time*20)+1.5",
                clip.blendWeight().expression());
    }
}
