package net.okitsu.ysmepicfightcompat.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.network.message.AttackSwingSoundMessage;
import net.okitsu.ysmepicfightcompat.network.message.ModelChunkMessage;
import net.okitsu.ysmepicfightcompat.network.message.ModelRequestMessage;
import net.okitsu.ysmepicfightcompat.network.message.ConfigurationVariableSnapshotMessage;
import net.okitsu.ysmepicfightcompat.network.message.ConfigurationVariableUpdateMessage;
import net.okitsu.ysmepicfightcompat.network.message.HeldItemPreferenceSnapshotMessage;
import net.okitsu.ysmepicfightcompat.network.message.HeldItemPreferenceUpdateMessage;
import net.okitsu.ysmepicfightcompat.network.message.MovementAnimationPreferenceSnapshotMessage;
import net.okitsu.ysmepicfightcompat.network.message.MovementAnimationPreferenceUpdateMessage;
import net.okitsu.ysmepicfightcompat.network.message.MaidMovementPreferenceQueryMessage;
import net.okitsu.ysmepicfightcompat.network.message.MaidMovementPreferenceUpdateMessage;
import net.okitsu.ysmepicfightcompat.network.message.MaidPreferenceQueryMessage;
import net.okitsu.ysmepicfightcompat.network.message.MaidPreferenceSnapshotMessage;
import net.okitsu.ysmepicfightcompat.network.message.MaidPreferenceUpdateMessage;
import net.okitsu.ysmepicfightcompat.network.message.OwnerPreferenceEpochMessage;
import net.okitsu.ysmepicfightcompat.network.message.SelectionUpdateMessage;
import net.okitsu.ysmepicfightcompat.network.message.SubEntityPreferenceQueryMessage;
import net.okitsu.ysmepicfightcompat.network.message.SubEntityPreferenceSnapshotMessage;
import net.okitsu.ysmepicfightcompat.network.message.SubEntityPreferenceUpdateMessage;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
        CHANNEL.registerMessage(id++, ModelChunkMessage.class,
                ModelChunkMessage::write, ModelChunkMessage::read,
                ModelChunkMessage::receive, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, ConfigurationVariableUpdateMessage.class,
                ConfigurationVariableUpdateMessage::write,
                ConfigurationVariableUpdateMessage::read,
                ConfigurationVariableUpdateMessage::receive,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, ConfigurationVariableSnapshotMessage.class,
                ConfigurationVariableSnapshotMessage::write,
                ConfigurationVariableSnapshotMessage::read,
                ConfigurationVariableSnapshotMessage::receive,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, AttackSwingSoundMessage.class,
                AttackSwingSoundMessage::write, AttackSwingSoundMessage::read,
                AttackSwingSoundMessage::receive,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, HeldItemPreferenceUpdateMessage.class,
                HeldItemPreferenceUpdateMessage::write,
                HeldItemPreferenceUpdateMessage::read,
                HeldItemPreferenceUpdateMessage::receive,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, HeldItemPreferenceSnapshotMessage.class,
                HeldItemPreferenceSnapshotMessage::write,
                HeldItemPreferenceSnapshotMessage::read,
                HeldItemPreferenceSnapshotMessage::receive,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, MovementAnimationPreferenceUpdateMessage.class,
                MovementAnimationPreferenceUpdateMessage::write,
                MovementAnimationPreferenceUpdateMessage::read,
                MovementAnimationPreferenceUpdateMessage::receive,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, MovementAnimationPreferenceSnapshotMessage.class,
                MovementAnimationPreferenceSnapshotMessage::write,
                MovementAnimationPreferenceSnapshotMessage::read,
                MovementAnimationPreferenceSnapshotMessage::receive,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, OwnerPreferenceEpochMessage.class,
                OwnerPreferenceEpochMessage::write,
                OwnerPreferenceEpochMessage::read,
                OwnerPreferenceEpochMessage::receive,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, MaidPreferenceQueryMessage.class,
                MaidPreferenceQueryMessage::write,
                MaidPreferenceQueryMessage::read,
                MaidPreferenceQueryMessage::receive,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, MaidPreferenceUpdateMessage.class,
                MaidPreferenceUpdateMessage::write,
                MaidPreferenceUpdateMessage::read,
                MaidPreferenceUpdateMessage::receive,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, MaidMovementPreferenceQueryMessage.class,
                MaidMovementPreferenceQueryMessage::write,
                MaidMovementPreferenceQueryMessage::read,
                MaidMovementPreferenceQueryMessage::receive,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, MaidMovementPreferenceUpdateMessage.class,
                MaidMovementPreferenceUpdateMessage::write,
                MaidMovementPreferenceUpdateMessage::read,
                MaidMovementPreferenceUpdateMessage::receive,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, MaidPreferenceSnapshotMessage.class,
                MaidPreferenceSnapshotMessage::write,
                MaidPreferenceSnapshotMessage::read,
                MaidPreferenceSnapshotMessage::receive,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, SubEntityPreferenceQueryMessage.class,
                SubEntityPreferenceQueryMessage::write,
                SubEntityPreferenceQueryMessage::read,
                SubEntityPreferenceQueryMessage::receive,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, SubEntityPreferenceUpdateMessage.class,
                SubEntityPreferenceUpdateMessage::write,
                SubEntityPreferenceUpdateMessage::read,
                SubEntityPreferenceUpdateMessage::receive,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id, SubEntityPreferenceSnapshotMessage.class,
                SubEntityPreferenceSnapshotMessage::write,
                SubEntityPreferenceSnapshotMessage::read,
                SubEntityPreferenceSnapshotMessage::receive,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
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

    public static void toTrackers(Entity entity, Object message) {
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> entity), message);
    }

    public static void sendConfigurationUpdate(Map<String, Double> changes) {
        CHANNEL.sendToServer(new ConfigurationVariableUpdateMessage(changes));
    }

    public static void sendHeldItemPreferences(HeldItemModelDisplayState state) {
        CHANNEL.sendToServer(new HeldItemPreferenceUpdateMessage(state));
    }

    public static void sendMovementAnimationPreferences(
            MovementAnimationDisplayState state) {
        CHANNEL.sendToServer(new MovementAnimationPreferenceUpdateMessage(state));
    }

    public static void sendMaidPreferences(MaidPreferenceUpdateMessage message) {
        CHANNEL.sendToServer(message);
    }

    public static void sendMaidMovementPreferences(
            MaidMovementPreferenceUpdateMessage message) {
        CHANNEL.sendToServer(message);
    }

    public static void sendSubEntityPreferences(
            SubEntityPreferenceUpdateMessage message) {
        CHANNEL.sendToServer(message);
    }

    public static void sendOwnerPreferenceEpoch(
            UUID heldItemEpoch, UUID movementEpoch) {
        CHANNEL.sendToServer(new OwnerPreferenceEpochMessage(
                heldItemEpoch, movementEpoch));
    }
}
