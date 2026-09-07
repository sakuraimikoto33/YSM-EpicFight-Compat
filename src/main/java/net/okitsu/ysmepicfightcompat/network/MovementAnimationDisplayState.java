package net.okitsu.ysmepicfightcompat.network;

import net.okitsu.ysmepicfightcompat.animation.ModAnimationClips;
import net.okitsu.ysmepicfightcompat.animation.ModAnimationType;
import net.okitsu.ysmepicfightcompat.animation.MovementAnimationType;

/** One owner's current movement and optional-mod pose decisions; local rules are never included. */
public record MovementAnimationDisplayState(
        String modelId,
        MovementAnimationType movement,
        boolean ysmOwned,
        boolean naturalLadderPose,
        ModAnimationType modAnimation,
        boolean modAnimationOwned,
        String modAnimationClip) {
    public static final int MAX_MOD_ANIMATION_CLIP_LENGTH = 64;
    public static final MovementAnimationDisplayState DEFAULT =
            new MovementAnimationDisplayState("", null, false, false);

    public MovementAnimationDisplayState(
            String modelId, MovementAnimationType movement, boolean ysmOwned) {
        this(modelId, movement, ysmOwned, false);
    }

    public MovementAnimationDisplayState(
            String modelId, MovementAnimationType movement,
            boolean ysmOwned, boolean naturalLadderPose) {
        this(modelId, movement, ysmOwned, naturalLadderPose, null, false);
    }

    /** A legacy family-only state has no authority over any individual clip. */
    public MovementAnimationDisplayState(
            String modelId, MovementAnimationType movement,
            boolean ysmOwned, boolean naturalLadderPose,
            ModAnimationType modAnimation, boolean modAnimationOwned) {
        this(modelId, movement, ysmOwned, naturalLadderPose,
                modAnimation, modAnimationOwned, "");
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
        modAnimationClip = modAnimationClip == null ? "" : modAnimationClip;
        if (modAnimationClip.length() > MAX_MOD_ANIMATION_CLIP_LENGTH
                || !modAnimationClip.isEmpty()
                && (modAnimation == null || ModAnimationClips.type(modAnimationClip) != modAnimation)) {
            throw new IllegalArgumentException("Invalid mod-animation clip");
        }
        if (modelId.isEmpty() || modAnimation == null || modAnimationClip.isEmpty()) {
            modAnimationOwned = false;
        }
    }

    public boolean usesYsm(String selectedModelId, MovementAnimationType currentMovement) {
        return ysmOwned && movement != null && movement == currentMovement
                && modelId.equals(MovementAnimationPolicy.normalizeModelId(selectedModelId));
    }

    /** Without the current clip, a family-only check cannot grant pose ownership. */
    public boolean usesYsmMod(String selectedModelId, ModAnimationType currentModAnimation) {
        return usesYsmMod(selectedModelId, currentModAnimation, "");
    }

    /** Match the owner's exact model, family, and clip; stale decisions never authorize a new clip. */
    public boolean usesYsmMod(String selectedModelId, ModAnimationType currentModAnimation,
                              String currentModAnimationClip) {
        return modAnimationOwned && modAnimation != null && modAnimation == currentModAnimation
                && !modAnimationClip.isEmpty() && modAnimationClip.equals(currentModAnimationClip)
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
