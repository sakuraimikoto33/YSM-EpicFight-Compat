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
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Evaluates YSM auxiliary and whole-model clips without mutating Epic Fight's animator. */
public final class ParallelAnimationProgram {
    private static final float HIDDEN_SCALE = 0.01F;
    private static final float EPSILON = 0.0001F;
    private static final Set<String> WHOLE_MODEL_MOUNTED_STATES = Set.of(
            "boat", "ride_pig", "ride", "sit");
    private static final ExecutorService ANIMATION_WORKERS = Executors.newFixedThreadPool(
            Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() - 1)), task -> {
                Thread worker = new Thread(task, "ysm-ef-molang-evaluator");
                worker.setDaemon(true);
                return worker;
            });

    /** Values are reused and remain valid only until the owning entity's next sample. */
    public record Frame(OpenMatrix4f[] parallelDeltas, OpenMatrix4f[] wholeModelDeltas,
                        boolean replaceEpicFightPose, Set<String> hiddenBones) {
    }

    private record VisibilityBone(GeometryDocument.Bone bone, int parentIndex) {
    }

    private record VisibilityVisit(GeometryDocument.Bone bone, int parentIndex) {
    }

    private record BoneProgram(int visibilityIndex, int auxiliaryIndex,
                               AnimationClip.BoneTracks tracks) {
    }

    private record ClipProgram(AnimationClip clip, float duration,
                               List<BoneProgram> bones, Set<Integer> variableSlots,
                               Set<Integer> querySlots, boolean asyncSafe) {
    }

    private record AsyncResult(Frame frame, EvaluationScratch scratch) {
    }

    private enum ApplyMode {
        OVERRIDE,
        PARALLEL
    }

    private static final class ControllerVariableEnvironment
            implements ExpressionEngine.Environment {
        private final ExpressionEngine.Environment delegate;
        private final Map<Integer, Double> variables;

        private ControllerVariableEnvironment(ExpressionEngine.Environment delegate,
                                              Map<Integer, Double> variables) {
            this.delegate = delegate;
            this.variables = new HashMap<>(variables);
        }

        @Override
        public double readVariable(int slot) {
            return variables.containsKey(slot) ? variables.get(slot) : delegate.readVariable(slot);
        }

        @Override
        public boolean hasVariable(int slot) {
            return variables.containsKey(slot) || delegate.hasVariable(slot);
        }

        @Override
        public void writeVariable(int slot, double value) {
            if (variables.containsKey(slot)) {
                variables.put(slot, Double.isFinite(value) ? value : 0.0D);
            } else {
                delegate.writeVariable(slot, value);
            }
        }

        @Override
        public double readQuery(int slot) {
            return delegate.readQuery(slot);
        }

        @Override
        public double invoke(String name, double[] arguments) {
            return delegate.invoke(name, arguments);
        }

        @Override
        public double invokeWithText(String name, String[] arguments) {
            return delegate.invokeWithText(name, arguments);
        }

        @Override
        public double invokeWithMixedArguments(String name, String[] textArguments,
                                               double[] numericArguments) {
            return delegate.invokeWithMixedArguments(name, textArguments, numericArguments);
        }
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
    private final Set<GeometryDocument.Bone> epicFightPoseControls;
    private final String modelId;
    private final List<VisibilityBone> visibilityBones;
    private final List<ClipProgram> parallelClips;
    private final Map<String, ClipProgram> automaticClips;
    private final Map<String, ClipProgram> controllerClips;
    private final Map<String, ClipProgram> rouletteClips;
    private final AutomaticAnimationSelector automaticSelector;
    private final AnimationControllerProgram controllerProgram;
    private final float horizontalScale;
    private final float verticalScale;
    private final Map<LivingEntity, RuntimeState> states = new WeakHashMap<>();

    private final int auxiliaryCount;
    private final EvaluationScratch testScratch;

    public ParallelAnimationProgram(GeometryDocument geometry,
                                    Map<String, AnimationClip> animations,
                                    AuxiliaryBoneLayout layout,
                                    float horizontalScale, float verticalScale) {
        this("", geometry, animations, Map.of(), layout, horizontalScale, verticalScale);
    }

    public ParallelAnimationProgram(GeometryDocument geometry,
                                    Map<String, AnimationClip> animations,
                                    Map<String, AnimationController> controllers,
                                    AuxiliaryBoneLayout layout,
                                    float horizontalScale, float verticalScale) {
        this("", geometry, animations, controllers, layout, horizontalScale, verticalScale);
    }

    public ParallelAnimationProgram(String modelId, GeometryDocument geometry,
                                    Map<String, AnimationClip> animations,
                                    Map<String, AnimationController> controllers,
                                    AuxiliaryBoneLayout layout,
                                    float horizontalScale, float verticalScale) {
        this.modelId = modelId == null ? "" : modelId;
        this.layout = layout;
        this.epicFightPoseControls = epicFightPoseControls(layout);
        this.horizontalScale = positiveScale(horizontalScale);
        this.verticalScale = positiveScale(verticalScale);
        visibilityBones = visibilityBones(geometry);
        Map<String, Integer> visibilityByName = new HashMap<>();
        for (int index = 0; index < visibilityBones.size(); index++) {
            visibilityByName.putIfAbsent(normalize(visibilityBones.get(index).bone().name()), index);
        }
        parallelClips = compileParallelClips(animations, visibilityByName);
        automaticClips = compileAutomaticClips(animations, visibilityByName);
        controllerClips = compileControllerClips(animations, visibilityByName);
        rouletteClips = compileRouletteClips(animations, visibilityByName);
        Map<String, AutomaticAnimationSelector.ClipInfo> automaticInfo = new HashMap<>();
        automaticClips.forEach((name, program) -> automaticInfo.put(name,
                new AutomaticAnimationSelector.ClipInfo(program.duration())));
        automaticSelector = new AutomaticAnimationSelector(automaticInfo);
        Map<String, AnimationControllerProgram.ClipInfo> controllerInfo = new HashMap<>();
        animations.values().forEach(clip -> {
            String name = normalize(clip.name());
            controllerInfo.putIfAbsent(name, new AnimationControllerProgram.ClipInfo(
                    duration(clip), clip.playback(),
                    controllerClips.containsKey(name)));
        });
        controllerProgram = new AnimationControllerProgram(controllers, controllerInfo);

        auxiliaryCount = layout.entries().size();
        testScratch = new EvaluationScratch(visibilityBones.size(), auxiliaryCount);
    }

    public boolean isEmpty() {
        return parallelClips.isEmpty() && automaticClips.isEmpty()
                && controllerProgram.isEmpty() && rouletteClips.isEmpty();
    }

    public Frame sample(LivingEntity entity, float partialTick, boolean firstPerson) {
        RuntimeState state = states.computeIfAbsent(entity,
                value -> new RuntimeState(value, modelId,
                        new EvaluationScratch(visibilityBones.size(), auxiliaryCount)));
        double sampledNow = (entity.tickCount + partialTick) / 20.0D;
        if (entity.tickCount < state.lastTickCount) {
            state.reset(sampledNow);
        }
        // Minecraft may restore a smaller partial tick after closing a single-player
        // pause screen. Keep the sampled clock monotonic unless the entity tick itself
        // rewound, which indicates a real lifecycle reset.
        double now = stableSampleTime(entity.tickCount, sampledNow,
                state.lastTickCount, state.lastNow);
        float stablePartialTick = (float) Math.max(0.0D,
                Math.min(1.0D, now * 20.0D - entity.tickCount));
        double elapsed = Math.max(0.0D, now - state.startedAt);
        double deltaTime = state.lastNow < 0.0D ? 0.0D
                : Math.min(0.25D, Math.max(0.0D, now - state.lastNow));
        state.lastTickCount = entity.tickCount;
        state.lastNow = now;
        state.environment.update(stablePartialTick, firstPerson, deltaTime);
        OfficialRoamingVariables.RouletteState roulette =
                OfficialRoamingVariables.rouletteState(entity);
        double rouletteElapsed = state.rouletteElapsed(roulette, now);
        ClipProgram rouletteClip = roulette.playing()
                ? rouletteClip(roulette.animationName()) : null;
        state.selectRouletteClip(rouletteClip);
        state.reportRoulette(entity, roulette, rouletteClip != null);
        List<AutomaticAnimationSelector.ActiveClip> automatic =
                automaticSelector.select(entity, now, state.automaticState);
        state.prepareAutomaticTimelines(automatic, automaticSelector.names());
        List<AnimationControllerProgram.ActiveAnimation> controlled =
                controllerProgram.select(now, state.environment, state.controllerState);
        state.prepareControllerTimelines(controllerProgram.activeKeys(controlled));
        collectCompletedEvaluation(state);
        boolean workerEligible = entity != Minecraft.getInstance().player
                && asyncSafe(automatic, controlled, rouletteClip);
        if (!workerEligible) {
            discardPendingEvaluation(state);
            evaluate(elapsed, automatic, controlled, rouletteClip, rouletteElapsed,
                    state.environment, state, state.scratch);
            state.publishedFrame = frame(state.scratch);
            state.publishedScratch = state.scratch;
        } else {
            fireActiveTimelines(elapsed, automatic, controlled, rouletteClip,
                    rouletteElapsed, state.environment, state);
            if (state.publishedFrame == null) {
                evaluate(elapsed, automatic, controlled, rouletteClip, rouletteElapsed,
                        state.environment, null, state.scratch);
                state.publishedFrame = frame(state.scratch);
                state.publishedScratch = state.scratch;
            }
            double interval = lodIntervalSeconds(entity, stablePartialTick);
            if (state.pendingEvaluation == null
                    && now + 1.0E-6D >= state.lastScheduledAt + interval) {
                scheduleEvaluation(state, elapsed, automatic, controlled,
                        rouletteClip, rouletteElapsed, now);
            }
        }
        if (rouletteClip != null
                && state.shouldStopRoulette(rouletteClip, rouletteElapsed)) {
            OfficialRoamingVariables.stopLocalRouletteAnimation(entity);
        }
        return state.publishedFrame;
    }

    private void scheduleEvaluation(RuntimeState state, double elapsed,
                                    List<AutomaticAnimationSelector.ActiveClip> automatic,
                                    List<AnimationControllerProgram.ActiveAnimation> controlled,
                                    ClipProgram rouletteClip, double rouletteElapsed,
                                    double now) {
        Set<Integer> variableSlots = new LinkedHashSet<>();
        Set<Integer> querySlots = new LinkedHashSet<>();
        collectDependencies(automatic, controlled, rouletteClip, variableSlots, querySlots);
        SnapshotExpressionEnvironment snapshot = SnapshotExpressionEnvironment.capture(
                state.environment, variableSlots, querySlots);
        EvaluationScratch working = state.spareWorkerScratch == null
                ? new EvaluationScratch(visibilityBones.size(), auxiliaryCount)
                : state.spareWorkerScratch;
        state.spareWorkerScratch = null;
        List<AutomaticAnimationSelector.ActiveClip> automaticCopy = List.copyOf(automatic);
        List<AnimationControllerProgram.ActiveAnimation> controlledCopy = List.copyOf(controlled);
        state.lastScheduledAt = now;
        state.pendingEvaluation = CompletableFuture.supplyAsync(() -> {
            evaluate(elapsed, automaticCopy, controlledCopy, rouletteClip, rouletteElapsed,
                    snapshot, null, working);
            return new AsyncResult(frame(working), working);
        }, ANIMATION_WORKERS);
    }

    private static void collectCompletedEvaluation(RuntimeState state) {
        CompletableFuture<AsyncResult> pending = state.pendingEvaluation;
        if (pending == null || !pending.isDone()) {
            return;
        }
        state.pendingEvaluation = null;
        try {
            AsyncResult result = pending.join();
            EvaluationScratch previous = state.publishedScratch;
            state.publishedFrame = result.frame();
            state.publishedScratch = result.scratch();
            if (previous != null && previous != state.scratch
                    && previous != result.scratch()) {
                state.spareWorkerScratch = previous;
            }
        } catch (RuntimeException exception) {
            CompatMod.LOG.debug("YSM-EF Compat: asynchronous Molang evaluation failed", exception);
        }
    }

    private static void discardPendingEvaluation(RuntimeState state) {
        if (state.pendingEvaluation != null) {
            state.pendingEvaluation.cancel(false);
            state.pendingEvaluation = null;
        }
    }

    private boolean asyncSafe(List<AutomaticAnimationSelector.ActiveClip> automatic,
                              List<AnimationControllerProgram.ActiveAnimation> controlled,
                              ClipProgram rouletteClip) {
        if (parallelClips.stream().anyMatch(program -> !program.asyncSafe())) {
            return false;
        }
        for (AutomaticAnimationSelector.ActiveClip active : automatic) {
            ClipProgram program = automaticClips.get(active.name());
            if (program != null && !program.asyncSafe()) {
                return false;
            }
        }
        for (AnimationControllerProgram.ActiveAnimation active : controlled) {
            ClipProgram program = controllerClips.get(active.name());
            if (program != null && !program.asyncSafe()) {
                return false;
            }
        }
        return rouletteClip == null || rouletteClip.asyncSafe();
    }

    private void collectDependencies(
            List<AutomaticAnimationSelector.ActiveClip> automatic,
            List<AnimationControllerProgram.ActiveAnimation> controlled,
            ClipProgram rouletteClip, Set<Integer> variables, Set<Integer> queries) {
        parallelClips.forEach(program -> collectDependencies(program, variables, queries));
        automatic.forEach(active -> collectDependencies(
                automaticClips.get(active.name()), variables, queries));
        controlled.forEach(active -> collectDependencies(
                controllerClips.get(active.name()), variables, queries));
        collectDependencies(rouletteClip, variables, queries);
    }

    private static void collectDependencies(ClipProgram program, Set<Integer> variables,
                                            Set<Integer> queries) {
        if (program != null) {
            variables.addAll(program.variableSlots());
            queries.addAll(program.querySlots());
        }
    }

    private static double lodIntervalSeconds(LivingEntity entity, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.level != entity.level()) {
            return 0.0D;
        }
        net.minecraft.world.phys.Vec3 camera =
                minecraft.gameRenderer.getMainCamera().getPosition();
        double x = net.minecraft.util.Mth.lerp(partialTick, entity.xOld, entity.getX());
        double y = net.minecraft.util.Mth.lerp(partialTick, entity.yOld, entity.getY());
        double z = net.minecraft.util.Mth.lerp(partialTick, entity.zOld, entity.getZ());
        double distanceSquared = camera.distanceToSqr(x, y, z);
        return lodIntervalSeconds(distanceSquared);
    }

    static double lodIntervalSeconds(double distanceSquared) {
        if (distanceSquared <= 16.0D * 16.0D) {
            return 0.0D;
        }
        if (distanceSquared <= 32.0D * 32.0D) {
            return 1.0D / 20.0D;
        }
        if (distanceSquared <= 64.0D * 64.0D) {
            return 2.0D / 20.0D;
        }
        return 4.0D / 20.0D;
    }

    Frame sampleAt(double elapsed, ExpressionEngine.Environment environment) {
        evaluate(elapsed, List.of(), List.of(), null, 0.0D, environment, null, testScratch);
        return frame(testScratch);
    }

    Frame sampleAt(double elapsed, String rouletteAnimation, double rouletteElapsed,
                   ExpressionEngine.Environment environment) {
        evaluate(elapsed, List.of(), List.of(), rouletteClip(rouletteAnimation), rouletteElapsed,
                environment, null, testScratch);
        return frame(testScratch);
    }

    Frame sampleAutomaticAt(double elapsed, List<String> animationNames,
                            ExpressionEngine.Environment environment) {
        List<AutomaticAnimationSelector.ActiveClip> active = animationNames.stream()
                .map(ParallelAnimationProgram::normalize)
                .map(name -> new AutomaticAnimationSelector.ActiveClip(name, elapsed, false))
                .toList();
        evaluate(elapsed, active, List.of(), null, 0.0D, environment, null, testScratch);
        return frame(testScratch);
    }

    Frame sampleControllersAt(double now, ExpressionEngine.Environment environment,
                              AnimationControllerProgram.RuntimeState controllerState) {
        List<AnimationControllerProgram.ActiveAnimation> controlled =
                controllerProgram.select(now, environment, controllerState);
        evaluate(now, List.of(), controlled, null, 0.0D, environment, null, testScratch);
        return frame(testScratch);
    }

    private Frame frame(EvaluationScratch scratch) {
        return new Frame(scratch.parallelPose.output, scratch.wholeModelPose.output,
                scratch.replaceEpicFightPose, scratch.hiddenView);
    }

    private void fireActiveTimelines(
            double elapsed, List<AutomaticAnimationSelector.ActiveClip> automatic,
            List<AnimationControllerProgram.ActiveAnimation> controlled,
            ClipProgram rouletteClip, double rouletteElapsed,
            ExpressionEngine.Environment environment, RuntimeState runtimeState) {
        for (ClipProgram program : parallelClips) {
            if (program.clip().name().startsWith("pre_parallel")) {
                fireProgramTimeline(program, localTime(program, elapsed), environment,
                        runtimeState, program.clip().name(), true);
            }
        }
        for (AutomaticAnimationSelector.ActiveClip active : automatic) {
            ClipProgram program = automaticClips.get(active.name());
            if (program == null) {
                continue;
            }
            if (active.restarted()) {
                runtimeState.environment.stopSoundScope(program.clip().name());
                runtimeState.lastLocalTime.remove(program.clip().name());
            }
            float localTime = automaticTime(program, active.elapsed());
            if (localTime >= 0.0F) {
                fireProgramTimeline(program, localTime, environment, runtimeState,
                        program.clip().name(), true);
            }
        }
        for (AnimationControllerProgram.ActiveAnimation active : controlled) {
            ClipProgram program = controllerClips.get(active.name());
            if (program == null) {
                continue;
            }
            ExpressionEngine.Environment controllerEnvironment = active.stateVariables().isEmpty()
                    ? environment : new ControllerVariableEnvironment(
                    environment, active.stateVariables());
            fireProgramTimeline(program, controllerTime(program, active.elapsed()),
                    controllerEnvironment, runtimeState, active.instanceKey(), true);
        }
        if (rouletteClip != null && rouletteElapsed >= 0.0D) {
            float localTime = rouletteTime(rouletteClip, rouletteElapsed);
            if (localTime >= 0.0F) {
                fireProgramTimeline(rouletteClip, localTime, environment, runtimeState,
                        rouletteClip.clip().name(), false);
            }
        }
        for (ClipProgram program : parallelClips) {
            if (!program.clip().name().startsWith("pre_parallel")) {
                fireProgramTimeline(program, localTime(program, elapsed), environment,
                        runtimeState, program.clip().name(), true);
            }
        }
    }

    private static void fireProgramTimeline(
            ClipProgram program, float localTime, ExpressionEngine.Environment environment,
            RuntimeState runtimeState, String timelineKey, boolean soundOutputEnabled) {
        EntityAnimationEnvironment entityEnvironment = entityEnvironment(environment);
        if (entityEnvironment != null) {
            entityEnvironment.clipTime(localTime);
            entityEnvironment.soundScope(timelineKey);
        }
        SnapshotExpressionEnvironment snapshot = snapshotEnvironment(environment);
        if (snapshot != null) {
            snapshot.clipTime(localTime);
        }
        boolean previousSoundOutput = entityEnvironment == null
                || entityEnvironment.soundOutputEnabled();
        if (entityEnvironment != null) {
            entityEnvironment.soundOutputEnabled(soundOutputEnabled);
        }
        try {
            fireTimeline(program.clip(), timelineKey, localTime, environment,
                    runtimeState.lastLocalTime);
        } finally {
            if (entityEnvironment != null) {
                entityEnvironment.soundOutputEnabled(previousSoundOutput);
            }
        }
    }

    private void evaluate(double elapsed,
                          List<AutomaticAnimationSelector.ActiveClip> automatic,
                          List<AnimationControllerProgram.ActiveAnimation> controlled,
                          ClipProgram rouletteClip, double rouletteElapsed,
                          ExpressionEngine.Environment environment,
                          RuntimeState runtimeState, EvaluationScratch scratch) {
        resetScratch(scratch);
        for (ClipProgram program : parallelClips) {
            if (program.clip().name().startsWith("pre_parallel")) {
                evaluateProgram(program, localTime(program, elapsed), environment,
                        runtimeState, scratch.parallelPose, ApplyMode.PARALLEL, scratch);
            }
        }
        for (AutomaticAnimationSelector.ActiveClip active : automatic) {
            ClipProgram program = automaticClips.get(active.name());
            if (program == null) {
                continue;
            }
            if (active.restarted() && runtimeState != null) {
                runtimeState.environment.stopSoundScope(program.clip().name());
                runtimeState.lastLocalTime.remove(program.clip().name());
            }
            float localTime = automaticTime(program, active.elapsed());
            if (localTime >= 0.0F) {
                boolean mounted = isWholeModelMountedClip(active.name());
                PoseScratch target = mounted ? scratch.wholeModelPose : scratch.parallelPose;
                boolean applied = evaluateProgram(program, localTime, environment,
                        runtimeState, target, ApplyMode.OVERRIDE, scratch);
                scratch.replaceEpicFightPose |= mounted && applied;
            }
        }
        for (AnimationControllerProgram.ActiveAnimation active : controlled) {
            ClipProgram program = controllerClips.get(active.name());
            if (program == null) {
                continue;
            }
            ExpressionEngine.Environment controllerEnvironment = active.stateVariables().isEmpty()
                    ? environment : new ControllerVariableEnvironment(
                    environment, active.stateVariables());
            evaluateProgram(program, controllerTime(program, active.elapsed()),
                    controllerEnvironment, runtimeState, scratch.parallelPose,
                    ApplyMode.OVERRIDE, active.weight(), active.blendViaShortestPath(),
                    active.instanceKey(), true, scratch);
        }
        if (rouletteClip != null && rouletteElapsed >= 0.0D) {
            float localTime = rouletteTime(rouletteClip, rouletteElapsed);
            if (localTime >= 0.0F) {
                // Official YSM owns roulette audio even while Epic Fight owns the pose.
                // Replaying the retained sound outputs here creates a second stream.
                evaluateProgram(rouletteClip, localTime, environment, runtimeState,
                        scratch.wholeModelPose, ApplyMode.PARALLEL, 1.0F, false,
                        rouletteClip.clip().name(), false, scratch);
            }
        }
        for (ClipProgram program : parallelClips) {
            if (!program.clip().name().startsWith("pre_parallel")) {
                evaluateProgram(program, localTime(program, elapsed), environment,
                        runtimeState, scratch.parallelPose, ApplyMode.PARALLEL, scratch);
            }
        }
        composeVisibility(scratch);
        composeAuxiliaryMatrices(scratch.parallelPose, scratch);
        composeAuxiliaryMatrices(scratch.wholeModelPose, scratch);
    }

    private boolean evaluateProgram(ClipProgram program, float localTime,
                                    ExpressionEngine.Environment environment,
                                    RuntimeState runtimeState, PoseScratch pose,
                                    ApplyMode applyMode, EvaluationScratch scratch) {
        return evaluateProgram(program, localTime, environment, runtimeState, pose,
                applyMode, 1.0F, false, program.clip().name(), true, scratch);
    }

    private boolean evaluateProgram(ClipProgram program, float localTime,
                                    ExpressionEngine.Environment environment,
                                    RuntimeState runtimeState, PoseScratch pose,
                                    ApplyMode applyMode, float externalWeight,
                                    boolean shortestPath, String timelineKey,
                                    boolean soundOutputEnabled, EvaluationScratch scratch) {
        EntityAnimationEnvironment entityEnvironment = entityEnvironment(environment);
        if (entityEnvironment != null) {
            entityEnvironment.clipTime(localTime);
            entityEnvironment.soundScope(timelineKey);
        }
        SnapshotExpressionEnvironment snapshot = snapshotEnvironment(environment);
        if (snapshot != null) {
            snapshot.clipTime(localTime);
        }
        if (runtimeState != null) {
            boolean previousSoundOutput = entityEnvironment == null
                    || entityEnvironment.soundOutputEnabled();
            if (entityEnvironment != null) {
                entityEnvironment.soundOutputEnabled(soundOutputEnabled);
            }
            try {
                fireTimeline(program.clip(), timelineKey, localTime, environment,
                        runtimeState.lastLocalTime);
            } finally {
                if (entityEnvironment != null) {
                    entityEnvironment.soundOutputEnabled(previousSoundOutput);
                }
            }
        }
        float clipWeight = finite(evaluate(program.clip().blendWeight(), environment), 1.0F);
        float blendWeight = finite(clipWeight * finite(externalWeight, 0.0F), 0.0F);
        if (Math.abs(blendWeight) <= EPSILON) {
            return false;
        }
        boolean appliedPose = false;
        for (BoneProgram bone : program.bones()) {
            AnimationClip.BoneTracks tracks = bone.tracks();
            if (tracks.rotation() != null) {
                sample(tracks.rotation(), localTime, environment, scratch.sample, scratch);
                if (bone.auxiliaryIndex() >= 0) {
                    appliedPose = true;
                    int auxiliary = bone.auxiliaryIndex();
                    float[] target = new float[]{
                            radians(-finite(scratch.sample[0], 0.0F)),
                            radians(-finite(scratch.sample[1], 0.0F)),
                            radians(finite(scratch.sample[2], 0.0F))};
                    for (int axis = 0; axis < 3; axis++) {
                        if (applyMode == ApplyMode.PARALLEL) {
                            pose.rotations[auxiliary][axis] += target[axis] * blendWeight;
                        } else {
                            float previous = pose.hasRotation[auxiliary]
                                    ? pose.rotations[auxiliary][axis] : 0.0F;
                            float difference = target[axis] - previous;
                            if (shortestPath) {
                                difference = shortestRadians(difference);
                            }
                            pose.rotations[auxiliary][axis] = previous
                                    + difference * blendWeight;
                        }
                    }
                    pose.hasRotation[bone.auxiliaryIndex()] = true;
                }
            }
            if (tracks.position() != null) {
                sample(tracks.position(), localTime, environment, scratch.sample, scratch);
                if (bone.auxiliaryIndex() >= 0) {
                    appliedPose = true;
                    int auxiliary = bone.auxiliaryIndex();
                    float[] target = new float[]{
                            -finite(scratch.sample[0], 0.0F) / 16.0F,
                            finite(scratch.sample[1], 0.0F) / 16.0F,
                            finite(scratch.sample[2], 0.0F) / 16.0F};
                    for (int axis = 0; axis < 3; axis++) {
                        float previous = applyMode == ApplyMode.OVERRIDE
                                && pose.hasPosition[auxiliary]
                                ? pose.positions[auxiliary][axis] : 0.0F;
                        pose.positions[auxiliary][axis] = previous
                                + (target[axis] - previous) * blendWeight;
                    }
                    pose.hasPosition[auxiliary] = true;
                }
            }
            if (tracks.scale() != null) {
                sample(tracks.scale(), localTime, environment, scratch.sample, scratch);
                if (bone.visibilityIndex() >= 0) {
                    for (int axis = 0; axis < 3; axis++) {
                        float previous = applyMode == ApplyMode.OVERRIDE
                                && scratch.hasVisibilityScale[bone.visibilityIndex()]
                                ? scratch.visibilityScales[bone.visibilityIndex()][axis] : 1.0F;
                        float target = finite(scratch.sample[axis], 1.0F);
                        float value = finite(previous
                                + (target - previous) * blendWeight, 1.0F);
                        scratch.visibilityScales[bone.visibilityIndex()][axis] = value;
                        if (bone.auxiliaryIndex() >= 0) {
                            appliedPose = true;
                            pose.scales[bone.auxiliaryIndex()][axis] = value;
                        }
                    }
                    scratch.hasVisibilityScale[bone.visibilityIndex()] = true;
                    if (bone.auxiliaryIndex() >= 0) {
                        pose.hasScale[bone.auxiliaryIndex()] = true;
                    }
                }
            }
        }
        return appliedPose;
    }

    static double stableSampleTime(int tickCount, double sampledNow,
                                   int previousTickCount, double previousNow) {
        return previousNow >= 0.0D && tickCount >= previousTickCount
                && sampledNow < previousNow ? previousNow : sampledNow;
    }

    private void resetScratch(EvaluationScratch scratch) {
        scratch.parallelPose.reset();
        scratch.wholeModelPose.reset();
        scratch.replaceEpicFightPose = false;
        Arrays.fill(scratch.hasVisibilityScale, false);
        for (float[] scale : scratch.visibilityScales) {
            Arrays.fill(scale, 1.0F);
        }
    }

    private void composeVisibility(EvaluationScratch scratch) {
        scratch.hiddenBones.clear();
        for (int index = 0; index < visibilityBones.size(); index++) {
            VisibilityBone bone = visibilityBones.get(index);
            float own = scratch.hasVisibilityScale[index]
                    ? Math.min(scratch.visibilityScales[index][0],
                    Math.min(scratch.visibilityScales[index][1],
                            scratch.visibilityScales[index][2]))
                    : 1.0F;
            float inherited = bone.parentIndex() < 0 ? 1.0F
                    : scratch.effectiveScale[bone.parentIndex()];
            scratch.effectiveScale[index] = own * inherited;
            if (scratch.effectiveScale[index] < HIDDEN_SCALE) {
                scratch.hiddenBones.add(bone.bone().name());
            }
        }
    }

    private void composeAuxiliaryMatrices(PoseScratch pose, EvaluationScratch scratch) {
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
            scratch.scaledDelta.identity().scale(
                            horizontalScale, verticalScale, horizontalScale)
                    .mul(pose.chainDelta[auxiliary])
                    .scale(1.0F / horizontalScale, 1.0F / verticalScale,
                            1.0F / horizontalScale);
            importMatrix(pose.output[auxiliary], scratch.scaledDelta);
        }
    }

    static void fireTimeline(AnimationClip clip, float localTime,
                             ExpressionEngine.Environment environment,
                             Map<String, Float> lastLocalTime) {
        fireTimeline(clip, clip.name(), localTime, environment, lastLocalTime);
    }

    private static void fireTimeline(AnimationClip clip, String timelineKey, float localTime,
                                     ExpressionEngine.Environment environment,
                                     Map<String, Float> lastLocalTime) {
        Float previous = lastLocalTime.put(timelineKey, localTime);
        if (previous == null) {
            fireTimelineRange(clip, Float.NEGATIVE_INFINITY, localTime, environment);
        } else if (localTime >= previous) {
            fireTimelineRange(clip, previous, localTime, environment);
        } else {
            // A loop crosses the end before returning to zero. Preserve that temporal
            // order because official physics timelines commit at the tail and calculate
            // the next step at the head.
            fireTimelineRange(clip, previous, Float.POSITIVE_INFINITY, environment);
            EntityAnimationEnvironment entityEnvironment = entityEnvironment(environment);
            if (entityEnvironment != null) {
                entityEnvironment.stopSoundScope(timelineKey);
            }
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
        EntityAnimationEnvironment entityEnvironment = entityEnvironment(environment);
        if (entityEnvironment != null) {
            for (AnimationClip.SoundEvent event : clip.soundEffects()) {
                if (event.time() > after && event.time() <= through) {
                    entityEnvironment.playSoundEffect(event.effect());
                }
            }
            for (AnimationClip.ParticleEvent event : clip.particleEffects()) {
                if (event.time() > after && event.time() <= through) {
                    entityEnvironment.playParticleEffect(event.particle(), false);
                }
            }
        }
    }

    private static final class EvaluationScratch {
        private final float[][] visibilityScales;
        private final boolean[] hasVisibilityScale;
        private final float[] effectiveScale;
        private final PoseScratch parallelPose;
        private final PoseScratch wholeModelPose;
        private final Matrix4f scaledDelta = new Matrix4f();
        private final Set<String> hiddenBones = new LinkedHashSet<>();
        private final Set<String> hiddenView = Collections.unmodifiableSet(hiddenBones);
        private final double[] sample = new double[3];
        private final double[] p0 = new double[3];
        private final double[] p1 = new double[3];
        private final double[] p2 = new double[3];
        private final double[] p3 = new double[3];
        private boolean replaceEpicFightPose;

        private EvaluationScratch(int visibilityCount, int auxiliaryCount) {
            visibilityScales = new float[visibilityCount][3];
            hasVisibilityScale = new boolean[visibilityCount];
            effectiveScale = new float[visibilityCount];
            parallelPose = new PoseScratch(auxiliaryCount);
            wholeModelPose = new PoseScratch(auxiliaryCount);
        }
    }

    private static EntityAnimationEnvironment entityEnvironment(
            ExpressionEngine.Environment environment) {
        if (environment instanceof EntityAnimationEnvironment value) {
            return value;
        }
        if (environment instanceof ControllerVariableEnvironment value) {
            return entityEnvironment(value.delegate);
        }
        return null;
    }

    private static SnapshotExpressionEnvironment snapshotEnvironment(
            ExpressionEngine.Environment environment) {
        if (environment instanceof SnapshotExpressionEnvironment value) {
            return value;
        }
        if (environment instanceof ControllerVariableEnvironment value) {
            return snapshotEnvironment(value.delegate);
        }
        return null;
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
                        auxiliary == null || epicFightPoseControls.contains(auxiliary.bone())
                                ? -1 : auxiliary.auxiliaryIndex(), tracks));
            });
            if (!bones.isEmpty() || !clip.timeline().isEmpty()
                    || !clip.soundEffects().isEmpty() || !clip.particleEffects().isEmpty()) {
                result.add(clipProgram(clip, bones));
            }
        }
        return List.copyOf(result);
    }

    private Map<String, ClipProgram> compileAutomaticClips(
            Map<String, AnimationClip> animations, Map<String, Integer> visibilityByName) {
        Map<String, ClipProgram> result = new HashMap<>();
        for (AnimationClip clip : animations.values()) {
            String name = normalize(clip.name());
            if (!BedrockAnimationParser.isAutomatic(name)
                    || name.startsWith("pre_parallel") || name.startsWith("parallel")
                    || BedrockAnimationParser.isHandItemAnimation(name)) {
                continue;
            }
            ClipProgram program = compileClip(
                    clip, visibilityByName, !isWholeModelMountedClip(name));
            if (program != null) {
                result.putIfAbsent(name, program);
            }
        }
        return Map.copyOf(result);
    }

    private Map<String, ClipProgram> compileRouletteClips(
            Map<String, AnimationClip> animations, Map<String, Integer> visibilityByName) {
        Map<String, ClipProgram> result = new HashMap<>();
        for (AnimationClip clip : animations.values()) {
            if (BedrockAnimationParser.isAutomatic(clip.name())) {
                continue;
            }
            ClipProgram program = compileClip(clip, visibilityByName, false);
            if (program != null) {
                result.putIfAbsent(normalize(clip.name()), program);
            }
        }
        return Map.copyOf(result);
    }

    private Map<String, ClipProgram> compileControllerClips(
            Map<String, AnimationClip> animations, Map<String, Integer> visibilityByName) {
        Map<String, ClipProgram> result = new HashMap<>();
        for (AnimationClip clip : animations.values()) {
            String name = normalize(clip.name());
            if (name.startsWith("pre_parallel") || name.startsWith("parallel")
                    || BedrockAnimationParser.isHandItemAnimation(name)) {
                continue;
            }
            ClipProgram program = compileClip(clip, visibilityByName, true);
            if (program != null) {
                result.putIfAbsent(name, program);
            }
        }
        return Map.copyOf(result);
    }

    private ClipProgram compileClip(AnimationClip clip,
                                    Map<String, Integer> visibilityByName,
                                    boolean auxiliaryOnly) {
        List<BoneProgram> bones = new ArrayList<>();
        clip.boneTracks().forEach((name, tracks) -> {
            Integer visibility = visibilityByName.get(normalize(name));
            AuxiliaryBoneLayout.Entry pose = layout.entryForBoneName(name);
            boolean epicFightOwned = pose != null
                    && epicFightPoseControls.contains(pose.bone());
            bones.add(new BoneProgram(visibility == null ? -1 : visibility,
                    pose == null || auxiliaryOnly && epicFightOwned
                            ? -1 : pose.auxiliaryIndex(), tracks));
        });
        return bones.isEmpty() && clip.timeline().isEmpty() && clip.soundEffects().isEmpty()
                && clip.particleEffects().isEmpty()
                ? null
                : clipProgram(clip, bones);
    }

    private static Set<GeometryDocument.Bone> epicFightPoseControls(
            AuxiliaryBoneLayout layout) {
        Set<GeometryDocument.Bone> result = Collections.newSetFromMap(
                new IdentityHashMap<>());
        for (AuxiliaryBoneLayout.Entry entry : layout.entries()) {
            if (!HumanoidRig.isMajorBone(entry.bone())) {
                continue;
            }
            for (GeometryDocument.Bone bone = entry.bone(); bone != null;
                 bone = bone.parent()) {
                result.add(bone);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static ClipProgram clipProgram(AnimationClip clip, List<BoneProgram> bones) {
        Set<Integer> variables = new LinkedHashSet<>();
        Set<Integer> queries = new LinkedHashSet<>();
        boolean[] safe = {clip.timeline().isEmpty()
                && clip.particleEffects().stream().allMatch(event ->
                event.particle().preEffectScript().isBlank())};
        collect(clip.blendWeight().compiledExpression(), variables, queries, safe);
        for (BoneProgram bone : bones) {
            collect(bone.tracks().rotation(), variables, queries, safe);
            collect(bone.tracks().position(), variables, queries, safe);
            collect(bone.tracks().scale(), variables, queries, safe);
        }
        return new ClipProgram(clip, duration(clip), List.copyOf(bones),
                Set.copyOf(variables), Set.copyOf(queries), safe[0]);
    }

    private static void collect(AnimationClip.Track track, Set<Integer> variables,
                                Set<Integer> queries, boolean[] safe) {
        if (track == null) {
            return;
        }
        for (AnimationClip.Keyframe keyframe : track.keyframes()) {
            collect(keyframe.value(), variables, queries, safe);
            collect(keyframe.incomingValue(), variables, queries, safe);
        }
    }

    private static void collect(AnimationClip.VectorValue value, Set<Integer> variables,
                                Set<Integer> queries, boolean[] safe) {
        if (value == null) {
            return;
        }
        for (int axis = 0; axis < 3; axis++) {
            collect(value.compiledExpression(axis), variables, queries, safe);
        }
    }

    private static void collect(ExpressionEngine.Expression expression,
                                Set<Integer> variables,
                                Set<Integer> queries, boolean[] safe) {
        if (expression == null) {
            return;
        }
        ExpressionEngine.Dependencies dependencies = expression.dependencies();
        variables.addAll(dependencies.variableSlots());
        queries.addAll(dependencies.querySlots());
        if (dependencies.writesVariables() || dependencies.hasTextArguments()
                || dependencies.functions().stream().anyMatch(
                function -> !asyncFunction(function))) {
            safe[0] = false;
        }
    }

    private static boolean asyncFunction(String function) {
        if (function.equals("ysm.perlin_noise")) {
            return true;
        }
        return function.startsWith("math.")
                && !function.equals("math.random")
                && !function.equals("math.randomi")
                && !function.equals("math.random_integer")
                && !function.equals("math.die_roll")
                && !function.equals("math.die_roll_integer")
                && !function.equals("math.roll")
                && !function.equals("math.rolli");
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
        for (AnimationClip.SoundEvent event : clip.soundEffects()) {
            result = Math.max(result, event.time());
        }
        for (AnimationClip.ParticleEvent event : clip.particleEffects()) {
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

    private static float automaticTime(ClipProgram program, double elapsed) {
        if (program.duration() <= EPSILON) {
            return 0.0F;
        }
        return switch (program.clip().playback()) {
            case REPEAT -> (float) (elapsed % program.duration());
            case ONCE -> elapsed <= program.duration() ? (float) elapsed : -1.0F;
            case HOLD_LAST_FRAME -> (float) Math.min(elapsed, program.duration());
        };
    }

    private static float controllerTime(ClipProgram program, double elapsed) {
        if (program.duration() <= EPSILON) {
            return 0.0F;
        }
        return program.clip().playback() == AnimationClip.Playback.REPEAT
                ? (float) (elapsed % program.duration())
                : (float) Math.min(elapsed, program.duration());
    }

    private void sample(AnimationClip.Track track, float time,
                        ExpressionEngine.Environment environment, double[] target,
                        EvaluationScratch scratch) {
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
        evaluate(leftKey.value(), environment, scratch.p1);
        evaluate(rightKey.incomingValue() == null
                ? rightKey.value() : rightKey.incomingValue(), environment, scratch.p2);
        if (rightKey.interpolation() == AnimationClip.Interpolation.CATMULL_ROM) {
            evaluate(keys.get(Math.max(0, left - 1)).value(), environment, scratch.p0);
            evaluate(keys.get(Math.min(keys.size() - 1, right + 1)).value(),
                    environment, scratch.p3);
            for (int axis = 0; axis < 3; axis++) {
                target[axis] = catmullRom(scratch.p0[axis], scratch.p1[axis],
                        scratch.p2[axis], scratch.p3[axis], alpha);
            }
        } else {
            for (int axis = 0; axis < 3; axis++) {
                target[axis] = scratch.p1[axis]
                        + (scratch.p2[axis] - scratch.p1[axis]) * alpha;
            }
        }
    }

    private static void evaluate(AnimationClip.VectorValue value,
                                 ExpressionEngine.Environment environment, double[] target) {
        for (int axis = 0; axis < 3; axis++) {
            String expression = value.expression(axis);
            target[axis] = expression == null ? value.constant(axis)
                    : value.compiledExpression(axis).evaluate(environment);
        }
    }

    private static double evaluate(AnimationClip.ScalarValue value,
                                   ExpressionEngine.Environment environment) {
        return value.expression() == null ? value.constant()
                : value.compiledExpression().evaluate(environment);
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

    private static float shortestRadians(float value) {
        float full = (float) (Math.PI * 2.0D);
        float wrapped = value % full;
        if (wrapped > Math.PI) {
            wrapped -= full;
        } else if (wrapped < -Math.PI) {
            wrapped += full;
        }
        return wrapped;
    }

    private static float positiveScale(float value) {
        return Float.isFinite(value) && value > EPSILON ? value : 1.0F;
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    static boolean isWholeModelMountedClip(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String normalized = normalize(name);
        return WHOLE_MODEL_MOUNTED_STATES.contains(normalized)
                || normalized.startsWith("vehicle:")
                || normalized.startsWith("vehicle$")
                || normalized.startsWith("vehicle#");
    }

    private static final class RuntimeState {
        private final Map<Integer, Double> variables = new HashMap<>();
        private final Set<Integer> assigned = new java.util.HashSet<>();
        private final Map<String, Float> lastLocalTime = new HashMap<>();
        private final EntityAnimationEnvironment environment;
        private final EvaluationScratch scratch;
        private final AutomaticAnimationSelector.State automaticState =
                new AutomaticAnimationSelector.State();
        private final AnimationControllerProgram.RuntimeState controllerState =
                new AnimationControllerProgram.RuntimeState();
        private double startedAt;
        private double lastNow = -1.0D;
        private int lastTickCount = Integer.MIN_VALUE;
        private String rouletteAnimation = "";
        private String rouletteClip = "";
        private String reportedRoulette = "";
        private double rouletteStartedAt;
        private boolean rouletteStopSent;
        private Frame publishedFrame;
        private EvaluationScratch publishedScratch;
        private EvaluationScratch spareWorkerScratch;
        private CompletableFuture<AsyncResult> pendingEvaluation;
        private double lastScheduledAt = Double.NEGATIVE_INFINITY;

        private RuntimeState(LivingEntity entity, String modelId,
                             EvaluationScratch scratch) {
            environment = new EntityAnimationEnvironment(entity, variables, assigned, modelId);
            this.scratch = scratch;
            startedAt = (entity.tickCount + Minecraft.getInstance().getFrameTime()) / 20.0D;
        }

        private void reset(double now) {
            if (pendingEvaluation != null) {
                pendingEvaluation.cancel(false);
                pendingEvaluation = null;
            }
            variables.clear();
            assigned.clear();
            environment.reset();
            automaticState.reset();
            controllerState.reset();
            lastLocalTime.clear();
            startedAt = now;
            lastNow = -1.0D;
            lastTickCount = Integer.MIN_VALUE;
            rouletteAnimation = "";
            rouletteClip = "";
            reportedRoulette = "";
            rouletteStartedAt = now;
            rouletteStopSent = false;
            publishedFrame = null;
            publishedScratch = null;
            spareWorkerScratch = null;
            lastScheduledAt = Double.NEGATIVE_INFINITY;
        }

        private void prepareAutomaticTimelines(
                List<AutomaticAnimationSelector.ActiveClip> active,
                Set<String> automaticNames) {
            Set<String> activeNames = new java.util.HashSet<>();
            for (AutomaticAnimationSelector.ActiveClip clip : active) {
                activeNames.add(clip.name());
            }
            lastLocalTime.keySet().removeIf(name -> {
                boolean removed = automaticNames.contains(normalize(name))
                        && !activeNames.contains(normalize(name));
                if (removed) {
                    environment.stopSoundScope(name);
                }
                return removed;
            });
        }

        private void prepareControllerTimelines(Set<String> activeKeys) {
            lastLocalTime.keySet().removeIf(name -> {
                boolean removed = name.startsWith("controller/")
                        && !activeKeys.contains(name);
                if (removed) {
                    environment.stopSoundScope(name);
                }
                return removed;
            });
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
                environment.stopSoundScope(rouletteClip);
                lastLocalTime.remove(rouletteClip);
                rouletteClip = "";
            }
        }
    }

    public static void clearSoundOutput() {
        ClientSoundOutput.clear();
        ClientParticleOutput.clear();
    }

    public static void releaseSoundOutput(String modelId) {
        ClientSoundOutput.stopModel(modelId);
    }
}
