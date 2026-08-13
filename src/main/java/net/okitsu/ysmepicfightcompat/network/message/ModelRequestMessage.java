package net.okitsu.ysmepicfightcompat.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.okitsu.ysmepicfightcompat.network.CompatNetwork;
import net.okitsu.ysmepicfightcompat.network.geometry.ServerModelTransfers;

import java.util.function.Supplier;

/** Client request for texture-free geometry of a currently selected server model. */
public record ModelRequestMessage(String modelId) {
    public static final int MAX_MODEL_ID_BYTES = 4096;

    public ModelRequestMessage {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("Empty model id");
        }
    }

    public static void write(ModelRequestMessage message, FriendlyByteBuf output) {
        output.writeUtf(message.modelId(), MAX_MODEL_ID_BYTES);
    }

    public static ModelRequestMessage read(FriendlyByteBuf input) {
        return new ModelRequestMessage(input.readUtf(MAX_MODEL_ID_BYTES));
    }

    public static void receive(ModelRequestMessage message,
                               Supplier<NetworkEvent.Context> suppliedContext) {
        NetworkEvent.Context context = suppliedContext.get();
        ServerPlayer sender = context.getSender();
        if (sender != null && context.getDirection().getReceptionSide().isServer()
                && CompatNetwork.isConnected(sender)) {
            context.enqueueWork(() -> ServerModelTransfers.request(sender, message.modelId()));
        }
        context.setPacketHandled(true);
    }
}
