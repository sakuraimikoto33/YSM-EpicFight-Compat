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
        assertFalse(BedrockAnimationParser.isAutomatic("manual.wave"));
    }
}
