package net.okitsu.ysmepicfightcompat.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.network.HeldItemModelDisplayState;
import net.okitsu.ysmepicfightcompat.network.MaidPreferenceBroadcaster;

import java.util.UUID;

/** Owner-to-server response containing decisions, never the owner's local rules. */
public record MaidPreferenceUpdateMessage(
        UUID queryId,
        int entityId,
        UUID entityUuid,
        UUID policyEpoch,
        long revision,
        HeldItemModelDisplayState heldItems) implements CustomPacketPayload {
    public static final Type<MaidPreferenceUpdateMessage> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(CompatMod.MOD_ID, "maid_preference_update"));
    public static final StreamCodec<FriendlyByteBuf, MaidPreferenceUpdateMessage> STREAM_CODEC =
            StreamCodec.of((output, message) -> write(message, output), MaidPreferenceUpdateMessage::read);

    @Override
    public Type<MaidPreferenceUpdateMessage> type() {
        return TYPE;
    }

    public MaidPreferenceUpdateMessage {
        if (queryId == null || entityId < 0 || entityUuid == null
                || policyEpoch == null || revision <= 0L
                || heldItems == null) {
            throw new IllegalArgumentException("Invalid maid preference response");
        }
    }

    public static void write(MaidPreferenceUpdateMessage message,
                             FriendlyByteBuf output) {
        output.writeUUID(message.queryId());
        output.writeVarInt(message.entityId());
        output.writeUUID(message.entityUuid());
        output.writeUUID(message.policyEpoch());
        output.writeVarLong(message.revision());
        HeldItemModelDisplayState held = message.heldItems();
        output.writeBoolean(held.mainHandYsm());
        output.writeBoolean(held.offHandYsm());
        output.writeBoolean(held.mainHandYsmSwitchAnimation());
        output.writeBoolean(held.offHandYsmSwitchAnimation());
    }

    public static MaidPreferenceUpdateMessage read(FriendlyByteBuf input) {
        UUID queryId = input.readUUID();
        int entityId = input.readVarInt();
        UUID entityUuid = input.readUUID();
        UUID policyEpoch = input.readUUID();
        long revision = input.readVarLong();
        HeldItemModelDisplayState held = new HeldItemModelDisplayState(
                input.readBoolean(), input.readBoolean(),
                input.readBoolean(), input.readBoolean());
        return new MaidPreferenceUpdateMessage(
                queryId, entityId, entityUuid, policyEpoch,
                revision, held);
    }

    public static void receive(MaidPreferenceUpdateMessage message,
                               IPayloadContext context) {
        if (context.flow() == PacketFlow.SERVERBOUND
                && context.player() instanceof ServerPlayer sender) {
            context.enqueueWork(() -> MaidPreferenceBroadcaster.accept(sender, message));
        }
    }
}
