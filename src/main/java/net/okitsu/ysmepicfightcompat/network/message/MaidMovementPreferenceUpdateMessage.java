package net.okitsu.ysmepicfightcompat.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.okitsu.ysmepicfightcompat.network.MaidPreferenceBroadcaster;

import java.util.UUID;
import java.util.function.Supplier;

/** Owner response containing one movement decision, never its local rule. */
public record MaidMovementPreferenceUpdateMessage(
        UUID queryId,
        int entityId,
        UUID entityUuid,
        UUID policyEpoch,
        long revision,
        boolean ysmMovement) {
    public MaidMovementPreferenceUpdateMessage {
        if (queryId == null || entityId < 0 || entityUuid == null
                || policyEpoch == null || revision <= 0L) {
            throw new IllegalArgumentException("Invalid maid movement response");
        }
    }

    public static void write(MaidMovementPreferenceUpdateMessage message,
                             FriendlyByteBuf output) {
        output.writeUUID(message.queryId());
        output.writeVarInt(message.entityId());
        output.writeUUID(message.entityUuid());
        output.writeUUID(message.policyEpoch());
        output.writeVarLong(message.revision());
        output.writeBoolean(message.ysmMovement());
    }

    public static MaidMovementPreferenceUpdateMessage read(FriendlyByteBuf input) {
        return new MaidMovementPreferenceUpdateMessage(
                input.readUUID(), input.readVarInt(), input.readUUID(),
                input.readUUID(), input.readVarLong(), input.readBoolean());
    }

    public static void receive(MaidMovementPreferenceUpdateMessage message,
                               Supplier<NetworkEvent.Context> suppliedContext) {
        NetworkEvent.Context context = suppliedContext.get();
        ServerPlayer sender = context.getSender();
        if (sender != null && context.getDirection().getReceptionSide().isServer()) {
            context.enqueueWork(() -> MaidPreferenceBroadcaster.accept(sender, message));
        }
        context.setPacketHandled(true);
    }
}
