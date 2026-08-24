package net.okitsu.ysmepicfightcompat.network.message;

import net.okitsu.ysmepicfightcompat.cache.ModelDiskCache;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelTransferMessageTest {
    @Test
    void validatesConditionalRequestDigestsAndCopiesArrays() {
        byte[] digest = ModelDiskCache.sha256(new byte[]{1});
        ModelRequestMessage request = new ModelRequestMessage("server/model", digest);
        digest[0] ^= 1;
        assertArrayEquals(ModelDiskCache.sha256(new byte[]{1}),
                request.knownPayloadDigest());
        byte[] exposed = request.knownPayloadDigest();
        exposed[0] ^= 1;
        assertArrayEquals(ModelDiskCache.sha256(new byte[]{1}),
                request.knownPayloadDigest());
        assertThrows(IllegalArgumentException.class,
                () -> new ModelRequestMessage("server/model", new byte[1]));
    }

    @Test
    void separatesDataUnchangedAndUnavailableResponses() {
        byte[] digest = ModelDiskCache.sha256(new byte[]{2});
        ModelChunkMessage unchanged = ModelChunkMessage.unchanged("server/model", digest);
        ModelChunkMessage unavailable = ModelChunkMessage.unavailable("server/model");
        ModelChunkMessage data = new ModelChunkMessage(ModelChunkMessage.Status.DATA,
                UUID.randomUUID(), "server/model", digest, 1, 0, 1, new byte[]{4});

        assertEquals(ModelChunkMessage.Status.UNCHANGED, unchanged.status());
        assertEquals(ModelChunkMessage.Status.UNAVAILABLE, unavailable.status());
        assertEquals(ModelChunkMessage.Status.DATA, data.status());
        assertThrows(IllegalArgumentException.class, () -> new ModelChunkMessage(
                ModelChunkMessage.Status.UNCHANGED, UUID.randomUUID(), "server/model",
                digest, 1, 0, 0, new byte[0]));
    }
}
