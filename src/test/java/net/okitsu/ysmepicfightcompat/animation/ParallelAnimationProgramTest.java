package net.okitsu.ysmepicfightcompat.animation;

import net.minecraft.world.InteractionHand;
import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import net.okitsu.ysmepicfightcompat.mesh.AuxiliaryBoneLayout;
import net.okitsu.ysmepicfightcompat.mesh.HumanoidRig;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.api.utils.math.Vec4f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelAnimationProgramTest {
    @Test
    void controllerTimelinesEmitOutputsOnlyForNonZeroWeights() {
        assertFalse(ParallelAnimationProgram.emitsControllerOutputs(0.0F));
        assertFalse(ParallelAnimationProgram.emitsControllerOutputs(Float.NaN));
        assertTrue(ParallelAnimationProgram.emitsControllerOutputs(1.0F));
        assertTrue(ParallelAnimationProgram.emitsControllerOutputs(-0.5F));
    }

    @Test
    void detectsAuthoredSoundFromAllowedSwingControllerDefinitions() {
        AnimationClip attack = new AnimationClip("sword_idle_attack1");
        attack.soundEffects().add(new AnimationClip.SoundEvent(0.0417F, "atk1"));
        AnimationController.State state = new AnimationController.State(
                "attack", List.of(new AnimationController.AnimationReference(
                attack.name(), "ctrl.idle")), List.of(), List.of(), List.of(),
                new AnimationController.BlendTransition(0.0F, List.of()), false);
        AnimationController controller = new AnimationController(
                "player.post_swing", "attack", Map.of("attack", state));

        assertTrue(ParallelAnimationProgram.authoredSwingControllerSound(
                Map.of(controller.name(), controller), Set.of(controller.name()),
                Map.of(attack.name(), attack)));
        assertFalse(ParallelAnimationProgram.authoredSwingControllerSound(
                Map.of(controller.name(), controller), Set.of(),
                Map.of(attack.name(), attack)));
    }

    @Test
    void keepsAnimationTimeMonotonicWhenPartialTickRewindsWithinTheSameTick() {
        assertEquals(5.04D, ParallelAnimationProgram.stableSampleTime(
                100, 5.0D, 100, 5.04D));
        assertEquals(1.0D, ParallelAnimationProgram.stableSampleTime(
                20, 1.0D, 100, 5.04D));
    }

    @Test
    void distanceLodKeepsNearActorsContinuousAndBoundsFarUpdateRates() {
        assertEquals(0.0D, ParallelAnimationProgram.lodIntervalSeconds(16.0D * 16.0D));
        assertEquals(1.0D / 20.0D,
                ParallelAnimationProgram.lodIntervalSeconds(24.0D * 24.0D));
        assertEquals(2.0D / 20.0D,
                ParallelAnimationProgram.lodIntervalSeconds(48.0D * 48.0D));
        assertEquals(4.0D / 20.0D,
                ParallelAnimationProgram.lodIntervalSeconds(96.0D * 96.0D));
    }

    @Test
    void keepsParallelAndRouletteTransformsInSeparateSpaces() {
        GeometryDocument geometry = headAndEar();
        AnimationClip parallel = new AnimationClip("pre_parallel0");
        parallel.boneTracks().put("ear", rotation(0.0D, 0.0D, 15.0D));
        AnimationClip roulette = new AnimationClip("extra0");
        roulette.boneTracks().put("head", rotation(0.0D, 0.0D, 90.0D));
        ParallelAnimationProgram program = new ParallelAnimationProgram(
                geometry, Map.of(parallel.name(), parallel, roulette.name(), roulette),
                AuxiliaryBoneLayout.create(geometry), 1.0F, 1.0F);

        ParallelAnimationProgram.Frame frame = program.sampleAt(
                0.0D, "extra0", 0.0D, new NeutralEnvironment());

        Matrix4f parallelEar = new Matrix4f().translation(0.0F, 1.0F, 0.0F)
                .rotateZ((float) Math.toRadians(15.0D)).translate(0.0F, -1.0F, 0.0F);
        Matrix4f rouletteHead = new Matrix4f()
                .rotateZ((float) Math.toRadians(90.0D));
        assertIdentity(frame.parallelDeltas()[0]);
        assertMatrix(parallelEar, frame.parallelDeltas()[1]);
        assertMatrix(rouletteHead, frame.wholeModelDeltas()[0]);
        assertMatrix(rouletteHead, frame.wholeModelDeltas()[1]);
        assertFalse(frame.replaceEpicFightPose());
    }

    @Test
    void appliesRouletteTracksToMajorBonesAndTheirChildren() {
        GeometryDocument geometry = headAndEar();
        AnimationClip clip = new AnimationClip("extra0");
        clip.boneTracks().put("head", rotation(0.0D, 0.0D, 90.0D));
        ParallelAnimationProgram program = program(geometry, clip);

        ParallelAnimationProgram.Frame frame = program.sampleAt(
                0.0D, "extra0", 0.0D, new NeutralEnvironment());

        Matrix4f expected = new Matrix4f().rotateZ((float) Math.toRadians(90.0D));
        assertMatrix(expected, frame.wholeModelDeltas()[0]);
        assertMatrix(expected, frame.wholeModelDeltas()[1]);
        assertIdentity(frame.parallelDeltas()[0]);
        assertIdentity(frame.parallelDeltas()[1]);
    }

    @Test
    void leavesThePoseUntouchedWhenTheSelectedRouletteClipIsUnknown() {
        GeometryDocument geometry = headAndEar();
        AnimationClip clip = new AnimationClip("extra0");
        clip.boneTracks().put("head", rotation(0.0D, 0.0D, 90.0D));
        ParallelAnimationProgram program = program(geometry, clip);

        ParallelAnimationProgram.Frame frame = program.sampleAt(
                0.0D, "missing", 0.0D, new NeutralEnvironment());

        assertIdentity(frame.wholeModelDeltas()[0]);
        assertIdentity(frame.wholeModelDeltas()[1]);
    }

    @Test
    void releasesAOneShotRoulettePoseAfterItsDuration() {
        GeometryDocument geometry = headAndEar();
        AnimationClip clip = new AnimationClip("extra0");
        clip.duration(1.0F);
        clip.boneTracks().put("head", rotation(0.0D, 0.0D, 90.0D));
        ParallelAnimationProgram program = program(geometry, clip);

        ParallelAnimationProgram.Frame frame = program.sampleAt(
                0.0D, "extra0", 1.1D, new NeutralEnvironment());

        assertIdentity(frame.wholeModelDeltas()[0]);
        assertIdentity(frame.wholeModelDeltas()[1]);
    }

    @Test
    void holdsTheLastRoulettePoseWhenTheClipRequestsIt() {
        GeometryDocument geometry = headAndEar();
        AnimationClip clip = new AnimationClip("extra0");
        clip.duration(1.0F);
        clip.playback(AnimationClip.Playback.HOLD_LAST_FRAME);
        clip.boneTracks().put("head", rotation(0.0D, 0.0D, 90.0D));
        ParallelAnimationProgram program = program(geometry, clip);

        ParallelAnimationProgram.Frame frame = program.sampleAt(
                0.0D, "extra0", 1.1D, new NeutralEnvironment());

        assertFalse(isIdentity(frame.wholeModelDeltas()[0]));
        assertFalse(isIdentity(frame.wholeModelDeltas()[1]));
    }

    @Test
    void appliesNonMountedAutomaticAnimationsOnlyToAuxiliaryBones() {
        GeometryDocument geometry = headAndEar();
        AnimationClip idle = new AnimationClip("idle");
        idle.playback(AnimationClip.Playback.REPEAT);
        idle.boneTracks().put("head", rotation(0.0D, 0.0D, 90.0D));
        idle.boneTracks().put("ear", rotation(0.0D, 0.0D, 20.0D));
        ParallelAnimationProgram program = program(geometry, idle);

        ParallelAnimationProgram.Frame frame = program.sampleAutomaticAt(
                0.0D, List.of("idle"), new NeutralEnvironment());

        assertIdentity(frame.parallelDeltas()[0]);
        Matrix4f animated = new Matrix4f().translation(0.0F, 1.0F, 0.0F)
                .rotateZ((float) Math.toRadians(20.0D)).translate(0.0F, -1.0F, 0.0F);
        assertMatrix(animated, frame.parallelDeltas()[1]);
        assertFalse(frame.replaceEpicFightPose());
    }

    @Test
    void ignoresAutomaticTransformsOnWrappersAboveEpicFightBones() {
        GeometryDocument geometry = wrappedRootAndTail();
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry);
        AnimationClip fly = new AnimationClip("fly");
        fly.playback(AnimationClip.Playback.REPEAT);
        fly.boneTracks().put("MRoot", rotation(0.0D, 0.0D, 90.0D));
        fly.boneTracks().put("tail", rotation(0.0D, 0.0D, 20.0D));
        ParallelAnimationProgram program = new ParallelAnimationProgram(
                geometry, Map.of(fly.name(), fly), layout, 1.0F, 1.0F);

        ParallelAnimationProgram.Frame frame = program.sampleAutomaticAt(
                0.0D, List.of("fly"), new NeutralEnvironment());

        assertIdentity(frame.parallelDeltas()[layout.entryForBoneName("MRoot").auxiliaryIndex()]);
        assertIdentity(frame.parallelDeltas()[layout.entryForBoneName("Root").auxiliaryIndex()]);
        assertIdentity(frame.parallelDeltas()[layout.entryForBoneName("MHead").auxiliaryIndex()]);
        assertIdentity(frame.parallelDeltas()[layout.entryForBoneName("Head").auxiliaryIndex()]);
        assertMatrix(new Matrix4f().rotateZ((float) Math.toRadians(20.0D)),
                frame.parallelDeltas()[layout.entryForBoneName("tail").auxiliaryIndex()]);
        assertFalse(frame.replaceEpicFightPose());
    }

    @Test
    void ignoresParallelTransformsOnWrappersAboveEpicFightBones() {
        GeometryDocument geometry = wrappedRootAndTail();
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry);
        AnimationClip parallel = new AnimationClip("parallel0");
        parallel.boneTracks().put("MRoot", rotation(0.0D, 0.0D, 90.0D));
        parallel.boneTracks().put("tail", rotation(0.0D, 0.0D, 20.0D));
        ParallelAnimationProgram program = new ParallelAnimationProgram(
                geometry, Map.of(parallel.name(), parallel), layout, 1.0F, 1.0F);

        ParallelAnimationProgram.Frame frame = program.sampleAt(
                0.0D, new NeutralEnvironment());

        assertIdentity(frame.parallelDeltas()[layout.entryForBoneName("MRoot").auxiliaryIndex()]);
        assertIdentity(frame.parallelDeltas()[layout.entryForBoneName("Root").auxiliaryIndex()]);
        assertIdentity(frame.parallelDeltas()[layout.entryForBoneName("MHead").auxiliaryIndex()]);
        assertIdentity(frame.parallelDeltas()[layout.entryForBoneName("Head").auxiliaryIndex()]);
        assertMatrix(new Matrix4f().rotateZ((float) Math.toRadians(20.0D)),
                frame.parallelDeltas()[layout.entryForBoneName("tail").auxiliaryIndex()]);
    }

    @Test
    void keepsWrapperTransformsForWholeModelRouletteAnimations() {
        GeometryDocument geometry = wrappedRootAndTail();
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry);
        AnimationClip roulette = new AnimationClip("extra0");
        roulette.boneTracks().put("MRoot", rotation(0.0D, 0.0D, 90.0D));
        ParallelAnimationProgram program = new ParallelAnimationProgram(
                geometry, Map.of(roulette.name(), roulette), layout, 1.0F, 1.0F);

        ParallelAnimationProgram.Frame frame = program.sampleAt(
                0.0D, "extra0", 0.0D, new NeutralEnvironment());

        Matrix4f expected = new Matrix4f().rotateZ((float) Math.toRadians(90.0D));
        assertMatrix(expected,
                frame.wholeModelDeltas()[layout.entryForBoneName("MRoot").auxiliaryIndex()]);
        assertMatrix(expected,
                frame.wholeModelDeltas()[layout.entryForBoneName("Root").auxiliaryIndex()]);
        assertMatrix(expected,
                frame.wholeModelDeltas()[layout.entryForBoneName("MHead").auxiliaryIndex()]);
        assertMatrix(expected,
                frame.wholeModelDeltas()[layout.entryForBoneName("Head").auxiliaryIndex()]);
    }

    @Test
    void appliesControllerAnimationsOnlyToAuxiliaryBones() {
        GeometryDocument geometry = headAndEar();
        AnimationClip controlled = new AnimationClip("custom.pose");
        controlled.playback(AnimationClip.Playback.REPEAT);
        controlled.boneTracks().put("head", rotation(0.0D, 0.0D, 90.0D));
        controlled.boneTracks().put("ear", rotation(0.0D, 0.0D, 20.0D));
        AnimationController.State state = new AnimationController.State(
                "default", List.of(new AnimationController.AnimationReference(
                controlled.name(), "1")), List.of(), List.of(), List.of(),
                new AnimationController.BlendTransition(0.0F, List.of()), false);
        AnimationController controller = new AnimationController(
                "player.parallel_4", "default", Map.of("default", state));
        ParallelAnimationProgram program = new ParallelAnimationProgram(
                geometry, Map.of(controlled.name(), controlled),
                Map.of(controller.name(), controller), AuxiliaryBoneLayout.create(geometry),
                1.0F, 1.0F);

        ParallelAnimationProgram.Frame frame = program.sampleControllersAt(
                0.0D, new NeutralEnvironment(),
                new AnimationControllerProgram.RuntimeState());

        assertIdentity(frame.parallelDeltas()[0]);
        Matrix4f animated = new Matrix4f().translation(0.0F, 1.0F, 0.0F)
                .rotateZ((float) Math.toRadians(20.0D)).translate(0.0F, -1.0F, 0.0F);
        assertMatrix(animated, frame.parallelDeltas()[1]);
        assertFalse(frame.replaceEpicFightPose());
    }

    @Test
    void appliesMountedStatesToMajorBonesAndTheirChildrenInWholeModelSpace() {
        GeometryDocument geometry = headAndEar();
        AnimationClip boat = new AnimationClip("boat");
        boat.playback(AnimationClip.Playback.REPEAT);
        boat.boneTracks().put("head", rotation(0.0D, 0.0D, 90.0D));
        ParallelAnimationProgram program = program(geometry, boat);

        ParallelAnimationProgram.Frame frame = program.sampleAutomaticAt(
                0.0D, List.of("boat"), new NeutralEnvironment());

        Matrix4f expected = new Matrix4f().rotateZ((float) Math.toRadians(90.0D));
        assertMatrix(expected, frame.wholeModelDeltas()[0]);
        assertMatrix(expected, frame.wholeModelDeltas()[1]);
        assertIdentity(frame.parallelDeltas()[0]);
        assertIdentity(frame.parallelDeltas()[1]);
        assertTrue(frame.replaceEpicFightPose());
    }

    @Test
    void recognizesEveryMountedStateAndVehicleConditionAsWholeModelAnimation() {
        assertTrue(ParallelAnimationProgram.isWholeModelMountedClip("boat"));
        assertTrue(ParallelAnimationProgram.isWholeModelMountedClip("ride_pig"));
        assertTrue(ParallelAnimationProgram.isWholeModelMountedClip("ride"));
        assertTrue(ParallelAnimationProgram.isWholeModelMountedClip("sit"));
        assertTrue(ParallelAnimationProgram.isWholeModelMountedClip(
                "vehicle$minecraft:boat"));
        assertTrue(ParallelAnimationProgram.isWholeModelMountedClip(
                "vehicle#minecraft:boats"));
        assertFalse(ParallelAnimationProgram.isWholeModelMountedClip("passenger:minecraft:pig"));
        assertFalse(ParallelAnimationProgram.isWholeModelMountedClip("idle"));
    }

    @Test
    void composesAutomaticPriorityBetweenPreParallelAndParallel() {
        GeometryDocument geometry = headAndEar();
        AnimationClip pre = new AnimationClip("pre_parallel0");
        pre.boneTracks().put("ear", rotation(0.0D, 0.0D, 10.0D));
        AnimationClip idle = new AnimationClip("idle");
        idle.boneTracks().put("ear", rotation(0.0D, 0.0D, 20.0D));
        AnimationClip equipment = new AnimationClip("head:default");
        equipment.boneTracks().put("ear", rotation(0.0D, 0.0D, 30.0D));
        AnimationClip parallel = new AnimationClip("parallel0");
        parallel.boneTracks().put("ear", rotation(0.0D, 0.0D, 5.0D));
        ParallelAnimationProgram program = new ParallelAnimationProgram(
                geometry, Map.of(pre.name(), pre, idle.name(), idle,
                equipment.name(), equipment, parallel.name(), parallel),
                AuxiliaryBoneLayout.create(geometry), 1.0F, 1.0F);

        ParallelAnimationProgram.Frame frame = program.sampleAutomaticAt(
                0.0D, List.of("idle", "head:default"),
                new NeutralEnvironment());

        Matrix4f animated = new Matrix4f().translation(0.0F, 1.0F, 0.0F)
                .rotateZ((float) Math.toRadians(35.0D)).translate(0.0F, -1.0F, 0.0F);
        assertMatrix(animated, frame.parallelDeltas()[1]);
    }

    @Test
    void keepsHandItemAuxiliaryBonesForAutomaticButNotRoulettePlayback() {
        GeometryDocument geometry = headAndEar();
        AnimationClip condition = new AnimationClip("hold_mainhand:sword");
        condition.boneTracks().put("ear", rotation(0.0D, 0.0D, 90.0D));
        ParallelAnimationProgram program = program(geometry, condition);

        assertFalse(program.isEmpty());

        ParallelAnimationProgram.Frame automatic = program.sampleAutomaticAt(
                0.0D, List.of(condition.name()), new NeutralEnvironment());
        assertIdentity(automatic.parallelDeltas()[0]);
        Matrix4f expectedEar = new Matrix4f().translation(0.0F, 1.0F, 0.0F)
                .rotateZ((float) Math.toRadians(90.0D))
                .translate(0.0F, -1.0F, 0.0F);
        assertMatrix(expectedEar, automatic.parallelDeltas()[1]);

        ParallelAnimationProgram.Frame roulette = program.sampleAt(
                0.0D, condition.name(), 0.0D, new NeutralEnvironment());
        assertIdentity(roulette.wholeModelDeltas()[0]);
        assertIdentity(roulette.wholeModelDeltas()[1]);
    }

    @Test
    void customBowReleaseUsesOfficialThreeTickEndingTransition() {
        assertEquals(1.0F, ParallelAnimationProgram.fullBodyEndingWeight(0.0D),
                0.00001F);
        assertEquals(2.0F / 3.0F,
                ParallelAnimationProgram.fullBodyEndingWeight(0.05D), 0.00001F);
        assertEquals(1.0F / 3.0F,
                ParallelAnimationProgram.fullBodyEndingWeight(0.10D), 0.00001F);
        assertEquals(0.0F,
                ParallelAnimationProgram.fullBodyEndingWeight(0.15D), 0.00001F);
        assertEquals(0.0F,
                ParallelAnimationProgram.fullBodyEndingWeight(1.0D), 0.00001F);
    }

    @Test
    void customBowAimYawYieldsBeforeTheFinalCompositeBlend() {
        assertTrue(ParallelAnimationProgram.shouldUseCustomBowHeadYaw(
                true, false, false));
        assertTrue(ParallelAnimationProgram.shouldUseCustomBowHeadYaw(
                true, true, true));
        assertTrue(ParallelAnimationProgram.shouldUseCustomBowHeadYaw(
                false, true, false));
        assertFalse(ParallelAnimationProgram.shouldUseCustomBowHeadYaw(
                false, true, true));
        assertFalse(ParallelAnimationProgram.shouldUseCustomBowHeadYaw(
                false, false, false));
    }

    @Test
    void customBowEndingStartsEvenWhenUseAndSwingHaveAnEmptyRenderFrame() {
        assertTrue(ParallelAnimationProgram.shouldStartFullBodyEnding(
                AnimationConditionMatcher.ItemAction.USE, InteractionHand.MAIN_HAND,
                null, null));
        assertTrue(ParallelAnimationProgram.shouldStartFullBodyEnding(
                AnimationConditionMatcher.ItemAction.USE, InteractionHand.MAIN_HAND,
                AnimationConditionMatcher.ItemAction.SWING,
                InteractionHand.MAIN_HAND));
        assertFalse(ParallelAnimationProgram.shouldStartFullBodyEnding(
                AnimationConditionMatcher.ItemAction.USE, InteractionHand.MAIN_HAND,
                AnimationConditionMatcher.ItemAction.SWING,
                InteractionHand.OFF_HAND));
        assertFalse(ParallelAnimationProgram.shouldStartFullBodyEnding(
                AnimationConditionMatcher.ItemAction.SWING, InteractionHand.MAIN_HAND,
                AnimationConditionMatcher.ItemAction.SWING,
                InteractionHand.MAIN_HAND));
        assertTrue(ParallelAnimationProgram.shouldStartFullBodyEnding(
                AnimationConditionMatcher.ItemAction.SWING, InteractionHand.MAIN_HAND,
                null, null));
    }

    @Test
    void retainsCustomBowSwingForItsAuthoredDurationAfterEpicLayerEnds() {
        GeometryDocument geometry = bowUpperBodyGeometry();
        AnimationClip pre = new AnimationClip("pre_parallel0");
        AnimationClip.BoneTracks hidden = new AnimationClip.BoneTracks();
        hidden.scale(constantTrack(0.0D, 0.0D, 0.0D));
        pre.boneTracks().put("custom_bow", hidden);
        AnimationClip hold = customBowHold();
        AnimationClip release = new AnimationClip("swing:bow");
        release.duration(1.0F);
        release.playback(AnimationClip.Playback.ONCE);
        release.boneTracks().put("RightArm", rotation(0.0D, -20.0D, 0.0D));
        release.boneTracks().put("custom_bow", rotation(0.0D, -15.0D, 0.0D));
        release.boneTracks().get("custom_bow")
                .scale(constantTrack(1.0D, 1.0D, 1.0D));
        ParallelAnimationProgram program = new ParallelAnimationProgram(
                geometry, Map.of(pre.name(), pre, hold.name(), hold,
                release.name(), release),
                AuxiliaryBoneLayout.create(geometry), 1.0F, 1.0F);
        ParallelAnimationProgram.FullBodySwingState state =
                new ParallelAnimationProgram.FullBodySwingState();
        AutomaticAnimationSelector.ActiveClip raw =
                new AutomaticAnimationSelector.ActiveClip(release.name(), 0.0D, true);

        List<AutomaticAnimationSelector.ActiveClip> started =
                program.updateFullBodySwingPlayback(List.of(raw), 10.0D, state);
        assertEquals(1, started.size());
        assertEquals(0.0D, started.get(0).elapsed(), 0.00001D);
        assertTrue(started.get(0).restarted());
        assertEquals(Set.of(InteractionHand.MAIN_HAND),
                program.customFullBodyInputHands(List.of(raw)));

        List<AutomaticAnimationSelector.ActiveClip> retained =
                program.updateFullBodySwingPlayback(List.of(), 10.6D, state);
        assertEquals(1, retained.size());
        assertEquals(release.name(), retained.get(0).name());
        assertEquals(0.6D, retained.get(0).elapsed(), 0.00001D);
        assertFalse(retained.get(0).restarted());
        assertEquals(Set.of(), program.customFullBodyInputHands(List.of()));

        List<AutomaticAnimationSelector.ActiveClip> finalFrame =
                program.updateFullBodySwingPlayback(List.of(), 11.0D, state);
        assertEquals(1, finalFrame.size());
        assertEquals(1.0D, finalFrame.get(0).elapsed(), 0.00001D);
        assertTrue(program.updateFullBodySwingPlayback(
                List.of(), 11.01D, state).isEmpty());

        state.reset();
        program.updateFullBodySwingPlayback(List.of(raw), 20.0D, state);
        List<AutomaticAnimationSelector.ActiveClip> skippedEndpoint =
                program.updateFullBodySwingPlayback(List.of(), 21.2D, state);
        assertEquals(1, skippedEndpoint.size());
        assertEquals(1.0D, skippedEndpoint.get(0).elapsed(), 0.00001D,
                "a skipped render sample must still publish the authored endpoint once");
        assertTrue(program.updateFullBodySwingPlayback(
                List.of(), 21.21D, state).isEmpty());
    }

    @Test
    void restartedSwingRestartsRetainedCustomBowAction() {
        GeometryDocument geometry = bowUpperBodyGeometry();
        AnimationClip pre = new AnimationClip("pre_parallel0");
        AnimationClip.BoneTracks hidden = new AnimationClip.BoneTracks();
        hidden.scale(constantTrack(0.0D, 0.0D, 0.0D));
        pre.boneTracks().put("custom_bow", hidden);
        AnimationClip hold = customBowHold();
        AnimationClip release = new AnimationClip("swing:bow");
        release.duration(1.0F);
        release.boneTracks().put("custom_bow", rotation(0.0D, -15.0D, 0.0D));
        release.boneTracks().get("custom_bow")
                .scale(constantTrack(1.0D, 1.0D, 1.0D));
        ParallelAnimationProgram program = new ParallelAnimationProgram(
                geometry, Map.of(pre.name(), pre, hold.name(), hold,
                release.name(), release),
                AuxiliaryBoneLayout.create(geometry), 1.0F, 1.0F);
        ParallelAnimationProgram.FullBodySwingState state =
                new ParallelAnimationProgram.FullBodySwingState();
        program.updateFullBodySwingPlayback(List.of(
                new AutomaticAnimationSelector.ActiveClip(
                        release.name(), 0.0D, true)), 4.0D, state);

        List<AutomaticAnimationSelector.ActiveClip> restarted =
                program.updateFullBodySwingPlayback(List.of(
                        new AutomaticAnimationSelector.ActiveClip(
                                release.name(), 0.0D, true)), 4.8D, state);
        assertEquals(0.0D, restarted.get(0).elapsed(), 0.00001D);
        List<AutomaticAnimationSelector.ActiveClip> retained =
                program.updateFullBodySwingPlayback(List.of(), 5.7D, state);
        assertEquals(0.9D, retained.get(0).elapsed(), 0.00001D);
    }

    @Test
    void longRawSwingDoesNotRestartACompletedAuthoredBowAction() {
        GeometryDocument geometry = bowUpperBodyGeometry();
        AnimationClip pre = new AnimationClip("pre_parallel0");
        AnimationClip.BoneTracks hidden = new AnimationClip.BoneTracks();
        hidden.scale(constantTrack(0.0D, 0.0D, 0.0D));
        pre.boneTracks().put("custom_bow", hidden);
        AnimationClip hold = customBowHold();
        AnimationClip release = new AnimationClip("swing:bow");
        release.duration(0.5F);
        release.boneTracks().put("custom_bow", rotation(0.0D, -15.0D, 0.0D));
        release.boneTracks().get("custom_bow")
                .scale(constantTrack(1.0D, 1.0D, 1.0D));
        ParallelAnimationProgram program = new ParallelAnimationProgram(
                geometry, Map.of(pre.name(), pre, hold.name(), hold,
                release.name(), release),
                AuxiliaryBoneLayout.create(geometry), 1.0F, 1.0F);
        ParallelAnimationProgram.FullBodySwingState state =
                new ParallelAnimationProgram.FullBodySwingState();
        program.updateFullBodySwingPlayback(List.of(
                new AutomaticAnimationSelector.ActiveClip(
                        release.name(), 0.0D, true)), 2.0D, state);

        List<AutomaticAnimationSelector.ActiveClip> endpoint =
                program.updateFullBodySwingPlayback(List.of(
                        new AutomaticAnimationSelector.ActiveClip(
                                release.name(), 0.6D, false)), 2.6D, state);
        assertEquals(1, endpoint.size());
        assertEquals(0.5D, endpoint.get(0).elapsed(), 0.00001D);
        assertTrue(program.updateFullBodySwingPlayback(List.of(
                new AutomaticAnimationSelector.ActiveClip(
                        release.name(), 0.7D, false)), 2.7D, state).isEmpty());

        List<AutomaticAnimationSelector.ActiveClip> restarted =
                program.updateFullBodySwingPlayback(List.of(
                        new AutomaticAnimationSelector.ActiveClip(
                                release.name(), 0.0D, true)), 2.8D, state);
        assertEquals(1, restarted.size());
        assertEquals(0.0D, restarted.get(0).elapsed(), 0.00001D);
        assertTrue(restarted.get(0).restarted());
    }

    @Test
    void keepsHoldVisibilityUnderTheCurrentSwingOverlay() {
        GeometryDocument geometry = heldAndBackProps();
        AnimationClip pre = new AnimationClip("pre_parallel0");
        AnimationClip.BoneTracks hidden = new AnimationClip.BoneTracks();
        hidden.scale(constantTrack(0.0D, 0.0D, 0.0D));
        pre.boneTracks().put("held_staff", hidden);
        AnimationClip.BoneTracks visible = new AnimationClip.BoneTracks();
        visible.scale(constantTrack(1.0D, 1.0D, 1.0D));
        AnimationClip.BoneTracks hiddenBack = new AnimationClip.BoneTracks();
        hiddenBack.scale(constantTrack(0.0D, 0.0D, 0.0D));
        AnimationClip hold = new AnimationClip("hold_mainhand:sword");
        hold.boneTracks().put("held_staff", visible);
        hold.boneTracks().put("back_staff", hiddenBack);
        AnimationClip swing = new AnimationClip("swing:sword");
        swing.boneTracks().put("held_staff", rotation(0.0D, 0.0D, 35.0D));
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry);
        ParallelAnimationProgram program = new ParallelAnimationProgram(
                geometry, Map.of(pre.name(), pre, hold.name(), hold,
                swing.name(), swing), layout, 1.0F, 1.0F);

        ParallelAnimationProgram.Frame idle = program.sampleAt(
                0.0D, new NeutralEnvironment());
        assertTrue(idle.hiddenBones().contains("held_staff"));
        assertFalse(idle.hiddenBones().contains("back_staff"));

        ParallelAnimationProgram.Frame attacking = program.sampleAutomaticAt(
                0.0D, List.of(hold.name(), swing.name()), new NeutralEnvironment());
        assertFalse(attacking.hiddenBones().contains("held_staff"));
        assertTrue(attacking.hiddenBones().contains("back_staff"));
        assertMatrix(new Matrix4f().rotateZ((float) Math.toRadians(35.0D)),
                attacking.parallelDeltas()[
                        layout.entryForBoneName("held_staff").auxiliaryIndex()]);
    }

    @Test
    void stripsAuthoredArmMotionBeforeAttachingACustomPropToTheToolJoint() {
        GeometryDocument geometry = handPropGeometry();
        AnimationClip pre = new AnimationClip("pre_parallel0");
        AnimationClip.BoneTracks hidden = new AnimationClip.BoneTracks();
        hidden.scale(constantTrack(0.0D, 0.0D, 0.0D));
        pre.boneTracks().put("custom_prop", hidden);

        AnimationClip firstHold = customSwordHold(15.0D);
        AnimationClip secondHold = customSwordHold(115.0D);
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry);
        ParallelAnimationProgram first = new ParallelAnimationProgram(
                geometry, Map.of(pre.name(), pre, firstHold.name(), firstHold),
                layout, 1.0F, 1.0F);
        ParallelAnimationProgram second = new ParallelAnimationProgram(
                geometry, Map.of(pre.name(), pre, secondHold.name(), secondHold),
                layout, 1.0F, 1.0F);

        ParallelAnimationProgram.Frame firstFrame = first.sampleAutomaticAt(
                0.0D, List.of(firstHold.name()), new NeutralEnvironment());
        ParallelAnimationProgram.Frame secondFrame = second.sampleAutomaticAt(
                0.0D, List.of(secondHold.name()), new NeutralEnvironment());
        int prop = layout.entryForBoneName("custom_prop").auxiliaryIndex();

        assertEquals(HumanoidRig.RIGHT_TOOL,
                firstFrame.heldItemAnchorJoints()[prop]);
        assertMatrixEquals(firstFrame.heldItemDeltas()[prop],
                secondFrame.heldItemDeltas()[prop]);
        assertMatrix(new Matrix4f().translation(0.0F, 1.0F, 0.0F)
                        .rotateZ((float) Math.toRadians(35.0D))
                        .translate(0.0F, -1.0F, 0.0F),
                firstFrame.heldItemDeltas()[prop]);
    }

    @Test
    void effectOnlyBowUseKeepsEpicPoseAndAnimatesThePrivateEffect() {
        GeometryDocument geometry = bowUpperBodyGeometry();
        AnimationClip pre = new AnimationClip("pre_parallel0");
        AnimationClip.BoneTracks hidden = new AnimationClip.BoneTracks();
        hidden.scale(constantTrack(0.0D, 0.0D, 0.0D));
        pre.boneTracks().put("custom_bow", hidden);
        AnimationClip useEffect = new AnimationClip("use_mainhand:bow");
        useEffect.boneTracks().put("RightArm", rotation(-65.0D, 10.0D, 0.0D));
        useEffect.boneTracks().put("custom_bow", rotation(0.0D, 25.0D, 0.0D));
        useEffect.boneTracks().get("custom_bow")
                .scale(constantTrack(1.0D, 1.0D, 1.0D));
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry);
        ParallelAnimationProgram program = new ParallelAnimationProgram(
                geometry, Map.of(pre.name(), pre, useEffect.name(), useEffect),
                layout, 1.0F, 1.0F);

        ParallelAnimationProgram.Frame frame = program.sampleAutomaticAt(
                0.0D, List.of(useEffect.name()), new NeutralEnvironment());
        int effect = layout.entryForBoneName("custom_bow").auxiliaryIndex();
        int rightArm = layout.entryForBoneName("RightArm").auxiliaryIndex();

        assertFalse(frame.replaceEpicFightPose());
        assertTrue(frame.replaceEpicFightAnchors()[effect]);
        assertEquals(HumanoidRig.LEFT_TOOL, frame.heldItemAnchorJoints()[effect]);
        assertTrue(frame.suppressParallelDeltas()[effect]);
        assertIdentity(frame.parallelDeltas()[rightArm]);
        assertFalse(isIdentity(frame.heldItemDeltas()[effect]));
        assertEquals(6.0F, frame.heldItemDeltas()[effect].m30, 0.0001F);
        assertFalse(frame.hiddenBones().contains("custom_bow"));
    }

    @Test
    void usesOneCompleteYsmPoseWhileDrawingACustomBow() {
        GeometryDocument geometry = bowUpperBodyGeometry();
        AnimationClip pre = new AnimationClip("pre_parallel0");
        AnimationClip.BoneTracks hidden = new AnimationClip.BoneTracks();
        hidden.scale(constantTrack(0.0D, 0.0D, 0.0D));
        pre.boneTracks().put("custom_bow", hidden);
        AnimationClip.BoneTracks hiddenCircle = new AnimationClip.BoneTracks();
        hiddenCircle.scale(constantTrack(0.0D, 0.0D, 0.0D));
        pre.boneTracks().put("magic_circle", hiddenCircle);

        AnimationClip hold = new AnimationClip("hold_mainhand:bow");
        hold.boneTracks().put("RightArm", rotation(0.0D, 0.0D, 15.0D));
        hold.boneTracks().put("RightForeArm", rotation(0.0D, 0.0D, 22.0D));
        hold.boneTracks().put("custom_bow", rotation(0.0D, 0.0D, 5.0D));
        hold.boneTracks().get("custom_bow")
                .scale(constantTrack(1.0D, 1.0D, 1.0D));

        AnimationClip differentHold = new AnimationClip("hold_mainhand:bow");
        differentHold.boneTracks().put("RightArm", rotation(0.0D, 0.0D, 15.0D));
        differentHold.boneTracks().put("RightForeArm",
                rotation(0.0D, 0.0D, 122.0D));
        differentHold.boneTracks().put("custom_bow",
                rotation(0.0D, 0.0D, 5.0D));
        differentHold.boneTracks().get("custom_bow")
                .scale(constantTrack(1.0D, 1.0D, 1.0D));

        AnimationClip use = new AnimationClip("use_mainhand:bow");
        use.boneTracks().put("AllBody", rotation(0.0D, 20.0D, 0.0D));
        use.boneTracks().put("UpBody", rotation(10.0D, 0.0D, 0.0D));
        use.boneTracks().put("Head", rotation(-5.0D, 0.0D, 0.0D));
        use.boneTracks().put("RightArm", rotation(-65.0D, 10.0D, 0.0D));
        use.boneTracks().put("LeftArm", rotation(-80.0D, -15.0D, 0.0D));
        use.boneTracks().put("custom_bow", rotation(0.0D, 25.0D, 0.0D));
        use.boneTracks().get("custom_bow")
                .scale(constantTrack(1.0D, 1.0D, 1.0D));
        use.boneTracks().put("magic_circle", rotation(0.0D, 0.0D, 45.0D));
        use.boneTracks().get("magic_circle")
                .scale(constantTrack(1.0D, 1.0D, 1.0D));
        AnimationClip sneak = new AnimationClip("sneak");
        sneak.boneTracks().put("DownBody", rotation(0.0D, 0.0D, 12.0D));
        sneak.boneTracks().put("RightLeg", rotation(15.0D, 0.0D, 0.0D));

        AnimationClip postMainPose = new AnimationClip("post_main_pose");
        postMainPose.boneTracks().put("RightArm", rotation(0.0D, 0.0D, 122.0D));
        AnimationController.State postMainState = new AnimationController.State(
                "default", List.of(new AnimationController.AnimationReference(
                postMainPose.name(), "1")), List.of(), List.of(), List.of(),
                new AnimationController.BlendTransition(0.0F, List.of()), false);
        AnimationController postMainController = new AnimationController(
                "player.post_main", "default",
                Map.of("default", postMainState));

        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry);
        ParallelAnimationProgram program = new ParallelAnimationProgram(
                geometry, Map.of(pre.name(), pre, sneak.name(), sneak,
                hold.name(), hold, use.name(), use),
                layout, 1.0F, 1.0F);
        ParallelAnimationProgram programWithDifferentHold = new ParallelAnimationProgram(
                geometry, Map.of(pre.name(), pre, sneak.name(), sneak,
                differentHold.name(), differentHold, use.name(), use),
                layout, 1.0F, 1.0F);
        ParallelAnimationProgram programWithPostMain = new ParallelAnimationProgram(
                geometry, Map.of(pre.name(), pre, sneak.name(), sneak,
                hold.name(), hold, use.name(), use,
                postMainPose.name(), postMainPose),
                Map.of(postMainController.name(), postMainController),
                layout, 1.0F, 1.0F);

        ParallelAnimationProgram.Frame held = program.sampleAutomaticAt(
                0.0D, List.of(hold.name()), new NeutralEnvironment());
        int prop = layout.entryForBoneName("custom_bow").auxiliaryIndex();
        int circle = layout.entryForBoneName("magic_circle").auxiliaryIndex();
        int rightArm = layout.entryForBoneName("RightArm").auxiliaryIndex();
        assertEquals(HumanoidRig.RIGHT_TOOL, held.heldItemAnchorJoints()[prop]);
        assertFalse(held.replaceEpicFightAnchors()[rightArm]);

        ParallelAnimationProgram.Frame drawing = program.sampleAutomaticAt(
                0.0D, List.of(sneak.name(), hold.name(), use.name()),
                new NeutralEnvironment());
        for (String bone : List.of("Root", "AllBody", "UpBody", "Head",
                "RightArm", "RightForeArm", "RightHand", "LeftArm", "LeftForeArm",
                "LeftHand", "DownBody", "RightLeg", "custom_bow")) {
            int auxiliary = layout.entryForBoneName(bone).auxiliaryIndex();
            assertFalse(drawing.replaceEpicFightAnchors()[auxiliary], bone);
            assertEquals(-1, drawing.heldItemAnchorJoints()[auxiliary], bone);
        }
        assertTrue(drawing.replaceEpicFightPose());
        assertFalse(isIdentity(drawing.wholeModelDeltas()[
                layout.entryForBoneName("AllBody").auxiliaryIndex()]));
        assertFalse(isIdentity(drawing.wholeModelDeltas()[rightArm]));
        assertFalse(isIdentity(drawing.wholeModelDeltas()[
                layout.entryForBoneName("RightLeg").auxiliaryIndex()]));
        assertFalse(isIdentity(drawing.wholeModelDeltas()[prop]));
        assertTrue(drawing.suppressParallelDeltas()[prop]);
        assertFalse(drawing.suppressParallelDeltas()[rightArm]);
        assertIdentity(drawing.parallelDeltas()[circle]);
        assertFalse(isIdentity(drawing.wholeModelDeltas()[circle]));
        assertFalse(drawing.hiddenBones().contains("magic_circle"));

        ParallelAnimationProgram.Frame differentDrawing =
                programWithDifferentHold.sampleAutomaticAt(
                        0.0D, List.of(sneak.name(), differentHold.name(), use.name()),
                        new NeutralEnvironment());
        int rightForeArm = layout.entryForBoneName("RightForeArm").auxiliaryIndex();
        assertMatrixEquals(drawing.wholeModelDeltas()[rightForeArm],
                differentDrawing.wholeModelDeltas()[rightForeArm]);

        ParallelAnimationProgram.Frame controllerDrawing =
                programWithPostMain.sampleAutomaticAndControllersAt(
                        0.0D, List.of(sneak.name(), hold.name(), use.name()),
                        new NeutralEnvironment(),
                        new AnimationControllerProgram.RuntimeState());
        assertMatrixEquals(drawing.wholeModelDeltas()[rightArm],
                controllerDrawing.wholeModelDeltas()[rightArm]);
    }

    @Test
    void blendsTheLastDrawPoseIntoTheCustomBowReleasePose() {
        GeometryDocument geometry = bowUpperBodyGeometry();
        AnimationClip pre = new AnimationClip("pre_parallel0");
        AnimationClip.BoneTracks hidden = new AnimationClip.BoneTracks();
        hidden.scale(constantTrack(0.0D, 0.0D, 0.0D));
        pre.boneTracks().put("custom_bow", hidden);
        AnimationClip hold = customBowHold();

        AnimationClip use = new AnimationClip("use_mainhand:bow");
        use.boneTracks().put("RightArm", rotation(0.0D, 80.0D, 0.0D));
        use.boneTracks().put("custom_bow", rotation(0.0D, 25.0D, 0.0D));
        use.boneTracks().get("custom_bow")
                .scale(constantTrack(1.0D, 1.0D, 1.0D));

        AnimationClip release = new AnimationClip("swing:bow");
        release.boneTracks().put("RightArm", rotation(0.0D, -20.0D, 0.0D));
        release.boneTracks().put("custom_bow", rotation(0.0D, -15.0D, 0.0D));
        release.boneTracks().get("custom_bow")
                .scale(constantTrack(1.0D, 1.0D, 1.0D));

        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry);
        ParallelAnimationProgram program = new ParallelAnimationProgram(
                geometry, Map.of(pre.name(), pre, hold.name(), hold, use.name(), use,
                release.name(), release), layout, 1.0F, 1.0F);
        int rightArm = layout.entryForBoneName("RightArm").auxiliaryIndex();

        OpenMatrix4f drawn = new OpenMatrix4f().load(program.sampleAutomaticAt(
                0.0D, List.of(use.name()), new NeutralEnvironment())
                .wholeModelDeltas()[rightArm]);
        OpenMatrix4f released = new OpenMatrix4f().load(program.sampleAutomaticAt(
                0.0D, List.of(release.name()), new NeutralEnvironment())
                .wholeModelDeltas()[rightArm]);
        OpenMatrix4f transitionStart = new OpenMatrix4f().load(
                program.sampleAutomaticWithEndingAt(
                        0.0D, List.of(release.name()), use.name(),
                        0.0D, 1.0F, new NeutralEnvironment())
                        .wholeModelDeltas()[rightArm]);
        OpenMatrix4f transitionMiddle = new OpenMatrix4f().load(
                program.sampleAutomaticWithEndingAt(
                        0.0D, List.of(release.name()), use.name(),
                        0.0D, 0.5F, new NeutralEnvironment())
                        .wholeModelDeltas()[rightArm]);

        assertMatrixEquals(drawn, transitionStart);
        assertFalse(matrixEquals(transitionMiddle, drawn));
        assertFalse(matrixEquals(transitionMiddle, released));
    }

    @Test
    void customBowEndingUsesTheSavedEvaluatedPoseInsteadOfCurrentMolangValues() {
        GeometryDocument geometry = bowUpperBodyGeometry();
        AnimationClip pre = new AnimationClip("pre_parallel0");
        AnimationClip.BoneTracks hidden = new AnimationClip.BoneTracks();
        hidden.scale(constantTrack(0.0D, 0.0D, 0.0D));
        pre.boneTracks().put("custom_bow", hidden);
        AnimationClip hold = customBowHold();
        AnimationClip use = new AnimationClip("use_mainhand:bow");
        AnimationClip.BoneTracks drawArm = new AnimationClip.BoneTracks();
        drawArm.rotation(expressionTrack("0", "v.draw_angle", "0"));
        use.boneTracks().put("RightArm", drawArm);
        use.boneTracks().put("custom_bow", rotation(0.0D, 25.0D, 0.0D));
        use.boneTracks().get("custom_bow")
                .scale(constantTrack(1.0D, 1.0D, 1.0D));
        AnimationClip release = new AnimationClip("swing:bow");
        release.boneTracks().put("RightArm", rotation(0.0D, -20.0D, 0.0D));
        release.boneTracks().put("custom_bow", rotation(0.0D, -15.0D, 0.0D));
        release.boneTracks().get("custom_bow")
                .scale(constantTrack(1.0D, 1.0D, 1.0D));
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry);
        ParallelAnimationProgram program = new ParallelAnimationProgram(
                geometry, Map.of(pre.name(), pre, hold.name(), hold, use.name(), use,
                release.name(), release), layout, 1.0F, 1.0F);
        NeutralEnvironment drawnEnvironment = new NeutralEnvironment();
        drawnEnvironment.writeVariable(
                ExpressionEngine.slot("v.draw_angle"), 80.0D);
        NeutralEnvironment releasedEnvironment = new NeutralEnvironment();
        releasedEnvironment.writeVariable(
                ExpressionEngine.slot("v.draw_angle"), -70.0D);
        int rightArm = layout.entryForBoneName("RightArm").auxiliaryIndex();

        OpenMatrix4f drawn = new OpenMatrix4f().load(program.sampleAutomaticAt(
                0.0D, List.of(use.name()), drawnEnvironment)
                .wholeModelDeltas()[rightArm]);
        OpenMatrix4f transitionStart = new OpenMatrix4f().load(
                program.sampleAutomaticWithEndingAt(
                        0.0D, List.of(release.name()), use.name(),
                        0.0D, 1.0F, drawnEnvironment, releasedEnvironment)
                        .wholeModelDeltas()[rightArm]);
        OpenMatrix4f reevaluatedWithReleaseValues = new OpenMatrix4f().load(
                program.sampleAutomaticAt(
                        0.0D, List.of(use.name()), releasedEnvironment)
                        .wholeModelDeltas()[rightArm]);

        assertMatrixEquals(drawn, transitionStart);
        assertFalse(matrixEquals(transitionStart, reevaluatedWithReleaseValues));
    }

    @Test
    void blendsTheSavedCustomBowSwingPoseIntoTheResumedHoldLayer() {
        GeometryDocument geometry = bowUpperBodyGeometry();
        AnimationClip pre = new AnimationClip("pre_parallel0");
        AnimationClip.BoneTracks hidden = new AnimationClip.BoneTracks();
        hidden.scale(constantTrack(0.0D, 0.0D, 0.0D));
        pre.boneTracks().put("custom_bow", hidden);
        AnimationClip hold = new AnimationClip("hold_mainhand:bow");
        hold.boneTracks().put("RightArm", rotation(0.0D, 10.0D, 0.0D));
        hold.boneTracks().put("custom_bow", rotation(0.0D, 5.0D, 0.0D));
        hold.boneTracks().get("custom_bow")
                .scale(constantTrack(1.0D, 1.0D, 1.0D));
        AnimationClip release = new AnimationClip("swing:bow");
        release.boneTracks().put("RightArm", rotation(0.0D, -50.0D, 0.0D));
        release.boneTracks().put("custom_bow", rotation(0.0D, -15.0D, 0.0D));
        release.boneTracks().get("custom_bow")
                .scale(constantTrack(1.0D, 1.0D, 1.0D));
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry);
        ParallelAnimationProgram program = new ParallelAnimationProgram(
                geometry, Map.of(pre.name(), pre, hold.name(), hold,
                release.name(), release), layout, 1.0F, 1.0F);
        int rightArm = layout.entryForBoneName("RightArm").auxiliaryIndex();

        OpenMatrix4f released = new OpenMatrix4f().load(program.sampleAutomaticAt(
                0.0D, List.of(release.name()), new NeutralEnvironment())
                .wholeModelDeltas()[rightArm]);
        OpenMatrix4f resumedHold = new OpenMatrix4f().load(
                program.sampleAutomaticWithEndingAt(
                        0.0D, List.of(hold.name()), release.name(),
                        0.0D, 0.0F, new NeutralEnvironment())
                        .wholeModelDeltas()[rightArm]);
        OpenMatrix4f transitionStart = new OpenMatrix4f().load(
                program.sampleAutomaticWithEndingAt(
                        0.0D, List.of(hold.name()), release.name(),
                        0.0D, 1.0F, new NeutralEnvironment())
                        .wholeModelDeltas()[rightArm]);
        OpenMatrix4f transitionMiddle = new OpenMatrix4f().load(
                program.sampleAutomaticWithEndingAt(
                        0.0D, List.of(hold.name()), release.name(),
                        0.0D, 0.5F, new NeutralEnvironment())
                        .wholeModelDeltas()[rightArm]);

        assertMatrixEquals(released, transitionStart);
        assertFalse(matrixEquals(transitionMiddle, released));
        assertFalse(matrixEquals(transitionMiddle, resumedHold));
    }

    @Test
    void finalBowEndingPublishesSavedFullBodySourceOverTheLiveNormalTarget() {
        GeometryDocument geometry = bowUpperBodyGeometry();
        AnimationClip pre = new AnimationClip("pre_parallel0");
        AnimationClip.BoneTracks hidden = new AnimationClip.BoneTracks();
        hidden.scale(constantTrack(0.0D, 0.0D, 0.0D));
        pre.boneTracks().put("custom_bow", hidden);
        AnimationClip hold = new AnimationClip("hold_mainhand:bow");
        hold.boneTracks().put("custom_bow", rotation(0.0D, 5.0D, 0.0D));
        hold.boneTracks().get("custom_bow")
                .scale(constantTrack(1.0D, 1.0D, 1.0D));
        AnimationClip release = new AnimationClip("swing:bow");
        AnimationClip.BoneTracks releaseArm = new AnimationClip.BoneTracks();
        releaseArm.rotation(expressionTrack("0", "v.release_angle", "0"));
        release.boneTracks().put("RightArm", releaseArm);
        release.boneTracks().put("custom_bow", rotation(0.0D, -15.0D, 0.0D));
        release.boneTracks().get("custom_bow")
                .scale(constantTrack(1.0D, 1.0D, 1.0D));
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry);
        ParallelAnimationProgram program = new ParallelAnimationProgram(
                geometry, Map.of(pre.name(), pre, hold.name(), hold,
                release.name(), release), layout, 1.0F, 1.0F);
        NeutralEnvironment releasedEnvironment = new NeutralEnvironment();
        releasedEnvironment.writeVariable(
                ExpressionEngine.slot("v.release_angle"), -55.0D);
        NeutralEnvironment normalEnvironment = new NeutralEnvironment();
        normalEnvironment.writeVariable(
                ExpressionEngine.slot("v.release_angle"), 75.0D);
        int rightArm = layout.entryForBoneName("RightArm").auxiliaryIndex();
        int prop = layout.entryForBoneName("custom_bow").auxiliaryIndex();
        OpenMatrix4f expectedSource = new OpenMatrix4f().load(
                program.sampleAutomaticAt(0.0D, List.of(release.name()),
                        releasedEnvironment).wholeModelDeltas()[rightArm]);

        ParallelAnimationProgram.Frame ending =
                program.sampleAutomaticOwnershipEndingAt(
                        0.0D, List.of(release.name()),
                        0.0D, List.of(hold.name()), release.name(), 0.5F,
                        releasedEnvironment, normalEnvironment);

        assertFalse(ending.replaceEpicFightPose(),
                "the final ending target must remain the ordinary Epic-owned path");
        assertEquals(0.5F, ending.fullBodyBlendWeight(), 0.00001F);
        assertTrue(ending.fullBodyBlendSource() != null);
        assertMatrixEquals(expectedSource, ending.fullBodyBlendSource()[rightArm]);
        assertEquals(HumanoidRig.RIGHT_TOOL, ending.heldItemAnchorJoints()[prop]);
        assertTrue(ending.replaceEpicFightAnchors()[prop]);
        assertFalse(ending.replaceEpicFightAnchors()[rightArm]);
    }

    @Test
    void restartsSwingChannelForAChangedOrRewoundEpicFightAttack() {
        AutomaticAnimationSelector.State state = new AutomaticAnimationSelector.State();
        AnimationConditionMatcher.SwingSignal first =
                new AnimationConditionMatcher.SwingSignal(
                        true, "epicfight:sword/attack1", 0.1F);
        String firstToken = state.swingToken(InteractionHand.MAIN_HAND, first);

        assertEquals(firstToken, state.swingToken(InteractionHand.MAIN_HAND,
                new AnimationConditionMatcher.SwingSignal(
                        true, "epicfight:sword/attack1", 0.5F)));
        assertFalse(firstToken.equals(state.swingToken(InteractionHand.MAIN_HAND,
                new AnimationConditionMatcher.SwingSignal(
                        true, "epicfight:sword/attack1", 0.05F))));
        String changed = state.swingToken(InteractionHand.MAIN_HAND,
                new AnimationConditionMatcher.SwingSignal(
                        true, "epicfight:sword/attack2", 0.0F));
        assertFalse(firstToken.equals(changed));
        state.swingToken(InteractionHand.MAIN_HAND,
                new AnimationConditionMatcher.SwingSignal(false, "", 0.0F));
        assertFalse(changed.equals(state.swingToken(InteractionHand.MAIN_HAND,
                first)));
    }

    @Test
    void neverRetainsAutomaticAnimationsAsRouletteClips() {
        GeometryDocument geometry = headAndEar();
        AnimationClip condition = new AnimationClip("vehicle$minecraft:pig");
        condition.boneTracks().put("ear", rotation(0.0D, 0.0D, 90.0D));
        ParallelAnimationProgram program = program(geometry, condition);

        ParallelAnimationProgram.Frame frame = program.sampleAt(
                0.0D, condition.name(), 0.0D, new NeutralEnvironment());

        assertIdentity(frame.wholeModelDeltas()[0]);
        assertIdentity(frame.wholeModelDeltas()[1]);
    }

    @Test
    void ignoresMajorPoseTracksEvenForTheirAuxiliaryChildren() {
        GeometryDocument geometry = headAndEar();
        AnimationClip clip = new AnimationClip("parallel0");
        clip.boneTracks().put("head", rotation(0.0D, 0.0D, 90.0D));
        ParallelAnimationProgram program = program(geometry, clip);

        ParallelAnimationProgram.Frame frame = program.sampleAt(0.0D, new NeutralEnvironment());

        assertIdentity(frame.parallelDeltas()[0]);
        assertIdentity(frame.parallelDeltas()[1]);
    }

    @Test
    void appliesPoseTracksOnlyToTheAddressedAuxiliaryBone() {
        GeometryDocument geometry = headAndEar();
        AnimationClip clip = new AnimationClip("parallel0");
        clip.boneTracks().put("ear", rotation(0.0D, 0.0D, 90.0D));
        ParallelAnimationProgram program = program(geometry, clip);

        ParallelAnimationProgram.Frame frame = program.sampleAt(0.0D, new NeutralEnvironment());

        assertFalse(isIdentity(frame.parallelDeltas()[1]));
    }

    @Test
    void composesAnimatedLocalTransformAfterTheRotatedParentBindFrame() {
        GeometryDocument geometry = headAndEar();
        GeometryDocument.Bone ear = geometry.bones().get("ear");
        ear.rotation(0.0F, 0.0F, (float) Math.toRadians(30.0D));
        AnimationClip clip = new AnimationClip("parallel0");
        clip.boneTracks().put("ear", rotation(0.0D, 0.0D, 90.0D));
        ParallelAnimationProgram program = program(geometry, clip);

        ParallelAnimationProgram.Frame frame = program.sampleAt(
                0.0D, new NeutralEnvironment());

        Matrix4f bind = new Matrix4f().translation(0.0F, 1.0F, 0.0F)
                .rotateZ((float) Math.toRadians(30.0D)).translate(0.0F, -1.0F, 0.0F);
        Matrix4f animated = new Matrix4f().translation(0.0F, 1.0F, 0.0F)
                .rotateZ((float) Math.toRadians(120.0D)).translate(0.0F, -1.0F, 0.0F);
        assertMatrix(new Matrix4f(animated).mul(new Matrix4f(bind).invert()),
                frame.parallelDeltas()[1]);
    }

    @Test
    void accumulatesRotationFromAllParallelClips() {
        GeometryDocument geometry = headAndEar();
        AnimationClip lowerPriority = new AnimationClip("parallel0");
        lowerPriority.boneTracks().put("ear", rotation(0.0D, 0.0D, 10.0D));
        AnimationClip higherPriority = new AnimationClip("parallel1");
        higherPriority.boneTracks().put("ear", rotation(0.0D, 0.0D, 25.0D));
        ParallelAnimationProgram program = new ParallelAnimationProgram(
                geometry, Map.of(lowerPriority.name(), lowerPriority,
                higherPriority.name(), higherPriority),
                AuxiliaryBoneLayout.create(geometry), 1.0F, 1.0F);

        ParallelAnimationProgram.Frame frame = program.sampleAt(
                0.0D, new NeutralEnvironment());

        Matrix4f animated = new Matrix4f().translation(0.0F, 1.0F, 0.0F)
                .rotateZ((float) Math.toRadians(35.0D)).translate(0.0F, -1.0F, 0.0F);
        assertMatrix(animated, frame.parallelDeltas()[1]);
    }

    @Test
    void appliesBlendWeightToAParallelRotation() {
        GeometryDocument geometry = headAndEar();
        AnimationClip clip = new AnimationClip("pre_parallel2");
        clip.blendWeight().setConstant(2.0D);
        clip.boneTracks().put("ear", rotation(0.0D, 0.0D, 10.0D));
        ParallelAnimationProgram program = program(geometry, clip);

        ParallelAnimationProgram.Frame frame = program.sampleAt(
                0.0D, new NeutralEnvironment());

        Matrix4f animated = new Matrix4f().translation(0.0F, 1.0F, 0.0F)
                .rotateZ((float) Math.toRadians(20.0D)).translate(0.0F, -1.0F, 0.0F);
        assertMatrix(animated, frame.parallelDeltas()[1]);
    }

    @Test
    void eyeDotPositionIsRelativeEvenWhenItsAuthoredPivotIsFarFromTheCube() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone head = new GeometryDocument.Bone("head");
        GeometryDocument.Bone eyeDot = new GeometryDocument.Bone("RightEyeDot");
        eyeDot.parentName("head");
        eyeDot.pivot(5.95F / 16.0F, 15.35F / 16.0F, -3.55F / 16.0F);
        geometry.add(head);
        geometry.add(eyeDot);
        geometry.linkHierarchy();
        AnimationClip clip = new AnimationClip("pre_parallel3");
        AnimationClip.BoneTracks tracks = new AnimationClip.BoneTracks();
        tracks.position(expressionTrack(
                "math.lerp(0,math.sin(q.anim_time*1440),math.exp(-q.anim_time*5))",
                "0", "0"));
        clip.boneTracks().put(eyeDot.name(), tracks);
        ParallelAnimationProgram program = program(geometry, clip);
        double animationTime = 0.3D;

        ParallelAnimationProgram.Frame frame = program.sampleAt(
                animationTime, new NeutralEnvironment(animationTime));

        double offset = Math.sin(Math.toRadians(animationTime * 1440.0D))
                * Math.exp(-animationTime * 5.0D);
        assertMatrix(new Matrix4f().translation((float) (-offset / 16.0D), 0.0F, 0.0F),
                frame.parallelDeltas()[1]);
    }

    @Test
    void evaluatesScriptTracksWithoutGivingTheirPseudoBonesAPose() {
        GeometryDocument geometry = headAndEar();
        AnimationClip script = new AnimationClip("pre_parallel0");
        AnimationClip.BoneTracks scriptTracks = new AnimationClip.BoneTracks();
        scriptTracks.rotation(expressionTrack("v.ear_angle=15", "0", "0"));
        script.boneTracks().put("molang", scriptTracks);
        AnimationClip pose = new AnimationClip("parallel0");
        AnimationClip.BoneTracks poseTracks = new AnimationClip.BoneTracks();
        poseTracks.rotation(expressionTrack("0", "0", "v.ear_angle"));
        pose.boneTracks().put("ear", poseTracks);
        ParallelAnimationProgram program = new ParallelAnimationProgram(
                geometry, Map.of(script.name(), script, pose.name(), pose),
                AuxiliaryBoneLayout.create(geometry), 1.0F, 1.0F);

        ParallelAnimationProgram.Frame frame = program.sampleAt(
                0.0D, new NeutralEnvironment());

        Matrix4f animated = new Matrix4f().translation(0.0F, 1.0F, 0.0F)
                .rotateZ((float) Math.toRadians(15.0D)).translate(0.0F, -1.0F, 0.0F);
        assertMatrix(animated, frame.parallelDeltas()[1]);
    }

    @Test
    void firesTimelineTailBeforeHeadWhenALoopWraps() {
        AnimationClip clip = new AnimationClip("parallel4");
        clip.timeline().add(new AnimationClip.TimelineEvent(
                0.0F, List.of("v.order=v.order*10+1")));
        clip.timeline().add(new AnimationClip.TimelineEvent(
                0.0101F, List.of("v.order=v.order*10+2")));
        NeutralEnvironment environment = new NeutralEnvironment();
        Map<String, Float> lastLocalTime = new HashMap<>();
        lastLocalTime.put(clip.name(), 0.009F);

        ParallelAnimationProgram.fireTimeline(
                clip, 0.005F, environment, lastLocalTime);

        assertTrue(Math.abs(environment.value("v.order") - 21.0D) < 0.00001D);
    }

    @Test
    void treatsMajorScaleTracksAsVisibilityWithoutChangingAuxiliaryMatrices() {
        GeometryDocument geometry = headAndEar();
        AnimationClip clip = new AnimationClip("parallel0");
        AnimationClip.BoneTracks tracks = new AnimationClip.BoneTracks();
        tracks.scale(constantTrack(0.0D, 0.0D, 0.0D));
        clip.boneTracks().put("head", tracks);
        ParallelAnimationProgram program = program(geometry, clip);

        ParallelAnimationProgram.Frame frame = program.sampleAt(0.0D, new NeutralEnvironment());

        assertTrue(frame.hiddenBones().contains("head"));
        assertTrue(frame.hiddenBones().contains("ear"));
        assertIdentity(frame.parallelDeltas()[0]);
    }

    private static ParallelAnimationProgram program(GeometryDocument geometry,
                                                     AnimationClip clip) {
        return new ParallelAnimationProgram(geometry, Map.of(clip.name(), clip),
                AuxiliaryBoneLayout.create(geometry), 1.0F, 1.0F);
    }

    private static GeometryDocument headAndEar() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone head = new GeometryDocument.Bone("head");
        GeometryDocument.Bone ear = new GeometryDocument.Bone("ear");
        ear.parentName("head");
        ear.pivot(0.0F, 1.0F, 0.0F);
        geometry.add(head);
        geometry.add(ear);
        geometry.linkHierarchy();
        return geometry;
    }

    private static GeometryDocument wrappedRootAndTail() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone wrapper = new GeometryDocument.Bone("MRoot");
        GeometryDocument.Bone root = new GeometryDocument.Bone("Root");
        GeometryDocument.Bone headWrapper = new GeometryDocument.Bone("MHead");
        GeometryDocument.Bone head = new GeometryDocument.Bone("Head");
        GeometryDocument.Bone tail = new GeometryDocument.Bone("tail");
        root.parentName("MRoot");
        headWrapper.parentName("Root");
        head.parentName("MHead");
        tail.parentName("MHead");
        geometry.add(wrapper);
        geometry.add(root);
        geometry.add(headWrapper);
        geometry.add(head);
        geometry.add(tail);
        geometry.linkHierarchy();
        return geometry;
    }

    private static GeometryDocument heldAndBackProps() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone root = new GeometryDocument.Bone("Root");
        GeometryDocument.Bone held = new GeometryDocument.Bone("held_staff");
        GeometryDocument.Bone back = new GeometryDocument.Bone("back_staff");
        held.parentName("Root");
        back.parentName("Root");
        geometry.add(root);
        geometry.add(held);
        geometry.add(back);
        geometry.linkHierarchy();
        return geometry;
    }

    private static GeometryDocument handPropGeometry() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone root = new GeometryDocument.Bone("Root");
        GeometryDocument.Bone arm = new GeometryDocument.Bone("RightArm");
        GeometryDocument.Bone forearm = new GeometryDocument.Bone("RightForeArm");
        GeometryDocument.Bone hand = new GeometryDocument.Bone("RightHand");
        GeometryDocument.Bone grip = new GeometryDocument.Bone("grip");
        GeometryDocument.Bone prop = new GeometryDocument.Bone("custom_prop");
        arm.parentName("Root");
        forearm.parentName("RightArm");
        hand.parentName("RightForeArm");
        grip.parentName("RightHand");
        prop.parentName("grip");
        prop.pivot(0.0F, 1.0F, 0.0F);
        prop.faces().add(new GeometryDocument.Face(new Vector3f[]{
                new Vector3f(-0.5F, 0.0F, 0.0F),
                new Vector3f(0.5F, 0.0F, 0.0F),
                new Vector3f(0.5F, 2.0F, 0.0F),
                new Vector3f(-0.5F, 2.0F, 0.0F)},
                new float[][]{{0.0F, 0.0F}, {1.0F, 0.0F},
                        {1.0F, 1.0F}, {0.0F, 1.0F}},
                new Vector3f(0.0F, 0.0F, 1.0F)));
        geometry.add(root);
        geometry.add(arm);
        geometry.add(forearm);
        geometry.add(hand);
        geometry.add(grip);
        geometry.add(prop);
        geometry.linkHierarchy();
        return geometry;
    }

    private static GeometryDocument bowUpperBodyGeometry() {
        GeometryDocument geometry = new GeometryDocument();
        GeometryDocument.Bone root = new GeometryDocument.Bone("Root");
        GeometryDocument.Bone body = new GeometryDocument.Bone("AllBody");
        GeometryDocument.Bone upper = new GeometryDocument.Bone("UpBody");
        GeometryDocument.Bone head = new GeometryDocument.Bone("Head");
        GeometryDocument.Bone rightArm = new GeometryDocument.Bone("RightArm");
        GeometryDocument.Bone rightForearm = new GeometryDocument.Bone("RightForeArm");
        GeometryDocument.Bone rightHand = new GeometryDocument.Bone("RightHand");
        GeometryDocument.Bone rightLocator = new GeometryDocument.Bone("RightHandLocator");
        GeometryDocument.Bone prop = new GeometryDocument.Bone("custom_bow");
        GeometryDocument.Bone circle = new GeometryDocument.Bone("magic_circle");
        GeometryDocument.Bone leftArm = new GeometryDocument.Bone("LeftArm");
        GeometryDocument.Bone leftForearm = new GeometryDocument.Bone("LeftForeArm");
        GeometryDocument.Bone leftHand = new GeometryDocument.Bone("LeftHand");
        GeometryDocument.Bone leftLocator = new GeometryDocument.Bone("LeftHandLocator");
        GeometryDocument.Bone downBody = new GeometryDocument.Bone("DownBody");
        GeometryDocument.Bone rightLeg = new GeometryDocument.Bone("RightLeg");
        body.parentName("Root");
        upper.parentName("AllBody");
        head.parentName("UpBody");
        rightArm.parentName("UpBody");
        rightForearm.parentName("RightArm");
        rightHand.parentName("RightForeArm");
        rightLocator.parentName("RightHand");
        rightLocator.pivot(-3.0F, 0.0F, 0.0F);
        prop.parentName("RightHand");
        circle.parentName("custom_bow");
        leftArm.parentName("UpBody");
        leftForearm.parentName("LeftArm");
        leftHand.parentName("LeftForeArm");
        leftLocator.parentName("LeftHand");
        leftLocator.pivot(3.0F, 0.0F, 0.0F);
        downBody.parentName("AllBody");
        rightLeg.parentName("DownBody");
        prop.faces().add(new GeometryDocument.Face(new Vector3f[]{
                new Vector3f(-0.5F, 0.0F, 0.0F),
                new Vector3f(0.5F, 0.0F, 0.0F),
                new Vector3f(0.5F, 2.0F, 0.0F),
                new Vector3f(-0.5F, 2.0F, 0.0F)},
                new float[][]{{0.0F, 0.0F}, {1.0F, 0.0F},
                        {1.0F, 1.0F}, {0.0F, 1.0F}},
                new Vector3f(0.0F, 0.0F, 1.0F)));
        for (GeometryDocument.Bone bone : List.of(root, body, upper, head,
                rightArm, rightForearm, rightHand, prop,
                rightLocator, circle, leftArm, leftForearm, leftHand, leftLocator,
                downBody, rightLeg)) {
            geometry.add(bone);
        }
        geometry.linkHierarchy();
        return geometry;
    }

    private static AnimationClip customSwordHold(double armRotation) {
        AnimationClip hold = new AnimationClip("hold_mainhand:sword");
        hold.boneTracks().put("RightArm", rotation(0.0D, 0.0D, armRotation));
        hold.boneTracks().put("grip", rotation(0.0D, 0.0D, 0.0D));
        hold.boneTracks().put("custom_prop", rotation(0.0D, 0.0D, 35.0D));
        AnimationClip.BoneTracks visible = hold.boneTracks().get("custom_prop");
        visible.scale(constantTrack(1.0D, 1.0D, 1.0D));
        return hold;
    }

    private static AnimationClip customBowHold() {
        AnimationClip hold = new AnimationClip("hold_mainhand:bow");
        hold.boneTracks().put("custom_bow", rotation(0.0D, 0.0D, 0.0D));
        hold.boneTracks().get("custom_bow")
                .scale(constantTrack(1.0D, 1.0D, 1.0D));
        return hold;
    }

    private static AnimationClip.BoneTracks rotation(double x, double y, double z) {
        AnimationClip.BoneTracks tracks = new AnimationClip.BoneTracks();
        tracks.rotation(constantTrack(x, y, z));
        return tracks;
    }

    private static AnimationClip.Track constantTrack(double x, double y, double z) {
        AnimationClip.VectorValue value = new AnimationClip.VectorValue();
        value.setConstant(0, x);
        value.setConstant(1, y);
        value.setConstant(2, z);
        AnimationClip.Track track = new AnimationClip.Track();
        track.keyframes().add(new AnimationClip.Keyframe(0.0F,
                AnimationClip.Interpolation.LINEAR, value, null));
        return track;
    }

    private static AnimationClip.Track expressionTrack(String x, String y, String z) {
        AnimationClip.VectorValue value = new AnimationClip.VectorValue();
        value.setExpression(0, x);
        value.setExpression(1, y);
        value.setExpression(2, z);
        AnimationClip.Track track = new AnimationClip.Track();
        track.keyframes().add(new AnimationClip.Keyframe(0.0F,
                AnimationClip.Interpolation.LINEAR, value, null));
        return track;
    }

    private static void assertIdentity(OpenMatrix4f matrix) {
        assertTrue(isIdentity(matrix));
    }

    private static void assertMatrix(Matrix4f expected, OpenMatrix4f actual) {
        float epsilon = 0.00001F;
        assertTrue(Math.abs(expected.m00() - actual.m00) < epsilon
                && Math.abs(expected.m01() - actual.m01) < epsilon
                && Math.abs(expected.m02() - actual.m02) < epsilon
                && Math.abs(expected.m03() - actual.m03) < epsilon
                && Math.abs(expected.m10() - actual.m10) < epsilon
                && Math.abs(expected.m11() - actual.m11) < epsilon
                && Math.abs(expected.m12() - actual.m12) < epsilon
                && Math.abs(expected.m13() - actual.m13) < epsilon
                && Math.abs(expected.m20() - actual.m20) < epsilon
                && Math.abs(expected.m21() - actual.m21) < epsilon
                && Math.abs(expected.m22() - actual.m22) < epsilon
                && Math.abs(expected.m23() - actual.m23) < epsilon
                && Math.abs(expected.m30() - actual.m30) < epsilon
                && Math.abs(expected.m31() - actual.m31) < epsilon
                && Math.abs(expected.m32() - actual.m32) < epsilon
                && Math.abs(expected.m33() - actual.m33) < epsilon);

        Vector4f expectedPoint = expected.transform(
                new Vector4f(2.25F, -3.5F, 4.75F, 1.0F));
        Vec4f actualPoint = OpenMatrix4f.transform(actual,
                new Vec4f(2.25F, -3.5F, 4.75F, 1.0F), new Vec4f());
        assertTrue(Math.abs(expectedPoint.x - actualPoint.x) < epsilon
                && Math.abs(expectedPoint.y - actualPoint.y) < epsilon
                && Math.abs(expectedPoint.z - actualPoint.z) < epsilon
                && Math.abs(expectedPoint.w - actualPoint.w) < epsilon);
    }

    private static void assertMatrixEquals(OpenMatrix4f expected, OpenMatrix4f actual) {
        assertTrue(matrixEquals(expected, actual));
    }

    private static boolean matrixEquals(OpenMatrix4f expected, OpenMatrix4f actual) {
        float epsilon = 0.00001F;
        return Math.abs(expected.m00 - actual.m00) < epsilon
                && Math.abs(expected.m01 - actual.m01) < epsilon
                && Math.abs(expected.m02 - actual.m02) < epsilon
                && Math.abs(expected.m03 - actual.m03) < epsilon
                && Math.abs(expected.m10 - actual.m10) < epsilon
                && Math.abs(expected.m11 - actual.m11) < epsilon
                && Math.abs(expected.m12 - actual.m12) < epsilon
                && Math.abs(expected.m13 - actual.m13) < epsilon
                && Math.abs(expected.m20 - actual.m20) < epsilon
                && Math.abs(expected.m21 - actual.m21) < epsilon
                && Math.abs(expected.m22 - actual.m22) < epsilon
                && Math.abs(expected.m23 - actual.m23) < epsilon
                && Math.abs(expected.m30 - actual.m30) < epsilon
                && Math.abs(expected.m31 - actual.m31) < epsilon
                && Math.abs(expected.m32 - actual.m32) < epsilon
                && Math.abs(expected.m33 - actual.m33) < epsilon;
    }

    private static boolean isIdentity(OpenMatrix4f matrix) {
        return Math.abs(matrix.m00 - 1.0F) < 0.00001F
                && Math.abs(matrix.m11 - 1.0F) < 0.00001F
                && Math.abs(matrix.m22 - 1.0F) < 0.00001F
                && Math.abs(matrix.m33 - 1.0F) < 0.00001F
                && Math.abs(matrix.m01) < 0.00001F && Math.abs(matrix.m02) < 0.00001F
                && Math.abs(matrix.m03) < 0.00001F && Math.abs(matrix.m10) < 0.00001F
                && Math.abs(matrix.m12) < 0.00001F && Math.abs(matrix.m13) < 0.00001F
                && Math.abs(matrix.m20) < 0.00001F && Math.abs(matrix.m21) < 0.00001F
                && Math.abs(matrix.m23) < 0.00001F && Math.abs(matrix.m30) < 0.00001F
                && Math.abs(matrix.m31) < 0.00001F && Math.abs(matrix.m32) < 0.00001F;
    }

    private static final class NeutralEnvironment implements ExpressionEngine.Environment {
        private final Map<Integer, Double> variables = new HashMap<>();
        private final double animationTime;

        private NeutralEnvironment() {
            this(0.0D);
        }

        private NeutralEnvironment(double animationTime) {
            this.animationTime = animationTime;
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

        private double value(String name) {
            return variables.getOrDefault(ExpressionEngine.slot(name), 0.0D);
        }

        @Override
        public double readQuery(int slot) {
            return "query.anim_time".equals(ExpressionEngine.slotName(slot))
                    ? animationTime : 0.0D;
        }

        @Override
        public double invoke(String name, double[] arguments) {
            return switch (name) {
                case "math.sin" -> Math.sin(Math.toRadians(arguments[0]));
                case "math.exp" -> Math.exp(arguments[0]);
                case "math.lerp" -> arguments[0]
                        + (arguments[1] - arguments[0]) * arguments[2];
                default -> 0.0D;
            };
        }

        @Override
        public double invokeWithText(String name, String[] arguments) {
            return 0.0D;
        }
    }
}
