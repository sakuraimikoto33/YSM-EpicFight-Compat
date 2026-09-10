package net.okitsu.ysmepicfightcompat.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.network.RemoteSelectionState;
import net.okitsu.ysmepicfightcompat.render.PlayerSelectionResolver;

import java.util.UUID;

/** Server snapshot of one player's official YSM selection. */
public record SelectionUpdateMessage(UUID playerId, String modelId,
                                     String textureName, boolean disabled) implements CustomPacketPayload {
    public static final Type<SelectionUpdateMessage> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(CompatMod.MOD_ID, "selection_update"));
    public static final StreamCodec<FriendlyByteBuf, SelectionUpdateMessage> STREAM_CODEC =
            StreamCodec.of((output, message) -> write(message, output), SelectionUpdateMessage::read);

    @Override
    public Type<SelectionUpdateMessage> type() {
        return TYPE;
    }

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
                               IPayloadContext context) {
        if (context.flow() == PacketFlow.CLIENTBOUND) {
            context.enqueueWork(() -> {
                RemoteSelectionState.accept(message.playerId(), message.modelId(),
                        message.textureName(), message.disabled());
                PlayerSelectionResolver.clear();
            });
        }
    }
}
