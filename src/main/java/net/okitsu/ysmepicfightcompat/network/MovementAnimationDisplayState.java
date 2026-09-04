package net.okitsu.ysmepicfightcompat.network;

import net.okitsu.ysmepicfightcompat.animation.MovementAnimationType;

/** One owner's currently resolved movement-pose decision; local rules are never included. */
public record MovementAnimationDisplayState(
        String modelId,
        MovementAnimationType movement,
        boolean ysmOwned,
        boolean naturalLadderPose) {
    public static final MovementAnimationDisplayState DEFAULT =
            new MovementAnimationDisplayState("", null, false, false);

    public MovementAnimationDisplayState(
            String modelId, MovementAnimationType movement, boolean ysmOwned) {
        this(modelId, movement, ysmOwned, false);
    }

    public MovementAnimationDisplayState {
        modelId = MovementAnimationPolicy.normalizeModelId(modelId);
        if (!modelId.isEmpty()
                && !MovementAnimationPolicy.isValidModelId(modelId)) {
            throw new IllegalArgumentException("Invalid movement model ID");
        }
        if (modelId.isEmpty() || movement == null) {
            ysmOwned = false;
        }
        if (!ysmOwned || movement == null || !movement.isLadder()) {
            naturalLadderPose = false;
        }
    }

    public boolean usesYsm(String selectedModelId, MovementAnimationType currentMovement) {
        return ysmOwned && movement != null && movement == currentMovement
                && modelId.equals(MovementAnimationPolicy.normalizeModelId(selectedModelId));
    }

    /** Whether this owner selected the natural ladder composition for this exact frame. */
    public boolean usesNaturalLadderPose(
            String selectedModelId, MovementAnimationType currentMovement) {
        return naturalLadderPose
                && usesYsm(selectedModelId, currentMovement);
    }

    /**
     * Authoritative semantic movement for this model owner's remote rendering.
     *
     * <p>The owner bit controls whether the ordinary movement pose replaces Epic
     * Fight. It must not discard the synchronized movement itself: a temporary
     * item-switch pose still needs the same structural YSM body clip on every
     * observing client even when ordinary YSM movement replacement is disabled.</p>
     */
    public MovementAnimationType semanticMovementFor(String selectedModelId) {
        return !modelId.isEmpty()
                && modelId.equals(MovementAnimationPolicy.normalizeModelId(selectedModelId))
                ? movement : null;
    }
}
