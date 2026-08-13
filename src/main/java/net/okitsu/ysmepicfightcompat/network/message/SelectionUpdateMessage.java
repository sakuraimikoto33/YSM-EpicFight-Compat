package net.okitsu.ysmepicfightcompat.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import net.okitsu.ysmepicfightcompat.network.RemoteSelectionState;

import java.util.UUID;
import java.util.function.Supplier;

/** Server snapshot of one player's official YSM selection. */
public record SelectionUpdateMessage(UUID playerId, String modelId,
                                     String textureName, boolean disabled) {
    private static final int MAX_TEXT = 4096;

    public SelectionUpdateMessage {
        if (playerId == null || modelId == null || textureName == null) {
            throw new IllegalArgumentException("Invalid player selection message");
        }
    }

    public static void write(SelectionUpdateMessage message, FriendlyByteBuf output) {
        output.writeUUID(message.playerId());
        output.writeUtf(message.modelId(), MAX_TEXT);
        output.writeUtf(message.textureName(), MAX_TEXT);
        output.writeBoolean(message.disabled());
    }

    public static SelectionUpdateMessage read(FriendlyByteBuf input) {
        return new SelectionUpdateMessage(input.readUUID(),
                input.readUtf(MAX_TEXT), input.readUtf(MAX_TEXT), input.readBoolean());
    }

    public static void receive(SelectionUpdateMessage message,
                               Supplier<NetworkEvent.Context> suppliedContext) {
        NetworkEvent.Context context = suppliedContext.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> RemoteSelectionState.accept(
                    message.playerId(), message.modelId(), message.textureName(), message.disabled()));
        }
        context.setPacketHandled(true);
    }
}
