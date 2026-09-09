package net.okitsu.ysmepicfightcompat.animation;

import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.network.CompatNetwork;
import net.okitsu.ysmepicfightcompat.network.PlayerSelectionNbt;
import net.okitsu.ysmepicfightcompat.network.message.ConfigurationVariableSnapshotMessage;
import net.okitsu.ysmepicfightcompat.network.message.ConfigurationVariableUpdateMessage;
import net.okitsu.ysmepicfightcompat.network.message.ConfigurationVariableScopeRequestMessage;
import net.okitsu.ysmepicfightcompat.network.message.ConfigurationVariableScopeReplyMessage;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Mirrors non-persistent v.* writes with server-confirmed values plus bounded pending edits. */
public final class OfficialConfigurationVariables {
    private static final Map<UUID, ModelConfigurationOverrides> STATES = new ConcurrentHashMap<>();
    private static final AtomicBoolean UPDATE_LOGGED = new AtomicBoolean();
    private static final UUID NO_CONTEXT = new UUID(0L, 0L);
    private static Connection connection;
    private static Player localPlayer;
    private static ConfigurationVariableScopeRequestMessage scopeRequest;
    private static long requestOrder;
    private static int retryTicks;

    private OfficialConfigurationVariables() {
    }

    public static void apply(Player player, String expression) {
        if (player == null || expression == null || expression.isBlank()) {
            return;
        }
        bindLocal();
        if (player != localPlayer || connection == null) {
            return;
        }
        // Configuration UI operations must use the live official selection, not the render cache.
        PlayerSelectionNbt.Selection selection = PlayerSelectionNbt.read(player);
        if (selection == null) {
            reset(player);
            return;
        }
        ModelConfigurationOverrides state = STATES.computeIfAbsent(player.getUUID(),
                ignored -> new ModelConfigurationOverrides());
        EntityAnimationEnvironment fallback = new EntityAnimationEnvironment(
                player, new HashMap<>(), new HashSet<>(), selection.modelId());
        fallback.update(0.0F, false, 0.0D);
        Map<String, Double> changes;
        try {
            changes = state.evaluate(selection.modelId(), expression, fallback);
        } catch (IllegalArgumentException ignored) {
            // An invalid/oversized edit must leave both the confirmed and pending values unchanged.
            return;
        }
        if (!changes.isEmpty()) {
            publishPending(state);
        }
        if (UPDATE_LOGGED.compareAndSet(false, true)) {
            CompatMod.LOG.info(
                    "YSM-EF Compat: official YSM configuration variable update was mirrored");
        }
    }

    static ConfigurationVariableOverrides.Lookup lookup(LivingEntity entity, String modelId, int slot) {
        if (!(entity instanceof Player)) {
            return ConfigurationVariableOverrides.Lookup.missing();
        }
        // Rendering can see the replacement before tickSync binds it. Never let its UUID
        // resolve to the previous local player's pending/confirmed configuration overlay.
        if (entity == Minecraft.getInstance().player && entity != localPlayer) {
            return ConfigurationVariableOverrides.Lookup.missing();
        }
        ModelConfigurationOverrides state = STATES.get(entity.getUUID());
        return state == null ? ConfigurationVariableOverrides.Lookup.missing()
                : state.lookup(modelId, slot);
    }

    /** Allocation-free change detection for the same overlay exposed by lookup. */
    static Object renderToken(LivingEntity entity, String modelId) {
        if (!(entity instanceof Player)) {
            return null;
        }
        if (entity == Minecraft.getInstance().player && entity != localPlayer) {
            return null;
        }
        ModelConfigurationOverrides state = STATES.get(entity.getUUID());
        return state == null ? null : state.renderToken(modelId);
    }

    public static void reset(Player player) {
        if (player != null) {
            Minecraft minecraft = Minecraft.getInstance();
            Player current = minecraft.level == null ? null
                    : minecraft.level.getPlayerByUUID(player.getUUID());
            if (current != null && current != player) {
                return;
            }
            if (localPlayer != null && localPlayer != player
                    && localPlayer.getUUID().equals(player.getUUID())) {
                return;
            }
            STATES.remove(player.getUUID());
            if (player == localPlayer) {
                scopeRequest = null;
                retryTicks = 0;
            }
        }
    }

    /** Retries only handshake metadata; expressions are never replayed. */
    public static void tickSync() {
        bindLocal();
        if (connection == null || localPlayer == null) {
            return;
        }
        ModelConfigurationOverrides state = STATES.get(localPlayer.getUUID());
        if (state == null) {
            return;
        }
        PlayerSelectionNbt.Selection selection = PlayerSelectionNbt.read(localPlayer);
        if (selection == null) {
            reset(localPlayer);
            return;
        }
        state.selectModel(selection.modelId());
        if (state.serverScope() == null && !state.pendingChanges().isEmpty() && --retryTicks <= 0) {
            requestScope(state);
        }
    }

    /** Rejects work queued by an old connection before touching any client state. */
    public static void acceptSnapshot(Connection source, ConfigurationVariableSnapshotMessage snapshot) {
        if (!isCurrentConnection(source)) {
            return;
        }
        bindLocal();
        ModelConfigurationOverrides state = STATES.computeIfAbsent(snapshot.playerId(),
                ignored -> new ModelConfigurationOverrides());
        if (localPlayer == null || !snapshot.playerId().equals(localPlayer.getUUID())) {
            state.accept(snapshot.modelId(), snapshot.values());
            return;
        }
        PlayerSelectionNbt.Selection selection = PlayerSelectionNbt.read(localPlayer);
        if (selection == null || !selection.modelId().equals(snapshot.modelId())) {
            return;
        }
        // A live selection change starts a new context even before its next UI operation.
        state.selectModel(selection.modelId());
        if (state.serverScope() != null
                && state.context().equals(snapshot.clientContext())
                && state.serverScope().equals(snapshot.serverScope())) {
            try {
                state.acknowledge(snapshot.modelId(), snapshot.clientContext(), snapshot.serverScope(),
                        snapshot.revision(), snapshot.throughSequence(), snapshot.values());
            } catch (IllegalArgumentException ignored) {
                // A later bounded ACK can still retire pending values rejected by the server.
            }
        } else if (NO_CONTEXT.equals(snapshot.clientContext())) {
            switch (state.acceptInitialSnapshot(snapshot.modelId(), snapshot.values())) {
                case REPLACED -> scopeRequest = null;
                case RESCOPE -> {
                    scopeRequest = null;
                    requestScope(state);
                }
                case PRESERVED -> { }
            }
        }
    }

    public static void acceptScope(Connection source, ConfigurationVariableScopeReplyMessage reply) {
        if (!isCurrentConnection(source)) {
            return;
        }
        bindLocal();
        if (localPlayer == null || scopeRequest == null
                || scopeRequest.requestOrder() != reply.requestOrder()
                || !scopeRequest.clientContext().equals(reply.clientContext())) {
            return;
        }
        ModelConfigurationOverrides state = STATES.get(localPlayer.getUUID());
        PlayerSelectionNbt.Selection selection = PlayerSelectionNbt.read(localPlayer);
        if (state == null || selection == null || !selection.modelId().equals(reply.modelId())
                || !state.modelId().equals(reply.modelId())
                || !state.context().equals(reply.clientContext())) {
            return;
        }
        if (reply.granted()) {
            if (state.serverScope() == null) {
                state.scope(reply.serverScope());
                publishPending(state);
            }
        } else if (reply.serverScope().equals(state.serverScope())) {
            state.scope(null);
            scopeRequest = null;
            requestScope(state);
        }
    }

    private static void publishPending(ModelConfigurationOverrides state) {
        Map<String, Double> pending = state.pendingChanges();
        if (pending.isEmpty()) {
            return;
        }
        if (state.serverScope() == null) {
            if (scopeRequest == null || !scopeRequest.clientContext().equals(state.context())) {
                requestScope(state);
            }
        } else if (scopeRequest != null && scopeRequest.clientContext().equals(state.context())) {
            CompatNetwork.sendConfigurationUpdate(new ConfigurationVariableUpdateMessage(
                    state.modelId(), state.context(), state.serverScope(),
                    scopeRequest.requestOrder(), state.sequence(), pending));
        }
    }

    private static void requestScope(ModelConfigurationOverrides state) {
        if (scopeRequest == null || !scopeRequest.clientContext().equals(state.context())) {
            scopeRequest = new ConfigurationVariableScopeRequestMessage(
                    state.modelId(), state.context(), Math.incrementExact(requestOrder));
            requestOrder = scopeRequest.requestOrder();
        }
        CompatNetwork.requestConfigurationScope(scopeRequest);
        retryTicks = 20;
    }

    private static boolean isCurrentConnection(Connection source) {
        var active = Minecraft.getInstance().getConnection();
        return source != null && active != null && active.getConnection() == source;
    }

    private static void bindLocal() {
        Minecraft minecraft = Minecraft.getInstance();
        Connection active = minecraft.getConnection() == null ? null
                : minecraft.getConnection().getConnection();
        if (active != connection) {
            clear();
            connection = active;
        }
        if (localPlayer != minecraft.player) {
            if (localPlayer != null) {
                STATES.remove(localPlayer.getUUID());
                if (minecraft.player != null) {
                    STATES.remove(minecraft.player.getUUID());
                }
            }
            localPlayer = minecraft.player;
            scopeRequest = null;
            retryTicks = 0;
        }
    }

    public static void clear() {
        STATES.clear();
        UPDATE_LOGGED.set(false);
        connection = null;
        localPlayer = null;
        scopeRequest = null;
        retryTicks = 0;
    }
}
