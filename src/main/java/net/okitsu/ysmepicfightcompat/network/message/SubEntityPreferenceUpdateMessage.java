package net.okitsu.ysmepicfightcompat.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.okitsu.ysmepicfightcompat.network.SubEntityModelKind;
import net.okitsu.ysmepicfightcompat.network.SubEntityPreferenceBroadcaster;

import java.util.UUID;
import java.util.function.Supplier;

/** Owner-to-server response containing one result, never the owner's local rules. */
public record SubEntityPreferenceUpdateMessage(
        UUID queryId,
        int entityId,
        UUID entityUuid,
        UUID ownerUuid,
        UUID policyEpoch,
        long revision,
        SubEntityModelKind kind,
        boolean ysm) {
    public SubEntityPreferenceUpdateMessage {
        if (queryId == null || entityId < 0 || entityUuid == null
                || ownerUuid == null || policyEpoch == null || revision <= 0L
                || kind == null) {
            throw new IllegalArgumentException("Invalid sub-entity preference response");
        }
    }

    public static void write(SubEntityPreferenceUpdateMessage message,
                             FriendlyByteBuf output) {
        output.writeUUID(message.queryId());
        output.writeVarInt(message.entityId());
        output.writeUUID(message.entityUuid());
        output.writeUUID(message.ownerUuid());
        output.writeUUID(message.policyEpoch());
        output.writeVarLong(message.revision());
        output.writeByte(message.kind().ordinal());
        output.writeBoolean(message.ysm());
    }

    public static SubEntityPreferenceUpdateMessage read(FriendlyByteBuf input) {
        return new SubEntityPreferenceUpdateMessage(
                input.readUUID(), input.readVarInt(), input.readUUID(),
                input.readUUID(), input.readUUID(), input.readVarLong(),
                SubEntityModelKind.fromNetworkId(input.readUnsignedByte()),
                input.readBoolean());
    }

    public static void receive(SubEntityPreferenceUpdateMessage message,
                               Supplier<NetworkEvent.Context> suppliedContext) {
        NetworkEvent.Context context = suppliedContext.get();
        ServerPlayer sender = context.getSender();
        if (sender != null && context.getDirection().getReceptionSide().isServer()) {
            context.enqueueWork(() -> SubEntityPreferenceBroadcaster.accept(sender, message));
        }
        context.setPacketHandled(true);
    }
}
