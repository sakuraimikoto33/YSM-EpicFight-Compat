package net.okitsu.ysmepicfightcompat.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.network.MaidPreferenceBroadcaster;
import net.okitsu.ysmepicfightcompat.network.SubEntityPreferenceBroadcaster;

import java.util.UUID;

/** Opaque generations; no client preference or rule content crosses the wire. */
public record OwnerPreferenceEpochMessage(
        UUID heldItemPolicyEpoch,
        UUID movementPolicyEpoch) implements CustomPacketPayload {
    public static final Type<OwnerPreferenceEpochMessage> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(CompatMod.MOD_ID, "owner_preference_epoch"));
    public static final StreamCodec<FriendlyByteBuf, OwnerPreferenceEpochMessage> STREAM_CODEC =
            StreamCodec.of((output, message) -> write(message, output), OwnerPreferenceEpochMessage::read);

    @Override
    public Type<OwnerPreferenceEpochMessage> type() {
        return TYPE;
    }

    public OwnerPreferenceEpochMessage {
        if (heldItemPolicyEpoch == null || movementPolicyEpoch == null) {
            throw new IllegalArgumentException("Missing owner preference epoch");
        }
    }

    public static void write(OwnerPreferenceEpochMessage message,
                             FriendlyByteBuf output) {
        output.writeUUID(message.heldItemPolicyEpoch());
        output.writeUUID(message.movementPolicyEpoch());
    }

    public static OwnerPreferenceEpochMessage read(FriendlyByteBuf input) {
        return new OwnerPreferenceEpochMessage(input.readUUID(), input.readUUID());
    }

    public static void receive(OwnerPreferenceEpochMessage message,
                               IPayloadContext context) {
        if (context.flow() == PacketFlow.SERVERBOUND
                && context.player() instanceof ServerPlayer sender) {
            context.enqueueWork(() -> {
                MaidPreferenceBroadcaster.acceptEpoch(
                        sender, message.heldItemPolicyEpoch(),
                        message.movementPolicyEpoch());
                SubEntityPreferenceBroadcaster.acceptEpoch(
                        sender, message.heldItemPolicyEpoch());
            });
        }
    }
}
