package net.okitsu.ysmepicfightcompat.animation;

import net.minecraft.world.InteractionHand;
import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import net.okitsu.ysmepicfightcompat.mesh.AuxiliaryBoneLayout;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimationOwnershipEvaluationDeadlineTest {
    private static final Set<InteractionHand> MAIN_HAND = Set.of(InteractionHand.MAIN_HAND);
    private static final AutomaticAnimationSelector.ActiveClip IDLE =
            new AutomaticAnimationSelector.ActiveClip("idle", 0, false);

    @Test
    void pendingControllerEdgesKeepEvaluationAdmittedUntilResolvedOrExpired() {
        ParallelAnimationProgram program = program();
        ParallelAnimationProgram.ItemSwitchState state =
                new ParallelAnimationProgram.ItemSwitchState();
        assertFalse(state.needsEvaluation(0));
        program.observeItemSwitchEdges(List.of(IDLE), MAIN_HAND, 0, false, state);
        assertTrue(state.needsEvaluation(0));
        assertNull(program.updateItemSwitchPose(List.of(IDLE), List.of(), IDLE, null,
                MAIN_HAND, 0.01D, false, state));
        assertTrue(state.needsEvaluation(0.02D),
                "controller initialization must not lose its short-lived switch edge");
        assertTrue(state.needsEvaluation(0.16D));
        program.updateItemSwitchPose(List.of(IDLE), List.of(), IDLE, null,
                MAIN_HAND, 0.16D, false, state);
        assertFalse(state.needsEvaluation(0.16D));
    }

    @Test
    void equipPlaybackAndExitOwnershipReleaseAtTheirOwnDeadlines() {
        ParallelAnimationProgram program = program();
        ParallelAnimationProgram.ItemSwitchState state =
                new ParallelAnimationProgram.ItemSwitchState();
        AutomaticAnimationSelector.ActiveClip hold =
                new AutomaticAnimationSelector.ActiveClip("hold_mainhand:bow", 0, true);
        assertNotNull(program.updateItemSwitchPose(List.of(IDLE, hold), IDLE, null,
                MAIN_HAND, 0, false, state));
        program.updateItemSwitchPose(List.of(IDLE), List.of(), IDLE, null,
                MAIN_HAND, 0.2D, false, state);
        assertFalse(state.needsEvaluation(0.49D));
        assertFalse(state.needsEvaluation(0.5D));
        assertTrue(state.needsEvaluation(0.51D));
        assertNull(program.updateItemSwitchPose(List.of(IDLE), List.of(), IDLE, null,
                MAIN_HAND, 0.51D, false, state));
        assertTrue(state.hasPoseOwnershipPotential(MAIN_HAND, 0.55D));
        assertFalse(state.needsEvaluation(0.55D));
        assertFalse(state.needsEvaluation(0.65D));
        assertTrue(state.needsEvaluation(0.66D));
        program.updateItemSwitchPose(List.of(IDLE), List.of(), IDLE, null,
                MAIN_HAND, 0.66D, false, state);
        assertFalse(state.needsEvaluation(0.66D));
        assertFalse(state.hasPoseOwnershipPotential(MAIN_HAND, 0.66D));
    }

    @Test
    void aClockJumpPastTheWholeEquipWindowClearsEveryDeadlineInOneEvaluation() {
        ParallelAnimationProgram program = program();
        ParallelAnimationProgram.ItemSwitchState state =
                new ParallelAnimationProgram.ItemSwitchState();
        AutomaticAnimationSelector.ActiveClip hold =
                new AutomaticAnimationSelector.ActiveClip("hold_mainhand:bow", 0, true);
        assertNotNull(program.updateItemSwitchPose(List.of(IDLE, hold), IDLE, null,
                MAIN_HAND, 0, false, state));
        assertTrue(state.needsEvaluation(2));
        assertNull(program.updateItemSwitchPose(List.of(IDLE), List.of(), IDLE, null,
                MAIN_HAND, 2, false, state));
        assertFalse(state.needsEvaluation(2));
    }

    @Test
    void swingEndpointIsPublishedOnceAndFrozenEndpointDoesNotForceMoreEvaluation() {
        ParallelAnimationProgram program = program();
        ParallelAnimationProgram.FullBodySwingState state =
                new ParallelAnimationProgram.FullBodySwingState();
        assertFalse(state.needsEvaluation(0));
        assertEquals(1, program.updateFullBodySwingPlayback(List.of(
                new AutomaticAnimationSelector.ActiveClip("swing:bow", 0, true)),
                0, state).size());
        assertFalse(state.needsEvaluation(0.99D));
        assertTrue(state.needsEvaluation(1));
        List<AutomaticAnimationSelector.ActiveClip> endpoint =
                program.updateFullBodySwingPlayback(List.of(), 1, state);
        assertEquals(1, endpoint.size());
        assertEquals(1, endpoint.get(0).elapsed(), 0.00001D);
        assertFalse(state.needsEvaluation(1));
        assertFalse(state.needsEvaluation(1));
        assertTrue(state.needsEvaluation(1.01D));
        assertTrue(program.updateFullBodySwingPlayback(List.of(), 1.01D, state).isEmpty());
        assertFalse(state.needsEvaluation(1.01D));
    }

    @Test
    void skippedSwingEndpointStillGetsOneFinalPoseThenImmediateCleanup() {
        ParallelAnimationProgram program = program();
        ParallelAnimationProgram.FullBodySwingState state =
                new ParallelAnimationProgram.FullBodySwingState();
        program.updateFullBodySwingPlayback(List.of(
                new AutomaticAnimationSelector.ActiveClip("swing:bow", 0, true)), 0, state);
        assertTrue(state.needsEvaluation(1.2D));
        List<AutomaticAnimationSelector.ActiveClip> endpoint =
                program.updateFullBodySwingPlayback(List.of(), 1.2D, state);
        assertEquals(1, endpoint.size());
        assertEquals(1, endpoint.get(0).elapsed(), 0.00001D);
        assertTrue(state.needsEvaluation(1.2D));
        assertTrue(program.updateFullBodySwingPlayback(List.of(), 1.2D, state).isEmpty());
        assertFalse(state.needsEvaluation(1.2D));
    }

    @Test
    void resetDisarmsBothDeadlineSources() {
        ParallelAnimationProgram program = program();
        ParallelAnimationProgram.ItemSwitchState item =
                new ParallelAnimationProgram.ItemSwitchState();
        program.observeItemSwitchEdges(List.of(IDLE), MAIN_HAND, 0, false, item);
        assertTrue(item.needsEvaluation(0));
        item.reset();
        assertFalse(item.needsEvaluation(1));
        ParallelAnimationProgram.FullBodySwingState swing =
                new ParallelAnimationProgram.FullBodySwingState();
        program.updateFullBodySwingPlayback(List.of(
                new AutomaticAnimationSelector.ActiveClip("swing:bow", 0, true)), 0, swing);
        assertTrue(swing.needsEvaluation(1));
        swing.reset();
        assertFalse(swing.needsEvaluation(1));
    }

    private static ParallelAnimationProgram program() {
        GeometryDocument geometry = new GeometryDocument();
        String parent = null;
        for (String name : List.of("Root", "RightArm", "RightForeArm", "RightHand", "custom_bow")) {
            GeometryDocument.Bone bone = new GeometryDocument.Bone(name);
            if (parent != null) bone.parentName(parent);
            if (name.equals("custom_bow")) {
                bone.faces().add(new GeometryDocument.Face(new Vector3f[]{
                        new Vector3f(-0.5F, 0, 0), new Vector3f(0.5F, 0, 0),
                        new Vector3f(0.5F, 2, 0), new Vector3f(-0.5F, 2, 0)},
                        new float[][]{{0, 0}, {1, 0}, {1, 1}, {0, 1}}, new Vector3f(0, 0, 1)));
            }
            geometry.add(bone);
            parent = name;
        }
        geometry.linkHierarchy();
        AnimationClip pre = new AnimationClip("pre_parallel0");
        AnimationClip.BoneTracks hidden = new AnimationClip.BoneTracks();
        hidden.scale(track(0, 0, 0));
        pre.boneTracks().put("custom_bow", hidden);
        AnimationClip idle = new AnimationClip("idle");
        AnimationClip hold = new AnimationClip("hold_mainhand:bow");
        hold.duration(1000);
        AnimationClip.BoneTracks equip = new AnimationClip.BoneTracks();
        AnimationClip.Track equipRotation = track(0, 0, 0);
        equipRotation.keyframes().add(new AnimationClip.Keyframe(0.5F,
                AnimationClip.Interpolation.LINEAR, vector(0, 0, 50), null));
        equip.rotation(equipRotation);
        hold.boneTracks().put("RightArm", equip);
        AnimationClip.BoneTracks visible = new AnimationClip.BoneTracks();
        visible.rotation(track(0, 0, 0));
        visible.scale(track(1, 1, 1));
        hold.boneTracks().put("custom_bow", visible);
        AnimationClip release = new AnimationClip("swing:bow");
        release.duration(1);
        release.playback(AnimationClip.Playback.ONCE);
        AnimationClip.BoneTracks swingArm = new AnimationClip.BoneTracks();
        swingArm.rotation(track(0, -20, 0));
        release.boneTracks().put("RightArm", swingArm);
        release.boneTracks().put("custom_bow", visible);
        return new ParallelAnimationProgram(geometry, Map.of(pre.name(), pre, idle.name(), idle,
                hold.name(), hold, release.name(), release), AuxiliaryBoneLayout.create(geometry), 1, 1);
    }

    private static AnimationClip.Track track(double x, double y, double z) {
        AnimationClip.Track track = new AnimationClip.Track();
        track.keyframes().add(new AnimationClip.Keyframe(0,
                AnimationClip.Interpolation.LINEAR, vector(x, y, z), null));
        return track;
    }

    private static AnimationClip.VectorValue vector(double x, double y, double z) {
        AnimationClip.VectorValue vector = new AnimationClip.VectorValue();
        vector.setConstant(0, x);
        vector.setConstant(1, y);
        vector.setConstant(2, z);
        return vector;
    }
}
