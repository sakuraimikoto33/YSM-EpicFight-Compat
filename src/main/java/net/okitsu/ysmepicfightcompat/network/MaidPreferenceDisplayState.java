package net.okitsu.ysmepicfightcompat.network;

import net.minecraft.resources.ResourceLocation;
import net.okitsu.ysmepicfightcompat.animation.MovementAnimationType;

import java.util.UUID;

/** One maid's validated source fingerprint and owner-resolved cosmetic decisions. */
public record MaidPreferenceDisplayState(
        UUID entityUuid,
        UUID ownerUuid,
        long revision,
        String modelId,
        ResourceLocation mainHandItem,
        ResourceLocation offHandItem,
        MovementAnimationType movement,
        HeldItemModelDisplayState heldItems,
        boolean ysmMovement) {
    public MaidPreferenceDisplayState {
        modelId = MovementAnimationPolicy.normalizeModelId(modelId);
        if (entityUuid == null || ownerUuid == null || revision <= 0L
                || (!modelId.isEmpty()
                && !MovementAnimationPolicy.isValidModelId(modelId))
                || mainHandItem == null || offHandItem == null || heldItems == null) {
            throw new IllegalArgumentException("Invalid maid preference display state");
        }
        if (movement == null) {
            ysmMovement = false;
        }
        if (modelId.isEmpty()) {
            heldItems = HeldItemModelDisplayState.UNKNOWN;
            ysmMovement = false;
        }
    }
}
