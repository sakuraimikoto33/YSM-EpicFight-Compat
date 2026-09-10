package net.okitsu.ysmepicfightcompat.network;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.network.geometry.ServerModelTransfers;
import net.okitsu.ysmepicfightcompat.network.message.SelectionUpdateMessage;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Mirrors official YSM selections to compatibility clients that render Epic Fight meshes. */
@EventBusSubscriber(modid = CompatMod.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class SelectionBroadcaster {
    private static final int POLL_INTERVAL = 20;
    private static final Map<UUID, Snapshot> LAST_SENT = new ConcurrentHashMap<>();

    private record Snapshot(String modelId, String textureName) {
    }

    private SelectionBroadcaster() {
    }

    @SubscribeEvent
    public static void playerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer recipient)) {
            return;
        }
        for (ServerPlayer online : recipient.server.getPlayerList().getPlayers()) {
            send(online, recipient);
            ConfigurationVariableBroadcaster.send(online, recipient);
        }
    }

    @SubscribeEvent
    public static void startedTracking(PlayerEvent.StartTracking event) {
        if (!(event.getEntity() instanceof ServerPlayer recipient)) {
            return;
        }
        ServerModelTransfers.startedTracking(recipient, event.getTarget());
        if (event.getTarget() instanceof ServerPlayer tracked) {
            send(tracked, recipient);
            ConfigurationVariableBroadcaster.send(tracked, recipient);
        }
    }

    @SubscribeEvent
    public static void stoppedTracking(PlayerEvent.StopTracking event) {
        if (event.getEntity() instanceof ServerPlayer recipient) {
            ServerModelTransfers.stoppedTracking(recipient, event.getTarget());
        }
    }

    @SubscribeEvent
    public static void playerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (!ConfigurationVariableBroadcaster.mayRemove(player)) {
                return;
            }
            LAST_SENT.remove(player.getUUID());
            ServerModelTransfers.playerDisconnected(player);
            ConfigurationVariableBroadcaster.remove(player);
        }
    }

    @SubscribeEvent
    public static void playerCloned(PlayerEvent.Clone event) {
        if (event.getOriginal() instanceof ServerPlayer original
                && event.getEntity() instanceof ServerPlayer replacement) {
            ConfigurationVariableBroadcaster.cloned(original, replacement, event.isWasDeath());
        }
    }

    @SubscribeEvent
    public static void playerRespawned(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ConfigurationVariableBroadcaster.respawned(player, event.isEndConquered());
        }
    }

    @SubscribeEvent
    public static void playerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PlayerSelectionNbt.Selection selection = PlayerSelectionNbt.read(player);
            ConfigurationVariableBroadcaster.reset(player,
                    selection == null ? "" : selection.modelId());
        }
    }

    @SubscribeEvent
    public static void serverTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }
        ConfigurationVariableBroadcaster.flushRespawns();
        if (server.getTickCount() % POLL_INTERVAL != 0) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            synchronize(player, PlayerSelectionNbt.read(player));
        }
    }

    /** Shared by polling and edits; returns whether a model change also sent its variables. */
    static boolean synchronize(ServerPlayer player, PlayerSelectionNbt.Selection selection) {
        if (!ConfigurationVariableBroadcaster.isCurrentPlayer(player)) {
            return false;
        }
        Snapshot current = snapshot(selection);
        Snapshot previous = LAST_SENT.put(player.getUUID(), current);
        if (!current.equals(previous)) {
            broadcast(player, current);
        }
        if (previous == null || !current.modelId().equals(previous.modelId())) {
            ConfigurationVariableBroadcaster.synchronize(player, current.modelId());
            return true;
        }
        return false;
    }

    /** A new life needs notification even when the selected model and texture are unchanged. */
    static void resynchronize(ServerPlayer player) {
        if (!ConfigurationVariableBroadcaster.isCurrentPlayer(player)) {
            return;
        }
        Snapshot current = snapshot(player);
        LAST_SENT.put(player.getUUID(), current);
        broadcast(player, current);
        ConfigurationVariableBroadcaster.synchronize(player, current.modelId());
    }

    @SubscribeEvent
    public static void serverStopped(ServerStoppedEvent event) {
        LAST_SENT.clear();
        ConfigurationVariableBroadcaster.clear();
        ServerModelTransfers.clear();
    }

    private static void send(ServerPlayer selectedPlayer, ServerPlayer recipient) {
        if (!ConfigurationVariableBroadcaster.isCurrentPlayer(selectedPlayer)
                || !CompatNetwork.isConnected(recipient)) {
            return;
        }
        Snapshot state = snapshot(selectedPlayer);
        CompatNetwork.toPlayer(recipient, message(selectedPlayer, state));
    }

    private static void broadcast(ServerPlayer player, Snapshot state) {
        CompatNetwork.toTrackersAndSelf(player, message(player, state));
    }

    private static SelectionUpdateMessage message(ServerPlayer player, Snapshot state) {
        return new SelectionUpdateMessage(player.getUUID(),
                state.modelId(), state.textureName(), state.modelId().isEmpty());
    }

    private static Snapshot snapshot(ServerPlayer player) {
        return snapshot(PlayerSelectionNbt.read(player));
    }

    private static Snapshot snapshot(PlayerSelectionNbt.Selection selection) {
        return selection == null
                ? new Snapshot("", "")
                : new Snapshot(selection.modelId(), selection.textureName());
    }
}
