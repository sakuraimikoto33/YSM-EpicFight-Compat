package net.okitsu.ysmepicfightcompat.animation;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;
import net.okitsu.ysmepicfightcompat.mesh.AuxiliaryBoneLayout;
import net.okitsu.ysmepicfightcompat.mesh.HumanoidRig;
import net.okitsu.ysmepicfightcompat.network.ClientHeldItemModelPreferences;
import net.okitsu.ysmepicfightcompat.network.ClientMovementAnimationPreferences;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Evaluates YSM auxiliary and whole-model clips without mutating Epic Fight's animator. */
public final class ParallelAnimationProgram {
    private static final float HIDDEN_SCALE = 0.01F;
    private static final float EPSILON = 0.0001F;
    /** Official YSM controller ending transition: three Minecraft ticks. */
    private static final double FULL_BODY_END_TRANSITION_SECONDS = 3.0D / 20.0D;
    /** Lets a held-item edge survive the controller's initialize-only render frame. */
    private static final double ITEM_SWITCH_CONTROLLER_PENDING_SECONDS = 3.0D / 20.0D;
    /** Matches the complete-matrix ownership blend performed by MovementPoseTransition. */
    private static final double ITEM_SWITCH_EXIT_BLEND_SECONDS = 3.0D / 20.0D;
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
                        OpenMatrix4f[] heldItemDeltas,
                        boolean replaceEpicFightPose, boolean[] replaceEpicFightAnchors,
                        boolean[] suppressParallelDeltas,
                        int[] heldItemAnchorJoints,
                        @Nullable OpenMatrix4f[] fullBodyBlendSource,
                        float fullBodyBlendWeight,
                        @Nullable String movementPoseKey,
                        Set<InteractionHand> itemSwitchHands,
                        Set<String> hiddenBones) {
    }

    private record VisibilityBone(GeometryDocument.Bone bone, int parentIndex) {
    }

    private record VisibilityVisit(GeometryDocument.Bone bone, int parentIndex) {
    }

    private record BoneProgram(int visibilityIndex, int auxiliaryIndex,
                               int mirroredVisibilityIndex,
                               int mirroredAuxiliaryIndex,
                               boolean epicFightOwned,
                               AnimationClip.BoneTracks tracks) {
    }

    /** Epic Fight seam that owns a selectively authored YSM pose subtree. */
    private record HeldAttachment(int anchorJoint, int[] relativePath,
                                  Matrix4f bindRebase) {
        private HeldAttachment {
            relativePath = relativePath.clone();
            bindRebase = new Matrix4f(bindRebase);
        }
    }

    private record ClipProgram(AnimationClip clip, float duration,
                               float itemSwitchDuration,
                               List<BoneProgram> bones, Set<Integer> variableSlots,
                               Set<Integer> querySlots, boolean asyncSafe,
                               Set<Integer> replacementIndices,
                               Set<Integer> propReplacementIndices,
                               Map<Integer, HeldAttachment> heldAttachments) {
    }

    private record FullBodyObservation(AutomaticAnimationSelector.ActiveClip active,
                                       InteractionHand hand,
                                       AnimationConditionMatcher.ItemAction action) {
    }

    private record FullBodyEnding(AutomaticAnimationSelector.ActiveClip active,
                                  InteractionHand hand, double startedAt, float weight,
                                  @Nullable PoseLayerSnapshot snapshot,
                                  @Nullable FullBodyCompositeSnapshot compositeSnapshot) {
    }

    private record FullBodySwingPlayback(String clipName, InteractionHand hand,
                                         double startedAt, double duration) {
    }

    private record MovementPose(AutomaticAnimationSelector.ActiveClip active,
                                MovementAnimationType movement) {
        private String key() {
            return movement.configKey() + ':' + active.name();
        }
    }

    private record ItemSwitchPlayback(double startedAt, double endsAt,
                                      long generation) {
    }

    private record PendingItemSwitch(double observedAt, double expiresAt,
                                     long generation) {
    }

    record ItemSwitchPose(AutomaticAnimationSelector.ActiveClip main,
                          @Nullable MovementAnimationType movement,
                          Set<InteractionHand> hands,
                          Set<InteractionHand> enabledHands,
                          long sequence) {
        ItemSwitchPose {
            hands = Set.copyOf(hands);
            enabledHands = Set.copyOf(enabledHands);
        }

        String key() {
            return "item-switch:" + sequence + ':'
                    + (movement == null ? "stationary" : movement.configKey())
                    + ':' + main.name()
                    + ":active=" + handMask(hands)
                    + ":enabled=" + handMask(enabledHands);
        }

        private static int handMask(Set<InteractionHand> hands) {
            int result = 0;
            if (hands.contains(InteractionHand.MAIN_HAND)) {
                result |= 1;
            }
            if (hands.contains(InteractionHand.OFF_HAND)) {
                result |= 2;
            }
            return result;
        }
    }

    static final class ItemSwitchState {
        private final Map<InteractionHand, ItemSwitchPlayback> playbacks =
                new EnumMap<>(InteractionHand.class);
        private final Map<InteractionHand, PendingItemSwitch> pending =
                new EnumMap<>(InteractionHand.class);
        private final Map<InteractionHand, Double> exitOwnershipUntil =
                new EnumMap<>(InteractionHand.class);
        private Set<String> activeControllerKeys = Set.of();
        private long sequence;

        void reset() {
            playbacks.clear();
            pending.clear();
            exitOwnershipUntil.clear();
            activeControllerKeys = Set.of();
            sequence = 0L;
        }

        private boolean hasPotential(Set<InteractionHand> enabledHands, double now) {
            for (InteractionHand hand : enabledHands) {
                ItemSwitchPlayback playback = playbacks.get(hand);
                PendingItemSwitch edge = pending.get(hand);
                if (playback != null && now <= playback.endsAt() + EPSILON
                        || edge != null && now <= edge.expiresAt() + EPSILON) {
                    return true;
                }
            }
            return false;
        }

        boolean hasPoseOwnershipPotential(
                Set<InteractionHand> enabledHands, double now) {
            for (InteractionHand hand : enabledHands) {
                ItemSwitchPlayback playback = playbacks.get(hand);
                PendingItemSwitch edge = pending.get(hand);
                Double until = exitOwnershipUntil.get(hand);
                if (playback != null && now <= playback.endsAt()
                        + ITEM_SWITCH_EXIT_BLEND_SECONDS + EPSILON
                        || edge != null && now <= edge.expiresAt() + EPSILON
                        || until != null && now <= until + EPSILON) {
                    return true;
                }
            }
            return false;
        }
    }

    static final class FullBodySwingState {
        private FullBodySwingPlayback playback;
        private boolean rawSwingConsumed;
        private boolean endpointPublished;

        void reset() {
            playback = null;
            rawSwingConsumed = false;
            endpointPublished = false;
        }
    }

    private record PoseLayer(int stage, int order,
                             AutomaticAnimationSelector.ActiveClip automatic,
                             AnimationControllerProgram.ActiveAnimation controlled,
                             FullBodyEnding ending) {
    }

    private record AsyncResult(Frame frame, EvaluationScratch scratch) {
    }

    private enum ApplyMode {
        OVERRIDE,
        FULL_BODY,
        PARALLEL
    }

    /** Optional local-space conversion applied after sampling one authored layer. */
    private enum PoseTransform {
        NONE,
        MIRROR_X
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
    private final CustomHeldItemPolicy customHeldItems;
    private final AnimationControllerProgram controllerProgram;
    private final Map<String, Set<InteractionHand>> heldItemControllerHands;
    private final boolean authoredSwingControllerSound;
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
        customHeldItems = CustomHeldItemPolicy.create(geometry, animations);
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
        heldItemControllerHands = customHeldItemControllerHands(controllers);
        LinkedHashSet<String> heldItemControllers = new LinkedHashSet<>(
                heldItemControllerHands.keySet());
        if (controllers != null) {
            controllers.keySet().stream()
                    .map(ParallelAnimationProgram::normalize)
                    .filter(name -> !holdControllerHands(name).isEmpty())
                    .forEach(heldItemControllers::add);
        }
        authoredSwingControllerSound = authoredSwingControllerSound(
                controllers, heldItemControllers, animations);
        controllerProgram = new AnimationControllerProgram(
                controllers, controllerInfo, heldItemControllers);

        auxiliaryCount = layout.entries().size();
        testScratch = new EvaluationScratch(visibilityBones.size(), auxiliaryCount);
    }

    private Map<String, Set<InteractionHand>> customHeldItemControllerHands(
            Map<String, AnimationController> controllers) {
        if (controllers == null || controllers.isEmpty()) {
            return Map.of();
        }
        EnumMap<InteractionHand, Set<Integer>> indicesByHand =
                new EnumMap<>(InteractionHand.class);
        for (InteractionHand hand : InteractionHand.values()) {
            LinkedHashSet<Integer> indices = new LinkedHashSet<>();
            for (String root : customHeldItems.allReplacementRoots(hand)) {
                AuxiliaryBoneLayout.Entry entry = layout.entryForBoneName(root);
                if (entry != null) {
                    indices.add(entry.auxiliaryIndex());
                }
            }
            indicesByHand.put(hand, Set.copyOf(indices));
        }
        Map<String, Set<InteractionHand>> result = new LinkedHashMap<>();
        controllers.forEach((name, controller) -> {
            LinkedHashSet<Integer> affected = new LinkedHashSet<>();
            controller.states().values().stream()
                    .flatMap(state -> state.animations().stream())
                    .map(reference -> controllerClips.get(normalize(reference.name())))
                    .filter(Objects::nonNull)
                    .forEach(program -> affected.addAll(program.replacementIndices()));
            LinkedHashSet<InteractionHand> hands = new LinkedHashSet<>();
            indicesByHand.forEach((hand, indices) -> {
                if (indices.stream().anyMatch(affected::contains)) {
                    hands.add(hand);
                }
            });
            if (!hands.isEmpty()) {
                result.put(normalize(name), Set.copyOf(hands));
            }
        });
        return Map.copyOf(result);
    }

    static boolean authoredSwingControllerSound(
            Map<String, AnimationController> controllers,
            Set<String> allowedControllers,
            Map<String, AnimationClip> animations) {
        if (controllers == null || controllers.isEmpty()
                || allowedControllers == null || allowedControllers.isEmpty()) {
            return false;
        }
        Map<String, AnimationClip> normalizedAnimations = new HashMap<>();
        if (animations != null) {
            animations.forEach((name, clip) -> {
                if (clip != null) {
                    normalizedAnimations.putIfAbsent(normalize(name), clip);
                    normalizedAnimations.putIfAbsent(normalize(clip.name()), clip);
                }
            });
        }
        for (Map.Entry<String, AnimationController> entry : controllers.entrySet()) {
            String name = normalize(entry.getKey());
            AnimationController controller = entry.getValue();
            if (controller == null || !allowedControllers.contains(name)
                    || !name.contains("swing")) {
                continue;
            }
            for (AnimationController.State state : controller.states().values()) {
                if (!state.soundEffects().isEmpty()
                        || containsPlaySound(state.onEntry())
                        || containsPlaySound(state.onExit())) {
                    return true;
                }
                for (AnimationController.AnimationReference reference
                        : state.animations()) {
                    if (hasSoundOutput(normalizedAnimations.get(
                            normalize(reference.name())))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean containsPlaySound(List<String> statements) {
        return statements != null && statements.stream()
                .filter(statement -> statement != null)
                .map(statement -> statement.toLowerCase(Locale.ROOT))
                .anyMatch(statement -> statement.contains("ysm.play_sound"));
    }

    public boolean isEmpty() {
        return parallelClips.isEmpty() && automaticClips.isEmpty()
                && controllerProgram.isEmpty() && rouletteClips.isEmpty();
    }

    /** Whether this model replaces Epic Fight's item rendering for the current hand item. */
    public boolean replacesHeldItem(LivingEntity entity, InteractionHand hand) {
        return ClientHeldItemModelPreferences.usesYsm(entity, modelId, hand)
                && customHeldItems.replaces(entity, hand);
    }

    /** Model-authored HOLD geometry without consulting the current display setting. */
    public boolean authorsHeldItem(LivingEntity entity, ItemStack stack) {
        return customHeldItems.authorsHeldItemAtRest(entity, stack);
    }

    /** Item-definition lookup used by server sound notifications before render catches up. */
    public boolean replacesAttackItem(LivingEntity entity, InteractionHand hand) {
        return ClientHeldItemModelPreferences.usesYsm(entity, modelId, hand)
                && customHeldItems.replacesAttackItem(entity, hand);
    }

    /** Whether the current replacement attack has an authored sound route. */
    public boolean hasAttackSoundRoute(LivingEntity entity, InteractionHand hand) {
        if (!replacesAttackItem(entity, hand)) {
            return false;
        }
        RuntimeState state = states.get(entity);
        if (state != null && state.attackSoundRouteHands.contains(hand)) {
            return true;
        }
        for (Map.Entry<String, ClipProgram> entry : automaticClips.entrySet()) {
            String name = entry.getKey();
            if (customHeldItems.replacementHand(name) == hand
                    && customHeldItems.clipAction(name)
                    == AnimationConditionMatcher.ItemAction.SWING
                    && customHeldItems.matchesClipItem(entity, name)
                    && (hasSoundOutput(entry.getValue())
                    || authoredSwingControllerSound)) {
                return true;
            }
        }
        return false;
    }

    /** Advances outputs for a ready model that was not sampled by rendering this tick. */
    public void advanceOutputs(LivingEntity entity, boolean firstPerson) {
        advanceOutputs(entity, firstPerson, true);
    }

    /** Advances outputs while respecting the current Epic Fight pose owner. */
    public void advanceOutputs(LivingEntity entity, boolean firstPerson,
                               boolean epicFightActionActive) {
        RuntimeState state = states.get(entity);
        if (state != null && state.lastTickCount >= entity.tickCount) {
            return;
        }
        sample(entity, 0.0F, firstPerson, null, epicFightActionActive);
    }

    /** Releases per-entity controller state and bound outputs when an entity unloads. */
    public void releaseEntity(LivingEntity entity) {
        RuntimeState removed = entity == null ? null : states.remove(entity);
        if (removed != null) {
            removed.reset(0.0D);
        }
    }

    /** Releases every entity state before the owning converted mesh is discarded. */
    public void releaseAllEntities() {
        states.values().forEach(state -> state.reset(0.0D));
        states.clear();
    }

    /** Bone-subtree roots that replace the current logical hand item. */
    public Set<String> heldItemReplacementRoots(LivingEntity entity, InteractionHand hand) {
        return ClientHeldItemModelPreferences.usesYsm(entity, modelId, hand)
                ? customHeldItems.replacementRoots(entity, hand) : Set.of();
    }

    /** Whether the current custom bow action replaces Epic Fight's complete body pose. */
    public boolean replacesBodyPose(LivingEntity entity) {
        Set<InteractionHand> enabledHands = ysmReplacementHands(entity);
        if (enabledHands.isEmpty()) {
            return false;
        }
        RuntimeState state = states.get(entity);
        boolean active = enabledHands.stream().anyMatch(hand ->
                customHeldItems.replacesBodyPose(entity, hand));
        if (active || state == null) {
            return active;
        }
        return state.fullBodyEnding != null
                && enabledHands.contains(state.fullBodyEnding.hand())
                || state.fullBodyObservation != null
                && enabledHands.contains(state.fullBodyObservation.hand());
    }

    /**
     * Whether an enabled held-item switch already owns, or will own on this frame,
     * the complete YSM pose. The unobserved comparison closes the renderer-before-
     * sample gap so the first changed-item frame cannot briefly use Epic Fight's body.
     */
    public boolean itemSwitchOwnsPose(LivingEntity entity) {
        Set<InteractionHand> enabledHands = ysmHeldItemAnimationHands(entity);
        if (enabledHands.isEmpty()) {
            return false;
        }
        RuntimeState runtime = states.get(entity);
        double sampledNow = (entity.tickCount + Minecraft.getInstance().getFrameTime()) / 20.0D;
        double now = runtime == null ? sampledNow : Math.max(sampledNow, runtime.lastNow);
        if (runtime != null && runtime.itemSwitchState
                .hasPoseOwnershipPotential(enabledHands, now)) {
            return true;
        }
        AutomaticAnimationSelector.State selectorState = runtime == null
                ? new AutomaticAnimationSelector.State() : runtime.automaticState;
        return enabledHands.stream().anyMatch(hand ->
                automaticSelector.hasUnobservedHeldItemChange(
                        entity, hand, selectorState));
    }

    public Frame sample(LivingEntity entity, float partialTick, boolean firstPerson) {
        return sample(entity, partialTick, firstPerson, null, false);
    }

    public Frame sample(LivingEntity entity, float partialTick, boolean firstPerson,
                        @Nullable Float epicModelYaw) {
        return sample(entity, partialTick, firstPerson, epicModelYaw, false);
    }

    public Frame sample(LivingEntity entity, float partialTick, boolean firstPerson,
                        @Nullable Float epicModelYaw,
                        boolean epicFightActionActive) {
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
        OfficialRoamingVariables.RouletteState roulette =
                OfficialRoamingVariables.rouletteState(entity);
        // Player roulette audio is already started by official YSM. EFTLM bypasses
        // that renderer for maids, so the converted maid path must own its copy.
        state.rouletteSoundOutputEnabled = compatOwnsRouletteSound(
                entity instanceof Player);
        double rouletteElapsed = state.rouletteElapsed(roulette, now);
        ClipProgram rouletteClip = roulette.playing()
                ? rouletteClip(roulette.animationName()) : null;
        state.selectRouletteClip(rouletteClip);
        state.reportRoulette(entity, roulette, rouletteClip != null);
        Set<InteractionHand> enabledReplacementHands = ysmReplacementHands(entity);
        Set<InteractionHand> activeReplacementHands =
                ysmActiveReplacementHands(entity);
        Set<InteractionHand> enabledItemAnimationHands =
                ysmHeldItemAnimationHands(entity);
        state.restrictFullBodyHands(enabledReplacementHands);
        MovementAnimationType synchronizedMovement =
                ClientMovementAnimationPreferences.remoteMovementOverride(
                        entity, modelId);
        AutomaticAnimationSelector.Selection selected =
                automaticSelector.select(entity, now, state.automaticState,
                        synchronizedMovement);
        List<AutomaticAnimationSelector.ActiveClip> rawAutomatic =
                filterDisabledItemReplacementClips(entity, selected.clips(),
                        enabledReplacementHands);
        state.fullBodyInputHands = customFullBodyInputHands(rawAutomatic);
        List<AutomaticAnimationSelector.ActiveClip> selectedAutomatic =
                updateFullBodySwingPlayback(rawAutomatic, now,
                        state.fullBodySwingState);
        FullBodyEnding fullBodyEnding = updateFullBodyEnding(
                state, selectedAutomatic, now);
        Set<InteractionHand> fullBodyHands = customFullBodyHands(
                selectedAutomatic);
        boolean itemSwitchBlocked = epicFightActionActive || !fullBodyHands.isEmpty()
                || fullBodyEnding != null || rouletteClip != null;
        observeItemSwitchEdges(selected.clips(), selected.heldItemChanges(), now,
                itemSwitchBlocked, state.itemSwitchState);
        MovementPose movementPose = !epicFightActionActive
                && fullBodyHands.isEmpty() && fullBodyEnding == null
                && rouletteClip == null
                && selected.main() != null && selected.movement() != null
                && ClientMovementAnimationPreferences.usesYsm(
                entity, modelId, selected.movement())
                ? new MovementPose(selected.main(), selected.movement()) : null;
        boolean customBowHeadYaw = shouldUseCustomBowHeadYaw(
                !fullBodyHands.isEmpty(), fullBodyEnding != null,
                fullBodyEnding != null && fullBodyEnding.compositeSnapshot() != null);
        Float visualFacingYaw = customBowHeadYaw
                ? entity.getViewYRot(stablePartialTick) : null;
        state.environment.update(stablePartialTick, firstPerson, deltaTime);
        state.environment.customBowAim(visualFacingYaw, epicModelYaw);
        Set<InteractionHand> attackReplacementHands = customAttackSoundHands(entity);
        state.environment.attackReplacementHands(attackReplacementHands);
        state.reportFullBody(entity, selectedAutomatic, fullBodyEnding, fullBodyHands,
                stablePartialTick, visualFacingYaw, epicModelYaw);
        boolean prospectiveItemSwitch = !itemSwitchBlocked && selected.main() != null
                && state.itemSwitchState.hasPotential(
                enabledItemAnimationHands, now);
        AnimationControllerProgram.Selection controllerSelection =
                controllerProgram.selectObserved(
                        now, state.environment, state.controllerState,
                        name -> controllerOutputsEnabled(
                                name, activeReplacementHands,
                                enabledItemAnimationHands,
                                prospectiveItemSwitch));
        ItemSwitchPose itemSwitchPose = updateItemSwitchPose(
                selected.clips(), controllerSelection.allActive(),
                selected.main(), selected.movement(),
                enabledItemAnimationHands, now, itemSwitchBlocked,
                state.itemSwitchState);
        List<AutomaticAnimationSelector.ActiveClip> automatic =
                filterInactiveOrdinaryHoldClips(
                        entity, selectedAutomatic, itemSwitchPose);
        state.prepareAutomaticTimelines(automatic, automaticSelector.names());
        state.environment.fullBodyReferenceYaw(
                movementPose == null && itemSwitchPose == null ? null : epicModelYaw);
        List<AnimationControllerProgram.ActiveAnimation> controlled =
                controllerSelection.outputActive();
        state.attackSoundRouteHands = attackSoundRouteHands(
                automatic, controlled, attackReplacementHands);
        state.prepareControllerTimelines(controllerProgram.activeKeys(controlled));
        collectCompletedEvaluation(state);
        boolean mountedFullBody = automatic.stream()
                .anyMatch(active -> isWholeModelMountedClip(active.name()));
        boolean currentFullBodyOwner = !fullBodyHands.isEmpty()
                || fullBodyEnding != null || movementPose != null
                || itemSwitchPose != null || mountedFullBody;
        boolean leavingPublishedFullBody = state.publishedFrame != null
                && state.publishedFrame.replaceEpicFightPose()
                && !currentFullBodyOwner;
        boolean workerEligible = entity != Minecraft.getInstance().player
                && !epicFightActionActive
                && !mountedFullBody
                && !leavingPublishedFullBody
                && customFullBodyHands(automatic).isEmpty()
                && fullBodyEnding == null
                && movementPose == null
                && itemSwitchPose == null
                && asyncSafe(automatic, controlled, rouletteClip);
        if (!workerEligible) {
            discardPendingEvaluation(state);
            evaluate(elapsed, automatic, controlled, fullBodyEnding, movementPose,
                    itemSwitchPose,
                    rouletteClip, rouletteElapsed,
                    state.environment, state, state.scratch);
            state.publishedFrame = frame(state.scratch);
            state.publishedScratch = state.scratch;
        } else {
            fireActiveTimelines(elapsed, automatic, controlled, rouletteClip,
                    rouletteElapsed, state.environment, state);
            if (state.publishedFrame == null) {
                evaluate(elapsed, automatic, controlled, null, null, null,
                        rouletteClip, rouletteElapsed,
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
            evaluate(elapsed, automaticCopy, controlledCopy, null, null, null,
                    rouletteClip, rouletteElapsed,
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
        evaluate(elapsed, List.of(), List.of(), null, null, null,
                null, 0.0D, environment, null, testScratch);
        return frame(testScratch);
    }

    Frame sampleAt(double elapsed, String rouletteAnimation, double rouletteElapsed,
                   ExpressionEngine.Environment environment) {
        evaluate(elapsed, List.of(), List.of(), null, null, null,
                rouletteClip(rouletteAnimation), rouletteElapsed,
                environment, null, testScratch);
        return frame(testScratch);
    }

    Frame sampleAutomaticAt(double elapsed, List<String> animationNames,
                            ExpressionEngine.Environment environment) {
        List<AutomaticAnimationSelector.ActiveClip> active = animationNames.stream()
                .map(ParallelAnimationProgram::normalize)
                .map(name -> new AutomaticAnimationSelector.ActiveClip(name, elapsed, false))
                .toList();
        evaluate(elapsed, active, List.of(), null, null, null,
                null, 0.0D, environment, null, testScratch);
        return frame(testScratch);
    }

    Frame sampleControllersAt(double now, ExpressionEngine.Environment environment,
                              AnimationControllerProgram.RuntimeState controllerState) {
        List<AnimationControllerProgram.ActiveAnimation> controlled =
                controllerProgram.select(now, environment, controllerState);
        evaluate(now, List.of(), controlled, null, null, null,
                null, 0.0D, environment, null, testScratch);
        return frame(testScratch);
    }

    private Set<InteractionHand> ysmReplacementHands(LivingEntity entity) {
        LinkedHashSet<InteractionHand> result = new LinkedHashSet<>();
        for (InteractionHand hand : InteractionHand.values()) {
            if (ClientHeldItemModelPreferences.usesYsm(entity, modelId, hand)
                    && customHeldItems.replacesAttackItem(entity, hand)) {
                result.add(hand);
            }
        }
        return Set.copyOf(result);
    }

    private List<AutomaticAnimationSelector.ActiveClip>
    filterDisabledItemReplacementClips(
            LivingEntity entity,
            List<AutomaticAnimationSelector.ActiveClip> automatic,
            Set<InteractionHand> enabledHands) {
        if (automatic.isEmpty()) {
            return automatic;
        }
        List<AutomaticAnimationSelector.ActiveClip> result = new ArrayList<>(automatic.size());
        for (AutomaticAnimationSelector.ActiveClip active : automatic) {
            InteractionHand hand = customHeldItems.replacementHand(active.name());
            boolean disabledReplacement = hand != null
                    && !enabledHands.contains(hand)
                    && customHeldItems.replacesAttackItem(entity, hand);
            if (!disabledReplacement) {
                result.add(active);
            }
        }
        return result.size() == automatic.size() ? automatic : List.copyOf(result);
    }

    /**
     * An ordinary Epic Fight item borrows its model's HOLD clip only for the
     * resolved equip window. Model-authored replacements keep their persistent
     * HOLD layer and continue to follow the existing held-item model setting.
     * Removing the ordinary clip here also prevents disabled switch rules from
     * leaking auxiliary-bone, sound, particle, or Molang timeline output.
     */
    private List<AutomaticAnimationSelector.ActiveClip>
    filterInactiveOrdinaryHoldClips(
            LivingEntity entity,
            List<AutomaticAnimationSelector.ActiveClip> automatic,
            @Nullable ItemSwitchPose itemSwitchPose) {
        if (automatic.isEmpty()) {
            return automatic;
        }
        Set<InteractionHand> switchingHands = itemSwitchPose == null
                ? Set.of() : itemSwitchPose.hands();
        List<AutomaticAnimationSelector.ActiveClip> result =
                new ArrayList<>(automatic.size());
        for (AutomaticAnimationSelector.ActiveClip active : automatic) {
            InteractionHand hand = holdHand(active.name());
            boolean keep = hand == null || keepsHeldItemHoldClip(
                    customHeldItems.replacesHeldItemAtRest(entity, hand),
                    switchingHands.contains(hand));
            if (keep) {
                result.add(active);
            }
        }
        return result.size() == automatic.size()
                ? automatic : List.copyOf(result);
    }

    static boolean keepsHeldItemHoldClip(
            boolean modelAuthoredReplacement, boolean switchAnimationActive) {
        return modelAuthoredReplacement || switchAnimationActive;
    }

    private boolean controllerOutputsEnabled(
            String controllerName, Set<InteractionHand> replacementHands,
            Set<InteractionHand> itemAnimationHands,
            boolean itemSwitchActive) {
        Set<InteractionHand> customHands =
                heldItemControllerHands.get(normalize(controllerName));
        boolean customEnabled = customHands != null
                && customHands.stream().anyMatch(replacementHands::contains);
        Set<InteractionHand> holdHands = holdControllerHands(controllerName);
        return keepsHeldItemControllerOutputs(customHands != null, customEnabled,
                holdHands, itemAnimationHands, itemSwitchActive);
    }

    static boolean keepsHeldItemControllerOutputs(
            boolean customController, boolean customEnabled,
            Set<InteractionHand> holdHands,
            Set<InteractionHand> itemAnimationHands,
            boolean itemSwitchActive) {
        if (!holdHands.isEmpty()) {
            return customEnabled || itemSwitchActive
                    && holdHands.stream().anyMatch(itemAnimationHands::contains);
        }
        return !customController || customEnabled;
    }

    private Set<InteractionHand> customAttackSoundHands(LivingEntity entity) {
        LinkedHashSet<InteractionHand> result = new LinkedHashSet<>();
        for (InteractionHand hand : InteractionHand.values()) {
            if (ClientHeldItemModelPreferences.usesYsm(entity, modelId, hand)
                    && customHeldItems.replaces(entity, hand)
                    && AnimationConditionMatcher.swingSignal(entity, hand).active()) {
                result.add(hand);
            }
        }
        return Set.copyOf(result);
    }

    private Set<InteractionHand> attackSoundRouteHands(
            List<AutomaticAnimationSelector.ActiveClip> automatic,
            List<AnimationControllerProgram.ActiveAnimation> controlled,
            Set<InteractionHand> replacementHands) {
        if (replacementHands.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<InteractionHand> result = new LinkedHashSet<>();
        for (AutomaticAnimationSelector.ActiveClip active : automatic) {
            InteractionHand hand = customHeldItems.replacementHand(active.name());
            ClipProgram program = automaticClips.get(active.name());
            if (hand != null && replacementHands.contains(hand)
                    && customHeldItems.clipAction(active.name())
                    == AnimationConditionMatcher.ItemAction.SWING
                    && hasSoundOutput(program)) {
                result.add(hand);
            }
        }
        for (AnimationControllerProgram.ActiveAnimation active : controlled) {
            InteractionHand hand = EntityAnimationEnvironment.attackHandForScope(
                    "controller/" + active.controllerName(), replacementHands);
            if (hand != null && emitsControllerOutputs(active.weight())
                    && hasSoundOutput(controllerClips.get(active.name()))) {
                result.add(hand);
            }
        }
        return Set.copyOf(result);
    }

    private static boolean hasSoundOutput(ClipProgram program) {
        return program != null && hasSoundOutput(program.clip());
    }

    private static boolean hasSoundOutput(AnimationClip clip) {
        if (clip == null) {
            return false;
        }
        if (!clip.soundEffects().isEmpty()) {
            return true;
        }
        return clip.timeline().stream()
                .flatMap(event -> event.statements().stream())
                .map(statement -> statement.toLowerCase(Locale.ROOT))
                .anyMatch(statement -> statement.contains("ysm.play_sound"));
    }

    static boolean emitsControllerOutputs(float weight) {
        return Float.isFinite(weight) && Math.abs(weight) > EPSILON;
    }

    Frame sampleAutomaticWithEndingAt(
            double elapsed, List<String> animationNames,
            String endingAnimation, double endingElapsed, float endingWeight,
            ExpressionEngine.Environment environment) {
        return sampleAutomaticWithEndingAt(elapsed, animationNames,
                endingAnimation, endingElapsed, endingWeight,
                environment, environment);
    }

    Frame sampleAutomaticWithEndingAt(
            double elapsed, List<String> animationNames,
            String endingAnimation, double endingElapsed, float endingWeight,
            ExpressionEngine.Environment capturedEnvironment,
            ExpressionEngine.Environment environment) {
        List<AutomaticAnimationSelector.ActiveClip> active = animationNames.stream()
                .map(ParallelAnimationProgram::normalize)
                .map(name -> new AutomaticAnimationSelector.ActiveClip(
                        name, elapsed, false))
                .toList();
        String endingName = normalize(endingAnimation);
        InteractionHand hand = customHeldItems.replacementHand(endingName);
        PoseLayerSnapshot snapshot = captureAutomaticLayer(
                endingName, endingElapsed, capturedEnvironment);
        FullBodyEnding ending = hand == null ? null : new FullBodyEnding(
                new AutomaticAnimationSelector.ActiveClip(
                        endingName, endingElapsed, false),
                hand, 0.0D, endingWeight, snapshot, null);
        evaluate(elapsed, active, List.of(), ending, null, null,
                null, 0.0D, environment, null, testScratch);
        return frame(testScratch);
    }

    Frame sampleAutomaticOwnershipEndingAt(
            double sourceElapsed, List<String> sourceAnimations,
            double targetElapsed, List<String> targetAnimations,
            String endingAnimation, float endingWeight,
            ExpressionEngine.Environment sourceEnvironment,
            ExpressionEngine.Environment targetEnvironment) {
        String endingName = normalize(endingAnimation);
        InteractionHand hand = customHeldItems.replacementHand(endingName);
        PoseLayerSnapshot actionSnapshot = captureAutomaticLayer(
                endingName, sourceElapsed, sourceEnvironment);
        List<AutomaticAnimationSelector.ActiveClip> source = sourceAnimations.stream()
                .map(ParallelAnimationProgram::normalize)
                .map(name -> new AutomaticAnimationSelector.ActiveClip(
                        name, sourceElapsed, false))
                .toList();
        evaluate(sourceElapsed, source, List.of(), null, null, null,
                null, 0.0D, sourceEnvironment, null, testScratch);
        FullBodyCompositeSnapshot composite = new FullBodyCompositeSnapshot(
                testScratch.wholeModelPose.positions.length);
        composite.capture(testScratch);

        List<AutomaticAnimationSelector.ActiveClip> target = targetAnimations.stream()
                .map(ParallelAnimationProgram::normalize)
                .map(name -> new AutomaticAnimationSelector.ActiveClip(
                        name, targetElapsed, false))
                .toList();
        FullBodyEnding ending = hand == null ? null : new FullBodyEnding(
                new AutomaticAnimationSelector.ActiveClip(
                        endingName, sourceElapsed, false),
                hand, 0.0D, endingWeight, actionSnapshot,
                composite.copyIfValid());
        evaluate(targetElapsed, target, List.of(), ending, null, null,
                null, 0.0D, targetEnvironment, null, testScratch);
        return frame(testScratch);
    }

    @Nullable
    private PoseLayerSnapshot captureAutomaticLayer(
            String animationName, double elapsed,
            ExpressionEngine.Environment environment) {
        ClipProgram program = automaticClips.get(animationName);
        if (program == null) {
            return null;
        }
        float localTime = automaticTime(program, elapsed);
        if (localTime < 0.0F) {
            return null;
        }
        resetScratch(testScratch);
        PoseLayerSnapshot snapshot = new PoseLayerSnapshot(
                testScratch.wholeModelPose.positions.length,
                testScratch.visibilityScales.length);
        boolean applied = evaluateProgram(program, localTime, environment,
                null, testScratch.wholeModelPose, ApplyMode.FULL_BODY,
                testScratch, snapshot);
        return applied ? snapshot : null;
    }

    Frame sampleAutomaticAndControllersAt(
            double now, List<String> animationNames,
            ExpressionEngine.Environment environment,
            AnimationControllerProgram.RuntimeState controllerState) {
        List<AutomaticAnimationSelector.ActiveClip> automatic = animationNames.stream()
                .map(ParallelAnimationProgram::normalize)
                .map(name -> new AutomaticAnimationSelector.ActiveClip(name, now, false))
                .toList();
        List<AnimationControllerProgram.ActiveAnimation> controlled =
                controllerProgram.select(now, environment, controllerState);
        evaluate(now, automatic, controlled, null, null, null,
                null, 0.0D,
                environment, null, testScratch);
        return frame(testScratch);
    }

    Frame sampleMovementAt(
            double now, List<String> animationNames, String mainAnimation,
            MovementAnimationType movement,
            ExpressionEngine.Environment environment,
            AnimationControllerProgram.RuntimeState controllerState) {
        List<AutomaticAnimationSelector.ActiveClip> automatic = animationNames.stream()
                .map(ParallelAnimationProgram::normalize)
                .map(name -> new AutomaticAnimationSelector.ActiveClip(name, now, false))
                .toList();
        String normalizedMain = normalize(mainAnimation);
        AutomaticAnimationSelector.ActiveClip main = automatic.stream()
                .filter(active -> active.name().equals(normalizedMain))
                .findFirst().orElseThrow();
        List<AnimationControllerProgram.ActiveAnimation> controlled =
                controllerProgram.select(now, environment, controllerState);
        evaluate(now, automatic, controlled, null,
                new MovementPose(main, movement), null, null, 0.0D,
                environment, null, testScratch);
        return frame(testScratch);
    }

    Frame sampleItemSwitchAt(
            double now, List<String> animationNames, String mainAnimation,
            @Nullable MovementAnimationType movement,
            Set<InteractionHand> switchingHands,
            Set<InteractionHand> enabledHands,
            ExpressionEngine.Environment environment,
            AnimationControllerProgram.RuntimeState controllerState) {
        List<AutomaticAnimationSelector.ActiveClip> automatic = animationNames.stream()
                .map(ParallelAnimationProgram::normalize)
                .map(name -> new AutomaticAnimationSelector.ActiveClip(
                        name, now, false))
                .toList();
        String normalizedMain = normalize(mainAnimation);
        AutomaticAnimationSelector.ActiveClip main = automatic.stream()
                .filter(active -> active.name().equals(normalizedMain))
                .findFirst().orElseThrow();
        List<AnimationControllerProgram.ActiveAnimation> controlled =
                controllerProgram.select(now, environment, controllerState);
        ItemSwitchPose itemSwitch = new ItemSwitchPose(
                main, movement, switchingHands, enabledHands, 1L);
        evaluate(now, automatic, controlled, null, null, itemSwitch,
                null, 0.0D, environment, null, testScratch);
        return frame(testScratch);
    }

    float itemSwitchDuration(String animationName) {
        ClipProgram program = automaticClips.get(normalize(animationName));
        return program == null ? 0.0F : program.itemSwitchDuration();
    }

    float controllerItemSwitchDuration(String animationName) {
        ClipProgram program = controllerClips.get(normalize(animationName));
        return program == null ? 0.0F : program.itemSwitchDuration();
    }

    private Frame frame(EvaluationScratch scratch) {
        return new Frame(scratch.parallelPose.output, scratch.wholeModelPose.output,
                scratch.heldItemPose.output,
                scratch.replaceEpicFightPose, scratch.replaceEpicFightAnchors,
                scratch.suppressParallelDeltas,
                scratch.heldItemAnchorJoints,
                scratch.fullBodyBlendSource, scratch.fullBodyBlendWeight,
                scratch.movementPoseKey,
                Set.copyOf(scratch.itemSwitchHands),
                scratch.hiddenView);
    }

    private void fireActiveTimelines(
            double elapsed, List<AutomaticAnimationSelector.ActiveClip> automatic,
            List<AnimationControllerProgram.ActiveAnimation> controlled,
            ClipProgram rouletteClip, double rouletteElapsed,
            ExpressionEngine.Environment environment, RuntimeState runtimeState) {
        Set<InteractionHand> pausedHoldHands = runtimeState.fullBodyInputHands;
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
            if (isPausedHoldClip(active.name(), pausedHoldHands)) {
                // Official YSM pauses and resets a hand's hold controller while its
                // use/swing controller owns the authored full-body bow pose. Keep the
                // hold rule available for item suppression, but do not emit its
                // timeline or let it resume from a stale local time on release.
                runtimeState.environment.stopSoundScope(program.clip().name());
                runtimeState.lastLocalTime.remove(program.clip().name());
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
            float localTime = controllerTime(program, active.elapsed());
            if (!emitsControllerOutputs(active.weight())) {
                // Bedrock controllers may list mutually exclusive clips with weights
                // such as ctrl.idle and !ctrl.idle. Keep the inactive clip's clock
                // current so reactivation does not replay past events, but never emit
                // its sounds, particles, or Molang timeline statements.
                runtimeState.environment.stopSoundScope(active.instanceKey());
                runtimeState.environment.stopParticleScope(active.instanceKey());
                runtimeState.lastLocalTime.put(active.instanceKey(), localTime);
                continue;
            }
            ExpressionEngine.Environment controllerEnvironment = active.stateVariables().isEmpty()
                    ? environment : new ControllerVariableEnvironment(
                    environment, active.stateVariables());
            fireProgramTimeline(program, localTime,
                    controllerEnvironment, runtimeState, active.instanceKey(), true);
        }
        if (rouletteClip != null && rouletteElapsed >= 0.0D) {
            float localTime = rouletteTime(rouletteClip, rouletteElapsed);
            if (localTime >= 0.0F) {
                fireProgramTimeline(rouletteClip, localTime, environment, runtimeState,
                        rouletteClip.clip().name(),
                        runtimeState.rouletteSoundOutputEnabled);
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
                          @Nullable FullBodyEnding fullBodyEnding,
                          @Nullable MovementPose movementPose,
                          @Nullable ItemSwitchPose itemSwitchPose,
                          ClipProgram rouletteClip, double rouletteElapsed,
                          ExpressionEngine.Environment environment,
                          RuntimeState runtimeState, EvaluationScratch scratch) {
        resetScratch(scratch);
        if (itemSwitchPose != null) {
            scratch.itemSwitchHands.addAll(itemSwitchPose.hands());
        }
        scratch.mirrorOrdinaryMainhandBowSwitch = itemSwitchPose != null
                && itemSwitchPose.hands().contains(InteractionHand.MAIN_HAND)
                && automatic.stream().anyMatch(active ->
                isOrdinaryMainhandBowHold(active.name()));
        scratch.movementPoseKey = itemSwitchPose != null
                ? (movementPose == null ? itemSwitchPose.key()
                : movementPose.key() + '|' + itemSwitchPose.key())
                : movementPose == null ? null : movementPose.key();
        Set<InteractionHand> fullBodyHands = customFullBodyHands(automatic);
        Set<InteractionHand> pausedHoldHands = runtimeState == null
                ? customFullBodyInputHands(automatic)
                : runtimeState.fullBodyInputHands;
        boolean liveFullBodyPose = !fullBodyHands.isEmpty();
        boolean blendFullBodyToEpic = !liveFullBodyPose && fullBodyEnding != null
                && fullBodyEnding.compositeSnapshot() != null;
        // USE -> SWING remains one complete authored hierarchy. Once the final action
        // ends, however, evaluate the ordinary Epic-owned path exactly once and blend
        // the saved complete YSM skin toward it in AuxiliaryPoseMatrices.
        boolean customFullBodyPose = liveFullBodyPose
                || fullBodyEnding != null && !blendFullBodyToEpic;
        // A configured movement pose is a complete official-YSM composition just like
        // a model-authored full-body item action. Keep pre/main/post/parallel layers in
        // one scratch hierarchy so a main clip can undo pre_parallel visibility scales.
        // Putting pre_parallel in a separate matrix would multiply scale 0 back into
        // effects such as 05_magical's run circles and flying staff.
        PoseScratch authoredTarget = customFullBodyPose || movementPose != null
                || itemSwitchPose != null
                ? scratch.wholeModelPose : scratch.parallelPose;
        for (ClipProgram program : parallelClips) {
            if (program.clip().name().startsWith("pre_parallel")) {
                evaluateProgram(program, localTime(program, elapsed), environment,
                        runtimeState, authoredTarget, ApplyMode.PARALLEL, scratch);
            }
        }
        if (customFullBodyPose) {
            for (PoseLayer layer : orderedFullBodyLayers(
                    automatic, controlled, fullBodyEnding)) {
                if (layer.automatic() != null) {
                    evaluateAutomaticLayer(layer.automatic(), true, false, false,
                            false,
                            pausedHoldHands,
                            environment, runtimeState, scratch);
                } else if (layer.controlled() != null) {
                    evaluateControllerLayer(layer.controlled(), true, false, false,
                            environment, runtimeState, scratch);
                } else {
                    evaluateEndingFullBodyLayer(layer.ending(), environment, scratch);
                }
            }
        } else if (itemSwitchPose != null) {
            for (PoseLayer layer : orderedFullBodyLayers(
                    automatic, controlled, null)) {
                if (layer.automatic() != null) {
                    boolean switchMain = layer.automatic().name()
                            .equals(itemSwitchPose.main().name());
                    // Every automatic main-state clip is kept alive by official YSM,
                    // including idle clips whose Bedrock playback flag is ONCE. It is
                    // the structural body base while a HOLD transition owns the pose.
                    boolean movementMain = switchMain;
                    InteractionHand holdHand = holdHand(
                            layer.automatic().name());
                    boolean switchHold = holdHand != null
                            && itemSwitchPose.hands().contains(holdHand);
                    evaluateAutomaticLayer(layer.automatic(), false, movementMain,
                            itemSwitchPose.movement() != null,
                            switchMain || switchHold,
                            pausedHoldHands, environment, runtimeState, scratch);
                } else if (layer.controlled() != null) {
                    boolean movementController = movementPose != null
                            && isMovementMainController(
                            layer.controlled().controllerName());
                    evaluateControllerLayer(layer.controlled(), false,
                            movementController,
                            isItemSwitchController(
                                    layer.controlled().controllerName(),
                                    itemSwitchPose.hands()),
                            environment, runtimeState, scratch);
                }
            }
        } else if (movementPose != null) {
            for (PoseLayer layer : orderedFullBodyLayers(
                    automatic, controlled, null)) {
                if (layer.automatic() != null) {
                    boolean movementMain = layer.automatic().name()
                            .equals(movementPose.active().name());
                    evaluateAutomaticLayer(layer.automatic(), false, movementMain, true,
                            false,
                            pausedHoldHands, environment, runtimeState, scratch);
                } else if (layer.controlled() != null) {
                    evaluateControllerLayer(layer.controlled(), false,
                            isMovementMainController(layer.controlled().controllerName()), false,
                            environment, runtimeState, scratch);
                }
            }
        } else {
            for (AutomaticAnimationSelector.ActiveClip active : automatic) {
                evaluateAutomaticLayer(active, false, false, false, false,
                        pausedHoldHands,
                        environment, runtimeState, scratch);
            }
            for (AnimationControllerProgram.ActiveAnimation active : controlled) {
                evaluateControllerLayer(active, false, false, false,
                        environment, runtimeState, scratch);
            }
        }
        if (rouletteClip != null && rouletteElapsed >= 0.0D) {
            float localTime = rouletteTime(rouletteClip, rouletteElapsed);
            if (localTime >= 0.0F) {
                // Official YSM owns player roulette audio. EFTLM bypasses the official
                // maid renderer, so the compat path owns maid roulette audio instead.
                evaluateProgram(rouletteClip, localTime, environment, runtimeState,
                        scratch.wholeModelPose, ApplyMode.FULL_BODY, 1.0F, false,
                        rouletteClip.clip().name(), runtimeState != null
                                && runtimeState.rouletteSoundOutputEnabled, scratch);
            }
        }
        for (ClipProgram program : parallelClips) {
            if (!program.clip().name().startsWith("pre_parallel")) {
                evaluateProgram(program, localTime(program, elapsed), environment,
                        runtimeState, authoredTarget, ApplyMode.PARALLEL, scratch);
            }
        }
        composeVisibility(scratch);
        composeAuxiliaryMatrices(scratch.parallelPose, scratch);
        composeAuxiliaryMatrices(scratch.wholeModelPose, scratch);
        composeAuxiliaryMatrices(scratch.heldItemPose, scratch);
        if (liveFullBodyPose && runtimeState != null) {
            runtimeState.fullBodyCompositeSnapshot.capture(scratch);
        }
        if (blendFullBodyToEpic) {
            FullBodyCompositeSnapshot source = fullBodyEnding.compositeSnapshot();
            scratch.fullBodyBlendSource = source.skinMatrices;
            scratch.fullBodyBlendWeight = fullBodyEnding.weight();
            // Keep a bone drawable while either endpoint is visible; its matrix scale
            // performs the actual transition without an early visibility pop.
            scratch.hiddenBones.retainAll(source.hiddenBones);
        }
    }

    private void evaluateAutomaticLayer(
            AutomaticAnimationSelector.ActiveClip active,
            boolean customFullBodyPose, boolean movementFullBody,
            boolean movementComposition, boolean itemSwitchFullBody,
            Set<InteractionHand> pausedHoldHands,
            ExpressionEngine.Environment environment, RuntimeState runtimeState,
            EvaluationScratch scratch) {
        ClipProgram program = automaticClips.get(active.name());
        if (program == null) {
            return;
        }
        if (isPausedHoldClip(active.name(), pausedHoldHands)) {
            if (runtimeState != null) {
                runtimeState.environment.stopSoundScope(program.clip().name());
                runtimeState.lastLocalTime.remove(program.clip().name());
            }
            return;
        }
        if (active.restarted() && runtimeState != null) {
            runtimeState.environment.stopSoundScope(program.clip().name());
            runtimeState.lastLocalTime.remove(program.clip().name());
        }
        float localTime = movementFullBody
                ? movementTime(program, active.elapsed())
                : automaticTime(program, active.elapsed());
        if (localTime < 0.0F) {
            return;
        }
        boolean mounted = isWholeModelMountedClip(active.name());
        boolean selectiveReplacement = !program.replacementIndices().isEmpty();
        // Official YSM composes the complete matching hold layer after its movement
        // main. A model-authored weapon is posed together with the arm, forearm, wrist,
        // and locator that hold it; retaining only the prop subtree makes the weapon
        // visible but leaves it oriented under an unrelated locomotion arm. Promote
        // only a real HOLD replacement here. Effect-only item augmentations and
        // use/swing actions retain their existing Epic Fight ownership boundaries.
        boolean movementReplacement = movementComposition && !movementFullBody
                && customHeldItems.clipAction(active.name())
                == AnimationConditionMatcher.ItemAction.HOLD
                && !customHeldItems.replacementRoots(active.name()).isEmpty();
        boolean wholeModel = mounted || customFullBodyPose || movementFullBody
                || movementReplacement || itemSwitchFullBody;
        PoseScratch target = wholeModel ? scratch.wholeModelPose
                : selectiveReplacement ? scratch.heldItemPose
                : scratch.parallelPose;
        AnimationConditionMatcher.ItemAction action =
                customHeldItems.clipAction(active.name());
        boolean captureActionPose = customFullBodyPose && runtimeState != null
                && (action == AnimationConditionMatcher.ItemAction.USE
                || action == AnimationConditionMatcher.ItemAction.SWING);
        PoseLayerSnapshot capture = captureActionPose
                ? runtimeState.fullBodyActionSnapshot : null;
        if (capture != null) {
            capture.reset();
        }
        PoseTransform transform = itemSwitchFullBody
                && scratch.mirrorOrdinaryMainhandBowSwitch
                && isOrdinaryMainhandBowHold(active.name())
                ? PoseTransform.MIRROR_X : PoseTransform.NONE;
        boolean applied = movementReplacement || itemSwitchFullBody
                ? evaluateProgram(program, localTime, environment,
                runtimeState, target, ApplyMode.FULL_BODY, scratch, capture, transform)
                : evaluateProgram(program, localTime, environment,
                runtimeState, target,
                wholeModel ? ApplyMode.FULL_BODY : ApplyMode.OVERRIDE,
                scratch, capture);
        if (capture != null) {
            runtimeState.fullBodyActionSnapshotClip = applied
                    ? normalize(active.name()) : "";
        }
        scratch.replaceEpicFightPose |= (mounted || customFullBodyPose
                || movementFullBody || itemSwitchFullBody) && applied;
        if (customFullBodyPose) {
            // The selected state and draw/release clips form one authored hierarchy.
            // The matching hold clip is paused above, exactly as in official YSM.
            program.propReplacementIndices().forEach(index ->
                    scratch.suppressParallelDeltas[index] = true);
        } else if (!movementReplacement && !itemSwitchFullBody
                && selectiveReplacement && applied) {
            program.replacementIndices().forEach(index -> {
                scratch.replaceEpicFightAnchors[index] = true;
                HeldAttachment attachment = program.heldAttachments().get(index);
                if (attachment != null) {
                    scratch.heldItemAnchorJoints[index] = attachment.anchorJoint();
                    scratch.heldItemRelativePaths[index] = attachment.relativePath();
                    scratch.heldItemBindRebases[index] = attachment.bindRebase();
                }
            });
            program.propReplacementIndices().forEach(index ->
                    scratch.suppressParallelDeltas[index] = true);
        }
    }

    /** Last evaluated output of one official controller layer, before its ending fade. */
    private static final class PoseLayerSnapshot {
        private final float[][] positions;
        private final float[][] rotations;
        private final float[][] scales;
        private final boolean[] hasPosition;
        private final boolean[] hasRotation;
        private final boolean[] hasScale;
        private final float[][] visibilityScales;
        private final boolean[] hasVisibilityScale;

        private PoseLayerSnapshot(int auxiliaryCount, int visibilityCount) {
            positions = new float[auxiliaryCount][3];
            rotations = new float[auxiliaryCount][3];
            scales = new float[auxiliaryCount][3];
            hasPosition = new boolean[auxiliaryCount];
            hasRotation = new boolean[auxiliaryCount];
            hasScale = new boolean[auxiliaryCount];
            visibilityScales = new float[visibilityCount][3];
            hasVisibilityScale = new boolean[visibilityCount];
            reset();
        }

        private void reset() {
            Arrays.fill(hasPosition, false);
            Arrays.fill(hasRotation, false);
            Arrays.fill(hasScale, false);
            Arrays.fill(hasVisibilityScale, false);
            for (float[] scale : scales) {
                Arrays.fill(scale, 1.0F);
            }
            for (float[] scale : visibilityScales) {
                Arrays.fill(scale, 1.0F);
            }
        }

        private void capturePosition(int index, float[] value) {
            System.arraycopy(value, 0, positions[index], 0, 3);
            hasPosition[index] = true;
        }

        private void captureRotation(int index, float[] value) {
            System.arraycopy(value, 0, rotations[index], 0, 3);
            hasRotation[index] = true;
        }

        private void captureScale(int index, float[] value) {
            System.arraycopy(value, 0, scales[index], 0, 3);
            hasScale[index] = true;
        }

        private void captureVisibilityScale(int index, float[] value) {
            System.arraycopy(value, 0, visibilityScales[index], 0, 3);
            hasVisibilityScale[index] = true;
        }

        private PoseLayerSnapshot copy() {
            PoseLayerSnapshot result = new PoseLayerSnapshot(
                    positions.length, visibilityScales.length);
            for (int index = 0; index < positions.length; index++) {
                if (hasPosition[index]) {
                    result.capturePosition(index, positions[index]);
                }
                if (hasRotation[index]) {
                    result.captureRotation(index, rotations[index]);
                }
                if (hasScale[index]) {
                    result.captureScale(index, scales[index]);
                }
            }
            for (int index = 0; index < visibilityScales.length; index++) {
                if (hasVisibilityScale[index]) {
                    result.captureVisibilityScale(index, visibilityScales[index]);
                }
            }
            return result;
        }
    }

    /**
     * Complete private-bone skin produced by the last full-body YSM frame. The snapshot is
     * immutable after publication so the final controller ending can blend it toward the live
     * Epic Fight pose without evaluating Molang a second time.
     */
    private static final class FullBodyCompositeSnapshot {
        private final OpenMatrix4f[] skinMatrices;
        private Set<String> hiddenBones = Set.of();
        private boolean valid;

        private FullBodyCompositeSnapshot(int auxiliaryCount) {
            skinMatrices = openMatrices(auxiliaryCount);
        }

        private void capture(EvaluationScratch scratch) {
            for (int index = 0; index < skinMatrices.length; index++) {
                OpenMatrix4f target = skinMatrices[index];
                target.setIdentity();
                if (!scratch.suppressParallelDeltas[index]) {
                    target.mulBack(scratch.parallelPose.output[index]);
                }
                target.mulFront(scratch.wholeModelPose.output[index]);
            }
            hiddenBones = Set.copyOf(scratch.hiddenView);
            valid = true;
        }

        @Nullable
        private FullBodyCompositeSnapshot copyIfValid() {
            if (!valid) {
                return null;
            }
            FullBodyCompositeSnapshot copy = new FullBodyCompositeSnapshot(
                    skinMatrices.length);
            for (int index = 0; index < skinMatrices.length; index++) {
                copy.skinMatrices[index].load(skinMatrices[index]);
            }
            copy.hiddenBones = hiddenBones;
            copy.valid = true;
            return copy;
        }

        private void reset() {
            hiddenBones = Set.of();
            valid = false;
        }
    }

    private void evaluateControllerLayer(
            AnimationControllerProgram.ActiveAnimation active,
            boolean customFullBodyPose, boolean movementFullBody,
            boolean itemSwitchFullBody,
            ExpressionEngine.Environment environment, RuntimeState runtimeState,
            EvaluationScratch scratch) {
        ClipProgram program = controllerClips.get(active.name());
        if (program == null) {
            return;
        }
        boolean heldItemController = !customFullBodyPose && !movementFullBody
                && !itemSwitchFullBody
                && program.replacementIndices().stream()
                .anyMatch(index -> scratch.replaceEpicFightAnchors[index]);
        // A custom bow controller may affect the whole hierarchy only when it also
        // owns the replacement prop. Ordinary main controllers remain auxiliary in
        // that path, preserving the authored bow action's single body authority.
        boolean customControllerFullBody = customFullBodyPose
                && !program.replacementIndices().isEmpty();
        boolean wholeModel = movementFullBody || customControllerFullBody
                || itemSwitchFullBody;
        ExpressionEngine.Environment controllerEnvironment = active.stateVariables().isEmpty()
                ? environment : new ControllerVariableEnvironment(
                environment, active.stateVariables());
        PoseTransform transform = itemSwitchFullBody
                && scratch.mirrorOrdinaryMainhandBowSwitch
                && isOrdinaryMainhandBowHold(program.clip().name())
                ? PoseTransform.MIRROR_X : PoseTransform.NONE;
        boolean applied = evaluateProgram(
                program, controllerTime(program, active.elapsed()),
                controllerEnvironment, runtimeState,
                customFullBodyPose || movementFullBody || itemSwitchFullBody
                        ? scratch.wholeModelPose
                        : heldItemController ? scratch.heldItemPose
                        : scratch.parallelPose,
                wholeModel ? ApplyMode.FULL_BODY : ApplyMode.OVERRIDE,
                active.weight(), active.blendViaShortestPath(),
                active.instanceKey(), true, scratch, null, transform);
        scratch.replaceEpicFightPose |= wholeModel && applied;
    }

    private static List<PoseLayer> orderedFullBodyLayers(
            List<AutomaticAnimationSelector.ActiveClip> automatic,
            List<AnimationControllerProgram.ActiveAnimation> controlled,
            @Nullable FullBodyEnding ending) {
        List<PoseLayer> result = new ArrayList<>(automatic.size()
                + controlled.size() + (ending == null ? 0 : 1));
        int order = 0;
        for (AutomaticAnimationSelector.ActiveClip active : automatic) {
            result.add(new PoseLayer(automaticStage(active.name()), order++,
                    active, null, null));
        }
        if (ending != null) {
            // The official use controller is later than the swing controller. During
            // its three-tick ending transition it therefore blends the last draw pose
            // over the newly-started release pose, then yields completely to release.
            result.add(new PoseLayer(automaticStage(ending.active().name()), order++,
                    null, null, ending));
        }
        for (AnimationControllerProgram.ActiveAnimation active : controlled) {
            result.add(new PoseLayer(controllerStage(active.controllerName()), order++,
                    null, active, null));
        }
        result.sort(Comparator.comparingInt(PoseLayer::stage)
                .thenComparingInt(PoseLayer::order));
        return result;
    }

    private void evaluateEndingFullBodyLayer(
            @Nullable FullBodyEnding ending,
            ExpressionEngine.Environment environment,
            EvaluationScratch scratch) {
        if (ending == null || ending.weight() <= EPSILON) {
            return;
        }
        ClipProgram program = automaticClips.get(ending.active().name());
        if (program == null) {
            return;
        }
        if (ending.snapshot() != null) {
            boolean applied = applyEndingSnapshot(
                    ending.snapshot(), ending.weight(), scratch.wholeModelPose, scratch);
            scratch.replaceEpicFightPose |= applied;
            program.propReplacementIndices().forEach(index ->
                    scratch.suppressParallelDeltas[index] = true);
            return;
        }
        float localTime = automaticTime(program, ending.active().elapsed());
        if (localTime < 0.0F) {
            return;
        }
        boolean applied = evaluateProgram(program, localTime, environment,
                null, scratch.wholeModelPose, ApplyMode.FULL_BODY,
                ending.weight(), true, program.clip().name() + ":ending",
                false, scratch);
        scratch.replaceEpicFightPose |= applied;
        program.propReplacementIndices().forEach(index ->
                scratch.suppressParallelDeltas[index] = true);
    }

    private static boolean applyEndingSnapshot(PoseLayerSnapshot snapshot, float weight,
                                               PoseScratch pose,
                                               EvaluationScratch scratch) {
        boolean applied = false;
        for (int index = 0; index < snapshot.positions.length; index++) {
            if (snapshot.hasRotation[index]) {
                applied = true;
                for (int axis = 0; axis < 3; axis++) {
                    float current = pose.hasRotation[index]
                            ? pose.rotations[index][axis] : 0.0F;
                    float difference = shortestRadians(
                            snapshot.rotations[index][axis] - current);
                    pose.rotations[index][axis] = current + difference * weight;
                }
                pose.hasRotation[index] = true;
            }
            if (snapshot.hasPosition[index]) {
                applied = true;
                for (int axis = 0; axis < 3; axis++) {
                    float current = pose.hasPosition[index]
                            ? pose.positions[index][axis] : 0.0F;
                    pose.positions[index][axis] = current
                            + (snapshot.positions[index][axis] - current) * weight;
                }
                pose.hasPosition[index] = true;
            }
            if (snapshot.hasScale[index]) {
                applied = true;
                for (int axis = 0; axis < 3; axis++) {
                    float current = pose.hasScale[index]
                            ? pose.scales[index][axis] : 1.0F;
                    pose.scales[index][axis] = current
                            + (snapshot.scales[index][axis] - current) * weight;
                }
                pose.hasScale[index] = true;
            }
        }
        for (int index = 0; index < snapshot.visibilityScales.length; index++) {
            if (!snapshot.hasVisibilityScale[index]) {
                continue;
            }
            for (int axis = 0; axis < 3; axis++) {
                float current = scratch.hasVisibilityScale[index]
                        ? scratch.visibilityScales[index][axis] : 1.0F;
                scratch.visibilityScales[index][axis] = current
                        + (snapshot.visibilityScales[index][axis] - current) * weight;
            }
            scratch.hasVisibilityScale[index] = true;
        }
        return applied;
    }

    private static int automaticStage(String clipName) {
        String name = normalize(clipName);
        if (name.startsWith("hold_mainhand") || name.startsWith("hold_offhand")) {
            return 25;
        }
        if (name.startsWith("swing")) {
            return 40;
        }
        if (name.startsWith("use_mainhand") || name.startsWith("use_offhand")) {
            return 55;
        }
        if (name.startsWith("passenger")) {
            return 65;
        }
        if (name.startsWith("armor_") || name.startsWith("vehicle")) {
            return 70;
        }
        return 10;
    }

    private static int controllerStage(String controllerName) {
        String name = normalize(controllerName);
        if (slot(name, "pre_main")) return 5;
        if (slot(name, "main")) return 10;
        if (slot(name, "post_main")) return 15;
        if (slot(name, "pre_hold")) return 20;
        if (slot(name, "hold_mainhand") || slot(name, "hold_offhand")) return 25;
        if (slot(name, "post_hold")) return 30;
        if (slot(name, "pre_swing")) return 35;
        if (slot(name, "swing")) return 40;
        if (slot(name, "post_swing")) return 45;
        if (slot(name, "pre_use")) return 50;
        if (slot(name, "use")) return 55;
        if (slot(name, "post_use")) return 60;
        if (slot(name, "passenger")) return 65;
        return 70;
    }

    /** Controllers that official YSM composes directly around its locomotion clip. */
    private static boolean isMovementMainController(String controllerName) {
        String name = normalize(controllerName);
        return slot(name, "pre_main") || slot(name, "main")
                || slot(name, "post_main");
    }

    /**
     * Direct HOLD playback owns the item-change clock, matching official YSM's
     * per-hand provider reset. Bedrock pre/HOLD/post-HOLD controllers keep their own
     * persistent state, but join the same temporary full-body hierarchy while their
     * resolved hand display is enabled.
     */
    private static boolean isItemSwitchController(
            String controllerName, Set<InteractionHand> enabledHands) {
        if (isMovementMainController(controllerName)) {
            return true;
        }
        return holdControllerHands(controllerName).stream()
                .anyMatch(enabledHands::contains);
    }

    /** Official pre/HOLD/post-HOLD controller slots associated with each hand. */
    private static Set<InteractionHand> holdControllerHands(String controllerName) {
        String name = normalize(controllerName);
        if (slot(name, "hold_mainhand")) {
            return Set.of(InteractionHand.MAIN_HAND);
        }
        if (slot(name, "hold_offhand")) {
            return Set.of(InteractionHand.OFF_HAND);
        }
        if (slot(name, "post_hold")) {
            return Set.of(InteractionHand.MAIN_HAND);
        }
        return slot(name, "pre_hold")
                ? Set.of(InteractionHand.MAIN_HAND, InteractionHand.OFF_HAND) : Set.of();
    }

    private static boolean slot(String controllerName, String slot) {
        return controllerName.equals(slot) || controllerName.endsWith('.' + slot);
    }

    private Set<InteractionHand> customFullBodyHands(
            List<AutomaticAnimationSelector.ActiveClip> automatic) {
        LinkedHashSet<InteractionHand> result = new LinkedHashSet<>();
        for (AutomaticAnimationSelector.ActiveClip active : automatic) {
            ClipProgram program = automaticClips.get(active.name());
            if (program == null || !customHeldItems.replacesBodyPose(active.name())
                    || automaticTime(program, active.elapsed()) < 0.0F) {
                continue;
            }
            InteractionHand hand = customHeldItems.replacementHand(active.name());
            if (hand != null) {
                result.add(hand);
            }
        }
        return Set.copyOf(result);
    }

    /** Enabled replacement hands whose authored geometry is active on this frame. */
    private Set<InteractionHand> ysmActiveReplacementHands(LivingEntity entity) {
        LinkedHashSet<InteractionHand> result = new LinkedHashSet<>();
        for (InteractionHand hand : InteractionHand.values()) {
            if (ClientHeldItemModelPreferences.usesYsm(entity, modelId, hand)
                    && customHeldItems.replaces(entity, hand)) {
                result.add(hand);
            }
        }
        return Set.copyOf(result);
    }

    /**
     * A model-authored replacement and its switch pose share the held-item model
     * preference. When Epic Fight keeps the ordinary item, the independent switch-
     * animation preference owns only the temporary official YSM body/locator pose.
     */
    private Set<InteractionHand> ysmHeldItemAnimationHands(LivingEntity entity) {
        LinkedHashSet<InteractionHand> result = new LinkedHashSet<>();
        for (InteractionHand hand : InteractionHand.values()) {
            boolean customReplacement =
                    customHeldItems.replacesHeldItemAtRest(entity, hand);
            boolean enabled = usesResolvedItemSwitchAnimation(
                    customReplacement,
                    ClientHeldItemModelPreferences.usesYsm(
                            entity, modelId, hand),
                    ClientHeldItemModelPreferences
                            .usesYsmSwitchAnimation(entity, modelId, hand));
            if (enabled) {
                result.add(hand);
            }
        }
        return Set.copyOf(result);
    }

    /**
     * Keeps the two user policies independent. A model-authored replacement and
     * its pose are one feature; an ordinary Epic Fight item can opt into only the
     * model's body/locator switch animation through the separate policy.
     */
    static boolean usesResolvedItemSwitchAnimation(
            boolean modelAuthoredReplacement,
            boolean ysmHeldItemModelEnabled,
            boolean ordinaryItemSwitchAnimationEnabled) {
        return modelAuthoredReplacement
                ? ysmHeldItemModelEnabled : ordinaryItemSwitchAnimationEnabled;
    }

    /**
     * Epic Fight renders an ordinary main-hand bow in its left Tool joint. Only a
     * HOLD clip without model-authored replacement roots enters this mirrored path;
     * custom YSM bows intentionally keep their established right-hand rendering.
     */
    private boolean isOrdinaryMainhandBowHold(String clipName) {
        String normalized = normalize(clipName);
        return (normalized.equals("hold_mainhand:bow")
                || normalized.equals("hold_mainhand$minecraft:bow"))
                && customHeldItems.replacementRoots(normalized).isEmpty();
    }

    @Nullable
    ItemSwitchPose updateItemSwitchPose(
            List<AutomaticAnimationSelector.ActiveClip> automatic,
            @Nullable AutomaticAnimationSelector.ActiveClip main,
            @Nullable MovementAnimationType movement,
            Set<InteractionHand> enabledHands, double now, boolean blocked,
            ItemSwitchState state) {
        LinkedHashSet<InteractionHand> restarted = new LinkedHashSet<>();
        for (AutomaticAnimationSelector.ActiveClip active : automatic) {
            InteractionHand hand = holdHand(active.name());
            if (hand != null && active.restarted()) {
                restarted.add(hand);
            }
        }
        observeItemSwitchEdges(automatic, restarted, now, blocked, state);
        return updateItemSwitchPose(automatic, List.of(), main, movement,
                enabledHands, now, blocked, state);
    }

    void observeItemSwitchEdges(
            List<AutomaticAnimationSelector.ActiveClip> automatic,
            Set<InteractionHand> changedHands, double now, boolean blocked,
            ItemSwitchState state) {
        if (blocked) {
            state.playbacks.clear();
            state.pending.clear();
            state.exitOwnershipUntil.clear();
            return;
        }
        EnumMap<InteractionHand, AutomaticAnimationSelector.ActiveClip> holds =
                new EnumMap<>(InteractionHand.class);
        for (AutomaticAnimationSelector.ActiveClip active : automatic) {
            InteractionHand hand = holdHand(active.name());
            if (hand != null) {
                holds.put(hand, active);
            }
        }
        for (InteractionHand hand : changedHands) {
            long generation = ++state.sequence;
            state.exitOwnershipUntil.remove(hand);
            state.pending.put(hand, new PendingItemSwitch(
                    now, now + ITEM_SWITCH_CONTROLLER_PENDING_SECONDS,
                    generation));
            AutomaticAnimationSelector.ActiveClip active = holds.get(hand);
            ClipProgram program = active == null ? null
                    : automaticClips.get(normalize(active.name()));
            if (program == null || program.itemSwitchDuration() <= EPSILON) {
                // Keep the edge pending even when the direct provider is absent or
                // only affects a non-major locator. A HOLD controller may own the
                // authored full-body transition on the following render frame.
                state.playbacks.remove(hand);
                continue;
            }
            double startedAt = now - Math.max(0.0D, active.elapsed());
            state.playbacks.put(hand, new ItemSwitchPlayback(
                    startedAt, startedAt + program.itemSwitchDuration(), generation));
        }
    }

    @Nullable
    ItemSwitchPose updateItemSwitchPose(
            List<AutomaticAnimationSelector.ActiveClip> automatic,
            List<AnimationControllerProgram.ActiveAnimation> observedControllers,
            @Nullable AutomaticAnimationSelector.ActiveClip main,
            @Nullable MovementAnimationType movement,
            Set<InteractionHand> enabledHands, double now, boolean blocked,
            ItemSwitchState state) {
        Set<String> currentControllerKeys = controllerProgram.activeKeys(
                observedControllers);
        Set<String> previousControllerKeys = state.activeControllerKeys;
        state.activeControllerKeys = currentControllerKeys;
        if (blocked || main == null) {
            // Epic Fight actions and other complete-pose owners cancel, rather than
            // suspend, an equip transition. A later item change creates a new one.
            state.playbacks.clear();
            state.pending.clear();
            state.exitOwnershipUntil.clear();
            return null;
        }

        LinkedHashSet<InteractionHand> controllerResolvedHands = new LinkedHashSet<>();
        for (AnimationControllerProgram.ActiveAnimation active : observedControllers) {
            if (previousControllerKeys.contains(active.instanceKey())) {
                continue;
            }
            float duration = controllerItemSwitchDuration(active);
            if (duration <= EPSILON) {
                continue;
            }
            for (InteractionHand hand : holdControllerHands(active.controllerName())) {
                PendingItemSwitch edge = state.pending.get(hand);
                if (edge == null || now > edge.expiresAt() + EPSILON) {
                    continue;
                }
                double startedAt = now - Math.max(0.0D, active.elapsed());
                double endsAt = startedAt + duration;
                ItemSwitchPlayback existing = state.playbacks.get(hand);
                if (existing == null || endsAt > existing.endsAt() + EPSILON) {
                    state.playbacks.put(hand, new ItemSwitchPlayback(
                            existing == null ? startedAt
                                    : Math.min(existing.startedAt(), startedAt),
                            endsAt, edge.generation()));
                }
                controllerResolvedHands.add(hand);
            }
        }
        // Every newly-entered HOLD controller in this sample contributes to the
        // maximum endpoint. Once a positive major clip was found, disarm the edge so
        // a later steady/idle state instance cannot reopen or extend the window.
        controllerResolvedHands.forEach(state.pending::remove);

        state.exitOwnershipUntil.entrySet().removeIf(entry ->
                now > entry.getValue() + EPSILON);
        state.playbacks.entrySet().removeIf(entry -> {
            ItemSwitchPlayback playback = entry.getValue();
            if (now <= playback.endsAt() + EPSILON) {
                return false;
            }
            double ownershipUntil = playback.endsAt()
                    + ITEM_SWITCH_EXIT_BLEND_SECONDS;
            if (now <= ownershipUntil + EPSILON) {
                state.exitOwnershipUntil.merge(
                        entry.getKey(), ownershipUntil, Math::max);
            }
            return true;
        });
        state.pending.entrySet().removeIf(entry ->
                now > entry.getValue().expiresAt() + EPSILON);

        LinkedHashSet<InteractionHand> activeHands = new LinkedHashSet<>();
        long activeGeneration = 1L;
        for (InteractionHand hand : InteractionHand.values()) {
            ItemSwitchPlayback playback = state.playbacks.get(hand);
            if (enabledHands.contains(hand) && playback != null) {
                activeHands.add(hand);
                activeGeneration = 31L * activeGeneration + playback.generation();
            }
        }
        // Keep still-valid observations while the resolved multiplayer display state
        // is unavailable/disabled. If an ON snapshot arrives before the authored
        // transition ends, evaluation catches up at the original HOLD clock.
        return activeHands.isEmpty() ? null : new ItemSwitchPose(
                main, movement, activeHands, enabledHands, activeGeneration);
    }

    private float controllerItemSwitchDuration(
            AnimationControllerProgram.ActiveAnimation active) {
        if (holdControllerHands(active.controllerName()).isEmpty()) {
            return 0.0F;
        }
        ClipProgram program = controllerClips.get(normalize(active.name()));
        return program == null ? 0.0F : program.itemSwitchDuration();
    }

    @Nullable
    private static InteractionHand holdHand(String clipName) {
        String normalized = normalize(clipName);
        if (normalized.startsWith("hold_offhand")) {
            return InteractionHand.OFF_HAND;
        }
        return normalized.startsWith("hold_mainhand")
                ? InteractionHand.MAIN_HAND : null;
    }

    /** Hands whose live use/rebound input is currently suppressing the hold controller. */
    Set<InteractionHand> customFullBodyInputHands(
            List<AutomaticAnimationSelector.ActiveClip> automatic) {
        LinkedHashSet<InteractionHand> result = new LinkedHashSet<>();
        for (AutomaticAnimationSelector.ActiveClip active : automatic) {
            if (!customHeldItems.replacesBodyPose(active.name())) {
                continue;
            }
            AnimationConditionMatcher.ItemAction action =
                    customHeldItems.clipAction(active.name());
            InteractionHand hand = customHeldItems.replacementHand(active.name());
            if (hand != null && (action == AnimationConditionMatcher.ItemAction.USE
                    || action == AnimationConditionMatcher.ItemAction.SWING)) {
                result.add(hand);
            }
        }
        return Set.copyOf(result);
    }

    /**
     * Keeps an authored bow release playing once after Epic Fight's short rebound signal ends.
     * The raw list remains the authority for pausing the hold controller; this retained list is
     * only the controller playback/whole-body ownership view.
     */
    List<AutomaticAnimationSelector.ActiveClip> updateFullBodySwingPlayback(
            List<AutomaticAnimationSelector.ActiveClip> rawAutomatic,
            double now, FullBodySwingState state) {
        AutomaticAnimationSelector.ActiveClip rawSwing = null;
        InteractionHand rawHand = null;
        ClipProgram rawProgram = null;
        for (AutomaticAnimationSelector.ActiveClip active : rawAutomatic) {
            if (!customHeldItems.replacesBodyPose(active.name())
                    || customHeldItems.clipAction(active.name())
                    != AnimationConditionMatcher.ItemAction.SWING) {
                continue;
            }
            ClipProgram program = automaticClips.get(active.name());
            InteractionHand hand = customHeldItems.replacementHand(active.name());
            if (program != null && hand != null) {
                rawSwing = active;
                rawHand = hand;
                rawProgram = program;
                break;
            }
        }

        FullBodySwingPlayback playback = state.playback;
        if (rawSwing == null) {
            state.rawSwingConsumed = false;
        } else if (rawSwing.restarted()) {
            state.rawSwingConsumed = false;
        }
        if (rawSwing != null && !state.rawSwingConsumed
                && (playback == null || rawSwing.restarted()
                || !playback.clipName().equals(normalize(rawSwing.name()))
                || playback.hand() != rawHand)) {
            double startedAt = now - Math.max(0.0D, rawSwing.elapsed());
            playback = new FullBodySwingPlayback(normalize(rawSwing.name()), rawHand,
                    startedAt, Math.max(0.0D, rawProgram.duration()));
            state.playback = playback;
            state.endpointPublished = false;
        }

        AutomaticAnimationSelector.ActiveClip retained = null;
        if (playback != null) {
            double elapsed = Math.max(0.0D, now - playback.startedAt());
            if (elapsed <= playback.duration() + EPSILON) {
                boolean restarted = rawSwing != null && rawSwing.restarted();
                retained = new AutomaticAnimationSelector.ActiveClip(
                        playback.clipName(), Math.min(elapsed, playback.duration()),
                        restarted);
                if (elapsed + EPSILON >= playback.duration()) {
                    state.endpointPublished = true;
                }
            } else if (!state.endpointPublished) {
                // A render sample may jump across the exact endpoint. Publish the
                // clamped final pose once so the ownership transition snapshots the
                // authored endpoint instead of an arbitrary earlier frame.
                retained = new AutomaticAnimationSelector.ActiveClip(
                        playback.clipName(), playback.duration(),
                        rawSwing != null && rawSwing.restarted());
                state.endpointPublished = true;
            } else {
                state.playback = null;
                state.endpointPublished = false;
                if (rawSwing != null) {
                    // A long Epic Fight rebound must not repeatedly restart an authored
                    // release that has already completed. A new raw restart token clears
                    // this guard above; raw disappearance clears it for the next action.
                    state.rawSwingConsumed = true;
                }
            }
        }

        if (rawSwing == null && retained == null) {
            return rawAutomatic;
        }
        List<AutomaticAnimationSelector.ActiveClip> result =
                new ArrayList<>(rawAutomatic.size() + (rawSwing == null ? 1 : 0));
        for (AutomaticAnimationSelector.ActiveClip active : rawAutomatic) {
            if (active == rawSwing) {
                if (retained != null) {
                    result.add(retained);
                }
            } else {
                result.add(active);
            }
        }
        if (rawSwing == null && retained != null) {
            result.add(retained);
        }
        return List.copyOf(result);
    }

    @Nullable
    private FullBodyEnding updateFullBodyEnding(
            RuntimeState state,
            List<AutomaticAnimationSelector.ActiveClip> automatic,
            double now) {
        FullBodyObservation current = null;
        for (AutomaticAnimationSelector.ActiveClip active : automatic) {
            ClipProgram program = automaticClips.get(active.name());
            if (program == null || !customHeldItems.replacesBodyPose(active.name())
                    || automaticTime(program, active.elapsed()) < 0.0F) {
                continue;
            }
            InteractionHand hand = customHeldItems.replacementHand(active.name());
            AnimationConditionMatcher.ItemAction action =
                    customHeldItems.clipAction(active.name());
            if (hand != null && action != null) {
                current = new FullBodyObservation(active, hand, action);
                break;
            }
        }

        FullBodyObservation previous = state.fullBodyObservation;
        if (current != null
                && current.action() == AnimationConditionMatcher.ItemAction.USE) {
            state.fullBodyEnding = null;
        } else if (previous != null && shouldStartFullBodyEnding(
                previous.action(), previous.hand(),
                current == null ? null : current.action(),
                current == null ? null : current.hand())) {
            // Item release can clear isUsingItem one render frame before Epic Fight
            // exposes the matching swing animation. Start the ending transition at
            // the disappearance of use instead of requiring both signals at once.
            PoseLayerSnapshot snapshot = state.fullBodyActionSnapshotClip.equals(
                    normalize(previous.active().name()))
                    ? state.fullBodyActionSnapshot.copy() : null;
            FullBodyCompositeSnapshot compositeSnapshot =
                    state.fullBodyCompositeSnapshot.copyIfValid();
            state.fullBodyEnding = new FullBodyEnding(previous.active(),
                    previous.hand(), now, 1.0F, snapshot, compositeSnapshot);
        }
        state.fullBodyObservation = current;

        FullBodyEnding ending = state.fullBodyEnding;
        if (ending == null) {
            return null;
        }
        float weight = fullBodyEndingWeight(now - ending.startedAt());
        if (weight <= 0.0F) {
            state.fullBodyEnding = null;
            return null;
        }
        FullBodyEnding updated = new FullBodyEnding(ending.active(), ending.hand(),
                ending.startedAt(), weight, ending.snapshot(),
                ending.compositeSnapshot());
        state.fullBodyEnding = updated;
        return updated;
    }

    static boolean shouldStartFullBodyEnding(
            AnimationConditionMatcher.ItemAction previousAction,
            InteractionHand previousHand,
            @Nullable AnimationConditionMatcher.ItemAction currentAction,
            @Nullable InteractionHand currentHand) {
        if (previousAction == AnimationConditionMatcher.ItemAction.USE) {
            return currentAction == null
                    || currentAction == AnimationConditionMatcher.ItemAction.SWING
                    && previousHand == currentHand;
        }
        return previousAction == AnimationConditionMatcher.ItemAction.SWING
                && currentAction == null;
    }

    static float fullBodyEndingWeight(double elapsedSeconds) {
        return (float) Math.max(0.0D, Math.min(1.0D,
                1.0D - elapsedSeconds / FULL_BODY_END_TRANSITION_SECONDS));
    }

    /**
     * Keeps the model-authored draw/release pose aimed while it is evaluated live. Once
     * the final YSM skin has been frozen, its blend target must use ordinary head yaw;
     * changing that target only after the three-tick blend would create a final-frame snap.
     */
    static boolean shouldUseCustomBowHeadYaw(boolean liveFullBodyPose,
                                             boolean endingActive,
                                             boolean endingUsesCompositeSnapshot) {
        return liveFullBodyPose || endingActive && !endingUsesCompositeSnapshot;
    }

    static boolean compatOwnsRouletteSound(boolean playerEntity) {
        return !playerEntity;
    }

    private boolean isPausedHoldClip(String clipName,
                                     Set<InteractionHand> fullBodyHands) {
        for (InteractionHand hand : fullBodyHands) {
            if (customHeldItems.isHoldClipForHand(clipName, hand)) {
                return true;
            }
        }
        return false;
    }

    private boolean evaluateProgram(ClipProgram program, float localTime,
                                    ExpressionEngine.Environment environment,
                                    RuntimeState runtimeState, PoseScratch pose,
                                    ApplyMode applyMode, EvaluationScratch scratch) {
        return evaluateProgram(program, localTime, environment, runtimeState, pose,
                applyMode, 1.0F, false, program.clip().name(), true, scratch, null);
    }

    private boolean evaluateProgram(ClipProgram program, float localTime,
                                    ExpressionEngine.Environment environment,
                                    RuntimeState runtimeState, PoseScratch pose,
                                    ApplyMode applyMode, EvaluationScratch scratch,
                                    @Nullable PoseLayerSnapshot capture) {
        return evaluateProgram(program, localTime, environment, runtimeState, pose,
                applyMode, 1.0F, false, program.clip().name(), true, scratch, capture,
                PoseTransform.NONE);
    }

    private boolean evaluateProgram(ClipProgram program, float localTime,
                                    ExpressionEngine.Environment environment,
                                    RuntimeState runtimeState, PoseScratch pose,
                                    ApplyMode applyMode, EvaluationScratch scratch,
                                    @Nullable PoseLayerSnapshot capture,
                                    PoseTransform transform) {
        return evaluateProgram(program, localTime, environment, runtimeState, pose,
                applyMode, 1.0F, false, program.clip().name(), true, scratch, capture,
                transform);
    }

    private boolean evaluateProgram(ClipProgram program, float localTime,
                                    ExpressionEngine.Environment environment,
                                    RuntimeState runtimeState, PoseScratch pose,
                                    ApplyMode applyMode, float externalWeight,
                                    boolean shortestPath, String timelineKey,
                                    boolean soundOutputEnabled, EvaluationScratch scratch) {
        return evaluateProgram(program, localTime, environment, runtimeState, pose,
                applyMode, externalWeight, shortestPath, timelineKey,
                soundOutputEnabled, scratch, null, PoseTransform.NONE);
    }

    private boolean evaluateProgram(ClipProgram program, float localTime,
                                    ExpressionEngine.Environment environment,
                                    RuntimeState runtimeState, PoseScratch pose,
                                    ApplyMode applyMode, float externalWeight,
                                    boolean shortestPath, String timelineKey,
                                    boolean soundOutputEnabled, EvaluationScratch scratch,
                                    @Nullable PoseLayerSnapshot capture) {
        return evaluateProgram(program, localTime, environment, runtimeState, pose,
                applyMode, externalWeight, shortestPath, timelineKey,
                soundOutputEnabled, scratch, capture, PoseTransform.NONE);
    }

    private boolean evaluateProgram(ClipProgram program, float localTime,
                                    ExpressionEngine.Environment environment,
                                    RuntimeState runtimeState, PoseScratch pose,
                                    ApplyMode applyMode, float externalWeight,
                                    boolean shortestPath, String timelineKey,
                                    boolean soundOutputEnabled, EvaluationScratch scratch,
                                    @Nullable PoseLayerSnapshot capture,
                                    PoseTransform transform) {
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
            int visibilityIndex = transform == PoseTransform.MIRROR_X
                    ? bone.mirroredVisibilityIndex() : bone.visibilityIndex();
            int auxiliaryIndex = bone.epicFightOwned()
                    && applyMode != ApplyMode.FULL_BODY
                    ? -1 : transform == PoseTransform.MIRROR_X
                    ? bone.mirroredAuxiliaryIndex() : bone.auxiliaryIndex();
            if (tracks.rotation() != null) {
                sample(tracks.rotation(), localTime, environment, scratch.sample, scratch);
                if (auxiliaryIndex >= 0) {
                    appliedPose = true;
                    int auxiliary = auxiliaryIndex;
                    float[] target = new float[]{
                            radians(-finite(scratch.sample[0], 0.0F)),
                            radians(-finite(scratch.sample[1], 0.0F)),
                            radians(finite(scratch.sample[2], 0.0F))};
                    if (transform == PoseTransform.MIRROR_X) {
                        // M * Rz(z) * Ry(y) * Rx(x) * M, M=diag(-1,1,1),
                        // is Rz(-z) * Ry(-y) * Rx(x). This exact reflection avoids
                        // handedness-dependent elbow twists from naïvely negating X.
                        target[1] = -target[1];
                        target[2] = -target[2];
                    }
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
                    pose.hasRotation[auxiliary] = true;
                    if (capture != null) {
                        capture.captureRotation(auxiliary, pose.rotations[auxiliary]);
                    }
                }
            }
            if (tracks.position() != null) {
                sample(tracks.position(), localTime, environment, scratch.sample, scratch);
                if (auxiliaryIndex >= 0) {
                    appliedPose = true;
                    int auxiliary = auxiliaryIndex;
                    float[] target = new float[]{
                            -finite(scratch.sample[0], 0.0F) / 16.0F,
                            finite(scratch.sample[1], 0.0F) / 16.0F,
                            finite(scratch.sample[2], 0.0F) / 16.0F};
                    if (transform == PoseTransform.MIRROR_X) {
                        target[0] = -target[0];
                    }
                    for (int axis = 0; axis < 3; axis++) {
                        float previous = applyMode != ApplyMode.PARALLEL
                                && pose.hasPosition[auxiliary]
                                ? pose.positions[auxiliary][axis] : 0.0F;
                        pose.positions[auxiliary][axis] = previous
                                + (target[axis] - previous) * blendWeight;
                    }
                    pose.hasPosition[auxiliary] = true;
                    if (capture != null) {
                        capture.capturePosition(auxiliary, pose.positions[auxiliary]);
                    }
                }
            }
            if (tracks.scale() != null) {
                sample(tracks.scale(), localTime, environment, scratch.sample, scratch);
                if (visibilityIndex >= 0) {
                    for (int axis = 0; axis < 3; axis++) {
                        float previous = applyMode != ApplyMode.PARALLEL
                                && scratch.hasVisibilityScale[visibilityIndex]
                                ? scratch.visibilityScales[visibilityIndex][axis] : 1.0F;
                        float target = finite(scratch.sample[axis], 1.0F);
                        float value = finite(previous
                                + (target - previous) * blendWeight, 1.0F);
                        scratch.visibilityScales[visibilityIndex][axis] = value;
                        if (auxiliaryIndex >= 0) {
                            appliedPose = true;
                            pose.scales[auxiliaryIndex][axis] = value;
                        }
                    }
                    scratch.hasVisibilityScale[visibilityIndex] = true;
                    if (capture != null) {
                        capture.captureVisibilityScale(visibilityIndex,
                                scratch.visibilityScales[visibilityIndex]);
                    }
                    if (auxiliaryIndex >= 0) {
                        pose.hasScale[auxiliaryIndex] = true;
                        if (capture != null) {
                            capture.captureScale(auxiliaryIndex,
                                    pose.scales[auxiliaryIndex]);
                        }
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

    static boolean rouletteTimelineChanged(
            String previousAnimation, long previousGeneration,
            OfficialRoamingVariables.RouletteState state) {
        String nextAnimation = state.playing() ? state.animationName() : "";
        return !nextAnimation.isBlank()
                && (!nextAnimation.equals(previousAnimation)
                || state.generation() != previousGeneration);
    }

    private void resetScratch(EvaluationScratch scratch) {
        scratch.parallelPose.reset();
        scratch.wholeModelPose.reset();
        scratch.heldItemPose.reset();
        scratch.replaceEpicFightPose = false;
        scratch.fullBodyBlendSource = null;
        scratch.fullBodyBlendWeight = 0.0F;
        scratch.movementPoseKey = null;
        scratch.mirrorOrdinaryMainhandBowSwitch = false;
        scratch.itemSwitchHands.clear();
        Arrays.fill(scratch.replaceEpicFightAnchors, false);
        Arrays.fill(scratch.suppressParallelDeltas, false);
        Arrays.fill(scratch.heldItemAnchorJoints, -1);
        Arrays.fill(scratch.heldItemRelativePaths, null);
        Arrays.fill(scratch.heldItemBindRebases, null);
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
            Matrix4f sourceDelta = pose.chainDelta[auxiliary];
            int[] relativePath = pose == scratch.heldItemPose
                    ? scratch.heldItemRelativePaths[auxiliary] : null;
            if (relativePath != null) {
                Matrix4f bindRebase = scratch.heldItemBindRebases[auxiliary];
                if (bindRebase == null) {
                    scratch.attachmentDelta.identity();
                } else {
                    scratch.attachmentDelta.set(bindRebase);
                }
                for (int pathIndex : relativePath) {
                    scratch.attachmentDelta.mul(pose.deltaModel[pathIndex]);
                }
                sourceDelta = scratch.attachmentDelta;
            }
            scratch.scaledDelta.identity().scale(
                            horizontalScale, verticalScale, horizontalScale)
                    .mul(sourceDelta)
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
        private final PoseScratch heldItemPose;
        private final boolean[] replaceEpicFightAnchors;
        private final boolean[] suppressParallelDeltas;
        private final int[] heldItemAnchorJoints;
        private final int[][] heldItemRelativePaths;
        private final Matrix4f[] heldItemBindRebases;
        private final Matrix4f scaledDelta = new Matrix4f();
        private final Matrix4f attachmentDelta = new Matrix4f();
        private final Set<String> hiddenBones = new LinkedHashSet<>();
        private final Set<String> hiddenView = Collections.unmodifiableSet(hiddenBones);
        private final Set<InteractionHand> itemSwitchHands =
                java.util.EnumSet.noneOf(InteractionHand.class);
        private final double[] sample = new double[3];
        private final double[] p0 = new double[3];
        private final double[] p1 = new double[3];
        private final double[] p2 = new double[3];
        private final double[] p3 = new double[3];
        private boolean replaceEpicFightPose;
        @Nullable
        private OpenMatrix4f[] fullBodyBlendSource;
        private float fullBodyBlendWeight;
        @Nullable
        private String movementPoseKey;
        private boolean mirrorOrdinaryMainhandBowSwitch;

        private EvaluationScratch(int visibilityCount, int auxiliaryCount) {
            visibilityScales = new float[visibilityCount][3];
            hasVisibilityScale = new boolean[visibilityCount];
            effectiveScale = new float[visibilityCount];
            parallelPose = new PoseScratch(auxiliaryCount);
            wholeModelPose = new PoseScratch(auxiliaryCount);
            heldItemPose = new PoseScratch(auxiliaryCount);
            replaceEpicFightAnchors = new boolean[auxiliaryCount];
            suppressParallelDeltas = new boolean[auxiliaryCount];
            heldItemAnchorJoints = new int[auxiliaryCount];
            heldItemRelativePaths = new int[auxiliaryCount][];
            heldItemBindRebases = new Matrix4f[auxiliaryCount];
            Arrays.fill(heldItemAnchorJoints, -1);
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
                // Official models also use non-geometry "molang" bones as ordered
                // expression runners. Retain them, but never give them a pose index.
                bones.add(boneProgram(name, tracks, visibilityByName));
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
                    || name.startsWith("pre_parallel") || name.startsWith("parallel")) {
                continue;
            }
            Set<String> replacementRoots = customHeldItems.replacementRoots(name);
            Set<String> epicItemEffectRoots = customHeldItems.epicItemEffectRoots(name);
            boolean customReplacement = !replacementRoots.isEmpty();
            ClipProgram program = compileClip(clip, visibilityByName);
            if (program != null) {
                if (customReplacement) {
                    Set<Integer> propIndices = replacementIndices(
                            clip, replacementRoots, false);
                    Map<Integer, HeldAttachment> attachments = heldPropAttachments(
                            replacementRoots, customHeldItems.replacementHand(name));
                    program = withReplacementIndices(program,
                            attachments.keySet(), propIndices, attachments);
                } else if (!epicItemEffectRoots.isEmpty()) {
                    Map<Integer, HeldAttachment> attachments =
                            epicBowEffectAttachments(epicItemEffectRoots);
                    if (!attachments.isEmpty()) {
                        // This geometry augments Epic Fight's retained bow. It needs the
                        // left Tool seam, but it must not suppress the item or body pose.
                        // Suppress only its separate pre_parallel matrix so the use clip's
                        // visible scale is not multiplied by the initialization scale zero.
                        program = withReplacementIndices(program,
                                attachments.keySet(), attachments.keySet(), attachments);
                    }
                }
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
            ClipProgram program = compileClip(clip, visibilityByName);
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
            if (name.startsWith("pre_parallel") || name.startsWith("parallel")) {
                continue;
            }
            Set<String> replacementRoots = affectedReplacementRoots(
                    clip, customHeldItems.allReplacementRoots());
            ClipProgram program = compileClip(clip, visibilityByName);
            if (program != null) {
                if (!replacementRoots.isEmpty()) {
                    Set<Integer> propIndices = replacementIndices(
                            clip, replacementRoots, false);
                    program = withReplacementIndices(
                            program, propIndices, propIndices, Map.of());
                }
                result.putIfAbsent(name, program);
            }
        }
        return Map.copyOf(result);
    }

    private Set<String> affectedReplacementRoots(AnimationClip clip,
                                                 Set<String> candidates) {
        Set<GeometryDocument.Bone> tracked = Collections.newSetFromMap(
                new IdentityHashMap<>());
        for (String name : clip.boneTracks().keySet()) {
            AuxiliaryBoneLayout.Entry entry = layout.entryForBoneName(name);
            if (entry != null) {
                tracked.add(entry.bone());
            }
        }
        if (tracked.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String candidate : candidates) {
            AuxiliaryBoneLayout.Entry root = layout.entryForBoneName(candidate);
            if (root == null) {
                continue;
            }
            for (GeometryDocument.Bone trackedBone : tracked) {
                if (isAncestor(trackedBone, root.bone())
                        || isAncestor(root.bone(), trackedBone)) {
                    result.add(candidate);
                    break;
                }
            }
        }
        return Set.copyOf(result);
    }

    private static boolean isAncestor(GeometryDocument.Bone ancestor,
                                      GeometryDocument.Bone bone) {
        for (GeometryDocument.Bone current = bone; current != null;
             current = current.parent()) {
            if (current == ancestor) {
                return true;
            }
        }
        return false;
    }

    private ClipProgram compileClip(AnimationClip clip,
                                    Map<String, Integer> visibilityByName) {
        List<BoneProgram> bones = new ArrayList<>();
        clip.boneTracks().forEach((name, tracks) ->
                bones.add(boneProgram(name, tracks, visibilityByName)));
        return bones.isEmpty() && clip.timeline().isEmpty() && clip.soundEffects().isEmpty()
                && clip.particleEffects().isEmpty()
                ? null
                : clipProgram(clip, bones);
    }

    private BoneProgram boneProgram(
            String name, AnimationClip.BoneTracks tracks,
            Map<String, Integer> visibilityByName) {
        String normalized = normalize(name);
        Integer visibility = visibilityByName.get(normalized);
        AuxiliaryBoneLayout.Entry pose = layout.entryForBoneName(name);
        String mirroredName = mirroredBoneName(normalized);
        Integer mirroredVisibility = mirroredName == null
                ? visibility : visibilityByName.get(mirroredName);
        AuxiliaryBoneLayout.Entry mirroredPose = mirroredName == null
                ? pose : layout.entryForBoneName(mirroredName);
        return new BoneProgram(
                visibility == null ? -1 : visibility,
                pose == null ? -1 : pose.auxiliaryIndex(),
                mirroredVisibility == null ? -1 : mirroredVisibility,
                mirroredPose == null ? -1 : mirroredPose.auxiliaryIndex(),
                pose != null && epicFightPoseControls.contains(pose.bone()),
                tracks);
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
        return new ClipProgram(clip, duration(clip), itemSwitchDuration(bones),
                List.copyOf(bones),
                Set.copyOf(variables), Set.copyOf(queries), safe[0],
                Set.of(), Set.of(), Map.of());
    }

    private static ClipProgram withReplacementIndices(ClipProgram program,
                                                       Set<Integer> indices,
                                                       Set<Integer> propIndices,
                                                       Map<Integer, HeldAttachment> attachments) {
        return new ClipProgram(program.clip(), program.duration(),
                program.itemSwitchDuration(), program.bones(),
                program.variableSlots(), program.querySlots(), program.asyncSafe(),
                Set.copyOf(indices), Set.copyOf(propIndices), Map.copyOf(attachments));
    }

    /**
     * Attaches a model-authored prop to Epic Fight's live Tool joint while retaining only
     * the YSM animation authored below the corresponding hand control. Arm and forearm
     * tracks must not be applied a second time behind Epic Fight's combat pose.
     */
    private Map<Integer, HeldAttachment> heldPropAttachments(
            Set<String> propRoots, InteractionHand hand) {
        int toolJoint = hand == InteractionHand.OFF_HAND
                ? HumanoidRig.LEFT_TOOL : HumanoidRig.RIGHT_TOOL;
        return heldAttachments(propRoots, toolJoint, false);
    }

    /** Epic Fight renders two-handed bows from its off-hand (left Tool) correction. */
    private Map<Integer, HeldAttachment> epicBowEffectAttachments(Set<String> effectRoots) {
        return heldAttachments(effectRoots, HumanoidRig.LEFT_TOOL, true);
    }

    private Map<Integer, HeldAttachment> heldAttachments(
            Set<String> roots, int toolJoint, boolean rebaseBindPosition) {
        Map<Integer, HeldAttachment> result = new java.util.LinkedHashMap<>();
        for (String rootName : roots) {
            AuxiliaryBoneLayout.Entry root = layout.entryForBoneName(rootName);
            if (root == null) {
                continue;
            }
            Matrix4f bindRebase = rebaseBindPosition
                    ? attachmentBindRebase(toolJointForAnchor(root.anchorJoint()), toolJoint)
                    : new Matrix4f();
            if (bindRebase == null) {
                // Without both official model pivots, retaining the authored hand seam
                // is safer than moving an effect through an arbitrary identity Tool pose.
                continue;
            }
            GeometryDocument.Bone seam = nearestMajorAncestor(root.bone());
            AuxiliaryBoneLayout.Entry seamEntry = seam == null
                    ? null : layout.entryForBoneName(seam.name());
            int seamIndex = seamEntry == null ? -1 : seamEntry.auxiliaryIndex();
            for (AuxiliaryBoneLayout.Entry entry : layout.entries()) {
                if (isAncestor(root.bone(), entry.bone())) {
                    result.put(entry.auxiliaryIndex(),
                            new HeldAttachment(toolJoint,
                                    relativePath(seamIndex, entry.auxiliaryIndex()),
                                    bindRebase));
                }
            }
        }
        return Map.copyOf(result);
    }

    private static int toolJointForAnchor(int joint) {
        return joint == HumanoidRig.RIGHT_HAND ? HumanoidRig.RIGHT_TOOL
                : joint == HumanoidRig.LEFT_HAND ? HumanoidRig.LEFT_TOOL : joint;
    }

    @Nullable
    private Matrix4f attachmentBindRebase(int sourceJoint, int targetJoint) {
        Vector3f source = layout.jointPivot(sourceJoint);
        Vector3f target = layout.jointPivot(targetJoint);
        if (source == null || target == null) {
            return null;
        }
        float x = (target.x() - source.x()) / horizontalScale;
        float y = (target.y() - source.y()) / verticalScale;
        float z = (target.z() - source.z()) / horizontalScale;
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
            return null;
        }
        return new Matrix4f().translation(x, y, z);
    }

    /** Parent-first auxiliary path after the seam, including the selected entry. */
    private int[] relativePath(int seamIndex, int auxiliaryIndex) {
        ArrayDeque<Integer> reversed = new ArrayDeque<>();
        int current = auxiliaryIndex;
        while (current >= 0 && current != seamIndex) {
            reversed.push(current);
            current = layout.entries().get(current).parentAuxiliaryIndex();
        }
        if (seamIndex >= 0 && current != seamIndex) {
            return new int[]{auxiliaryIndex};
        }
        int[] result = new int[reversed.size()];
        int index = 0;
        while (!reversed.isEmpty()) {
            result[index++] = reversed.pop();
        }
        return result;
    }

    private static GeometryDocument.Bone nearestMajorAncestor(
            GeometryDocument.Bone bone) {
        for (GeometryDocument.Bone parent = bone.parent(); parent != null;
             parent = parent.parent()) {
            if (HumanoidRig.isMajorBone(parent)) {
                return parent;
            }
        }
        return null;
    }

    private Set<Integer> replacementIndices(AnimationClip clip, Set<String> propRoots,
                                            boolean includeTrackedBody) {
        Set<GeometryDocument.Bone> roots = Collections.newSetFromMap(
                new IdentityHashMap<>());
        for (String root : propRoots) {
            AuxiliaryBoneLayout.Entry entry = layout.entryForBoneName(root);
            if (entry != null) {
                roots.add(entry.bone());
            }
        }
        if (includeTrackedBody) {
            for (String name : clip.boneTracks().keySet()) {
                AuxiliaryBoneLayout.Entry entry = layout.entryForBoneName(name);
                if (entry != null && HumanoidRig.isMajorBone(entry.bone())) {
                    roots.add(entry.bone());
                }
            }
        }
        LinkedHashSet<Integer> result = new LinkedHashSet<>();
        for (AuxiliaryBoneLayout.Entry entry : layout.entries()) {
            if (hasAncestor(entry.bone(), roots)) {
                result.add(entry.auxiliaryIndex());
            }
        }
        return Set.copyOf(result);
    }

    private static boolean hasAncestor(GeometryDocument.Bone bone,
                                       Set<GeometryDocument.Bone> ancestors) {
        for (GeometryDocument.Bone current = bone; current != null;
             current = current.parent()) {
            if (ancestors.contains(current)) {
                return true;
            }
        }
        return false;
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

    /**
     * End of the authored transition on bones normally owned by Epic Fight. Model
     * packs commonly declare a 1000-second HOLD clip only to freeze its last frame;
     * using that declared duration would revoke Epic Fight pose ownership for minutes.
     */
    private static float itemSwitchDuration(List<BoneProgram> bones) {
        float result = 0.0F;
        for (BoneProgram bone : bones) {
            if (!bone.epicFightOwned()) {
                continue;
            }
            result = Math.max(result, lastTime(bone.tracks().rotation()));
            result = Math.max(result, lastTime(bone.tracks().position()));
            result = Math.max(result, lastTime(bone.tracks().scale()));
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

    /**
     * Official YSM keeps the selected automatic locomotion state alive until the
     * entity changes state. Some built-in locomotion clips (including 05_magical's
     * run) omit Bedrock's loop flag even though the main-state player cycles them.
     */
    private static float movementTime(ClipProgram program, double elapsed) {
        if (program.duration() <= EPSILON) {
            return 0.0F;
        }
        return program.clip().playback() == AnimationClip.Playback.HOLD_LAST_FRAME
                ? (float) Math.min(elapsed, program.duration())
                : (float) (elapsed % program.duration());
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

    /**
     * Returns the opposite side's conventional YSM bone name. A missing counterpart
     * remains missing instead of redirecting a right-side track back onto the right
     * joint; that fail-closed behavior prevents a partially mirrored limb.
     */
    @Nullable
    private static String mirroredBoneName(String normalizedName) {
        if (normalizedName == null || normalizedName.isEmpty()) {
            return null;
        }
        boolean right = normalizedName.contains("right");
        boolean left = normalizedName.contains("left");
        if (!right && !left) {
            return null;
        }
        return normalizedName.replace("right", "#ysm_mirror_side#")
                .replace("left", "right")
                .replace("#ysm_mirror_side#", "left");
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
        private long rouletteGeneration = Long.MIN_VALUE;
        private String rouletteClip = "";
        private String reportedRoulette = "";
        private boolean rouletteSoundOutputEnabled;
        private double rouletteStartedAt;
        private boolean rouletteStopSent;
        private Frame publishedFrame;
        private EvaluationScratch publishedScratch;
        private EvaluationScratch spareWorkerScratch;
        private CompletableFuture<AsyncResult> pendingEvaluation;
        private double lastScheduledAt = Double.NEGATIVE_INFINITY;
        private FullBodyObservation fullBodyObservation;
        private FullBodyEnding fullBodyEnding;
        private final PoseLayerSnapshot fullBodyActionSnapshot;
        private final FullBodyCompositeSnapshot fullBodyCompositeSnapshot;
        private String fullBodyActionSnapshotClip = "";
        private final FullBodySwingState fullBodySwingState =
                new FullBodySwingState();
        private final ItemSwitchState itemSwitchState = new ItemSwitchState();
        private Set<InteractionHand> fullBodyInputHands = Set.of();
        private Set<InteractionHand> attackSoundRouteHands = Set.of();
        private String reportedFullBody = "";

        private RuntimeState(LivingEntity entity, String modelId,
                             EvaluationScratch scratch) {
            environment = new EntityAnimationEnvironment(entity, variables, assigned, modelId);
            this.scratch = scratch;
            fullBodyActionSnapshot = new PoseLayerSnapshot(
                    scratch.wholeModelPose.positions.length,
                    scratch.visibilityScales.length);
            fullBodyCompositeSnapshot = new FullBodyCompositeSnapshot(
                    scratch.wholeModelPose.positions.length);
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
            rouletteGeneration = Long.MIN_VALUE;
            rouletteClip = "";
            reportedRoulette = "";
            rouletteSoundOutputEnabled = false;
            rouletteStartedAt = now;
            rouletteStopSent = false;
            publishedFrame = null;
            publishedScratch = null;
            spareWorkerScratch = null;
            lastScheduledAt = Double.NEGATIVE_INFINITY;
            fullBodyObservation = null;
            fullBodyEnding = null;
            fullBodyActionSnapshot.reset();
            fullBodyCompositeSnapshot.reset();
            fullBodyActionSnapshotClip = "";
            fullBodySwingState.reset();
            itemSwitchState.reset();
            fullBodyInputHands = Set.of();
            attackSoundRouteHands = Set.of();
            reportedFullBody = "";
        }

        private void restrictFullBodyHands(Set<InteractionHand> enabledHands) {
            boolean disabled = fullBodyObservation != null
                    && !enabledHands.contains(fullBodyObservation.hand())
                    || fullBodyEnding != null
                    && !enabledHands.contains(fullBodyEnding.hand())
                    || fullBodySwingState.playback != null
                    && !enabledHands.contains(fullBodySwingState.playback.hand());
            if (!disabled) {
                return;
            }
            fullBodyObservation = null;
            fullBodyEnding = null;
            fullBodyActionSnapshot.reset();
            fullBodyCompositeSnapshot.reset();
            fullBodyActionSnapshotClip = "";
            fullBodySwingState.reset();
            fullBodyInputHands = Set.of();
        }

        private void reportFullBody(
                LivingEntity entity,
                List<AutomaticAnimationSelector.ActiveClip> automatic,
                @Nullable FullBodyEnding ending,
                Set<InteractionHand> hands,
                float partialTick,
                @Nullable Float visualFacingYaw,
                @Nullable Float epicModelYaw) {
            boolean active = !hands.isEmpty() || ending != null;
            String clips = active ? automatic.stream()
                    .map(AutomaticAnimationSelector.ActiveClip::name)
                    .toList().toString() : "";
            String endingName = ending == null ? "" : ending.active().name();
            String signature = active
                    ? clips + '|' + endingName + '|' + hands : "";
            if (signature.equals(reportedFullBody)) {
                return;
            }
            boolean wasActive = !reportedFullBody.isEmpty();
            reportedFullBody = signature;
            if (!active) {
                if (wasActive) {
                    CompatMod.LOG.debug(
                            "YSM-EF Compat: custom full-body bow pose ended for '{}'",
                            entity.getScoreboardName());
                }
                return;
            }
            CompatMod.LOG.debug(
                    "YSM-EF Compat: custom full-body bow pose player='{}' clips={} hands={} ending='{}' savedActionPose={} visualYaw={} epicModelYaw={} relativeYaw={} bodyYaw={} headYaw={} viewYaw={}",
                    entity.getScoreboardName(), clips, hands, endingName,
                    ending != null && ending.snapshot() != null,
                    visualFacingYaw,
                    epicModelYaw,
                    visualFacingYaw == null || epicModelYaw == null
                            ? null : EntityAnimationEnvironment.customBowRelativeHeadYaw(
                            entity.getYRot(), visualFacingYaw,
                            entity instanceof net.minecraft.client.player.LocalPlayer,
                            epicModelYaw),
                    Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot),
                    Mth.lerp(partialTick, entity.yHeadRotO, entity.yHeadRot),
                    entity.getViewYRot(partialTick));
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
                rouletteGeneration = state.generation();
                rouletteStartedAt = now;
                rouletteStopSent = false;
                return -1.0D;
            }
            if (rouletteTimelineChanged(
                    rouletteAnimation, rouletteGeneration, state)) {
                clearRouletteTimeline();
                rouletteAnimation = name;
                rouletteGeneration = state.generation();
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
        ClientAttackSoundRouter.clear();
    }

    public static void releaseSoundOutput(String modelId) {
        ClientSoundOutput.stopModel(modelId);
    }
}
