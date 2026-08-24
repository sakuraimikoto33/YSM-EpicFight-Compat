package net.okitsu.ysmepicfightcompat.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelDiskCacheTest {
    @TempDir
    Path temporary;

    @Test
    void roundTripsOpaqueHashedEntryAndRejectsCorruption() throws Exception {
        Path root = temporary.resolve("remote");
        byte[] validation = ModelDiskCache.sha256(new byte[]{1});
        byte[] payload = new byte[]{7, 8, 9, 10};
        byte[] payloadDigest = ModelDiskCache.sha256(payload);

        assertTrue(ModelDiskCache.write(root, "server.example\0private/model",
                new ModelDiskCache.Entry(validation, payloadDigest, payload), 1024));
        Path file = root.resolve(ModelDiskCache.hashKey(
                "server.example\0private/model") + ".cache");
        assertTrue(Files.isRegularFile(file));
        assertFalse(file.getFileName().toString().contains("private"));
        assertFalse(Arrays.equals(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47},
                Arrays.copyOf(Files.readAllBytes(file), 4)));
        ModelDiskCache.Entry restored = ModelDiskCache.read(
                root, "server.example\0private/model", 1024).orElseThrow();
        assertArrayEquals(validation, restored.validationDigest());
        assertArrayEquals(payloadDigest, restored.payloadDigest());
        assertArrayEquals(payload, restored.payload());

        byte[] corrupt = Files.readAllBytes(file);
        corrupt[corrupt.length - 1] ^= 0x55;
        Files.write(file, corrupt);
        assertTrue(ModelDiskCache.read(root,
                "server.example\0private/model", 1024).isEmpty());
        assertFalse(Files.exists(file));
    }

    @Test
    void zeroAndSmallLimitsDoNotPreventSessionPayloadUse() throws Exception {
        Path root = temporary.resolve("client");
        byte[] payload = new byte[256];
        byte[] digest = ModelDiskCache.sha256(payload);
        ModelDiskCache.Entry entry = new ModelDiskCache.Entry(digest, digest, payload);

        assertFalse(ModelDiskCache.write(root, "oversized", entry, 128));
        assertFalse(Files.exists(root.resolve(
                ModelDiskCache.hashKey("oversized") + ".cache")));
        assertTrue(ModelDiskCache.write(root, "kept", entry, 1024));
        ModelDiskCache.maintain(root, 0);
        try (var files = Files.list(root)) {
            assertTrue(files.findAny().isEmpty());
        }
    }

    @Test
    void removesLeastRecentlyUsedEntriesPerDirectory() throws Exception {
        Path root = temporary.resolve("server");
        byte[] firstPayload = new byte[100];
        byte[] secondPayload = new byte[100];
        secondPayload[0] = 1;
        byte[] firstDigest = ModelDiskCache.sha256(firstPayload);
        byte[] secondDigest = ModelDiskCache.sha256(secondPayload);
        assertTrue(ModelDiskCache.write(root, "first",
                new ModelDiskCache.Entry(firstDigest, firstDigest, firstPayload), 4096));
        assertTrue(ModelDiskCache.write(root, "second",
                new ModelDiskCache.Entry(secondDigest, secondDigest, secondPayload), 4096));
        Path first = root.resolve(ModelDiskCache.hashKey("first") + ".cache");
        Path second = root.resolve(ModelDiskCache.hashKey("second") + ".cache");
        Files.setLastModifiedTime(first, FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(second, FileTime.fromMillis(2_000));

        ModelDiskCache.maintain(root, Files.size(second));

        assertFalse(Files.exists(first));
        assertTrue(Files.exists(second));
    }
}
