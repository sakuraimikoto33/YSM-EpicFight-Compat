package net.okitsu.ysmepicfightcompat.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import net.okitsu.ysmepicfightcompat.network.geometry.ClientModelTransfers;
import net.okitsu.ysmepicfightcompat.network.geometry.GeometryTransferCodec;
import net.okitsu.ysmepicfightcompat.cache.ModelDiskCache;

import java.util.Arrays;
import java.util.UUID;
import java.util.function.Supplier;

/** One bounded response or data chunk for a server model request. */
public record ModelChunkMessage(Status status, UUID transferId, String modelId,
                                byte[] payloadDigest, int totalBytes,
                                int chunkIndex, int chunkCount, byte[] bytes) {
    public enum Status {
        DATA,
        UNCHANGED,
        UNAVAILABLE
    }

    public static final int CHUNK_BYTES = 512 * 1024;
    private static final int MAX_CHUNKS =
            (GeometryTransferCodec.MAX_COMPRESSED_BYTES + CHUNK_BYTES - 1) / CHUNK_BYTES;

    public ModelChunkMessage {
        if (status == null || transferId == null || modelId == null || modelId.isBlank()
                || bytes == null || bytes.length > CHUNK_BYTES) {
            throw new IllegalArgumentException("Invalid model transfer chunk");
        }
        if (payloadDigest == null || (status == Status.UNAVAILABLE
                ? payloadDigest.length != 0
                : payloadDigest.length != ModelDiskCache.DIGEST_BYTES)) {
            throw new IllegalArgumentException("Invalid model payload digest");
        }
        payloadDigest = Arrays.copyOf(payloadDigest, payloadDigest.length);
        bytes = Arrays.copyOf(bytes, bytes.length);
        if (status == Status.DATA) {
            if (totalBytes <= 0 || totalBytes > GeometryTransferCodec.MAX_COMPRESSED_BYTES
                || chunkCount <= 0 || chunkCount > MAX_CHUNKS
                || chunkIndex < 0 || chunkIndex >= chunkCount) {
                throw new IllegalArgumentException("Invalid model transfer bounds");
            }
        } else if (totalBytes != 0 || chunkIndex != 0 || chunkCount != 0
                || bytes.length != 0) {
            throw new IllegalArgumentException("Invalid model status response");
        }
    }

    public static ModelChunkMessage unavailable(String modelId) {
        return new ModelChunkMessage(Status.UNAVAILABLE, UUID.randomUUID(), modelId,
                new byte[0], 0, 0, 0, new byte[0]);
    }

    public static ModelChunkMessage unchanged(String modelId, byte[] payloadDigest) {
        return new ModelChunkMessage(Status.UNCHANGED, UUID.randomUUID(), modelId,
                payloadDigest, 0, 0, 0, new byte[0]);
    }

    @Override
    public byte[] payloadDigest() {
        return Arrays.copyOf(payloadDigest, payloadDigest.length);
    }

    @Override
    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }

    public static void write(ModelChunkMessage message, FriendlyByteBuf output) {
        output.writeEnum(message.status());
        output.writeUUID(message.transferId());
        output.writeUtf(message.modelId(), ModelRequestMessage.MAX_MODEL_ID_BYTES);
        output.writeByteArray(message.payloadDigest());
        output.writeInt(message.totalBytes());
        output.writeVarInt(message.chunkIndex());
        output.writeVarInt(message.chunkCount());
        output.writeByteArray(message.bytes());
    }

    public static ModelChunkMessage read(FriendlyByteBuf input) {
        return new ModelChunkMessage(input.readEnum(Status.class), input.readUUID(),
                input.readUtf(ModelRequestMessage.MAX_MODEL_ID_BYTES),
                input.readByteArray(ModelDiskCache.DIGEST_BYTES), input.readInt(),
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
