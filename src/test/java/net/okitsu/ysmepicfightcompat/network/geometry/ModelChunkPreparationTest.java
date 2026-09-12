package net.okitsu.ysmepicfightcompat.network.geometry;

import net.okitsu.ysmepicfightcompat.network.message.ModelChunkMessage;
import net.okitsu.ysmepicfightcompat.network.message.ModelRequestMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ModelChunkPreparationTest {
    @Test
    void slicesAndEncodesOnlyOnTheWorkerAndRetainsWireOrder() throws Exception {
        byte[] payload = new byte[ModelChunkMessage.CHUNK_BYTES + 37];
        Arrays.fill(payload, (byte) 73);
        byte[] digest = new byte[32];
        digest[0] = 5;
        Thread server = Thread.currentThread();
        List<Thread> calls = new ArrayList<>();
        var worker = Executors.newSingleThreadExecutor();
        List<ModelChunkMessage> packets;
        try {
            packets = worker.submit(() -> ModelChunkPreparation.prepare("model", digest, payload,
                    () -> true, message -> {
                        calls.add(Thread.currentThread());
                        return message;
                    })).get(10, TimeUnit.SECONDS);
        } finally {
            worker.shutdownNow();
        }
        assertEquals(2, calls.size());
        assertTrue(calls.stream().allMatch(thread -> thread != server));
        assertEquals(2, packets.size());
        assertEquals(0, packets.get(0).chunkIndex());
        assertEquals(1, packets.get(1).chunkIndex());
        assertEquals(packets.get(0).transferId(), packets.get(1).transferId());
        assertEquals(payload.length, packets.get(1).totalBytes());
        assertEquals(2, packets.get(1).chunkCount());
        assertArrayEquals(Arrays.copyOfRange(payload, 0, ModelChunkMessage.CHUNK_BYTES),
                packets.get(0).bytes());
        assertArrayEquals(Arrays.copyOfRange(payload, ModelChunkMessage.CHUNK_BYTES, payload.length),
                packets.get(1).bytes());
        Arrays.fill(payload, (byte) 0);
        Arrays.fill(digest, (byte) 0);
        assertEquals(73, packets.get(0).bytes()[0]);
        assertEquals(5, packets.get(1).payloadDigest()[0]);
        assertThrows(UnsupportedOperationException.class, () -> packets.clear());
    }

    @Test
    void cancellationDoesNotPublishAPartialTransfer() {
        AtomicBoolean current = new AtomicBoolean(true);
        List<ModelChunkMessage> encoded = new ArrayList<>();
        var packets = ModelChunkPreparation.prepare("model", new byte[32],
                new byte[ModelChunkMessage.CHUNK_BYTES + 1], current::get, message -> {
                    encoded.add(message);
                    current.set(false);
                    return message;
                });
        assertEquals(1, encoded.size());
        assertTrue(packets.isEmpty());
        assertTrue(ModelChunkPreparation.prepare("model", new byte[32], new byte[1],
                () -> false, message -> fail("Cancelled preparation must not encode")).isEmpty());
    }

    @Test
    void reservesFramingAndStillAcceptsTheLargestSupportedPayload() {
        long amount = ModelChunkPreparation.retainedBytes(
                ModelRequestMessage.MAX_MODEL_ID_BYTES, GeometryTransferCodec.MAX_COMPRESSED_BYTES);
        assertEquals(ModelChunkPreparation.MAX_RETAINED_BYTES, amount);
        assertTrue(amount > 2L * GeometryTransferCodec.MAX_COMPRESSED_BYTES);
        ModelDeliveryQueue<String, String> queue = new ModelDeliveryQueue<>(32, amount);
        var reservation = queue.reserve(amount);
        assertNotNull(reservation);
        assertNull(queue.reserve(1));
        queue.cancel(reservation);
        assertNotNull(queue.reserve(amount));
    }

    @Test
    void preservesEncoderFailureAndRejectsEmptyInput() {
        IllegalStateException failure = new IllegalStateException("encoder");
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> ModelChunkPreparation.prepare("model", new byte[32], new byte[1],
                        () -> true, message -> { throw failure; })));
        assertThrows(IllegalArgumentException.class, () -> ModelChunkPreparation.prepare(
                "model", new byte[32], new byte[0], () -> true, message -> message));
    }
}

