package net.okitsu.ysmepicfightcompat.animation;

import net.minecraft.world.InteractionHand;
import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomHeldItemPolicyTest {
    @Test
    void detectsShownHiddenPropAndExplicitlyHiddenHandLocator() {
        GeometryDocument geometry = handProps();
        AnimationClip pre = clip("pre_parallel0", "custom_staff", scale(0.0D));
        pre.boneTracks().put("custom_shield", scale(0.0D));
        AnimationClip sword = clip("hold_mainhand:sword", "custom_staff", scale(1.0D));
        AnimationClip shield = clip("hold_offhand$minecraft:shield",
                "LeftHandLocator", scale(0.0D));
        shield.boneTracks().put("custom_shield", scale(1.0D));
        AnimationClip bowWithoutReplacement = clip("hold_mainhand:bow",
                "tail", scale(1.0D));
        AnimationClip transientLocatorHide = clip("hold_mainhand:pickaxe",
                "RightHandLocator", scale(0.0D, 1.0D));

        Map<String, AnimationClip> clips = new LinkedHashMap<>();
        for (AnimationClip clip : new AnimationClip[]{pre, sword, shield,
                bowWithoutReplacement, transientLocatorHide}) {
            clips.put(clip.name(), clip);
        }
        CustomHeldItemPolicy policy = CustomHeldItemPolicy.create(geometry, clips);

        assertEquals(Set.of(":sword"), policy.selectors(InteractionHand.MAIN_HAND));
        assertEquals(Set.of(":sword"), policy.selectors(InteractionHand.MAIN_HAND,
                AnimationConditionMatcher.ItemAction.HOLD));
        assertEquals(Set.of("$minecraft:shield"),
                policy.selectors(InteractionHand.OFF_HAND));
        assertEquals(Set.of("custom_staff"), policy.replacementRoots(
                InteractionHand.MAIN_HAND,
                AnimationConditionMatcher.ItemAction.HOLD, ":sword"));
        assertEquals(Set.of("custom_shield"), policy.replacementRoots(
                InteractionHand.OFF_HAND,
                AnimationConditionMatcher.ItemAction.HOLD, "$minecraft:shield"));
    }

    @Test
    void expressionCanRevealAHiddenRenderablePropButZeroScaleCannot() {
        GeometryDocument geometry = handProps();
        AnimationClip pre = clip("pre_parallel0", "custom_staff", scale(0.0D));
        AnimationClip axe = clip("use_mainhand:axe", "custom_staff",
                expressionScale("v.show_weapon"));
        AnimationClip bow = clip("use_mainhand:bow", "custom_staff", scale(0.0D));

        CustomHeldItemPolicy policy = CustomHeldItemPolicy.create(geometry,
                Map.of(pre.name(), pre, axe.name(), axe, bow.name(), bow));

        assertEquals(Set.of(":axe"), policy.selectors(InteractionHand.MAIN_HAND));
        assertEquals(Set.of(":axe"), policy.selectors(InteractionHand.MAIN_HAND,
                AnimationConditionMatcher.ItemAction.USE));
        assertEquals(Set.of(), policy.selectors(InteractionHand.MAIN_HAND,
                AnimationConditionMatcher.ItemAction.HOLD));
        assertEquals(Set.of("custom_staff"), policy.replacementRoots(
                InteractionHand.MAIN_HAND,
                AnimationConditionMatcher.ItemAction.USE, ":axe"));
    }

    @Test
    void effectOnlyBowUseDoesNotReplaceEpicFightsHeldBow() {
        GeometryDocument geometry = handProps();
        AnimationClip pre = clip("pre_parallel0", "custom_staff", scale(0.0D));
        AnimationClip useEffect = clip("use_mainhand:bow", "custom_staff", scale(1.0D));

        CustomHeldItemPolicy policy = CustomHeldItemPolicy.create(geometry,
                Map.of(pre.name(), pre, useEffect.name(), useEffect));

        assertEquals(Set.of(), policy.selectors(InteractionHand.MAIN_HAND));
        assertEquals(Set.of(), policy.replacementRoots(useEffect.name()));
        assertEquals(Set.of("custom_staff"),
                policy.epicItemEffectRoots(useEffect.name()));
        assertFalse(policy.replacesBodyPose(useEffect.name()));
    }

    @Test
    void launchAuthorshipUsesOnlySteadyHoldReplacementRules() {
        GeometryDocument geometry = handProps();
        AnimationClip pre = clip("pre_parallel0", "custom_staff", scale(0.0D));
        AnimationClip held = clip("hold_mainhand:sword",
                "custom_staff", scale(1.0D));
        AnimationClip useOnly = clip("use_mainhand:axe",
                "custom_staff", scale(1.0D));
        CustomHeldItemPolicy policy = CustomHeldItemPolicy.create(geometry,
                Map.of(pre.name(), pre, held.name(), held, useOnly.name(), useOnly));

        assertTrue(policy.authorsHeldItemAtRest((hand, selector) ->
                hand == InteractionHand.MAIN_HAND && selector.equals(":sword")));
        assertFalse(policy.authorsHeldItemAtRest((hand, selector) ->
                hand == InteractionHand.MAIN_HAND && selector.equals(":axe")));
        assertFalse(policy.authorsHeldItemAtRest(null));
    }

    private static AnimationClip clip(String name, String bone,
                                      AnimationClip.BoneTracks tracks) {
        AnimationClip result = new AnimationClip(name);
        result.boneTracks().put(bone, tracks);
        return result;
    }

    private static AnimationClip.BoneTracks scale(double value) {
        AnimationClip.VectorValue vector = new AnimationClip.VectorValue();
        for (int axis = 0; axis < 3; axis++) {
            vector.setConstant(axis, value);
        }
        return scale(vector);
    }

    private static AnimationClip.BoneTracks scale(double... values) {
        AnimationClip.Track track = new AnimationClip.Track();
        for (int index = 0; index < values.length; index++) {
            AnimationClip.VectorValue vector = new AnimationClip.VectorValue();
            for (int axis = 0; axis < 3; axis++) {
                vector.setConstant(axis, values[index]);
            }
            track.keyframes().add(new AnimationClip.Keyframe(index,
                    AnimationClip.Interpolation.LINEAR, vector, null));
        }
        AnimationClip.BoneTracks tracks = new AnimationClip.BoneTracks();
        tracks.scale(track);
        return tracks;
    }

    private static AnimationClip.BoneTracks expressionScale(String expression) {
        AnimationClip.VectorValue vector = new AnimationClip.VectorValue();
        for (int axis = 0; axis < 3; axis++) {
            vector.setExpression(axis, expression);
        }
        return scale(vector);
    }

    private static AnimationClip.BoneTracks scale(AnimationClip.VectorValue value) {
        AnimationClip.Track track = new AnimationClip.Track();
        track.keyframes().add(new AnimationClip.Keyframe(0.0F,
                AnimationClip.Interpolation.LINEAR, value, null));
        AnimationClip.BoneTracks tracks = new AnimationClip.BoneTracks();
        tracks.scale(track);
        return tracks;
    }

    private static GeometryDocument handProps() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone root = bone("Root", "");
        GeometryDocument.Bone rightHand = bone("RightHand", "Root");
        GeometryDocument.Bone rightLocator = bone("RightHandLocator", "RightHand");
        GeometryDocument.Bone leftHand = bone("LeftHand", "Root");
        GeometryDocument.Bone leftLocator = bone("LeftHandLocator", "LeftHand");
        GeometryDocument.Bone staff = bone("custom_staff", "RightHand");
        GeometryDocument.Bone staffMesh = bone("staff_mesh", "custom_staff");
        GeometryDocument.Bone shield = bone("custom_shield", "LeftHand");
        GeometryDocument.Bone shieldMesh = bone("shield_mesh", "custom_shield");
        GeometryDocument.Bone tail = bone("tail", "Root");
        staffMesh.faces().add(face());
        shieldMesh.faces().add(face());
        tail.faces().add(face());
        for (GeometryDocument.Bone bone : new GeometryDocument.Bone[]{root, rightHand,
                rightLocator, leftHand, leftLocator, staff, staffMesh, shield,
                shieldMesh, tail}) {
            geometry.add(bone);
        }
        geometry.linkHierarchy();
        return geometry;
    }

    private static GeometryDocument.Bone bone(String name, String parent) {
        GeometryDocument.Bone bone = new GeometryDocument.Bone(name);
        bone.parentName(parent);
        return bone;
    }

    private static GeometryDocument.Face face() {
        Vector3f[] positions = {
                new Vector3f(0.0F, 0.0F, 0.0F),
                new Vector3f(1.0F, 0.0F, 0.0F),
                new Vector3f(1.0F, 1.0F, 0.0F),
                new Vector3f(0.0F, 1.0F, 0.0F)};
        float[][] uv = {{0.0F, 0.0F}, {1.0F, 0.0F},
                {1.0F, 1.0F}, {0.0F, 1.0F}};
        return new GeometryDocument.Face(positions, uv, new Vector3f(0.0F, 0.0F, 1.0F));
    }
}
