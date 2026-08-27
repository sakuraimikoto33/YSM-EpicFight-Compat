package net.okitsu.ysmepicfightcompat.mesh;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import yesman.epicfight.api.utils.math.OpenMatrix4f;

import javax.annotation.Nullable;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

/** Smooths YSM pose ownership changes without delaying Epic Fight actions. */
final class MovementPoseTransition {
    static final double DURATION_TICKS = 3.0D;
    private static final double TIME_EPSILON = 1.0E-6D;
    private static final double MAX_UNOBSERVED_TICKS = 20.0D;

    private final Map<LivingEntity, EntityChannels> channels = new WeakHashMap<>();

    Set<InteractionHand> apply(
            LivingEntity entity, boolean firstPerson, double animationTicks,
            @Nullable String movementPoseKey, Set<InteractionHand> itemSwitchHands,
            boolean epicFightActionActive,
            AuxiliaryPoseMatrices poses, OpenMatrix4f[] complete) {
        if (entity == null || poses == null || complete == null) {
            return Set.of();
        }
        EntityChannels entityChannels = channels.computeIfAbsent(
                entity, ignored -> new EntityChannels());
        Channel channel = firstPerson
                ? entityChannels.firstPerson : entityChannels.thirdPerson;
        return channel.apply(animationTicks,
                epicFightActionActive ? null : movementPoseKey,
                itemSwitchHands, epicFightActionActive, poses, complete);
    }

    private static final class EntityChannels {
        private final Channel firstPerson = new Channel();
        private final Channel thirdPerson = new Channel();
    }

    /** Package-visible deterministic state machine for focused matrix tests. */
    static final class Channel {
        @Nullable
        private String previousMovementPoseKey;
        @Nullable
        private OpenMatrix4f[] lastOutput;
        @Nullable
        private OpenMatrix4f[] transitionSource;
        private Set<InteractionHand> previousItemSwitchHands = Set.of();
        private Set<InteractionHand> transitionItemSwitchHands = Set.of();
        private double transitionStart = Double.NaN;
        private double previousAnimationTicks = Double.NaN;

        void apply(double animationTicks, @Nullable String movementPoseKey,
                   boolean epicFightActionActive,
                   AuxiliaryPoseMatrices poses, OpenMatrix4f[] complete) {
            apply(animationTicks, movementPoseKey, Set.of(),
                    epicFightActionActive, poses, complete);
        }

        Set<InteractionHand> apply(
                double animationTicks, @Nullable String movementPoseKey,
                Set<InteractionHand> itemSwitchHands,
                boolean epicFightActionActive,
                AuxiliaryPoseMatrices poses, OpenMatrix4f[] complete) {
            if (!Double.isFinite(animationTicks) || !valid(complete)) {
                reset();
                return Set.of();
            }
            Set<InteractionHand> currentItemSwitchHands = itemSwitchHands == null
                    ? Set.of() : Set.copyOf(itemSwitchHands);
            if (Double.isFinite(previousAnimationTicks)
                    && (animationTicks + TIME_EPSILON < previousAnimationTicks
                    || animationTicks - previousAnimationTicks > MAX_UNOBSERVED_TICKS)) {
                reset();
            }
            if (lastOutput != null && lastOutput.length != complete.length) {
                reset();
            }

            if (epicFightActionActive) {
                // Hit, guard, dodge, aim and every other Epic Fight action must take
                // over on its first frame, even if an ordinary ownership blend was active.
                transitionSource = null;
                transitionItemSwitchHands = Set.of();
                transitionStart = Double.NaN;
            } else if (!Objects.equals(previousMovementPoseKey, movementPoseKey)) {
                if (lastOutput != null) {
                    // Entering or leaving YSM ownership, or switching between two
                    // authored movements, starts from the exact prior displayed pose.
                    transitionSource = copyOf(lastOutput);
                    transitionItemSwitchHands = union(
                            previousItemSwitchHands, currentItemSwitchHands);
                    transitionStart = animationTicks;
                } else {
                    transitionSource = null;
                    transitionItemSwitchHands = Set.of();
                    transitionStart = Double.NaN;
                }
            }
            previousMovementPoseKey = movementPoseKey;

            Set<InteractionHand> displayedItemSwitchHands = currentItemSwitchHands;
            if (!epicFightActionActive && transitionSource != null) {
                double elapsed = Math.max(0.0D, animationTicks - transitionStart);
                if (elapsed < DURATION_TICKS) {
                    float sourceWeight = (float) (1.0D - elapsed / DURATION_TICKS);
                    poses.blendFromComplete(complete, transitionSource, sourceWeight);
                    displayedItemSwitchHands = union(
                            displayedItemSwitchHands, transitionItemSwitchHands);
                } else {
                    transitionSource = null;
                    transitionItemSwitchHands = Set.of();
                    transitionStart = Double.NaN;
                }
            }

            OpenMatrix4f[] snapshot = copyOf(complete);
            if (snapshot == null) {
                reset();
                return Set.of();
            }
            lastOutput = snapshot;
            previousItemSwitchHands = epicFightActionActive
                    ? Set.of() : currentItemSwitchHands;
            previousAnimationTicks = animationTicks;
            return epicFightActionActive ? Set.of() : displayedItemSwitchHands;
        }

        private void reset() {
            previousMovementPoseKey = null;
            lastOutput = null;
            transitionSource = null;
            previousItemSwitchHands = Set.of();
            transitionItemSwitchHands = Set.of();
            transitionStart = Double.NaN;
            previousAnimationTicks = Double.NaN;
        }
    }

    private static Set<InteractionHand> union(
            Set<InteractionHand> first, Set<InteractionHand> second) {
        if (first.isEmpty()) {
            return second.isEmpty() ? Set.of() : Set.copyOf(second);
        }
        if (second.isEmpty()) {
            return Set.copyOf(first);
        }
        LinkedHashSet<InteractionHand> result = new LinkedHashSet<>(first);
        result.addAll(second);
        return Set.copyOf(result);
    }

    @Nullable
    private static OpenMatrix4f[] copyOf(OpenMatrix4f[] matrices) {
        if (!valid(matrices)) {
            return null;
        }
        OpenMatrix4f[] copy = new OpenMatrix4f[matrices.length];
        for (int index = 0; index < matrices.length; index++) {
            copy[index] = new OpenMatrix4f(matrices[index]);
        }
        return copy;
    }

    private static boolean valid(@Nullable OpenMatrix4f[] matrices) {
        if (matrices == null || matrices.length == 0) {
            return false;
        }
        for (OpenMatrix4f matrix : matrices) {
            if (matrix == null || !finite(matrix)) {
                return false;
            }
        }
        return true;
    }

    private static boolean finite(OpenMatrix4f matrix) {
        return Float.isFinite(matrix.m00) && Float.isFinite(matrix.m01)
                && Float.isFinite(matrix.m02) && Float.isFinite(matrix.m03)
                && Float.isFinite(matrix.m10) && Float.isFinite(matrix.m11)
                && Float.isFinite(matrix.m12) && Float.isFinite(matrix.m13)
                && Float.isFinite(matrix.m20) && Float.isFinite(matrix.m21)
                && Float.isFinite(matrix.m22) && Float.isFinite(matrix.m23)
                && Float.isFinite(matrix.m30) && Float.isFinite(matrix.m31)
                && Float.isFinite(matrix.m32) && Float.isFinite(matrix.m33);
    }
}
