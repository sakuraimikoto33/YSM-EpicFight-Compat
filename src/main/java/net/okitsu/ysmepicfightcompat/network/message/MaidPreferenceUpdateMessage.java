package net.okitsu.ysmepicfightcompat.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.okitsu.ysmepicfightcompat.network.HeldItemModelDisplayState;
import net.okitsu.ysmepicfightcompat.network.MaidPreferenceBroadcaster;

import java.util.UUID;
import java.util.function.Supplier;

/** Owner-to-server response containing decisions, never the owner's local rules. */
public record MaidPreferenceUpdateMessage(
        UUID queryId,
        int entityId,
        UUID entityUuid,
        UUID policyEpoch,
        long revision,
        HeldItemModelDisplayState heldItems) {
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
                               Supplier<NetworkEvent.Context> suppliedContext) {
        NetworkEvent.Context context = suppliedContext.get();
        ServerPlayer sender = context.getSender();
        if (sender != null && context.getDirection().getReceptionSide().isServer()) {
            context.enqueueWork(() -> MaidPreferenceBroadcaster.accept(sender, message));
        }
        context.setPacketHandled(true);
    }
}
