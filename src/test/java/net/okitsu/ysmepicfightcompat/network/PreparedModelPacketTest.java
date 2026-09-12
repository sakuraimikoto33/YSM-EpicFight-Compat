package net.okitsu.ysmepicfightcompat.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.okitsu.ysmepicfightcompat.cache.ModelDiskCache;
import net.okitsu.ysmepicfightcompat.network.message.ModelChunkMessage;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PreparedModelPacketTest {
    @Test
    void preparedDataAndStatusPayloadsKeepTheOriginalCodecWireAndDecode() {
        for (ModelChunkMessage message : messages()) {
            var prepared = CompatNetwork.prepareModelChunk(message);
            FriendlyByteBuf normalWire = buffer();
            FriendlyByteBuf preparedWire = buffer();
            try {
                ModelChunkMessage.STREAM_CODEC.encode(normalWire, message);
                ModelChunkMessage.STREAM_CODEC.encode(preparedWire, prepared.payload());
                assertSame(message, prepared.payload(), "recipients share the immutable payload");
                assertEquals(message.type(), prepared.payload().type());
                assertArrayEquals(bytes(normalWire), bytes(preparedWire));
                assertMessage(message, ModelChunkMessage.STREAM_CODEC.decode(preparedWire));
                assertFalse(preparedWire.isReadable(), "the existing decoder consumes the entire payload");
            } finally {
                normalWire.release();
                preparedWire.release();
            }
        }
    }

    @Test
    void sharedPayloadKeepsDefensiveCopiesForConstructorArgumentsAndAccessors() {
        byte[] source = {1, 2, 3};
        byte[] digest = new byte[ModelDiskCache.DIGEST_BYTES];
        Arrays.fill(digest, (byte) 42);
        byte[] expectedSource = source.clone();
        byte[] expectedDigest = digest.clone();
        ModelChunkMessage message = new ModelChunkMessage(ModelChunkMessage.Status.DATA,
                new UUID(1, 2), "sample:model", digest, source.length, 0, 1, source);
        var prepared = CompatNetwork.prepareModelChunk(message);
        Arrays.fill(source, (byte) 9);
        Arrays.fill(digest, (byte) 8);
        Arrays.fill(prepared.payload().bytes(), (byte) 7);
        Arrays.fill(prepared.payload().payloadDigest(), (byte) 6);
        assertSame(message, prepared.payload());
        assertArrayEquals(expectedSource, prepared.payload().bytes());
        assertArrayEquals(expectedDigest, prepared.payload().payloadDigest());
        FriendlyByteBuf wire = buffer();
        try {
            ModelChunkMessage.STREAM_CODEC.encode(wire, prepared.payload());
            ModelChunkMessage decoded = ModelChunkMessage.STREAM_CODEC.decode(wire);
            assertArrayEquals(expectedSource, decoded.bytes());
            assertArrayEquals(expectedDigest, decoded.payloadDigest());
            assertMessage(message, decoded);
        } finally {
            wire.release();
        }
    }

    @Test
    void workerPreparedPayloadCanBeEncodedIndependentlyForConcurrentRecipientBuffers() throws Exception {
        ModelChunkMessage message = data(new byte[]{1, 2, 3});
        var executor = Executors.newFixedThreadPool(2);
        try {
            var prepared = executor.submit(() -> CompatNetwork.prepareModelChunk(message)).get(5, TimeUnit.SECONDS);
            var first = executor.submit(() -> encode(prepared.payload()));
            var second = executor.submit(() -> encode(prepared.payload()));
            byte[] firstWire = first.get(5, TimeUnit.SECONDS);
            byte[] secondWire = second.get(5, TimeUnit.SECONDS);
            assertArrayEquals(firstWire, secondWire);
            FriendlyByteBuf firstInput = new FriendlyByteBuf(Unpooled.wrappedBuffer(firstWire));
            FriendlyByteBuf secondInput = new FriendlyByteBuf(Unpooled.wrappedBuffer(secondWire));
            try {
                assertMessage(message, ModelChunkMessage.STREAM_CODEC.decode(firstInput));
                assertEquals(0, secondInput.readerIndex());
                assertMessage(message, ModelChunkMessage.STREAM_CODEC.decode(secondInput));
                assertFalse(firstInput.isReadable());
                assertFalse(secondInput.isReadable());
            } finally {
                firstInput.release();
                secondInput.release();
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static List<ModelChunkMessage> messages() {
        byte[] maximumChunk = new byte[ModelChunkMessage.CHUNK_BYTES];
        for (int i = 0; i < maximumChunk.length; i++) { maximumChunk[i] = (byte) i; }
        return List.of(data(new byte[]{1, 2, 3}), data(maximumChunk),
                ModelChunkMessage.unchanged("sample:status", new byte[ModelDiskCache.DIGEST_BYTES]),
                ModelChunkMessage.unavailable("sample:status"));
    }

    private static ModelChunkMessage data(byte[] payload) {
        byte[] digest = new byte[ModelDiskCache.DIGEST_BYTES];
        Arrays.fill(digest, (byte) 42);
        return new ModelChunkMessage(ModelChunkMessage.Status.DATA, new UUID(1, 2),
                "sample:model_\u3042", digest, payload.length, 0, 1, payload);
    }

    private static void assertMessage(ModelChunkMessage expected, ModelChunkMessage actual) {
        assertEquals(expected.status(), actual.status());
        assertEquals(expected.transferId(), actual.transferId());
        assertEquals(expected.modelId(), actual.modelId());
        assertArrayEquals(expected.payloadDigest(), actual.payloadDigest());
        assertEquals(expected.totalBytes(), actual.totalBytes());
        assertEquals(expected.chunkIndex(), actual.chunkIndex());
        assertEquals(expected.chunkCount(), actual.chunkCount());
        assertArrayEquals(expected.bytes(), actual.bytes());
    }

    private static byte[] encode(ModelChunkMessage payload) {
        FriendlyByteBuf wire = buffer();
        try {
            ModelChunkMessage.STREAM_CODEC.encode(wire, payload);
            return bytes(wire);
        } finally {
            wire.release();
        }
    }

    private static FriendlyByteBuf buffer() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    private static byte[] bytes(FriendlyByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return bytes;
    }
}
