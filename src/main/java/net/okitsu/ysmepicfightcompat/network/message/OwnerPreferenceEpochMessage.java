package net.okitsu.ysmepicfightcompat.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.okitsu.ysmepicfightcompat.network.MaidPreferenceBroadcaster;

import java.util.UUID;
import java.util.function.Supplier;

/** Opaque generations; no client preference or rule content crosses the wire. */
public record OwnerPreferenceEpochMessage(
        UUID heldItemPolicyEpoch,
        UUID movementPolicyEpoch) {
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
                               Supplier<NetworkEvent.Context> suppliedContext) {
        NetworkEvent.Context context = suppliedContext.get();
        ServerPlayer sender = context.getSender();
        if (sender != null && context.getDirection().getReceptionSide().isServer()) {
            context.enqueueWork(() -> MaidPreferenceBroadcaster.acceptEpoch(
                    sender, message.heldItemPolicyEpoch(),
                    message.movementPolicyEpoch()));
        }
        context.setPacketHandled(true);
    }
}
