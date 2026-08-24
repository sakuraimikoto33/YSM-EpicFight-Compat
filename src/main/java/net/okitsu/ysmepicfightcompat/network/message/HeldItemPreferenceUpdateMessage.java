package net.okitsu.ysmepicfightcompat.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.okitsu.ysmepicfightcompat.network.HeldItemModelDisplayState;
import net.okitsu.ysmepicfightcompat.network.HeldItemPreferenceBroadcaster;

import java.util.function.Supplier;

/** Client-to-server update containing only the sender's resolved display state. */
public record HeldItemPreferenceUpdateMessage(boolean mainHandYsm,
                                              boolean offHandYsm) {
    public HeldItemPreferenceUpdateMessage(HeldItemModelDisplayState state) {
        this(state.mainHandYsm(), state.offHandYsm());
    }

    public static void write(HeldItemPreferenceUpdateMessage message,
                             FriendlyByteBuf output) {
        output.writeBoolean(message.mainHandYsm());
        output.writeBoolean(message.offHandYsm());
    }

    public static HeldItemPreferenceUpdateMessage read(FriendlyByteBuf input) {
        return new HeldItemPreferenceUpdateMessage(
                input.readBoolean(), input.readBoolean());
    }

    public static void receive(HeldItemPreferenceUpdateMessage message,
                               Supplier<NetworkEvent.Context> suppliedContext) {
        NetworkEvent.Context context = suppliedContext.get();
        ServerPlayer sender = context.getSender();
        if (sender != null) {
            context.enqueueWork(() -> HeldItemPreferenceBroadcaster.accept(sender,
                    new HeldItemModelDisplayState(message.mainHandYsm(),
                            message.offHandYsm())));
        }
        context.setPacketHandled(true);
    }
}
