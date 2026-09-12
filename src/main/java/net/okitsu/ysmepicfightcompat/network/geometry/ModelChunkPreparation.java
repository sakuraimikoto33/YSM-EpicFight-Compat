package net.okitsu.ysmepicfightcompat.network.geometry;

import net.okitsu.ysmepicfightcompat.network.message.ModelChunkMessage;
import net.okitsu.ysmepicfightcompat.network.message.ModelRequestMessage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Pure worker-side slicing and encoding, shared by every recipient of one prepared model. */
final class ModelChunkPreparation {
    static final long MAX_RETAINED_BYTES = retainedBytes(
            ModelRequestMessage.MAX_MODEL_ID_BYTES, GeometryTransferCodec.MAX_COMPRESSED_BYTES);

    private ModelChunkPreparation() {
    }

    static long retainedBytes(int modelNameCharacters, int payloadBytes) {
        if (modelNameCharacters < 0 || payloadBytes <= 0
                || payloadBytes > GeometryTransferCodec.MAX_COMPRESSED_BYTES) {
            throw new IllegalArgumentException("Invalid prepared model bounds");
        }
        long chunks = (payloadBytes + ModelChunkMessage.CHUNK_BYTES - 1)
                / ModelChunkMessage.CHUNK_BYTES;
        // Includes the source, wire payload, UTF-8 name and conservative per-chunk framing.
        return 2L * payloadBytes + chunks * (3L * modelNameCharacters + 128L);
    }

    static <P> List<P> prepare(String modelId, byte[] digest, byte[] payload,
                              BooleanSupplier current, Function<ModelChunkMessage, P> encode) {
        if (payload.length == 0 || payload.length > GeometryTransferCodec.MAX_COMPRESSED_BYTES) {
            throw new IllegalArgumentException("Invalid prepared model size");
        }
        int count = (payload.length + ModelChunkMessage.CHUNK_BYTES - 1)
                / ModelChunkMessage.CHUNK_BYTES;
        List<P> packets = new ArrayList<>(count);
        UUID transfer = UUID.randomUUID();
        for (int index = 0; index < count; index++) {
            if (!current.getAsBoolean()) {
                return List.of();
            }
            int offset = index * ModelChunkMessage.CHUNK_BYTES;
            int end = Math.min(payload.length, offset + ModelChunkMessage.CHUNK_BYTES);
            packets.add(encode.apply(new ModelChunkMessage(ModelChunkMessage.Status.DATA,
                    transfer, modelId, digest, payload.length, index, count,
                    Arrays.copyOfRange(payload, offset, end))));
        }
        return List.copyOf(packets);
    }
}
