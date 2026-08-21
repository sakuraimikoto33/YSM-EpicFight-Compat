package net.okitsu.ysmepicfightcompat.animation;

import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import net.okitsu.ysmepicfightcompat.mesh.AuxiliaryBoneLayout;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.api.utils.math.Vec4f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelAnimationProgramTest {
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
        assertMatrix(rouletteHead, frame.rouletteDeltas()[0]);
        assertMatrix(rouletteHead, frame.rouletteDeltas()[1]);
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
        assertMatrix(expected, frame.rouletteDeltas()[0]);
        assertMatrix(expected, frame.rouletteDeltas()[1]);
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

        assertIdentity(frame.rouletteDeltas()[0]);
        assertIdentity(frame.rouletteDeltas()[1]);
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

        assertIdentity(frame.rouletteDeltas()[0]);
        assertIdentity(frame.rouletteDeltas()[1]);
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

        assertFalse(isIdentity(frame.rouletteDeltas()[0]));
        assertFalse(isIdentity(frame.rouletteDeltas()[1]));
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
