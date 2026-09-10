package net.okitsu.ysmepicfightcompat.assets.binary;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Reads the official legacy V2 named-asset envelope entirely in memory.
 * See docs/implementation.md for the official public format reference.
 */
public final class V2PackageDecoder {
    private static final int MAGIC = 0x59534750;
    private static final int HEADER_BYTES = 24;
    private static final int MAX_ARCHIVE_BYTES = 256 * 1024 * 1024;
    private static final int MAX_ENTRIES = 4_096;
    private static final int MAX_NAME_BYTES = 4 * 1024;
    private static final int MAX_ASSET_BYTES = 128 * 1024 * 1024;
    private static final int MAX_EXPANDED_BYTES = 512 * 1024 * 1024;
    private static final int BLOCK_BYTES = 16;
    private static final int WRAPPED_KEY_BYTES = 32;

    private V2PackageDecoder() {
    }

    /** Recognizes the legacy family, including versions that must be rejected by open. */
    public static boolean hasMagic(byte[] file) {
        return file != null && file.length >= Integer.BYTES
                && ByteBuffer.wrap(file).getInt() == MAGIC;
    }

    public static Map<String, byte[]> open(byte[] file) {
        require(file != null && file.length >= HEADER_BYTES,
                "Legacy YSM package is truncated");
        require(file.length <= MAX_ARCHIVE_BYTES, "Legacy YSM package exceeds its size limit");
        Cursor input = new Cursor(file);
        require(input.integer() == MAGIC, "Invalid legacy YSM package magic");
        require(input.integer() == 2, "Unsupported legacy YSM package version");
        byte[] expectedDigest = input.bytes(BLOCK_BYTES);
        require(MessageDigest.isEqual(expectedDigest, digest(file, HEADER_BYTES,
                file.length - HEADER_BYTES)), "Legacy YSM package checksum mismatch");

        Map<String, byte[]> assets = new LinkedHashMap<>();
        int expandedBytes = 0;
        while (input.remaining() != 0) {
            require(assets.size() < MAX_ENTRIES, "Too many legacy YSM package assets");
            String name = readName(input);
            require(!assets.containsKey(name), "Duplicate legacy YSM asset name");
            int encryptedSize = input.integer();
            require(encryptedSize > 0 && encryptedSize % BLOCK_BYTES == 0,
                    "Invalid legacy YSM encrypted asset size");
            require(input.integer() == WRAPPED_KEY_BYTES,
                    "Invalid legacy YSM wrapped key size");
            byte[] wrappedKey = input.bytes(WRAPPED_KEY_BYTES);
            byte[] iv = input.bytes(BLOCK_BYTES);
            byte[] encrypted = input.bytes(encryptedSize);
            byte[] encryptedDigest = digest(encrypted, 0, encrypted.length);
            long seed = ByteBuffer.wrap(encryptedDigest, Long.BYTES, Long.BYTES).getLong();
            byte[] wrappingKey = new byte[BLOCK_BYTES];
            new Random(seed).nextBytes(wrappingKey);
            byte[] key = decrypt(wrappedKey, wrappingKey, iv);
            require(key.length == BLOCK_BYTES, "Invalid legacy YSM asset key");
            byte[] compressed = decrypt(encrypted, key, iv);
            int limit = Math.min(MAX_ASSET_BYTES, MAX_EXPANDED_BYTES - expandedBytes);
            byte[] asset = expand(compressed, limit);
            expandedBytes += asset.length;
            assets.put(name, asset);
        }
        return assets;
    }

    private static String readName(Cursor input) {
        int size = input.integer();
        require(size > 0 && size <= MAX_NAME_BYTES, "Invalid legacy YSM asset name size");
        byte[] encoded = input.bytes(size);
        String name;
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            require(Arrays.equals(Base64.getEncoder().encode(decoded), encoded),
                    "Noncanonical legacy YSM asset name encoding");
            name = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(decoded)).toString();
        } catch (IllegalArgumentException | CharacterCodingException exception) {
            throw new IllegalArgumentException("Invalid legacy YSM asset name encoding", exception);
        }
        require(!name.isBlank() && name.equals(name.strip()) && !name.endsWith(".")
                        && !name.equals(".") && !name.equals(".."),
                "Invalid legacy YSM asset basename");
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            require(!Character.isISOControl(character) && "/\\:<>\"|?*".indexOf(character) < 0,
                    "Legacy YSM asset name must be a flat basename");
        }
        return name;
    }

    private static byte[] decrypt(byte[] encrypted, byte[] key, byte[] iv) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(encrypted);
        } catch (GeneralSecurityException exception) {
            throw new IllegalArgumentException("Unable to open legacy YSM asset", exception);
        }
    }

    static byte[] expand(byte[] compressed, int limit) {
        // The official exporter represents an empty file as empty compressed bytes.
        if (compressed.length == 0) {
            return compressed;
        }
        Inflater inflater = new Inflater();
        inflater.setInput(compressed);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[64 * 1024];
            int total = 0;
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                require(count <= limit - total, "Expanded legacy YSM assets exceed their size limit");
                output.write(buffer, 0, count);
                total += count;
                require(count != 0 || inflater.finished(),
                        "Legacy YSM compressed asset is truncated or requires a dictionary");
            }
            require(inflater.getRemaining() == 0, "Legacy YSM compressed asset has trailing data");
            return output.toByteArray();
        } catch (DataFormatException | java.io.IOException exception) {
            throw new IllegalArgumentException("Unable to expand legacy YSM asset", exception);
        } finally {
            inflater.end();
        }
    }

    private static byte[] digest(byte[] input, int offset, int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            digest.update(input, offset, length);
            return digest.digest();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("MD5 is unavailable", exception);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static final class Cursor {
        private final ByteBuffer input;

        private Cursor(byte[] file) {
            input = ByteBuffer.wrap(file);
        }

        private int remaining() {
            return input.remaining();
        }

        private int integer() {
            require(input.remaining() >= Integer.BYTES, "Legacy YSM package is truncated");
            return input.getInt();
        }

        private byte[] bytes(int size) {
            require(size >= 0 && size <= input.remaining(), "Legacy YSM asset exceeds package bounds");
            byte[] bytes = new byte[size];
            input.get(bytes);
            return bytes;
        }
    }
}
