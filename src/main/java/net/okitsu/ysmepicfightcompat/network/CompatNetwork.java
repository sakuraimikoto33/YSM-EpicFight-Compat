package net.okitsu.ysmepicfightcompat.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
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
import net.okitsu.ysmepicfightcompat.network.message.ConfigurationVariableScopeRequestMessage;
import net.okitsu.ysmepicfightcompat.network.message.ConfigurationVariableScopeReplyMessage;
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
import net.okitsu.ysmepicfightcompat.network.message.ScriptSyncRequestMessage;
import net.okitsu.ysmepicfightcompat.network.message.ScriptSyncSnapshotMessage;
import net.okitsu.ysmepicfightcompat.network.message.ShieldBlockMessage;
import net.okitsu.ysmepicfightcompat.network.message.SubEntityPreferenceQueryMessage;
import net.okitsu.ysmepicfightcompat.network.message.SubEntityPreferenceSnapshotMessage;
import net.okitsu.ysmepicfightcompat.network.message.SubEntityPreferenceUpdateMessage;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Forge channel for compatibility-owned state; official YSM's channel remains untouched. */
public final class CompatNetwork {
    public static final String PROTOCOL = "1";
    private static final ResourceLocation BRIDGE =
            ResourceLocation.fromNamespaceAndPath(CompatMod.MOD_ID, "bridge");
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            BRIDGE,
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
        CHANNEL.registerMessage(id++, SubEntityPreferenceSnapshotMessage.class,
                SubEntityPreferenceSnapshotMessage::write,
                SubEntityPreferenceSnapshotMessage::read,
                SubEntityPreferenceSnapshotMessage::receive,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, ScriptSyncRequestMessage.class,
                ScriptSyncRequestMessage::write, ScriptSyncRequestMessage::read,
                ScriptSyncRequestMessage::receive,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, ScriptSyncSnapshotMessage.class,
                ScriptSyncSnapshotMessage::write, ScriptSyncSnapshotMessage::read,
                ScriptSyncSnapshotMessage::receive,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, ShieldBlockMessage.class,
                ShieldBlockMessage::write, ShieldBlockMessage::read,
                ShieldBlockMessage::receive,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, ConfigurationVariableScopeRequestMessage.class,
                ConfigurationVariableScopeRequestMessage::write,
                ConfigurationVariableScopeRequestMessage::read,
                ConfigurationVariableScopeRequestMessage::receive,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id, ConfigurationVariableScopeReplyMessage.class,
                ConfigurationVariableScopeReplyMessage::write,
                ConfigurationVariableScopeReplyMessage::read,
                ConfigurationVariableScopeReplyMessage::receive,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static boolean isConnected(ServerPlayer player) {
        return player != null && player.connection != null
                && player.connection.connection != null
                && player.connection.connection.isConnected();
    }

    /** Immutable wire bytes prepared by a model worker, with a fresh buffer per recipient. */
    public static final class PreparedModelPacket {
        private final byte[] wireBytes;

        private PreparedModelPacket(byte[] wireBytes) {
            this.wireBytes = wireBytes;
        }

        public ClientboundCustomPayloadPacket packet() {
            return new ClientboundCustomPayloadPacket(BRIDGE,
                    new FriendlyByteBuf(Unpooled.wrappedBuffer(wireBytes).asReadOnly()));
        }
    }

    /** Called on model workers after channel registration; never reads game state. */
    public static PreparedModelPacket prepareModelChunk(ModelChunkMessage message) {
        FriendlyByteBuf output = new FriendlyByteBuf(Unpooled.buffer());
        try {
            CHANNEL.encodeMessage(message, output);
            byte[] bytes = new byte[output.readableBytes()];
            output.getBytes(output.readerIndex(), bytes);
            return new PreparedModelPacket(bytes);
        } finally {
            output.release();
        }
    }

    public static void sendPreparedModelChunk(ServerPlayer player, PreparedModelPacket packet) {
        if (isConnected(player)) {
            player.connection.send(packet.packet());
        }
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

    public static void sendConfigurationUpdate(ConfigurationVariableUpdateMessage message) {
        CHANNEL.sendToServer(message);
    }

    public static void requestConfigurationScope(ConfigurationVariableScopeRequestMessage message) {
        CHANNEL.sendToServer(message);
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
