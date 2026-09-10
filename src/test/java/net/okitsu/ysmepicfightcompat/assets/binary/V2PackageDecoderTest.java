package net.okitsu.ysmepicfightcompat.assets.binary;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.*;

class V2PackageDecoderTest {
    @Test
    void opensAnIndependentSyntheticKnownAnswer() {
        // Authored with .NET AES/PKCS7 + ZLibStream and an independent 48-bit LCG.
        // Contains only main.json with "{}"; no official package bytes are included.
        byte[] archive = Base64.getDecoder().decode(
                "WVNHUAAAAAIE9nBY6FrA9iDyxPg6ESQEAAAADGJXRnBiaTVxYzI5dQAAABAAAAAg"
                        + "/YHvB+48FHd/t4VCDLE+/4Icw9+SEA62N5Da3oZRiEkQERITFBUWFxgZGhscHR4f"
                        + "qnPz/ugeExjk8oL3NDdI+w==");
        Map<String, byte[]> assets = V2PackageDecoder.open(archive);
        assertEquals(1, assets.size());
        assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8), assets.get("main.json"));
    }

    @Test
    void opensMultipleNamedAssetsAndEmptyFilesWithoutFilesystemExtraction() {
        Map<String, byte[]> expected = new LinkedHashMap<>();
        expected.put("main.json", "{\"minecraft:geometry\":[]}".getBytes(StandardCharsets.UTF_8));
        expected.put("日本語.png", new byte[] {0, 1, 2, -1, 3});
        expected.put("extra.animation.json", new byte[0]);
        Map<String, byte[]> actual = V2PackageDecoder.open(V2PackageFixtures.archive(expected));
        assertEquals(expected.keySet(), actual.keySet());
        expected.forEach((name, bytes) -> assertArrayEquals(bytes, actual.get(name)));
    }

    @Test
    void recognizesLegacyMagicBeforeCheckingVersionOrFullHeader() {
        assertFalse(V2PackageDecoder.hasMagic(null));
        assertFalse(V2PackageDecoder.hasMagic(new byte[3]));
        assertFalse(V2PackageDecoder.hasMagic(new byte[24]));
        assertTrue(V2PackageDecoder.hasMagic(new byte[] {'Y', 'S', 'G', 'P'}));
        byte[] unsupported = validArchive();
        ByteBuffer.wrap(unsupported).putInt(4, 1);
        assertTrue(V2PackageDecoder.hasMagic(unsupported));
        assertThrows(IllegalArgumentException.class, () -> V2PackageDecoder.open(unsupported));
    }

    @Test
    void rejectsBadMagicTruncationAndChecksumChanges() {
        assertThrows(IllegalArgumentException.class, () -> V2PackageDecoder.open(null));
        assertThrows(IllegalArgumentException.class, () -> V2PackageDecoder.open(new byte[23]));
        byte[] wrongMagic = validArchive();
        wrongMagic[0] = 'X';
        assertThrows(IllegalArgumentException.class, () -> V2PackageDecoder.open(wrongMagic));
        byte[] altered = validArchive();
        altered[altered.length - 1] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> V2PackageDecoder.open(altered));
    }

    @Test
    void rejectsDuplicateNamesAndUnsafeBasenames() {
        byte[] duplicate = V2PackageFixtures.archiveEntries(List.of(
                Map.entry("main.json", new byte[0]), Map.entry("main.json", new byte[0])), false);
        assertThrows(IllegalArgumentException.class, () -> V2PackageDecoder.open(duplicate));
        for (String unsafe : List.of("../main.json", "folder/main.json", "folder\\main.json",
                "/main.json", "C:main.json", "..", ".", "", " main.json", "main.json ",
                "main.json.", "bad\u0000.json", "main?.json")) {
            byte[] archive = V2PackageFixtures.archive(Map.of(unsafe, new byte[0]));
            assertThrows(IllegalArgumentException.class, () -> V2PackageDecoder.open(archive), unsafe);
        }
    }

    @Test
    void rejectsInvalidBase64AndMalformedUtf8Names() throws Exception {
        byte[] invalidBase64 = validArchive();
        invalidBase64[28] = '!';
        resign(invalidBase64);
        assertThrows(IllegalArgumentException.class, () -> V2PackageDecoder.open(invalidBase64));
        byte[] invalidUtf8 = V2PackageFixtures.archive(Map.of("abc", new byte[0]));
        System.arraycopy("wK8=".getBytes(StandardCharsets.US_ASCII), 0, invalidUtf8, 28, 4);
        resign(invalidUtf8);
        assertThrows(IllegalArgumentException.class, () -> V2PackageDecoder.open(invalidUtf8));
    }

    @Test
    void rejectsNameAndCiphertextSizesBeforeAllocatingBodies() throws Exception {
        for (int size : new int[] {-1, 0, 4097, Integer.MAX_VALUE}) {
            byte[] archive = validArchive();
            ByteBuffer.wrap(archive).putInt(24, size);
            resign(archive);
            assertThrows(IllegalArgumentException.class, () -> V2PackageDecoder.open(archive));
        }
        for (int size : new int[] {-1, 0, 1, Integer.MAX_VALUE - 15}) {
            byte[] archive = validArchive();
            ByteBuffer.wrap(archive).putInt(sizeOffset(archive), size);
            resign(archive);
            assertThrows(IllegalArgumentException.class, () -> V2PackageDecoder.open(archive));
        }
        byte[] keySize = validArchive();
        ByteBuffer.wrap(keySize).putInt(sizeOffset(keySize) + 4, Integer.MAX_VALUE);
        resign(keySize);
        assertThrows(IllegalArgumentException.class, () -> V2PackageDecoder.open(keySize));
    }

    @Test
    void rejectsTruncatedRecordsEvenWithValidEnvelopeChecksum() throws Exception {
        byte[] valid = validArchive();
        for (int removed : new int[] {1, 16, 48, valid.length - 25}) {
            byte[] truncated = Arrays.copyOf(valid, valid.length - removed);
            resign(truncated);
            assertThrows(IllegalArgumentException.class, () -> V2PackageDecoder.open(truncated));
        }
    }

    @Test
    void rejectsInvalidWrappedKeyPaddingEvenWithValidEnvelopeChecksum() throws Exception {
        byte[] archive = validArchive();
        // Modifying the preceding CBC block corrupts the known final padding byte.
        archive[sizeOffset(archive) + 8 + 15] ^= 1;
        resign(archive);
        assertThrows(IllegalArgumentException.class, () -> V2PackageDecoder.open(archive));
    }

    @Test
    void rejectsMalformedTruncatedDictionaryAndTrailingCompressedStreams() {
        Deflater compressor = new Deflater();
        byte[] compressed = new byte[64];
        byte[] complete;
        try {
            compressor.setInput(new byte[] {1, 2, 3});
            compressor.finish();
            complete = Arrays.copyOf(compressed, compressor.deflate(compressed));
        } finally {
            compressor.end();
        }
        for (byte[] malformed : List.of(new byte[] {1, 2, 3},
                Arrays.copyOf(complete, complete.length - 1),
                Arrays.copyOf(complete, complete.length + 1), dictionaryCompressed())) {
            byte[] archive = V2PackageFixtures.archiveEntries(
                    List.of(Map.entry("main.json", malformed)), true);
            assertThrows(IllegalArgumentException.class, () -> V2PackageDecoder.open(archive));
        }
    }

    @Test
    void rejectsExcessiveEntryCount() {
        List<Map.Entry<String, byte[]>> entries = IntStream.range(0, 4097)
                .mapToObj(index -> Map.entry(index + ".json", new byte[0])).toList();
        byte[] archive = V2PackageFixtures.archiveEntries(entries, false);
        assertThrows(IllegalArgumentException.class, () -> V2PackageDecoder.open(archive));
    }

    @Test
    void enforcesTheRemainingExpandedByteBudget() {
        Deflater compressor = new Deflater();
        byte[] compressed = new byte[64];
        try {
            compressor.setInput(new byte[] {1, 2, 3, 4});
            compressor.finish();
            compressed = Arrays.copyOf(compressed, compressor.deflate(compressed));
        } finally {
            compressor.end();
        }
        byte[] source = compressed;
        assertArrayEquals(new byte[] {1, 2, 3, 4}, V2PackageDecoder.expand(source, 4));
        assertThrows(IllegalArgumentException.class, () -> V2PackageDecoder.expand(source, 3));
        assertThrows(IllegalArgumentException.class, () -> V2PackageDecoder.expand(source, 0));
        assertArrayEquals(new byte[0], V2PackageDecoder.expand(new byte[0], 0));
    }

    private static byte[] dictionaryCompressed() {
        Deflater compressor = new Deflater();
        try {
            compressor.setDictionary(new byte[] {1, 2, 3});
            compressor.setInput(new byte[] {1, 2, 3});
            compressor.finish();
            byte[] output = new byte[64];
            return Arrays.copyOf(output, compressor.deflate(output));
        } finally {
            compressor.end();
        }
    }

    private static byte[] validArchive() {
        return V2PackageFixtures.archive(Map.of("main.json", new byte[] {1, 2, 3}));
    }

    private static int sizeOffset(byte[] archive) {
        return 28 + ByteBuffer.wrap(archive).getInt(24);
    }

    private static void resign(byte[] archive) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        digest.update(archive, 24, archive.length - 24);
        System.arraycopy(digest.digest(), 0, archive, 8, 16);
    }
}
