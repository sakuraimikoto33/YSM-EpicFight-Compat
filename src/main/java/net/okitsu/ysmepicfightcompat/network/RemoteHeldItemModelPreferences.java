package net.okitsu.ysmepicfightcompat.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Client snapshots of model owners' resolved held-item display state. */
public final class RemoteHeldItemModelPreferences {
    private static volatile Map<UUID, HeldItemModelDisplayState> current = Map.of();

    private RemoteHeldItemModelPreferences() {
    }

    public static HeldItemModelDisplayState find(UUID playerId) {
        return current.getOrDefault(playerId, HeldItemModelDisplayState.DEFAULT);
    }

    public static synchronized void accept(UUID playerId,
                                           HeldItemModelDisplayState state) {
        if (playerId == null || state == null) {
            return;
        }
        Map<UUID, HeldItemModelDisplayState> next = new HashMap<>(current);
        next.put(playerId, state);
        current = Map.copyOf(next);
    }

    public static void beginConnection() {
        current = Map.of();
    }
}
