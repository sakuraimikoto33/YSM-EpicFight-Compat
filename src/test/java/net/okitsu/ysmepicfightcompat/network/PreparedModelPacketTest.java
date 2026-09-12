package net.okitsu.ysmepicfightcompat.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.okitsu.ysmepicfightcompat.cache.ModelDiskCache;
import net.okitsu.ysmepicfightcompat.network.message.ModelChunkMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.nio.ReadOnlyBufferException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Isolated
class PreparedModelPacketTest {
    @BeforeAll
    static void registerProductionChannelMessages() {
        // Plain JUnit has no ModLauncher-injected event constructors. Initialize the
        // real listener lists through public event instances before channel registration.
        new NetworkEvent(() -> null).getListenerList();
        new NetworkEvent.GatherLoginPayloadsEvent(new ArrayList<>(), false).getListenerList();
        CompatNetwork.registerMessages();
    }

    @Test
    void preparedDataAndStatusPacketsMatchTheNormalChannelWireAndDecode() throws Exception {
        for (ModelChunkMessage message : messages()) {
            ClientboundCustomPayloadPacket normal = (ClientboundCustomPayloadPacket)
                    CompatNetwork.CHANNEL.toVanillaPacket(message, NetworkDirection.PLAY_TO_CLIENT);
            ClientboundCustomPayloadPacket prepared = CompatNetwork.prepareModelChunk(message).packet();
            FriendlyByteBuf normalWire = buffer();
            FriendlyByteBuf preparedWire = buffer();
            try {
                normal.write(normalWire);
                prepared.write(preparedWire);
                assertArrayEquals(bytes(normalWire), bytes(preparedWire));
                assertEquals(normal.getIdentifier(), preparedWire.readResourceLocation());
                assertEquals(2, preparedWire.readUnsignedByte(), "the existing ModelChunk channel discriminator");
                assertMessage(message, ModelChunkMessage.read(preparedWire));
                assertFalse(preparedWire.isReadable(), "the existing decoder consumes the complete packet body");
            } finally {
                normalWire.release();
                preparedWire.release();
                internalData(normal).release();
                internalData(prepared).release();
            }
        }
    }

    @Test
    void eachRecipientHasAnIndependentReadOnlyViewAndReleasingOneDoesNotInvalidateAnother() throws Exception {
        var prepared = CompatNetwork.prepareModelChunk(data(new byte[]{1, 2, 3}));
        ClientboundCustomPayloadPacket first = prepared.packet();
        ClientboundCustomPayloadPacket second = prepared.packet();
        FriendlyByteBuf firstData = internalData(first);
        FriendlyByteBuf secondData = internalData(second);
        FriendlyByteBuf laterData = null;
        try {
            assertNotSame(first, second);
            assertNotSame(firstData, secondData);
            assertTrue(firstData.isReadOnly());
            assertTrue(secondData.isReadOnly());
            byte[] expected = bytes(secondData);
            firstData.readByte();
            assertEquals(0, secondData.readerIndex());
            assertThrows(ReadOnlyBufferException.class, () -> firstData.setByte(0, 7));
            firstData.release();
            assertEquals(1, secondData.refCnt());
            assertArrayEquals(expected, bytes(secondData));

            laterData = internalData(prepared.packet());
            assertEquals(0, laterData.readerIndex());
            assertArrayEquals(expected, bytes(laterData));
        } finally {
            if (firstData.refCnt() > 0) { firstData.release(); }
            secondData.release();
            if (laterData != null) { laterData.release(); }
        }
    }

    @Test
    void workerPreparationOwnsTheEncodedBytesDespiteCallerArrayMutations() throws Exception {
        byte[] source = {1, 2, 3};
        ModelChunkMessage message = data(source);
        byte[] expected = message.bytes();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var prepared = executor.submit(() -> CompatNetwork.prepareModelChunk(message)).get(5, TimeUnit.SECONDS);
            Arrays.fill(source, (byte) 9);
            Arrays.fill(message.bytes(), (byte) 8);
            Arrays.fill(message.payloadDigest(), (byte) 7);
            ClientboundCustomPayloadPacket packet = prepared.packet();
            FriendlyByteBuf payload = internalData(packet);
            try {
                assertEquals(2, payload.readUnsignedByte());
                ModelChunkMessage decoded = ModelChunkMessage.read(payload);
                assertArrayEquals(expected, decoded.bytes());
                assertMessage(message, decoded);
            } finally {
                payload.release();
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

    private static FriendlyByteBuf buffer() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    private static byte[] bytes(FriendlyByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return bytes;
    }

    private static FriendlyByteBuf internalData(ClientboundCustomPayloadPacket packet) throws Exception {
        // getData() copies its contents, so it cannot verify the actual per-recipient buffer's ownership.
        Field field = ClientboundCustomPayloadPacket.class.getDeclaredField("data");
        field.setAccessible(true);
        return (FriendlyByteBuf) field.get(packet);
    }
}
