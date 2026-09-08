package net.okitsu.ysmepicfightcompat.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void delayedCleanupKeepsAReplacementPayloadAndOtherServersEntry() throws Exception {
        Path root = temporary.resolve("remote");
        String firstKey = "remote\0first.example\0shared/model";
        String secondKey = "remote\0second.example\0shared/model";
        byte[] oldPayload = {1, 2, 3};
        byte[] replacement = {4, 5, 6};
        byte[] oldDigest = ModelDiskCache.sha256(oldPayload);
        byte[] newDigest = ModelDiskCache.sha256(replacement);
        ModelDiskCache.Entry old = new ModelDiskCache.Entry(oldDigest, oldDigest, oldPayload);
        assertTrue(ModelDiskCache.write(root, firstKey, old, 4096));
        assertTrue(ModelDiskCache.write(root, secondKey, old, 4096));

        assertTrue(ModelDiskCache.write(root, firstKey,
                new ModelDiskCache.Entry(newDigest, newDigest, replacement), 4096));
        Path updatedFile = root.resolve(ModelDiskCache.hashKey(firstKey) + ".cache");
        FileTime age = FileTime.fromMillis(2_000L);
        Files.setLastModifiedTime(updatedFile, age);

        assertFalse(ModelDiskCache.removeIfPayloadDigestMatches(root, firstKey, oldDigest));
        assertEquals(age, Files.getLastModifiedTime(updatedFile));
        assertArrayEquals(replacement, ModelDiskCache.read(root, firstKey, 4096).orElseThrow().payload());
        assertTrue(ModelDiskCache.removeIfPayloadDigestMatches(root, secondKey, oldDigest));
        assertTrue(ModelDiskCache.read(root, secondKey, 4096).isEmpty());
        assertArrayEquals(replacement, ModelDiskCache.read(root, firstKey, 4096).orElseThrow().payload());
    }

    @Test
    void matchingCleanupRemovesOnlyTheCapturedPayload() {
        Path root = temporary.resolve("remote");
        byte[] payload = {1, 2, 3};
        byte[] digest = ModelDiskCache.sha256(payload);
        assertTrue(ModelDiskCache.write(root, "model",
                new ModelDiskCache.Entry(digest, digest, payload), 1024));

        assertTrue(ModelDiskCache.removeIfPayloadDigestMatches(root, "model", digest));
        assertFalse(ModelDiskCache.removeIfPayloadDigestMatches(root, "model", digest));
        assertTrue(ModelDiskCache.read(root, "model", 1024).isEmpty());
    }

    @Test
    void conditionalCleanupComparesThePayloadDigestNotTheValidationDigest() {
        Path root = temporary.resolve("remote");
        byte[] payload = {1, 2, 3};
        byte[] digest = ModelDiskCache.sha256(payload);
        byte[] validation = ModelDiskCache.sha256(new byte[]{4});
        assertTrue(ModelDiskCache.write(root, "model",
                new ModelDiskCache.Entry(validation, digest, payload), 1024));

        assertFalse(ModelDiskCache.removeIfPayloadDigestMatches(root, "model", validation));
        assertArrayEquals(payload, ModelDiskCache.read(root, "model", 1024).orElseThrow().payload());
        assertTrue(ModelDiskCache.removeIfPayloadDigestMatches(root, "model", digest));
    }

    @Test
    void conditionalCleanupLeavesUnrecognizedFilesAndDirectoriesAlone() throws Exception {
        Path root = temporary.resolve("remote");
        Files.createDirectories(root);
        byte[] digest = ModelDiskCache.sha256(new byte[]{1});
        Path malformed = root.resolve(ModelDiskCache.hashKey("malformed") + ".cache");
        Files.write(malformed, new byte[128]);
        Path directory = root.resolve(ModelDiskCache.hashKey("directory") + ".cache");
        Files.createDirectory(directory);

        assertFalse(ModelDiskCache.removeIfPayloadDigestMatches(root, "malformed", digest));
        assertTrue(Files.exists(malformed));
        assertFalse(ModelDiskCache.removeIfPayloadDigestMatches(root, "directory", digest));
        assertTrue(Files.isDirectory(directory));
    }
}
