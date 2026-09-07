package net.okitsu.ysmepicfightcompat.animation;

import com.google.gson.JsonParser;
import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import net.okitsu.ysmepicfightcompat.mesh.AuxiliaryBoneLayout;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ModAnimationSelectionTest {
    @Test
    void onlyDeclaredOptionalClipsBecomeAutomatic() {
        assertTrue(BedrockAnimationParser.isAutomatic("parcool:roll_front"));
        assertTrue(BedrockAnimationParser.isAutomatic("PARCOOL:RIDE_ZIPLINE"));
        assertTrue(BedrockAnimationParser.isAutomatic("swem:canter_ext"));
        assertTrue(BedrockAnimationParser.isAutomatic("swem:jump_lv5"));
        assertFalse(BedrockAnimationParser.isAutomatic("swem:jump_lv6"));
        assertFalse(BedrockAnimationParser.isAutomatic("parcool:custom"));
        assertFalse(BedrockAnimationParser.isAutomatic("swem:idle$untrusted"));
        assertNull(ModAnimationResolver.sample(null, 0.0F));
    }

    @Test
    void invalidClockUsesFallbackButInvalidClipFamilyIsRejected() {
        assertTrue(Double.isNaN(new ModAnimationSample(ModAnimationType.SWEM,
                "swem:idle", Double.POSITIVE_INFINITY, 1L).elapsedSeconds()));
        assertThrows(IllegalArgumentException.class, () -> new ModAnimationSample(
                ModAnimationType.PARCOOL, "swem:idle", 0.0D, 1L));
    }

    @Test
    void nativeClockAndRestartTokenRemainAuthoritative() {
        AutomaticAnimationSelector selector = new AutomaticAnimationSelector(Map.of());
        AutomaticAnimationSelector.State state = new AutomaticAnimationSelector.State();
        var first = selector.trackMod(state, parkour(0.35D, 4L), 20.0D);
        var continued = selector.trackMod(state, parkour(0.45D, 4L), 20.1D);
        var repeated = selector.trackMod(state, parkour(0.025D, 5L), 20.2D);
        assertTrue(first.restarted());
        assertEquals(0.35D, first.elapsed());
        assertFalse(continued.restarted());
        assertEquals(0.45D, continued.elapsed());
        assertTrue(repeated.restarted());
        assertEquals(0.025D, repeated.elapsed());
    }

    @Test
    void absentNativeClockUsesElapsedSinceSelectionAndResetStartsFresh() {
        AutomaticAnimationSelector selector = new AutomaticAnimationSelector(Map.of());
        AutomaticAnimationSelector.State state = new AutomaticAnimationSelector.State();
        selector.trackMod(state, parkour(Double.NaN, 4L), 20.0D);
        assertEquals(0.5D, selector.trackMod(state, parkour(Double.NaN, 4L), 20.5D).elapsed());
        state.reset();
        var reset = selector.trackMod(state, parkour(Double.NaN, 4L), 30.0D);
        assertTrue(reset.restarted());
        assertEquals(0.0D, reset.elapsed());
    }

    @Test
    void optionalOneShotOwnsWholeBodyAndHoldsEndpointWithoutLooping() {
        Fixture fixture = fixture("parcool:roll_front", false);
        var frame = fixture.program.sampleModAnimationAt(2.0D, parkour(1.25D, 1L), new Neutral());
        assertTrue(frame.replaceEpicFightPose());
        assertEquals("mod:PARCOOL:parcool:roll_front", frame.movementPoseKey());
        assertFalse(frame.naturalLadderPose());
        assertEquals(0.0F, frame.wholeModelDeltas()[fixture.headIndex].m00, 1.0E-5F);
    }

    @Test
    void optionalRepeatingGaitKeepsAuthoredLoop() {
        Fixture fixture = fixture("swem:walk", true);
        var sample = new ModAnimationSample(ModAnimationType.SWEM, "swem:walk", 1.25D, 1L);
        var frame = fixture.program.sampleModAnimationAt(2.0D, sample, new Neutral());
        assertTrue(frame.replaceEpicFightPose());
        assertEquals("mod:SWEM:swem:walk", frame.movementPoseKey());
        assertEquals((float) Math.cos(Math.PI / 8.0D),
                frame.wholeModelDeltas()[fixture.headIndex].m00, 1.0E-5F);
    }

    @Test
    void optionalPoseRetainsOfficialFinalCameraHeadRotation() {
        Fixture fixture = fixture("swem:idle", true);
        Neutral environment = new Neutral() {
            @Override
            public double readQuery(int slot) {
                return slot == ExpressionEngine.querySlot("ysm.head_yaw") ? 45.0D : 0.0D;
            }
        };
        var sample = new ModAnimationSample(ModAnimationType.SWEM, "swem:idle", 0.0D, 1L);
        var frame = fixture.program.sampleModAnimationAt(0.0D, sample, environment);
        assertEquals((float) Math.cos(Math.PI / 4.0D),
                frame.wholeModelDeltas()[fixture.headIndex].m00, 1.0E-5F);
    }

    @Test
    void expressionOnlyNativeClipDoesNotFreezeItsAnimationClock() {
        Fixture fixture = fixture("parcool:hang", """
                {"bones":{"Head":{"rotation":[0,0,"q.anim_time * 90"]}}}
                """);
        SnapshotExpressionEnvironment environment = SnapshotExpressionEnvironment.capture(
                new Neutral(), Set.of(), Set.of());
        var sample = new ModAnimationSample(ModAnimationType.PARCOOL, "parcool:hang", 0.5D, 1L);
        var frame = fixture.program.sampleModAnimationAt(50.0D, sample, environment);
        assertEquals((float) Math.cos(Math.PI / 4.0D),
                frame.wholeModelDeltas()[fixture.headIndex].m00, 1.0E-5F);
    }

    @Test
    void missingEmptyOrUnmatchedOptionalClipRetainsEpicFightOwnership() {
        Fixture fixture = fixture("parcool:backward_wall_jump", "{\"loop\":true}");
        assertFalse(fixture.program.supportsModAnimation("parcool:backward_wall_jump"));
        assertFalse(fixture.program.supportsModAnimation("swem:idle"));
        var empty = new ModAnimationSample(ModAnimationType.PARCOOL,
                "parcool:backward_wall_jump", 0.2D, 1L);
        var frame = fixture.program.sampleModAnimationAt(0.2D, empty, new Neutral());
        assertFalse(frame.replaceEpicFightPose());
        assertNull(frame.movementPoseKey());

        Fixture unmatched = fixture("parcool:roll_front",
                "{\"bones\":{\"MissingBone\":{\"rotation\":[0,0,90]}}}");
        assertFalse(unmatched.program.supportsModAnimation("parcool:roll_front"));
        assertFalse(unmatched.program.sampleModAnimationAt(0.2D,
                parkour(0.2D, 1L), new Neutral()).replaceEpicFightPose());
    }

    private static ModAnimationSample parkour(double elapsed, long token) {
        return new ModAnimationSample(ModAnimationType.PARCOOL, "parcool:roll_front", elapsed, token);
    }

    private record Fixture(ParallelAnimationProgram program, int headIndex) { }

    private static Fixture fixture(String name, boolean loop) {
        return fixture(name, """
                {"animation_length":1,"loop":%s,"bones":{
                    "Head":{"rotation":{"0":[0,0,0],"1":[0,0,90]}}
                }}
                """.formatted(loop));
    }

    private static Fixture fixture(String name, String source) {
        GeometryDocument geometry = new GeometryDocument();
        geometry.add(new GeometryDocument.Bone("Root"));
        GeometryDocument.Bone head = new GeometryDocument.Bone("Head");
        head.parentName("Root");
        geometry.add(head);
        geometry.linkHierarchy();
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry);
        AnimationClip clip = BedrockAnimationParser.parse(name,
                JsonParser.parseString(source).getAsJsonObject());
        return new Fixture(new ParallelAnimationProgram(geometry, Map.of(name, clip),
                layout, 1.0F, 1.0F), layout.entryForBoneName("Head").auxiliaryIndex());
    }

    private static class Neutral implements ExpressionEngine.Environment {
        public double readVariable(int slot) { return 0.0D; }
        public boolean hasVariable(int slot) { return false; }
        public void writeVariable(int slot, double value) { }
        public double readQuery(int slot) { return 0.0D; }
        public double invoke(String name, double[] arguments) { return 0.0D; }
        public double invokeWithText(String name, String[] arguments) { return 0.0D; }
    }
}
