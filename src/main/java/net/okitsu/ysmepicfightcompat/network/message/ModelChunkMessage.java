package net.okitsu.ysmepicfightcompat.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import net.okitsu.ysmepicfightcompat.network.geometry.ClientModelTransfers;
import net.okitsu.ysmepicfightcompat.network.geometry.GeometryTransferCodec;

import java.util.UUID;
import java.util.function.Supplier;

/** One bounded portion of a texture-free model transfer. */
public record ModelChunkMessage(UUID transferId, String modelId, int totalBytes,
                                int chunkIndex, int chunkCount, byte[] bytes) {
    public static final int CHUNK_BYTES = 512 * 1024;
    private static final int MAX_CHUNKS =
            (GeometryTransferCodec.MAX_COMPRESSED_BYTES + CHUNK_BYTES - 1) / CHUNK_BYTES;

    public ModelChunkMessage {
        if (transferId == null || modelId == null || modelId.isBlank()
                || bytes == null || bytes.length > CHUNK_BYTES) {
            throw new IllegalArgumentException("Invalid model transfer chunk");
        }
        if (totalBytes == -1) {
            if (chunkIndex != 0 || chunkCount != 0 || bytes.length != 0) {
                throw new IllegalArgumentException("Invalid unavailable model response");
            }
        } else if (totalBytes <= 0 || totalBytes > GeometryTransferCodec.MAX_COMPRESSED_BYTES
                || chunkCount <= 0 || chunkCount > MAX_CHUNKS
                || chunkIndex < 0 || chunkIndex >= chunkCount) {
            throw new IllegalArgumentException("Invalid model transfer bounds");
        }
    }

    public static ModelChunkMessage unavailable(String modelId) {
        return new ModelChunkMessage(UUID.randomUUID(), modelId, -1, 0, 0, new byte[0]);
    }

    public static void write(ModelChunkMessage message, FriendlyByteBuf output) {
        output.writeUUID(message.transferId());
        output.writeUtf(message.modelId(), ModelRequestMessage.MAX_MODEL_ID_BYTES);
        output.writeInt(message.totalBytes());
        output.writeVarInt(message.chunkIndex());
        output.writeVarInt(message.chunkCount());
        output.writeByteArray(message.bytes());
    }

    public static ModelChunkMessage read(FriendlyByteBuf input) {
        return new ModelChunkMessage(input.readUUID(),
                input.readUtf(ModelRequestMessage.MAX_MODEL_ID_BYTES), input.readInt(),
                input.readVarInt(), input.readVarInt(), input.readByteArray(CHUNK_BYTES));
    }

    public static void receive(ModelChunkMessage message,
                               Supplier<NetworkEvent.Context> suppliedContext) {
        NetworkEvent.Context context = suppliedContext.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> ClientModelTransfers.accept(message));
        }
        context.setPacketHandled(true);
    }
}
