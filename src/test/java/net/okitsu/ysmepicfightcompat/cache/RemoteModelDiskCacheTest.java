package net.okitsu.ysmepicfightcompat.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteModelDiskCacheTest {
    @Test
    void neverOffersLegacyDurationPayloadsForConditionalReuse(@TempDir Path root) {
        String legacyKey = "remote\0server-a\0model";
        String currentKey = RemoteModelDiskCache.key("server-a", "model");
        assertNotEquals(legacyKey, currentKey);
        byte[] oldPayload = {1, 2, 3};
        byte[] oldDigest = ModelDiskCache.sha256(oldPayload);
        assertTrue(ModelDiskCache.write(root, legacyKey,
                new ModelDiskCache.Entry(oldDigest, oldDigest, oldPayload), 1024 * 1024));
        assertTrue(ModelDiskCache.read(root, currentKey, 1024 * 1024).isEmpty());

        byte[] newPayload = {4, 5, 6};
        byte[] newDigest = ModelDiskCache.sha256(newPayload);
        assertTrue(ModelDiskCache.write(root, currentKey,
                new ModelDiskCache.Entry(newDigest, newDigest, newPayload), 1024 * 1024));
        assertArrayEquals(newDigest, ModelDiskCache.read(root, currentKey, 1024 * 1024)
                .orElseThrow().payloadDigest());
        assertFalse(ModelDiskCache.read(root, legacyKey, 1024 * 1024).isEmpty());
        assertTrue(ModelDiskCache.read(root, RemoteModelDiskCache.key("server-b", "model"),
                1024 * 1024).isEmpty());
    }
}
