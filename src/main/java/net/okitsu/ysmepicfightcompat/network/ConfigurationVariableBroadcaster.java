package net.okitsu.ysmepicfightcompat.network;

import net.minecraft.server.level.ServerPlayer;
import net.okitsu.ysmepicfightcompat.network.message.ConfigurationVariableSnapshotMessage;
import net.okitsu.ysmepicfightcompat.network.message.ConfigurationVariableUpdateMessage;
import net.okitsu.ysmepicfightcompat.network.message.ConfigurationVariableScopeRequestMessage;
import net.okitsu.ysmepicfightcompat.network.message.ConfigurationVariableScopeReplyMessage;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Server-owned, session-only ordinary-variable snapshots scoped to the selected model. */
public final class ConfigurationVariableBroadcaster {
    private static final Map<UUID, ModelConfigurationSnapshot> STATES = new ConcurrentHashMap<>();
    // Non-death replacements invalidate ACK identity without clearing ordinary values.
    private static final Map<UUID, ConfigurationSyncSession> SESSIONS = new ConcurrentHashMap<>();
    private static final ConfigurationPlayerTransitions<ServerPlayer> TRANSITIONS =
            new ConfigurationPlayerTransitions<>();

    private ConfigurationVariableBroadcaster() {
    }

    public static void accept(ServerPlayer player, ConfigurationVariableUpdateMessage update) {
        if (!isCurrentSender(player)) {
            return;
        }
        PlayerSelectionNbt.Selection selection = PlayerSelectionNbt.read(player);
        if (selection == null) {
            SelectionBroadcaster.synchronize(player, null);
            STATES.remove(player.getUUID());
            invalidate(player, update);
            return;
        }
        ConfigurationSyncSession session = session(player, selection.modelId());
        if (!selection.modelId().equals(update.modelId())
                || !session.accepts(update.serverScope(), update.clientContext(), update.sequence())) {
            invalidate(player, update);
            return;
        }
        if (session.duplicate(update.sequence())) {
            CompatNetwork.toPlayer(player, message(player, current(player)));
            return;
        }
        ModelConfigurationSnapshot next;
        try {
            next = STATES.compute(player.getUUID(), (ignored, current) ->
                    ModelConfigurationSnapshot.reconcile(current, selection.modelId())
                            .merge(update.changes()));
        } catch (IllegalArgumentException ignored) {
            // Rejections also cover the processed sequence; no pending edit is stranded.
            next = current(player);
        }
        session.processed(update.sequence());
        // Merge before publishing the selection so its first snapshot already includes the edit.
        if (!SelectionBroadcaster.synchronize(player, selection)) {
            CompatNetwork.toTrackersAndSelf(player, message(player, next));
        }
    }

    /** Grants only identity, never restores old values to a newly created client player. */
    public static void requestScope(ServerPlayer player, ConfigurationVariableScopeRequestMessage request) {
        if (!isCurrentSender(player)) {
            return;
        }
        PlayerSelectionNbt.Selection selection = PlayerSelectionNbt.read(player);
        if (selection == null || !selection.modelId().equals(request.modelId())) {
            // Official YSM's selection may arrive later. The client retains and retries its edit.
            return;
        }
        ConfigurationSyncSession session = session(player, selection.modelId());
        if (session.grant(request.clientContext(), request.requestOrder())) {
            CompatNetwork.toPlayer(player, new ConfigurationVariableScopeReplyMessage(
                    request.modelId(), request.clientContext(), request.requestOrder(),
                    session.scope(), true));
        }
    }

    public static void send(ServerPlayer selectedPlayer, ServerPlayer recipient) {
        if (!isCurrentPlayer(selectedPlayer) || !CompatNetwork.isConnected(recipient)) {
            return;
        }
        CompatNetwork.toPlayer(recipient, message(selectedPlayer, current(selectedPlayer)));
    }

    public static void reset(ServerPlayer player, String modelId) {
        if (!isCurrentPlayer(player)) {
            return;
        }
        STATES.remove(player.getUUID());
        String selectedModelId = modelId == null ? "" : modelId;
        session(player, selectedModelId).invalidate();
        CompatNetwork.toTrackersAndSelf(player, message(player,
                new ModelConfigurationSnapshot(selectedModelId, Map.of())));
    }

    /** Send after the selection notification, preserving values already received for this model. */
    static void synchronize(ServerPlayer player, String modelId) {
        if (!isCurrentPlayer(player)) {
            return;
        }
        ModelConfigurationSnapshot next = STATES.compute(player.getUUID(), (ignored, current) ->
                ModelConfigurationSnapshot.reconcile(current, modelId));
        CompatNetwork.toTrackersAndSelf(player, message(player, next));
    }

    /** Clone precedes tracking and may run before the replacement has its selection/connection. */
    static void cloned(ServerPlayer original, ServerPlayer replacement, boolean wasDeath) {
        if (!original.getUUID().equals(replacement.getUUID())) {
            return;
        }
        if (!TRANSITIONS.begin(original.getUUID(), original, replacement,
                onlinePlayer(original), wasDeath)) {
            return;
        }
        if (wasDeath) {
            // Exactly once, before StartTracking can publish the old life. Do not read the
            // replacement's not-yet-restored selection or send anything from this event.
            STATES.remove(original.getUUID());
            SESSIONS.put(original.getUUID(), new ConfigurationSyncSession(replacement, ""));
        }
    }

    static void respawned(ServerPlayer player, boolean endConquered) {
        TRANSITIONS.respawned(player.getUUID(), player, endConquered);
    }

    /** Runs at tick END, after all synchronous official-YSM respawn listeners have returned. */
    static void flushRespawns() {
        for (var pending : TRANSITIONS.ready()) {
            ServerPlayer player = pending.replacement();
            if (onlinePlayer(player) == player && CompatNetwork.isConnected(player)
                    && pending.deathNotificationRequired()) {
                // This publishes CURRENT new-life values, including edits accepted since
                // respawn. Never reset values or invalidate a grant a second time here.
                SelectionBroadcaster.resynchronize(player);
            }
            TRANSITIONS.remove(pending.playerId(), player);
        }
    }

    public static void remove(ServerPlayer player) {
        if (!mayRemove(player)) {
            return;
        }
        STATES.remove(player.getUUID());
        SESSIONS.remove(player.getUUID());
        TRANSITIONS.remove(player.getUUID(), player);
    }

    public static void clear() {
        STATES.clear();
        SESSIONS.clear();
        TRANSITIONS.clear();
    }

    private static ModelConfigurationSnapshot current(ServerPlayer player) {
        PlayerSelectionNbt.Selection selection = PlayerSelectionNbt.read(player);
        String modelId = selection == null ? "" : selection.modelId();
        return ModelConfigurationSnapshot.reconcile(STATES.get(player.getUUID()), modelId);
    }

    private static ConfigurationVariableSnapshotMessage message(ServerPlayer player,
                                                                 ModelConfigurationSnapshot snapshot) {
        ConfigurationSyncSession session = session(player, snapshot.modelId());
        return new ConfigurationVariableSnapshotMessage(player.getUUID(),
                snapshot.modelId(), session.scope(), session.context(), session.revision(),
                session.processedSequence(), snapshot.values());
    }

    private static ConfigurationSyncSession session(ServerPlayer player, String modelId) {
        ConfigurationSyncSession current = SESSIONS.get(player.getUUID());
        if (current == null || !current.isOwner(player)) {
            current = new ConfigurationSyncSession(player, modelId);
            SESSIONS.put(player.getUUID(), current);
        } else {
            current.changeModel(modelId);
        }
        return current;
    }

    private static boolean isCurrentSender(ServerPlayer player) {
        return CompatNetwork.isConnected(player) && isCurrentPlayer(player)
                && onlinePlayer(player) == player;
    }

    static boolean isCurrentPlayer(ServerPlayer player) {
        return player != null && TRANSITIONS.allows(player.getUUID(), player, onlinePlayer(player));
    }

    static boolean mayRemove(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        ServerPlayer online = onlinePlayer(player);
        return TRANSITIONS.allows(player.getUUID(), player, online == null ? player : online);
    }

    private static ServerPlayer onlinePlayer(ServerPlayer player) {
        return player.getServer() == null ? null
                : player.getServer().getPlayerList().getPlayer(player.getUUID());
    }

    private static void invalidate(ServerPlayer player, ConfigurationVariableUpdateMessage update) {
        CompatNetwork.toPlayer(player, new ConfigurationVariableScopeReplyMessage(
                update.modelId(), update.clientContext(), update.requestOrder(),
                update.serverScope(), false));
    }
}
