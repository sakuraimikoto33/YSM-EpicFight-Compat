package net.okitsu.ysmepicfightcompat.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import net.okitsu.ysmepicfightcompat.network.HeldItemModelDisplayState;
import net.okitsu.ysmepicfightcompat.network.RemoteHeldItemModelPreferences;

import java.util.UUID;
import java.util.function.Supplier;

/** Server-to-client snapshot containing only one player's resolved display state. */
public record HeldItemPreferenceSnapshotMessage(UUID playerId,
                                                boolean mainHandYsm,
                                                boolean offHandYsm) {
    public HeldItemPreferenceSnapshotMessage {
        if (playerId == null) {
            throw new IllegalArgumentException("Missing player ID");
        }
    }

    public static void write(HeldItemPreferenceSnapshotMessage message,
                             FriendlyByteBuf output) {
        output.writeUUID(message.playerId());
        output.writeBoolean(message.mainHandYsm());
        output.writeBoolean(message.offHandYsm());
    }

    public static HeldItemPreferenceSnapshotMessage read(FriendlyByteBuf input) {
        return new HeldItemPreferenceSnapshotMessage(input.readUUID(),
                input.readBoolean(), input.readBoolean());
    }

    public static void receive(HeldItemPreferenceSnapshotMessage message,
                               Supplier<NetworkEvent.Context> suppliedContext) {
        NetworkEvent.Context context = suppliedContext.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> RemoteHeldItemModelPreferences.accept(
                    message.playerId(), new HeldItemModelDisplayState(
                            message.mainHandYsm(), message.offHandYsm())));
        }
        context.setPacketHandled(true);
    }
}
