package net.okitsu.ysmepicfightcompat.network;

import net.minecraft.server.level.ServerPlayer;
import net.okitsu.ysmepicfightcompat.network.message.ConfigurationVariableSnapshotMessage;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Server-owned, session-only ordinary-variable snapshots scoped to the selected model. */
public final class ConfigurationVariableBroadcaster {
    private record Snapshot(String modelId, Map<String, Double> values) {
    }

    private static final Map<UUID, Snapshot> STATES = new ConcurrentHashMap<>();

    private ConfigurationVariableBroadcaster() {
    }

    public static void accept(ServerPlayer player, Map<String, Double> changes) {
        if (player == null || changes.isEmpty()) {
            return;
        }
        PlayerSelectionNbt.Selection selection = PlayerSelectionNbt.read(player);
        if (selection == null) {
            STATES.remove(player.getUUID());
            return;
        }
        Snapshot next;
        try {
            next = STATES.compute(player.getUUID(), (ignored, current) -> {
                Map<String, Double> previous = current != null
                        && current.modelId().equals(selection.modelId())
                        ? current.values() : Map.of();
                return new Snapshot(selection.modelId(),
                        ConfigurationVariableValues.merge(previous, changes));
            });
        } catch (IllegalArgumentException ignored) {
            return;
        }
        CompatNetwork.toTrackersAndSelf(player, message(player, next));
    }

    public static void send(ServerPlayer selectedPlayer, ServerPlayer recipient) {
        if (!CompatNetwork.isConnected(recipient)) {
            return;
        }
        CompatNetwork.toPlayer(recipient, message(selectedPlayer, current(selectedPlayer)));
    }

    public static void reset(ServerPlayer player, String modelId) {
        STATES.remove(player.getUUID());
        CompatNetwork.toTrackersAndSelf(player, new ConfigurationVariableSnapshotMessage(
                player.getUUID(), modelId == null ? "" : modelId, Map.of()));
    }

    public static void remove(ServerPlayer player) {
        if (player != null) {
            STATES.remove(player.getUUID());
        }
    }

    public static void clear() {
        STATES.clear();
    }

    private static Snapshot current(ServerPlayer player) {
        PlayerSelectionNbt.Selection selection = PlayerSelectionNbt.read(player);
        String modelId = selection == null ? "" : selection.modelId();
        Snapshot current = STATES.get(player.getUUID());
        return current != null && current.modelId().equals(modelId)
                ? current : new Snapshot(modelId, Map.of());
    }

    private static ConfigurationVariableSnapshotMessage message(ServerPlayer player,
                                                                 Snapshot snapshot) {
        return new ConfigurationVariableSnapshotMessage(player.getUUID(),
                snapshot.modelId(), snapshot.values());
    }
}
