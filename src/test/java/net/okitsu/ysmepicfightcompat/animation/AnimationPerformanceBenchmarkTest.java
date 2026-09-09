package net.okitsu.ysmepicfightcompat.animation;

import net.minecraft.world.phys.Vec3;
import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import net.okitsu.ysmepicfightcompat.mesh.AuxiliaryBoneLayout;
import net.okitsu.ysmepicfightcompat.mesh.DisplayedBoneQueries;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Run explicitly with {@code ./gradlew animationBenchmark}.
 *
 * <p>These synthetic CPU/allocation measurements use no game, GPU, or model files and
 * do not measure Minecraft frame time or FPS. The {@code sampleAt} seam evaluates
 * real pose code but does not include entity-scoped lifecycle/controller updates.
 * The repeated-time case keeps pose composition active and can reuse only immutable
 * numeric track samples; it is not whole-frame caching. No timing threshold is used
 * as a test assertion.</p>
 */
@EnabledIfSystemProperty(named = "ysm.ef.benchmark", matches = "true")
class AnimationPerformanceBenchmarkTest {
    private static volatile double sink;
    private static final int BATCHES = 7;

    @Test
    void syntheticAnimationAndBoneQueries() {
        System.out.printf("BENCH_ENV java=%s vm=%s processors=%d maxHeap=%d%n",
                System.getProperty("java.version"), System.getProperty("java.vm.name"),
                Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory());
        com.sun.management.ThreadMXBean allocation = allocationBean();

        GeometryDocument geometry = geometry(2);
        AnimationClip clip = new AnimationClip("pre_parallel0");
        clip.playback(AnimationClip.Playback.REPEAT);
        clip.duration(60.0F);
        AnimationClip.Track track = new AnimationClip.Track();
        for (int key = 0; key < 4096; key++) {
            AnimationClip.VectorValue value = new AnimationClip.VectorValue();
            value.setConstant(0, 0.0D);
            value.setConstant(1, 0.0D);
            value.setConstant(2, 30.0D * Math.sin(key * 0.02D));
            track.keyframes().add(new AnimationClip.Keyframe(key * 60.0F / 4095.0F,
                    AnimationClip.Interpolation.LINEAR, value, null));
        }
        AnimationClip.BoneTracks tracks = new AnimationClip.BoneTracks();
        tracks.rotation(track);
        clip.boneTracks().put("ear_1", tracks);
        AuxiliaryBoneLayout layout = AuxiliaryBoneLayout.create(geometry);
        ParallelAnimationProgram program = new ParallelAnimationProgram(
                geometry, Map.of(clip.name(), clip), layout, 1.0F, 1.0F);
        ExpressionEngine.Environment environment = new NeutralEnvironment();

        // Validate the fixture outside every timed region, including warmup.
        assertEquals(2, layout.entries().size());
        OpenMatrix4f probe = lastParallelPose(program.sampleAt(0.625D, environment));
        assertFinite(probe);
        assertTrue(Math.abs(probe.m00 - 1.0F) > 1.0E-5F || Math.abs(probe.m01) > 1.0E-5F,
                "The numeric track must produce a non-identity animated pose");

        int[] sampleIndex = {0};
        measure("sampleAt_sorted_numeric_4096keys_2bones", () -> {
            double time = (sampleIndex[0]++ & 4095) * 60.0D / 4096.0D;
            OpenMatrix4f result = lastParallelPose(program.sampleAt(time, environment));
            return result.m00 + result.m01 + result.m30;
        }, allocation);
        measure("sampleAt_repeated_time_numeric_4096keys_2bones", () -> {
            OpenMatrix4f result = lastParallelPose(program.sampleAt(32.5D, environment));
            return result.m00 + result.m01 + result.m30;
        }, allocation);

        AuxiliaryBoneLayout largeLayout = AuxiliaryBoneLayout.create(geometry(443), 1.25F, 0.85F);
        OpenMatrix4f[] poses = new OpenMatrix4f[largeLayout.totalPoseCount()];
        for (int index = 0; index < poses.length; index++) {
            poses[index] = new OpenMatrix4f().translate(0.03F, 0.07F, -0.02F);
        }
        assertEquals(443, largeLayout.entries().size());
        BoneQuerySnapshot snapshot = DisplayedBoneQueries.capture(
                largeLayout, poses, BoneQuerySnapshot.EMPTY, Set.of());
        assertFinite(snapshot.values("head"));
        assertFinite(snapshot.values("ear_442"));
        BoneQuerySnapshot[] previous = {BoneQuerySnapshot.EMPTY};
        measure("bone_capture_443bones", () -> {
            previous[0] = DisplayedBoneQueries.capture(largeLayout, poses, previous[0], Set.of());
            BoneQuerySnapshot.BoneValues result = previous[0].values("ear_442");
            return result.rotation().x + result.position().y + result.absolutePivot().z;
        }, allocation);
        System.out.println("BENCH_SCOPE synthetic CPU kernels; no game, GPU, model files, or FPS measurement");
    }

    private static OpenMatrix4f lastParallelPose(ParallelAnimationProgram.Frame frame) {
        return frame.parallelDeltas()[frame.parallelDeltas().length - 1];
    }

    private static void assertFinite(OpenMatrix4f matrix) {
        assertNotNull(matrix);
        for (float value : new float[]{matrix.m00, matrix.m01, matrix.m02, matrix.m03,
                matrix.m10, matrix.m11, matrix.m12, matrix.m13,
                matrix.m20, matrix.m21, matrix.m22, matrix.m23,
                matrix.m30, matrix.m31, matrix.m32, matrix.m33}) {
            assertTrue(Float.isFinite(value), "The sampled pose must be finite");
        }
    }

    private static void assertFinite(BoneQuerySnapshot.BoneValues values) {
        assertNotNull(values);
        for (Vec3 value : List.of(values.rotation(), values.position(),
                values.scale(), values.absolutePivot())) {
            assertTrue(Double.isFinite(value.x) && Double.isFinite(value.y)
                    && Double.isFinite(value.z), "The captured bone channels must be finite");
        }
    }

    private static GeometryDocument geometry(int count) {
        GeometryDocument geometry = new GeometryDocument();
        for (int index = 0; index < count; index++) {
            GeometryDocument.Bone bone = new GeometryDocument.Bone(index == 0 ? "head" : "ear_" + index);
            bone.parentName(index == 0 ? "" : "head");
            bone.pivot((index % 9) * 0.015F, 1.0F + (index % 7) * 0.01F, (index % 5) * 0.02F);
            bone.rotation((index % 3) * 0.01F, (index % 5) * 0.02F, (index % 7) * 0.01F);
            geometry.add(bone);
        }
        geometry.linkHierarchy();
        return geometry;
    }

    private static com.sun.management.ThreadMXBean allocationBean() {
        try {
            var bean = ManagementFactory.getThreadMXBean();
            if (bean instanceof com.sun.management.ThreadMXBean allocation
                    && allocation.isThreadAllocatedMemorySupported()) {
                if (!allocation.isThreadAllocatedMemoryEnabled()) {
                    allocation.setThreadAllocatedMemoryEnabled(true);
                }
                return allocation;
            }
        } catch (SecurityException | UnsupportedOperationException unavailable) {
            // Allocation counters are optional; wall-clock batches remain useful.
        }
        return null;
    }

    private static long allocatedBytes(com.sun.management.ThreadMXBean allocation) {
        if (allocation != null) {
            try {
                return allocation.getCurrentThreadAllocatedBytes();
            } catch (SecurityException | UnsupportedOperationException unavailable) {
                // A restricted JVM may expose the bean but reject individual reads.
            }
        }
        return -1L;
    }

    private static void measure(String name, Operation operation,
                                com.sun.management.ThreadMXBean allocation) {
        long warmupEnd = System.nanoTime() + 700_000_000L;
        do {
            run(operation, 128);
        } while (System.nanoTime() < warmupEnd);
        int iterations = 128;
        while (iterations < 1_048_576) {
            long start = System.nanoTime();
            run(operation, iterations);
            if (System.nanoTime() - start >= 100_000_000L) break;
            iterations *= 2;
        }
        double[] nanos = new double[BATCHES];
        double[] bytes = new double[BATCHES];
        boolean allocationAvailable = true;
        for (int batch = 0; batch < BATCHES; batch++) {
            long beforeBytes = allocatedBytes(allocation);
            long start = System.nanoTime();
            run(operation, iterations);
            nanos[batch] = (System.nanoTime() - start) / (double) iterations;
            long afterBytes = allocatedBytes(allocation);
            if (beforeBytes < 0L || afterBytes < beforeBytes) {
                allocationAvailable = false;
            } else {
                bytes[batch] = (afterBytes - beforeBytes) / (double) iterations;
            }
        }
        Arrays.sort(nanos);
        Arrays.sort(bytes);
        String allocated = allocationAvailable
                ? String.format(Locale.ROOT, "%.3f", bytes[BATCHES / 2]) : "N/A";
        System.out.printf(Locale.ROOT,
                "BENCH_RESULT name=%s iterations=%d batches=%d median_ns_per_op=%.3f min_ns_per_op=%.3f max_ns_per_op=%.3f median_alloc_bytes_per_op=%s checksum=%.6f%n",
                name, iterations, BATCHES, nanos[BATCHES / 2], nanos[0], nanos[BATCHES - 1],
                allocated, sink);
    }

    private static void run(Operation operation, int iterations) {
        double checksum = 0.0D;
        for (int index = 0; index < iterations; index++) checksum += operation.run();
        sink = checksum;
    }

    @FunctionalInterface
    private interface Operation { double run(); }

    private static final class NeutralEnvironment implements ExpressionEngine.Environment {
        public double readVariable(int slot) { return 0.0D; }
        public boolean hasVariable(int slot) { return false; }
        public void writeVariable(int slot, double value) { }
        public double readQuery(int slot) { return 0.0D; }
        public double invoke(String name, double[] arguments) { return 0.0D; }
        public double invokeWithText(String name, String[] arguments) { return 0.0D; }
    }
}
