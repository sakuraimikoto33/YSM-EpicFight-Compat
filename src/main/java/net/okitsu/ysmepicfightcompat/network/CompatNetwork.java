package net.okitsu.ysmepicfightcompat.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.network.message.ModelChunkMessage;
import net.okitsu.ysmepicfightcompat.network.message.ModelRequestMessage;
import net.okitsu.ysmepicfightcompat.network.message.SelectionUpdateMessage;

import java.util.Optional;

/** Forge channel for compatibility-owned state; official YSM's channel remains untouched. */
public final class CompatNetwork {
    public static final String PROTOCOL = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(CompatMod.MOD_ID, "bridge"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private CompatNetwork() {
    }

    public static void registerMessages() {
        int id = 0;
        CHANNEL.registerMessage(id++, SelectionUpdateMessage.class,
                SelectionUpdateMessage::write, SelectionUpdateMessage::read,
                SelectionUpdateMessage::receive, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, ModelRequestMessage.class,
                ModelRequestMessage::write, ModelRequestMessage::read,
                ModelRequestMessage::receive, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id, ModelChunkMessage.class,
                ModelChunkMessage::write, ModelChunkMessage::read,
                ModelChunkMessage::receive, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static boolean isConnected(ServerPlayer player) {
        return player != null && player.connection != null
                && player.connection.connection != null
                && player.connection.connection.isConnected();
    }

    public static void toPlayer(ServerPlayer player, Object message) {
        if (isConnected(player)) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
        }
    }

    public static void toTrackersAndSelf(Player player, Object message) {
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> player), message);
    }
}
