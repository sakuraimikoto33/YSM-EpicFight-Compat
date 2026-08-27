package net.okitsu.ysmepicfightcompat.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Client snapshots of model owners' current resolved movement-pose decisions. */
public final class RemoteMovementAnimationPreferences {
    private static volatile Map<UUID, MovementAnimationDisplayState> current = Map.of();

    private RemoteMovementAnimationPreferences() {
    }

    public static MovementAnimationDisplayState find(UUID playerId) {
        return current.getOrDefault(playerId, MovementAnimationDisplayState.DEFAULT);
    }

    public static synchronized void accept(
            UUID playerId, MovementAnimationDisplayState state) {
        if (playerId == null || state == null) {
            return;
        }
        Map<UUID, MovementAnimationDisplayState> next = new HashMap<>(current);
        next.put(playerId, state);
        current = Map.copyOf(next);
    }

    public static void beginConnection() {
        current = Map.of();
    }
}
