package net.okitsu.ysmepicfightcompat.network;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.network.geometry.ServerModelTransfers;
import net.okitsu.ysmepicfightcompat.network.message.SelectionUpdateMessage;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Mirrors official YSM selections to compatibility clients that render Epic Fight meshes. */
@Mod.EventBusSubscriber(modid = CompatMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
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
        }
    }

    @SubscribeEvent
    public static void startedTracking(PlayerEvent.StartTracking event) {
        if (event.getEntity() instanceof ServerPlayer recipient
                && event.getTarget() instanceof ServerPlayer tracked) {
            send(tracked, recipient);
        }
    }

    @SubscribeEvent
    public static void playerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_SENT.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        if (server == null || server.getTickCount() % POLL_INTERVAL != 0) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Snapshot current = snapshot(player);
            Snapshot previous = LAST_SENT.put(player.getUUID(), current);
            if (!current.equals(previous)) {
                broadcast(player, current);
            }
        }
    }

    @SubscribeEvent
    public static void serverStopped(ServerStoppedEvent event) {
        LAST_SENT.clear();
        ServerModelTransfers.clear();
    }

    private static void send(ServerPlayer selectedPlayer, ServerPlayer recipient) {
        if (!CompatNetwork.isConnected(recipient)) {
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
        PlayerSelectionNbt.Selection selection = PlayerSelectionNbt.read(player);
        return selection == null
                ? new Snapshot("", "")
                : new Snapshot(selection.modelId(), selection.textureName());
    }
}
