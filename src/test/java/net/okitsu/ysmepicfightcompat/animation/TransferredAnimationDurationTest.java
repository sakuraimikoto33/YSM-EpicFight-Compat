package net.okitsu.ysmepicfightcompat.animation;

import net.okitsu.ysmepicfightcompat.assets.ModelBundle;
import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import net.okitsu.ysmepicfightcompat.mesh.AuxiliaryBoneLayout;
import net.okitsu.ysmepicfightcompat.network.geometry.GeometryTransferCodec;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TransferredAnimationDurationTest {
    @ParameterizedTest
    @ValueSource(floats = {0.0F, 1.0F, Float.POSITIVE_INFINITY})
    void samplingAfterTheLastPositiveKeyKeepsLoopAndEndBehavior(float duration) throws IOException {
        for (AnimationClip.Playback playback : AnimationClip.Playback.values()) {
            ModelBundle source = model(duration, playback);
            AnimationClip.Track rotation = new AnimationClip.Track();
            AnimationClip.VectorValue start = new AnimationClip.VectorValue();
            AnimationClip.VectorValue end = new AnimationClip.VectorValue();
            end.setConstant(2, 90);
            rotation.keyframes().add(new AnimationClip.Keyframe(0,
                    AnimationClip.Interpolation.LINEAR, start, null));
            rotation.keyframes().add(new AnimationClip.Keyframe(1,
                    AnimationClip.Interpolation.LINEAR, end, null));
            AnimationClip.BoneTracks tracks = new AnimationClip.BoneTracks();
            tracks.rotation(rotation);
            source.animations().get("extra0").boneTracks().put("head", tracks);
            ModelBundle restored = restored(source);
            ModelBundle forwarded = restored(restored);
            ParallelAnimationProgram expected = program(source);
            ParallelAnimationProgram decoded = program(restored);
            ParallelAnimationProgram relayed = program(forwarded);
            for (double time : new double[]{0, 0.5, 1.25, 2.75}) {
                float[] pose = pose(expected, time);
                assertArrayEquals(pose, pose(decoded, time), 0.00001F);
                assertArrayEquals(pose, pose(relayed, time), 0.00001F);
            }
            // With a positive key at 1 second, the old Infinity-to-zero codec
            // either wrapped REPEAT or released ONCE at 1.25 seconds.
            if (duration == Float.POSITIVE_INFINITY) {
                assertEquals(0.0F, pose(decoded, 1.25)[0], 0.00001F);
            } else if (playback == AnimationClip.Playback.ONCE) {
                assertEquals(1.0F, pose(decoded, 1.25)[0], 0.00001F);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(floats = {0.0F, 1.0F, Float.POSITIVE_INFINITY})
    void controllerCompletionAndLifecycleEventsRemainIdenticalAfterTransfer(float duration)
            throws IOException {
        for (AnimationClip.Playback playback : AnimationClip.Playback.values()) {
            ModelBundle source = model(duration, playback);
            AnimationController.State waiting = new AnimationController.State("waiting",
                    List.of(new AnimationController.AnimationReference("extra0", "1")),
                    List.of(new AnimationController.Transition("done", "q.all_animations_finished")),
                    List.of("v.entered+=1;"), List.of("v.exited+=1;"),
                    new AnimationController.BlendTransition(0, List.of()), false);
            AnimationController.State done = new AnimationController.State("done",
                    List.of(), List.of(), List.of("v.completed+=1;"), List.of(),
                    new AnimationController.BlendTransition(0, List.of()), false);
            AnimationController controller = new AnimationController("player.parallel_0", "waiting",
                    Map.of("waiting", waiting, "done", done));
            source.animationControllers().put(controller.name(), controller);
            ModelBundle restored = restored(source);
            List<ControllerStep> expected = controllerSteps(source);
            assertEquals(expected, controllerSteps(restored));
            assertEquals(expected, controllerSteps(restored(restored)));
            ControllerStep last = expected.get(expected.size() - 1);
            assertEquals(1, last.entered());
            assertEquals(duration == Float.POSITIVE_INFINITY ? 0 : 1, last.exited());
            assertEquals(duration == Float.POSITIVE_INFINITY ? 0 : 1, last.completed());
        }
    }

    private static ModelBundle model(float duration, AnimationClip.Playback playback) {
        GeometryDocument geometry = new GeometryDocument();
        geometry.add(new GeometryDocument.Bone("head"));
        geometry.linkHierarchy();
        AnimationClip clip = new AnimationClip("extra0");
        clip.duration(duration);
        clip.playback(playback);
        return ModelBundle.remote("duration", geometry, Map.of(clip.name(), clip), 1, 1, "");
    }

    private static ModelBundle restored(ModelBundle model) throws IOException {
        return GeometryTransferCodec.decode("duration", GeometryTransferCodec.encode(model));
    }

    private static ParallelAnimationProgram program(ModelBundle model) {
        return new ParallelAnimationProgram(model.geometry(), model.animations(),
                AuxiliaryBoneLayout.create(model.geometry()), 1, 1);
    }

    private static float[] pose(ParallelAnimationProgram program, double time) {
        OpenMatrix4f matrix = program.sampleAt(time, "extra0", time,
                new TestEnvironment()).wholeModelDeltas()[0];
        return new float[]{matrix.m00, matrix.m01, matrix.m02, matrix.m03,
                matrix.m10, matrix.m11, matrix.m12, matrix.m13,
                matrix.m20, matrix.m21, matrix.m22, matrix.m23,
                matrix.m30, matrix.m31, matrix.m32, matrix.m33};
    }

    private static List<ControllerStep> controllerSteps(ModelBundle model) {
        AnimationClip clip = model.animations().get("extra0");
        AnimationControllerProgram program = new AnimationControllerProgram(
                model.animationControllers(), Map.of(clip.name(),
                new AnimationControllerProgram.ClipInfo(clip.duration(), clip.playback())));
        AnimationControllerProgram.RuntimeState state = new AnimationControllerProgram.RuntimeState();
        TestEnvironment environment = new TestEnvironment();
        List<ControllerStep> steps = new ArrayList<>();
        for (double time : new double[]{0, 0.5, 1.25, 2.75}) {
            List<String> active = program.select(time, environment, state).stream()
                    .map(AnimationControllerProgram.ActiveAnimation::name).toList();
            steps.add(new ControllerStep(active, environment.value("v.entered"),
                    environment.value("v.exited"), environment.value("v.completed")));
        }
        return steps;
    }

    private record ControllerStep(List<String> active, double entered, double exited, double completed) {
    }

    private static final class TestEnvironment implements ExpressionEngine.Environment {
        private final Map<Integer, Double> variables = new HashMap<>();

        private double value(String name) {
            return readVariable(ExpressionEngine.slot(name));
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
            return 0;
        }

        @Override
        public double invoke(String name, double[] arguments) {
            return 0;
        }

        @Override
        public double invokeWithText(String name, String[] arguments) {
            return 0;
        }
    }
}
