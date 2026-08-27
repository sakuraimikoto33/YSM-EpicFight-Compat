package net.okitsu.ysmepicfightcompat.network;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.okitsu.ysmepicfightcompat.integration.tlm.TouhouMaidSelectionAccess;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Client snapshots keyed by maid UUID and guarded by the current entity fingerprint. */
public final class RemoteMaidPreferences {
    private static volatile Map<UUID, MaidPreferenceDisplayState> current = Map.of();

    private RemoteMaidPreferences() {
    }

    public static HeldItemModelDisplayState heldItems(
            LivingEntity maid, String selectedModelId) {
        MaidPreferenceDisplayState state = heldState(maid, selectedModelId);
        return state == null ? HeldItemModelDisplayState.UNKNOWN : state.heldItems();
    }

    public static MovementAnimationDisplayState movement(
            LivingEntity maid, String selectedModelId) {
        MaidPreferenceDisplayState state = movementState(maid, selectedModelId);
        return state == null ? MovementAnimationDisplayState.DEFAULT
                : new MovementAnimationDisplayState(
                state.modelId(), state.movement(), state.ysmMovement());
    }

    public static synchronized void accept(MaidPreferenceDisplayState state) {
        if (state == null) {
            return;
        }
        MaidPreferenceDisplayState previous = current.get(state.entityUuid());
        if (previous != null && state.revision() <= previous.revision()) {
            return;
        }
        Map<UUID, MaidPreferenceDisplayState> next = new HashMap<>(current);
        next.put(state.entityUuid(), state);
        current = Map.copyOf(next);
    }

    public static synchronized void remove(UUID entityUuid) {
        if (entityUuid == null || !current.containsKey(entityUuid)) {
            return;
        }
        Map<UUID, MaidPreferenceDisplayState> next = new HashMap<>(current);
        next.remove(entityUuid);
        current = Map.copyOf(next);
    }

    public static void beginConnection() {
        current = Map.of();
    }

    static MaidPreferenceDisplayState find(UUID entityUuid) {
        return current.get(entityUuid);
    }

    static boolean matchesHeld(MaidPreferenceDisplayState state,
                               UUID entityUuid, UUID ownerUuid,
                               String modelId, ResourceLocation mainHandItem,
                               ResourceLocation offHandItem) {
        return state != null && entityUuid != null && ownerUuid != null
                && state.entityUuid().equals(entityUuid)
                && state.ownerUuid().equals(ownerUuid)
                && state.modelId().equals(MovementAnimationPolicy.normalizeModelId(modelId))
                && state.mainHandItem().equals(mainHandItem)
                && state.offHandItem().equals(offHandItem);
    }

    static boolean matchesMovement(MaidPreferenceDisplayState state,
                                   UUID entityUuid, UUID ownerUuid,
                                   String modelId) {
        return state != null && entityUuid != null && ownerUuid != null
                && state.entityUuid().equals(entityUuid)
                && state.ownerUuid().equals(ownerUuid)
                && state.modelId().equals(
                MovementAnimationPolicy.normalizeModelId(modelId));
    }

    private static MaidPreferenceDisplayState heldState(
            LivingEntity maid, String modelId) {
        if (maid == null || !TouhouMaidSelectionAccess.isSupportedMaid(maid)) {
            return null;
        }
        MaidPreferenceDisplayState state = current.get(maid.getUUID());
        return matchesHeld(state, maid.getUUID(),
                TouhouMaidSelectionAccess.ownerUuid(maid), modelId,
                BuiltInRegistries.ITEM.getKey(maid.getMainHandItem().getItem()),
                BuiltInRegistries.ITEM.getKey(maid.getOffhandItem().getItem()))
                ? state : null;
    }

    private static MaidPreferenceDisplayState movementState(
            LivingEntity maid, String modelId) {
        if (maid == null || !TouhouMaidSelectionAccess.isSupportedMaid(maid)) {
            return null;
        }
        MaidPreferenceDisplayState state = current.get(maid.getUUID());
        return matchesMovement(state, maid.getUUID(),
                TouhouMaidSelectionAccess.ownerUuid(maid), modelId)
                ? state : null;
    }
}
