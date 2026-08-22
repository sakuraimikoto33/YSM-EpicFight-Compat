package net.okitsu.ysmepicfightcompat.animation;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import net.okitsu.ysmepicfightcompat.mesh.AuxiliaryBoneLayout;
import net.okitsu.ysmepicfightcompat.mesh.HumanoidRig;
import org.joml.Matrix4f;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Evaluates YSM parallel and roulette clips without mutating Epic Fight's animator. */
public final class ParallelAnimationProgram {
    private static final float HIDDEN_SCALE = 0.01F;
    private static final float EPSILON = 0.0001F;

    /** Values are reused and remain valid only until this program's next sample. */
    public record Frame(OpenMatrix4f[] parallelDeltas, OpenMatrix4f[] rouletteDeltas,
                        Set<String> hiddenBones) {
    }

    private record VisibilityBone(GeometryDocument.Bone bone, int parentIndex) {
    }

    private record VisibilityVisit(GeometryDocument.Bone bone, int parentIndex) {
    }

    private record BoneProgram(int visibilityIndex, int auxiliaryIndex,
                               AnimationClip.BoneTracks tracks) {
    }

    private record ClipProgram(AnimationClip clip, float duration,
                               List<BoneProgram> bones) {
    }

    private static final class PoseScratch {
        private final float[][] positions;
        private final float[][] rotations;
        private final float[][] scales;
        private final boolean[] hasPosition;
        private final boolean[] hasRotation;
        private final boolean[] hasScale;
        private final Matrix4f[] localAnimated;
        private final Matrix4f[] deltaModel;
        private final Matrix4f[] chainDelta;
        private final OpenMatrix4f[] output;

        private PoseScratch(int count) {
            positions = new float[count][3];
            rotations = new float[count][3];
            scales = new float[count][3];
            hasPosition = new boolean[count];
            hasRotation = new boolean[count];
            hasScale = new boolean[count];
            localAnimated = matrices(count);
            deltaModel = matrices(count);
            chainDelta = matrices(count);
            output = openMatrices(count);
        }

        private void reset() {
            Arrays.fill(hasPosition, false);
            Arrays.fill(hasRotation, false);
            Arrays.fill(hasScale, false);
            for (float[] position : positions) {
                Arrays.fill(position, 0.0F);
            }
            for (float[] rotation : rotations) {
                Arrays.fill(rotation, 0.0F);
            }
            for (float[] scale : scales) {
                Arrays.fill(scale, 1.0F);
            }
        }
    }

    private final AuxiliaryBoneLayout layout;
    private final List<VisibilityBone> visibilityBones;
    private final List<ClipProgram> parallelClips;
    private final Map<String, ClipProgram> rouletteClips;
    private final float horizontalScale;
    private final float verticalScale;
    private final Map<LivingEntity, RuntimeState> states = new WeakHashMap<>();

    private final float[][] visibilityScales;
    private final boolean[] hasVisibilityScale;
    private final float[] effectiveScale;
    private final PoseScratch parallelPose;
    private final PoseScratch roulettePose;
    private final Matrix4f scaledDelta = new Matrix4f();
    private final Set<String> hiddenBones = new LinkedHashSet<>();
    private final Set<String> hiddenView = Collections.unmodifiableSet(hiddenBones);
    private final double[] sample = new double[3];
    private final double[] p0 = new double[3];
    private final double[] p1 = new double[3];
    private final double[] p2 = new double[3];
    private final double[] p3 = new double[3];

    public ParallelAnimationProgram(GeometryDocument geometry,
                                    Map<String, AnimationClip> animations,
                                    AuxiliaryBoneLayout layout,
                                    float horizontalScale, float verticalScale) {
        this.layout = layout;
        this.horizontalScale = positiveScale(horizontalScale);
        this.verticalScale = positiveScale(verticalScale);
        visibilityBones = visibilityBones(geometry);
        Map<String, Integer> visibilityByName = new HashMap<>();
        for (int index = 0; index < visibilityBones.size(); index++) {
            visibilityByName.putIfAbsent(normalize(visibilityBones.get(index).bone().name()), index);
        }
        parallelClips = compileParallelClips(animations, visibilityByName);
        rouletteClips = compileRouletteClips(animations, visibilityByName);

        int auxiliaryCount = layout.entries().size();
        visibilityScales = new float[visibilityBones.size()][3];
        hasVisibilityScale = new boolean[visibilityBones.size()];
        effectiveScale = new float[visibilityBones.size()];
        parallelPose = new PoseScratch(auxiliaryCount);
        roulettePose = new PoseScratch(auxiliaryCount);
    }

    public boolean isEmpty() {
        return parallelClips.isEmpty() && rouletteClips.isEmpty();
    }

    public Frame sample(LivingEntity entity, float partialTick, boolean firstPerson) {
        RuntimeState state = states.computeIfAbsent(entity, RuntimeState::new);
        double now = (entity.tickCount + partialTick) / 20.0D;
        if (now + 1.0E-6D < state.lastNow) {
            state.reset(now);
        }
        double elapsed = Math.max(0.0D, now - state.startedAt);
        double deltaTime = state.lastNow < 0.0D ? 0.0D
                : Math.min(0.25D, Math.max(0.0D, now - state.lastNow));
        state.lastNow = now;
        state.environment.update(partialTick, firstPerson, deltaTime);
        OfficialRoamingVariables.RouletteState roulette =
                OfficialRoamingVariables.rouletteState(entity);
        double rouletteElapsed = state.rouletteElapsed(roulette, now);
        ClipProgram rouletteClip = roulette.playing()
                ? rouletteClip(roulette.animationName()) : null;
        state.selectRouletteClip(rouletteClip);
        state.reportRoulette(entity, roulette, rouletteClip != null);
        evaluate(elapsed, rouletteClip, rouletteElapsed, state.environment, state);
        if (rouletteClip != null
                && state.shouldStopRoulette(rouletteClip, rouletteElapsed)) {
            OfficialRoamingVariables.stopLocalRouletteAnimation(entity);
        }
        return frame();
    }

    Frame sampleAt(double elapsed, ExpressionEngine.Environment environment) {
        evaluate(elapsed, null, 0.0D, environment, null);
        return frame();
    }

    Frame sampleAt(double elapsed, String rouletteAnimation, double rouletteElapsed,
                   ExpressionEngine.Environment environment) {
        evaluate(elapsed, rouletteClip(rouletteAnimation), rouletteElapsed,
                environment, null);
        return frame();
    }

    private Frame frame() {
        return new Frame(parallelPose.output, roulettePose.output, hiddenView);
    }

    private void evaluate(double elapsed, ClipProgram rouletteClip, double rouletteElapsed,
                          ExpressionEngine.Environment environment,
                          RuntimeState runtimeState) {
        resetScratch();
        for (ClipProgram program : parallelClips) {
            if (program.clip().name().startsWith("pre_parallel")) {
                evaluateProgram(program, localTime(program, elapsed), environment,
                        runtimeState, parallelPose);
            }
        }
        if (rouletteClip != null && rouletteElapsed >= 0.0D) {
            float localTime = rouletteTime(rouletteClip, rouletteElapsed);
            if (localTime >= 0.0F) {
                evaluateProgram(rouletteClip, localTime, environment, runtimeState,
                        roulettePose);
            }
        }
        for (ClipProgram program : parallelClips) {
            if (!program.clip().name().startsWith("pre_parallel")) {
                evaluateProgram(program, localTime(program, elapsed), environment,
                        runtimeState, parallelPose);
            }
        }
        composeVisibility();
        composeAuxiliaryMatrices(parallelPose);
        composeAuxiliaryMatrices(roulettePose);
    }

    private void evaluateProgram(ClipProgram program, float localTime,
                                 ExpressionEngine.Environment environment,
                                 RuntimeState runtimeState, PoseScratch pose) {
        if (environment instanceof EntityAnimationEnvironment entityEnvironment) {
            entityEnvironment.clipTime(localTime);
        }
        if (runtimeState != null) {
            fireTimeline(program.clip(), localTime, environment,
                    runtimeState.lastLocalTime);
        }
        float blendWeight = finite(evaluate(program.clip().blendWeight(), environment), 1.0F);
        if (Math.abs(blendWeight) <= EPSILON) {
            return;
        }
        for (BoneProgram bone : program.bones()) {
            AnimationClip.BoneTracks tracks = bone.tracks();
            if (tracks.rotation() != null) {
                sample(tracks.rotation(), localTime, environment, sample);
                if (bone.auxiliaryIndex() >= 0) {
                    pose.rotations[bone.auxiliaryIndex()][0] +=
                            radians(-finite(sample[0] * blendWeight, 0.0F));
                    pose.rotations[bone.auxiliaryIndex()][1] +=
                            radians(-finite(sample[1] * blendWeight, 0.0F));
                    pose.rotations[bone.auxiliaryIndex()][2] +=
                            radians(finite(sample[2] * blendWeight, 0.0F));
                    pose.hasRotation[bone.auxiliaryIndex()] = true;
                }
            }
            if (tracks.position() != null) {
                sample(tracks.position(), localTime, environment, sample);
                if (bone.auxiliaryIndex() >= 0) {
                    pose.positions[bone.auxiliaryIndex()][0] =
                            -finite(sample[0] * blendWeight, 0.0F) / 16.0F;
                    pose.positions[bone.auxiliaryIndex()][1] =
                            finite(sample[1] * blendWeight, 0.0F) / 16.0F;
                    pose.positions[bone.auxiliaryIndex()][2] =
                            finite(sample[2] * blendWeight, 0.0F) / 16.0F;
                    pose.hasPosition[bone.auxiliaryIndex()] = true;
                }
            }
            if (tracks.scale() != null) {
                sample(tracks.scale(), localTime, environment, sample);
                if (bone.visibilityIndex() >= 0) {
                    for (int axis = 0; axis < 3; axis++) {
                        float value = finite(
                                1.0D + (sample[axis] - 1.0D) * blendWeight, 1.0F);
                        visibilityScales[bone.visibilityIndex()][axis] = value;
                        if (bone.auxiliaryIndex() >= 0) {
                            pose.scales[bone.auxiliaryIndex()][axis] = value;
                        }
                    }
                    hasVisibilityScale[bone.visibilityIndex()] = true;
                    if (bone.auxiliaryIndex() >= 0) {
                        pose.hasScale[bone.auxiliaryIndex()] = true;
                    }
                }
            }
        }
    }

    private void resetScratch() {
        parallelPose.reset();
        roulettePose.reset();
        Arrays.fill(hasVisibilityScale, false);
        for (float[] scale : visibilityScales) {
            Arrays.fill(scale, 1.0F);
        }
    }

    private void composeVisibility() {
        hiddenBones.clear();
        for (int index = 0; index < visibilityBones.size(); index++) {
            VisibilityBone bone = visibilityBones.get(index);
            float own = hasVisibilityScale[index]
                    ? Math.min(visibilityScales[index][0],
                    Math.min(visibilityScales[index][1], visibilityScales[index][2]))
                    : 1.0F;
            float inherited = bone.parentIndex() < 0 ? 1.0F : effectiveScale[bone.parentIndex()];
            effectiveScale[index] = own * inherited;
            if (effectiveScale[index] < HIDDEN_SCALE) {
                hiddenBones.add(bone.bone().name());
            }
        }
    }

    private void composeAuxiliaryMatrices(PoseScratch pose) {
        for (AuxiliaryBoneLayout.Entry entry : layout.entries()) {
            int auxiliary = entry.auxiliaryIndex();
            GeometryDocument.Bone bone = entry.bone();
            float px = bone.pivotX() + (pose.hasPosition[auxiliary]
                    ? pose.positions[auxiliary][0] : 0.0F);
            float py = bone.pivotY() + (pose.hasPosition[auxiliary]
                    ? pose.positions[auxiliary][1] : 0.0F);
            float pz = bone.pivotZ() + (pose.hasPosition[auxiliary]
                    ? pose.positions[auxiliary][2] : 0.0F);
            float rx = bone.rotationX()
                    + (pose.hasRotation[auxiliary] ? pose.rotations[auxiliary][0] : 0.0F);
            float ry = bone.rotationY()
                    + (pose.hasRotation[auxiliary] ? pose.rotations[auxiliary][1] : 0.0F);
            float rz = bone.rotationZ()
                    + (pose.hasRotation[auxiliary] ? pose.rotations[auxiliary][2] : 0.0F);
            float sx = pose.hasScale[auxiliary] ? pose.scales[auxiliary][0] : 1.0F;
            float sy = pose.hasScale[auxiliary] ? pose.scales[auxiliary][1] : 1.0F;
            float sz = pose.hasScale[auxiliary] ? pose.scales[auxiliary][2] : 1.0F;

            pose.localAnimated[auxiliary].translation(px, py, pz)
                    .rotateZ(rz).rotateY(ry).rotateX(rx)
                    .scale(sx, sy, sz)
                    .translate(-bone.pivotX(), -bone.pivotY(), -bone.pivotZ());
            pose.deltaModel[auxiliary].set(entry.bindWorld()).mul(entry.bindLocalInverse())
                    .mul(pose.localAnimated[auxiliary])
                    .mul(entry.bindWorldInverse());
            if (entry.parentAuxiliaryIndex() >= 0) {
                pose.chainDelta[auxiliary].set(
                                pose.chainDelta[entry.parentAuxiliaryIndex()])
                        .mul(pose.deltaModel[auxiliary]);
            } else {
                pose.chainDelta[auxiliary].set(pose.deltaModel[auxiliary]);
            }
            scaledDelta.identity().scale(horizontalScale, verticalScale, horizontalScale)
                    .mul(pose.chainDelta[auxiliary])
                    .scale(1.0F / horizontalScale, 1.0F / verticalScale,
                            1.0F / horizontalScale);
            importMatrix(pose.output[auxiliary], scaledDelta);
        }
    }

    static void fireTimeline(AnimationClip clip, float localTime,
                             ExpressionEngine.Environment environment,
                             Map<String, Float> lastLocalTime) {
        Float previous = lastLocalTime.put(clip.name(), localTime);
        if (previous == null) {
            fireTimelineRange(clip, Float.NEGATIVE_INFINITY, localTime, environment);
        } else if (localTime >= previous) {
            fireTimelineRange(clip, previous, localTime, environment);
        } else {
            // A loop crosses the end before returning to zero. Preserve that temporal
            // order because official physics timelines commit at the tail and calculate
            // the next step at the head.
            fireTimelineRange(clip, previous, Float.POSITIVE_INFINITY, environment);
            fireTimelineRange(clip, Float.NEGATIVE_INFINITY, localTime, environment);
        }
    }

    private static void fireTimelineRange(AnimationClip clip, float after, float through,
                                          ExpressionEngine.Environment environment) {
        for (AnimationClip.TimelineEvent event : clip.timeline()) {
            if (event.time() > after && event.time() <= through) {
                event.statements().forEach(statement ->
                        ExpressionEngine.compile(statement).evaluate(environment));
            }
        }
    }

    private List<ClipProgram> compileParallelClips(Map<String, AnimationClip> animations,
                                                   Map<String, Integer> visibilityByName) {
        List<AnimationClip> ordered = animations.values().stream()
                .filter(clip -> clip.name().startsWith("pre_parallel")
                        || clip.name().startsWith("parallel"))
                .sorted(Comparator.comparing((AnimationClip clip) ->
                                clip.name().startsWith("pre_parallel") ? 0 : 1)
                        .thenComparing(AnimationClip::name))
                .toList();
        List<ClipProgram> result = new ArrayList<>();
        for (AnimationClip clip : ordered) {
            List<BoneProgram> bones = new ArrayList<>();
            clip.boneTracks().forEach((name, tracks) -> {
                Integer visibility = visibilityByName.get(normalize(name));
                AuxiliaryBoneLayout.Entry auxiliary = layout.entryForBoneName(name);
                // Official models also use non-geometry "molang" bones as ordered
                // expression runners. Retain them, but never give them a pose index.
                bones.add(new BoneProgram(visibility == null ? -1 : visibility,
                        auxiliary == null || HumanoidRig.isMajorBone(auxiliary.bone())
                                ? -1 : auxiliary.auxiliaryIndex(), tracks));
            });
            if (!bones.isEmpty() || !clip.timeline().isEmpty()) {
                result.add(new ClipProgram(clip, duration(clip), List.copyOf(bones)));
            }
        }
        return List.copyOf(result);
    }

    private Map<String, ClipProgram> compileRouletteClips(
            Map<String, AnimationClip> animations, Map<String, Integer> visibilityByName) {
        Map<String, ClipProgram> result = new HashMap<>();
        for (AnimationClip clip : animations.values()) {
            if (BedrockAnimationParser.isAutomatic(clip.name())) {
                continue;
            }
            List<BoneProgram> bones = new ArrayList<>();
            clip.boneTracks().forEach((name, tracks) -> {
                Integer visibility = visibilityByName.get(normalize(name));
                AuxiliaryBoneLayout.Entry pose = layout.entryForBoneName(name);
                bones.add(new BoneProgram(visibility == null ? -1 : visibility,
                        pose == null ? -1 : pose.auxiliaryIndex(), tracks));
            });
            if (!bones.isEmpty() || !clip.timeline().isEmpty()) {
                result.putIfAbsent(normalize(clip.name()),
                        new ClipProgram(clip, duration(clip), List.copyOf(bones)));
            }
        }
        return Map.copyOf(result);
    }

    private ClipProgram rouletteClip(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        ClipProgram direct = rouletteClips.get(normalize(name));
        if (direct != null) {
            return direct;
        }
        int separator = name.lastIndexOf('.');
        return separator < 0 ? null
                : rouletteClips.get(normalize(name.substring(separator + 1)));
    }

    private static float duration(AnimationClip clip) {
        float result = clip.duration();
        for (AnimationClip.BoneTracks tracks : clip.boneTracks().values()) {
            result = Math.max(result, lastTime(tracks.rotation()));
            result = Math.max(result, lastTime(tracks.position()));
            result = Math.max(result, lastTime(tracks.scale()));
        }
        for (AnimationClip.TimelineEvent event : clip.timeline()) {
            result = Math.max(result, event.time());
        }
        return result;
    }

    private static float lastTime(AnimationClip.Track track) {
        return track == null || track.keyframes().isEmpty() ? 0.0F
                : track.keyframes().get(track.keyframes().size() - 1).time();
    }

    private static float localTime(ClipProgram program, double elapsed) {
        return program.duration() <= EPSILON ? (float) elapsed
                : (float) (elapsed % program.duration());
    }

    private static float rouletteTime(ClipProgram program, double elapsed) {
        if (program.duration() <= EPSILON) {
            return (float) elapsed;
        }
        return switch (program.clip().playback()) {
            case REPEAT -> (float) (elapsed % program.duration());
            case ONCE -> elapsed <= program.duration() ? (float) elapsed : -1.0F;
            case HOLD_LAST_FRAME -> (float) Math.min(elapsed, program.duration());
        };
    }

    private void sample(AnimationClip.Track track, float time,
                        ExpressionEngine.Environment environment, double[] target) {
        List<AnimationClip.Keyframe> keys = track.keyframes();
        int right = 0;
        while (right < keys.size() && keys.get(right).time() <= time) {
            right++;
        }
        if (right == 0) {
            evaluate(keys.get(0).value(), environment, target);
            return;
        }
        if (right >= keys.size()) {
            evaluate(keys.get(keys.size() - 1).value(), environment, target);
            return;
        }
        int left = right - 1;
        AnimationClip.Keyframe leftKey = keys.get(left);
        AnimationClip.Keyframe rightKey = keys.get(right);
        if (rightKey.interpolation() == AnimationClip.Interpolation.STEP
                || rightKey.time() <= leftKey.time()) {
            evaluate(leftKey.value(), environment, target);
            return;
        }
        double alpha = Math.max(0.0D, Math.min(1.0D,
                (time - leftKey.time()) / (rightKey.time() - leftKey.time())));
        evaluate(leftKey.value(), environment, p1);
        evaluate(rightKey.incomingValue() == null
                ? rightKey.value() : rightKey.incomingValue(), environment, p2);
        if (rightKey.interpolation() == AnimationClip.Interpolation.CATMULL_ROM) {
            evaluate(keys.get(Math.max(0, left - 1)).value(), environment, p0);
            evaluate(keys.get(Math.min(keys.size() - 1, right + 1)).value(), environment, p3);
            for (int axis = 0; axis < 3; axis++) {
                target[axis] = catmullRom(p0[axis], p1[axis], p2[axis], p3[axis], alpha);
            }
        } else {
            for (int axis = 0; axis < 3; axis++) {
                target[axis] = p1[axis] + (p2[axis] - p1[axis]) * alpha;
            }
        }
    }

    private static void evaluate(AnimationClip.VectorValue value,
                                 ExpressionEngine.Environment environment, double[] target) {
        for (int axis = 0; axis < 3; axis++) {
            String expression = value.expression(axis);
            target[axis] = expression == null ? value.constant(axis)
                    : ExpressionEngine.compile(expression).evaluate(environment);
        }
    }

    private static double evaluate(AnimationClip.ScalarValue value,
                                   ExpressionEngine.Environment environment) {
        return value.expression() == null ? value.constant()
                : ExpressionEngine.compile(value.expression()).evaluate(environment);
    }

    private static double catmullRom(double a, double b, double c, double d, double time) {
        double squared = time * time;
        double cubed = squared * time;
        return 0.5D * (2.0D * b + (-a + c) * time
                + (2.0D * a - 5.0D * b + 4.0D * c - d) * squared
                + (-a + 3.0D * b - 3.0D * c + d) * cubed);
    }

    private static List<VisibilityBone> visibilityBones(GeometryDocument geometry) {
        List<VisibilityBone> result = new ArrayList<>();
        ArrayDeque<VisibilityVisit> pending = new ArrayDeque<>();
        List<GeometryDocument.Bone> roots = geometry.roots();
        for (int index = roots.size() - 1; index >= 0; index--) {
            pending.push(new VisibilityVisit(roots.get(index), -1));
        }
        while (!pending.isEmpty()) {
            VisibilityVisit visit = pending.pop();
            int ownIndex = result.size();
            result.add(new VisibilityBone(visit.bone(), visit.parentIndex()));
            List<GeometryDocument.Bone> children = visit.bone().children();
            for (int index = children.size() - 1; index >= 0; index--) {
                pending.push(new VisibilityVisit(children.get(index), ownIndex));
            }
        }
        return List.copyOf(result);
    }

    private static Matrix4f[] matrices(int count) {
        Matrix4f[] result = new Matrix4f[count];
        Arrays.setAll(result, ignored -> new Matrix4f());
        return result;
    }

    private static OpenMatrix4f[] openMatrices(int count) {
        OpenMatrix4f[] result = new OpenMatrix4f[count];
        Arrays.setAll(result, ignored -> new OpenMatrix4f());
        return result;
    }

    private static void importMatrix(OpenMatrix4f target, Matrix4f source) {
        target.m00 = source.m00();
        target.m01 = source.m01();
        target.m02 = source.m02();
        target.m03 = source.m03();
        target.m10 = source.m10();
        target.m11 = source.m11();
        target.m12 = source.m12();
        target.m13 = source.m13();
        target.m20 = source.m20();
        target.m21 = source.m21();
        target.m22 = source.m22();
        target.m23 = source.m23();
        target.m30 = source.m30();
        target.m31 = source.m31();
        target.m32 = source.m32();
        target.m33 = source.m33();
    }

    private static float finite(double value, float fallback) {
        return Double.isFinite(value) && Math.abs(value) <= Float.MAX_VALUE
                ? (float) value : fallback;
    }

    private static float radians(float degrees) {
        return (float) Math.toRadians(degrees);
    }

    private static float positiveScale(float value) {
        return Float.isFinite(value) && value > EPSILON ? value : 1.0F;
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static final class RuntimeState {
        private final Map<Integer, Double> variables = new HashMap<>();
        private final Set<Integer> assigned = new java.util.HashSet<>();
        private final Map<String, Float> lastLocalTime = new HashMap<>();
        private final EntityAnimationEnvironment environment;
        private double startedAt;
        private double lastNow = -1.0D;
        private String rouletteAnimation = "";
        private String rouletteClip = "";
        private String reportedRoulette = "";
        private double rouletteStartedAt;
        private boolean rouletteStopSent;

        private RuntimeState(LivingEntity entity) {
            environment = new EntityAnimationEnvironment(entity, variables, assigned);
            startedAt = (entity.tickCount + Minecraft.getInstance().getFrameTime()) / 20.0D;
        }

        private void reset(double now) {
            variables.clear();
            assigned.clear();
            environment.reset();
            lastLocalTime.clear();
            startedAt = now;
            lastNow = -1.0D;
            rouletteAnimation = "";
            rouletteClip = "";
            reportedRoulette = "";
            rouletteStartedAt = now;
            rouletteStopSent = false;
        }

        private void reportRoulette(LivingEntity entity,
                                    OfficialRoamingVariables.RouletteState state,
                                    boolean available) {
            String name = state.playing() ? state.animationName() : "";
            if (name.equals(reportedRoulette)) {
                return;
            }
            reportedRoulette = name;
            if (name.isEmpty()) {
                return;
            }
            if (available) {
                CompatMod.LOG.info(
                        "YSM-EF Compat: applying YSM roulette animation '{}' for {}",
                        name, entity.getScoreboardName());
            } else {
                CompatMod.LOG.warn(
                        "YSM-EF Compat: YSM roulette animation '{}' is not retained for {}",
                        name, entity.getScoreboardName());
            }
        }

        private double rouletteElapsed(OfficialRoamingVariables.RouletteState state,
                                       double now) {
            String name = state.playing() ? state.animationName() : "";
            if (name.isBlank()) {
                clearRouletteTimeline();
                rouletteAnimation = "";
                rouletteStartedAt = now;
                rouletteStopSent = false;
                return -1.0D;
            }
            if (!name.equals(rouletteAnimation)) {
                clearRouletteTimeline();
                rouletteAnimation = name;
                rouletteStartedAt = now;
                rouletteStopSent = false;
            }
            return Math.max(0.0D, now - rouletteStartedAt);
        }

        private void selectRouletteClip(ClipProgram program) {
            String name = program == null ? "" : program.clip().name();
            if (!name.equals(rouletteClip)) {
                clearRouletteTimeline();
                rouletteClip = name;
                if (!name.isEmpty()) {
                    lastLocalTime.remove(name);
                }
            }
        }

        private boolean shouldStopRoulette(ClipProgram program, double elapsed) {
            if (rouletteStopSent || program.clip().playback() != AnimationClip.Playback.ONCE
                    || elapsed <= program.duration()) {
                return false;
            }
            rouletteStopSent = true;
            return true;
        }

        private void clearRouletteTimeline() {
            if (!rouletteClip.isEmpty()) {
                lastLocalTime.remove(rouletteClip);
                rouletteClip = "";
            }
        }
    }
}
