package net.okitsu.ysmepicfightcompat.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Immutable client snapshot of player selections received on the current connection. */
public final class RemoteSelectionState {
    public record Entry(String modelId, String textureName) {
    }

    private static volatile Map<UUID, Entry> current = Map.of();

    private RemoteSelectionState() {
    }

    public static Entry find(UUID playerId) {
        return current.get(playerId);
    }

    public static synchronized void accept(UUID playerId, String modelId,
                                           String textureName, boolean disabled) {
        Map<UUID, Entry> next = new HashMap<>(current);
        if (disabled || modelId == null || modelId.isBlank()) {
            next.remove(playerId);
        } else {
            next.put(playerId, new Entry(modelId, textureName == null ? "" : textureName));
        }
        current = Map.copyOf(next);
    }

    public static void beginConnection() {
        current = Map.of();
    }
}
