package net.okitsu.ysmepicfightcompat.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
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

import java.util.UUID;

/** NeoForge payloads for compatibility-owned state; official YSM's payloads remain untouched. */
public final class CompatNetwork {
    public static final String PROTOCOL = "1";

    private CompatNetwork() {
    }

    public static void registerMessages(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL)
                .executesOn(HandlerThread.NETWORK);
        registrar.playToClient(SelectionUpdateMessage.TYPE,
                SelectionUpdateMessage.STREAM_CODEC, SelectionUpdateMessage::receive);
        registrar.playToServer(ModelRequestMessage.TYPE,
                ModelRequestMessage.STREAM_CODEC, ModelRequestMessage::receive);
        registrar.playToClient(ModelChunkMessage.TYPE,
                ModelChunkMessage.STREAM_CODEC, ModelChunkMessage::receive);
        registrar.playToServer(ConfigurationVariableUpdateMessage.TYPE,
                ConfigurationVariableUpdateMessage.STREAM_CODEC, ConfigurationVariableUpdateMessage::receive);
        registrar.playToClient(ConfigurationVariableSnapshotMessage.TYPE,
                ConfigurationVariableSnapshotMessage.STREAM_CODEC, ConfigurationVariableSnapshotMessage::receive);
        registrar.playToClient(AttackSwingSoundMessage.TYPE,
                AttackSwingSoundMessage.STREAM_CODEC, AttackSwingSoundMessage::receive);
        registrar.playToServer(HeldItemPreferenceUpdateMessage.TYPE,
                HeldItemPreferenceUpdateMessage.STREAM_CODEC, HeldItemPreferenceUpdateMessage::receive);
        registrar.playToClient(HeldItemPreferenceSnapshotMessage.TYPE,
                HeldItemPreferenceSnapshotMessage.STREAM_CODEC, HeldItemPreferenceSnapshotMessage::receive);
        registrar.playToServer(MovementAnimationPreferenceUpdateMessage.TYPE,
                MovementAnimationPreferenceUpdateMessage.STREAM_CODEC, MovementAnimationPreferenceUpdateMessage::receive);
        registrar.playToClient(MovementAnimationPreferenceSnapshotMessage.TYPE,
                MovementAnimationPreferenceSnapshotMessage.STREAM_CODEC, MovementAnimationPreferenceSnapshotMessage::receive);
        registrar.playToServer(OwnerPreferenceEpochMessage.TYPE,
                OwnerPreferenceEpochMessage.STREAM_CODEC, OwnerPreferenceEpochMessage::receive);
        registrar.playToClient(MaidPreferenceQueryMessage.TYPE,
                MaidPreferenceQueryMessage.STREAM_CODEC, MaidPreferenceQueryMessage::receive);
        registrar.playToServer(MaidPreferenceUpdateMessage.TYPE,
                MaidPreferenceUpdateMessage.STREAM_CODEC, MaidPreferenceUpdateMessage::receive);
        registrar.playToClient(MaidMovementPreferenceQueryMessage.TYPE,
                MaidMovementPreferenceQueryMessage.STREAM_CODEC, MaidMovementPreferenceQueryMessage::receive);
        registrar.playToServer(MaidMovementPreferenceUpdateMessage.TYPE,
                MaidMovementPreferenceUpdateMessage.STREAM_CODEC, MaidMovementPreferenceUpdateMessage::receive);
        registrar.playToClient(MaidPreferenceSnapshotMessage.TYPE,
                MaidPreferenceSnapshotMessage.STREAM_CODEC, MaidPreferenceSnapshotMessage::receive);
        registrar.playToClient(SubEntityPreferenceQueryMessage.TYPE,
                SubEntityPreferenceQueryMessage.STREAM_CODEC, SubEntityPreferenceQueryMessage::receive);
        registrar.playToServer(SubEntityPreferenceUpdateMessage.TYPE,
                SubEntityPreferenceUpdateMessage.STREAM_CODEC, SubEntityPreferenceUpdateMessage::receive);
        registrar.playToClient(SubEntityPreferenceSnapshotMessage.TYPE,
                SubEntityPreferenceSnapshotMessage.STREAM_CODEC, SubEntityPreferenceSnapshotMessage::receive);
        registrar.playToServer(ScriptSyncRequestMessage.TYPE,
                ScriptSyncRequestMessage.STREAM_CODEC, ScriptSyncRequestMessage::receive);
        registrar.playToClient(ScriptSyncSnapshotMessage.TYPE,
                ScriptSyncSnapshotMessage.STREAM_CODEC, ScriptSyncSnapshotMessage::receive);
        registrar.playToClient(ShieldBlockMessage.TYPE,
                ShieldBlockMessage.STREAM_CODEC, ShieldBlockMessage::receive);
        registrar.playToServer(ConfigurationVariableScopeRequestMessage.TYPE,
                ConfigurationVariableScopeRequestMessage.STREAM_CODEC, ConfigurationVariableScopeRequestMessage::receive);
        registrar.playToClient(ConfigurationVariableScopeReplyMessage.TYPE,
                ConfigurationVariableScopeReplyMessage.STREAM_CODEC, ConfigurationVariableScopeReplyMessage::receive);
    }

    public static boolean isConnected(ServerPlayer player) {
        return player != null && player.connection != null
                && player.connection.getConnection() != null
                && player.connection.getConnection().isConnected();
    }

    public static void toPlayer(ServerPlayer player, CustomPacketPayload message) {
        if (isConnected(player)) {
            PacketDistributor.sendToPlayer(player, message);
        }
    }

    public static void toTrackersAndSelf(Player player, CustomPacketPayload message) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, message);
    }

    public static void toTrackers(Entity entity, CustomPacketPayload message) {
        PacketDistributor.sendToPlayersTrackingEntity(entity, message);
    }

    public static void toServer(CustomPacketPayload message) {
        PacketDistributor.sendToServer(message);
    }

    public static void sendConfigurationUpdate(ConfigurationVariableUpdateMessage message) {
        PacketDistributor.sendToServer(message);
    }

    public static void requestConfigurationScope(ConfigurationVariableScopeRequestMessage message) {
        PacketDistributor.sendToServer(message);
    }

    public static void sendHeldItemPreferences(HeldItemModelDisplayState state) {
        PacketDistributor.sendToServer(new HeldItemPreferenceUpdateMessage(state));
    }

    public static void sendMovementAnimationPreferences(
            MovementAnimationDisplayState state) {
        PacketDistributor.sendToServer(new MovementAnimationPreferenceUpdateMessage(state));
    }

    public static void sendMaidPreferences(MaidPreferenceUpdateMessage message) {
        PacketDistributor.sendToServer(message);
    }

    public static void sendMaidMovementPreferences(
            MaidMovementPreferenceUpdateMessage message) {
        PacketDistributor.sendToServer(message);
    }

    public static void sendSubEntityPreferences(
            SubEntityPreferenceUpdateMessage message) {
        PacketDistributor.sendToServer(message);
    }

    public static void sendOwnerPreferenceEpoch(
            UUID heldItemEpoch, UUID movementEpoch) {
        PacketDistributor.sendToServer(new OwnerPreferenceEpochMessage(
                heldItemEpoch, movementEpoch));
    }
}
