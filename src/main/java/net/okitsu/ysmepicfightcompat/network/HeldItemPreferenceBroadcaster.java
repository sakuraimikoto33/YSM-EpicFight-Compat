package net.okitsu.ysmepicfightcompat.network;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.network.message.HeldItemPreferenceSnapshotMessage;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Relays each model owner's resolved cosmetic display state to tracking clients. */
@Mod.EventBusSubscriber(modid = CompatMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class HeldItemPreferenceBroadcaster {
    private static final Map<UUID, HeldItemModelDisplayState> DISPLAY_STATES =
            new ConcurrentHashMap<>();

    private HeldItemPreferenceBroadcaster() {
    }

    public static void accept(ServerPlayer player,
                              HeldItemModelDisplayState state) {
        if (player == null || state == null) {
            return;
        }
        HeldItemModelDisplayState previous =
                DISPLAY_STATES.put(player.getUUID(), state);
        if (!state.equals(previous)) {
            CompatNetwork.toTrackersAndSelf(player, message(player));
        }
    }

    @SubscribeEvent
    public static void playerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer recipient)) {
            return;
        }
        DISPLAY_STATES.remove(recipient.getUUID());
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
        DISPLAY_STATES.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void serverStopped(ServerStoppedEvent event) {
        DISPLAY_STATES.clear();
    }

    private static void send(ServerPlayer selectedPlayer, ServerPlayer recipient) {
        if (CompatNetwork.isConnected(recipient)) {
            CompatNetwork.toPlayer(recipient, message(selectedPlayer));
        }
    }

    private static HeldItemPreferenceSnapshotMessage message(ServerPlayer player) {
        HeldItemModelDisplayState state = DISPLAY_STATES.getOrDefault(
                player.getUUID(), HeldItemModelDisplayState.DEFAULT);
        return new HeldItemPreferenceSnapshotMessage(player.getUUID(),
                state.mainHandYsm(), state.offHandYsm());
    }
}
