package net.okitsu.ysmepicfightcompat.network;

import net.minecraft.server.level.ServerPlayer;
import net.okitsu.ysmepicfightcompat.network.message.ConfigurationVariableSnapshotMessage;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Server-owned, session-only ordinary-variable snapshots scoped to the selected model. */
public final class ConfigurationVariableBroadcaster {
    private static final Map<UUID, ModelConfigurationSnapshot> STATES = new ConcurrentHashMap<>();

    private ConfigurationVariableBroadcaster() {
    }

    public static void accept(ServerPlayer player, Map<String, Double> changes) {
        if (player == null || changes.isEmpty()) {
            return;
        }
        PlayerSelectionNbt.Selection selection = PlayerSelectionNbt.read(player);
        if (selection == null) {
            SelectionBroadcaster.synchronize(player, null);
            STATES.remove(player.getUUID());
            return;
        }
        ModelConfigurationSnapshot next;
        try {
            next = STATES.compute(player.getUUID(), (ignored, current) ->
                    ModelConfigurationSnapshot.reconcile(current, selection.modelId()).merge(changes));
        } catch (IllegalArgumentException ignored) {
            return;
        }
        // Merge before publishing the selection so its first snapshot already includes the edit.
        if (!SelectionBroadcaster.synchronize(player, selection)) {
            CompatNetwork.toTrackersAndSelf(player, message(player, next));
        }
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

    /** Send after the selection notification, preserving values already received for this model. */
    static void synchronize(ServerPlayer player, String modelId) {
        ModelConfigurationSnapshot next = STATES.compute(player.getUUID(), (ignored, current) ->
                ModelConfigurationSnapshot.reconcile(current, modelId));
        CompatNetwork.toTrackersAndSelf(player, message(player, next));
    }

    public static void remove(ServerPlayer player) {
        if (player != null) {
            STATES.remove(player.getUUID());
        }
    }

    public static void clear() {
        STATES.clear();
    }

    private static ModelConfigurationSnapshot current(ServerPlayer player) {
        PlayerSelectionNbt.Selection selection = PlayerSelectionNbt.read(player);
        String modelId = selection == null ? "" : selection.modelId();
        return ModelConfigurationSnapshot.reconcile(STATES.get(player.getUUID()), modelId);
    }

    private static ConfigurationVariableSnapshotMessage message(ServerPlayer player,
                                                                 ModelConfigurationSnapshot snapshot) {
        return new ConfigurationVariableSnapshotMessage(player.getUUID(),
                snapshot.modelId(), snapshot.values());
    }
}
