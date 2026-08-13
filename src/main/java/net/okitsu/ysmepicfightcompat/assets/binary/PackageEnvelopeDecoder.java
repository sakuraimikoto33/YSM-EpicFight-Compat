package net.okitsu.ysmepicfightcompat.assets.binary;

import com.github.luben.zstd.ZstdInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Opens crypto-v3 YSM package envelopes and returns their uncompressed binary payload. */
public final class PackageEnvelopeDecoder {
    private static final int TRAILER_BYTES = 64;
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 24;
    private static final int MAX_OUTPUT = 512 * 1024 * 1024;
    private static final long STREAM_SEED = 0xA62B1A2C43842BC3L;
    private static final long WHITENING_SEED = 0xD017CBBA7B5D3581L;

    private PackageEnvelopeDecoder() {
    }

    public static byte[] open(byte[] file) {
        if (file == null || file.length < TRAILER_BYTES + Integer.BYTES + 2) {
            throw new IllegalArgumentException("YSM package is truncated");
        }
        int terminator = 0;
        while (terminator < file.length && file[terminator] != 0) {
            terminator++;
        }
        int encryptedStart = terminator + 1;
        int trailerStart = file.length - TRAILER_BYTES;
        if (encryptedStart + Integer.BYTES > trailerStart) {
            throw new IllegalArgumentException("YSM package header overlaps its trailer");
        }
        int version = readInt(file, encryptedStart);
        if (version != 3) {
            throw new IllegalArgumentException("Unsupported YSM package crypto version: " + version);
        }
        encryptedStart += Integer.BYTES;

        byte[] key = Arrays.copyOfRange(file, trailerStart, trailerStart + KEY_BYTES);
        byte[] nonce = Arrays.copyOfRange(file, trailerStart + KEY_BYTES,
                trailerStart + KEY_BYTES + NONCE_BYTES);
        byte[] ciphertext = Arrays.copyOfRange(file, encryptedStart, trailerStart);
        byte[] keyMaterial = join(key, nonce);

        byte[] streamPlaintext = decryptRolling(ciphertext, key, nonce);
        byte[] whitened = removeWhitening(streamPlaintext, keyMaterial);
        if (whitened.length < 2) {
            throw new IllegalArgumentException("YSM package payload has no padding descriptor");
        }
        int padding = ((whitened[0] & 0xFF) | (whitened[1] & 0xFF) << 8) & 0x3FF;
        int compressedStart = 2 + padding;
        if (compressedStart > whitened.length) {
            throw new IllegalArgumentException("YSM package padding exceeds its payload");
        }
        byte[] frame = normalizeFrame(Arrays.copyOfRange(
                whitened, compressedStart, whitened.length));
        return decompress(frame);
    }

    private static byte[] decryptRolling(byte[] input, byte[] key, byte[] nonce) {
        long firstHash = City64.hashWithSeed(join(key, nonce), STREAM_SEED);
        RollingXChaCha cipher = new RollingXChaCha(key, nonce, roundCount(firstHash));
        byte[] output = new byte[input.length];
        int offset = 0;
        int segmentLength = segmentLength(firstHash);
        while (offset < input.length) {
            int count = Math.min(segmentLength, input.length - offset);
            byte[] segment = cipher.transform(input, offset, count);
            System.arraycopy(segment, 0, output, offset, count);
            offset += count;
            if (offset < input.length) {
                long feedback = City64.hashWithSeed(segment, STREAM_SEED);
                segmentLength = cipher.mixFeedback(feedback);
            }
        }
        return output;
    }

    private static byte[] removeWhitening(byte[] input, byte[] keyMaterial) {
        Twister64 generator = new Twister64(City64.hashWithSeed(keyMaterial, WHITENING_SEED));
        byte[] result = new byte[input.length];
        int offset = 0;
        while (offset < input.length) {
            long word = generator.next();
            for (int byteIndex = 0; byteIndex < Long.BYTES && offset < input.length; byteIndex++) {
                result[offset] = (byte) (input[offset] ^ (word >>> (byteIndex * 8)));
                offset++;
            }
        }
        return result;
    }

    private static byte[] normalizeFrame(byte[] source) {
        if (source.length < 5 || readInt(source, 0) != 0xFD2FB528) {
            throw new IllegalArgumentException("YSM package does not contain a Zstandard frame");
        }
        byte descriptor = source[4];
        source[4] = (byte) (descriptor & 0xFB);
        int cursor = 4 + frameHeaderLength(descriptor);
        while (cursor + 3 <= source.length) {
            int first = source[cursor] & 0xFF;
            int encodedHeader = ((first & 0x1F) << 16)
                    | (source[cursor + 1] & 0xFF)
                    | (source[cursor + 2] & 0xFF) << 8;
            int decodedSize = encodedHeader ^ 0xD4E9;
            int ysmType = first >>> 5 & 3;
            int standardType = switch (ysmType) {
                case 0 -> 2;
                case 1 -> 1;
                case 2 -> 3;
                default -> 0;
            };
            int last = first >>> 7 & 1;
            int standardHeader = last | standardType << 1 | decodedSize << 3;
            source[cursor] = (byte) standardHeader;
            source[cursor + 1] = (byte) (standardHeader >>> 8);
            source[cursor + 2] = (byte) (standardHeader >>> 16);
            int storedBytes = standardType == 1 ? 1 : decodedSize;
            if (storedBytes < 0 || storedBytes > source.length - cursor - 3) {
                throw new IllegalArgumentException("YSM Zstandard block exceeds frame bounds");
            }
            cursor += 3 + storedBytes;
            if (last != 0) {
                return source;
            }
        }
        throw new IllegalArgumentException("YSM Zstandard frame has no final block");
    }

    private static byte[] decompress(byte[] frame) {
        try (ZstdInputStream input = new ZstdInputStream(new ByteArrayInputStream(frame));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[64 * 1024];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > MAX_OUTPUT - total) {
                    throw new IllegalStateException("Expanded YSM package exceeds " + MAX_OUTPUT + " bytes");
                }
                output.write(buffer, 0, count);
                total += count;
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to expand YSM package", exception);
        }
    }

    private static int frameHeaderLength(byte descriptor) {
        boolean singleSegment = (descriptor >>> 5 & 1) != 0;
        int dictionaryBytes = switch (descriptor & 3) {
            case 1 -> 1;
            case 2 -> 2;
            case 3 -> 4;
            default -> 0;
        };
        int sizeBits = descriptor >>> 6 & 3;
        int contentSizeBytes = switch (sizeBits) {
            case 0 -> singleSegment ? 1 : 0;
            case 1 -> 2;
            case 2 -> 4;
            default -> 8;
        };
        return 1 + (singleSegment ? 0 : 1) + dictionaryBytes + contentSizeBytes;
    }

    private static int roundCount(long hash) {
        return 10 + 10 * (int) Long.remainderUnsigned(hash, 3);
    }

    private static int segmentLength(long hash) {
        return (int) (((hash & 0x3FL) | 0x40L) << 6);
    }

    private static int readInt(byte[] source, int offset) {
        return ByteBuffer.wrap(source, offset, Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static byte[] join(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static final class RollingXChaCha {
        private static final int[] CONSTANTS = {
                0x61707865, 0x3320646E, 0x79622D32, 0x6B206574
        };
        private final int[] state = new int[16];
        private int rounds;

        private RollingXChaCha(byte[] keyBytes, byte[] nonceBytes, int rounds) {
            if (keyBytes.length != KEY_BYTES || nonceBytes.length != NONCE_BYTES) {
                throw new IllegalArgumentException("Invalid XChaCha key or nonce length");
            }
            int[] key = littleEndianWords(keyBytes);
            int[] nonce = littleEndianWords(nonceBytes);
            int[] hState = new int[16];
            System.arraycopy(CONSTANTS, 0, hState, 0, 4);
            System.arraycopy(key, 0, hState, 4, 8);
            System.arraycopy(nonce, 0, hState, 12, 4);
            permute(hState, rounds);
            int[] subkey = {hState[0], hState[1], hState[2], hState[3],
                    hState[12], hState[13], hState[14], hState[15]};
            System.arraycopy(CONSTANTS, 0, state, 0, 4);
            System.arraycopy(subkey, 0, state, 4, 8);
            state[14] = nonce[4];
            state[15] = nonce[5];
            this.rounds = rounds;
        }

        private byte[] transform(byte[] input, int inputOffset, int length) {
            byte[] output = new byte[length];
            int consumed = 0;
            while (consumed < length) {
                byte[] keyStream = block();
                int count = Math.min(64, length - consumed);
                for (int i = 0; i < count; i++) {
                    output[consumed + i] = (byte) (input[inputOffset + consumed + i] ^ keyStream[i]);
                }
                consumed += count;
                if (++state[12] == 0) {
                    state[13]++;
                }
            }
            return output;
        }

        private int mixFeedback(long hash) {
            rounds = roundCount(hash);
            int low = (int) hash;
            int high = (int) (hash >>> 32);
            for (int index = 4; index < state.length; index++) {
                state[index] ^= (index & 1) == 0 ? low : high;
            }
            return segmentLength(hash);
        }

        private byte[] block() {
            int[] working = state.clone();
            permute(working, rounds);
            ByteBuffer bytes = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < working.length; i++) {
                bytes.putInt(working[i] + state[i]);
            }
            return bytes.array();
        }

        private static void permute(int[] words, int rounds) {
            for (int round = 0; round < rounds; round += 2) {
                quarter(words, 0, 4, 8, 12);
                quarter(words, 1, 5, 9, 13);
                quarter(words, 2, 6, 10, 14);
                quarter(words, 3, 7, 11, 15);
                quarter(words, 0, 5, 10, 15);
                quarter(words, 1, 6, 11, 12);
                quarter(words, 2, 7, 8, 13);
                quarter(words, 3, 4, 9, 14);
            }
        }

        private static void quarter(int[] words, int a, int b, int c, int d) {
            words[a] += words[b];
            words[d] = Integer.rotateLeft(words[d] ^ words[a], 16);
            words[c] += words[d];
            words[b] = Integer.rotateLeft(words[b] ^ words[c], 12);
            words[a] += words[b];
            words[d] = Integer.rotateLeft(words[d] ^ words[a], 8);
            words[c] += words[d];
            words[b] = Integer.rotateLeft(words[b] ^ words[c], 7);
        }

        private static int[] littleEndianWords(byte[] bytes) {
            int[] words = new int[bytes.length / Integer.BYTES];
            ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < words.length; i++) {
                words[i] = input.getInt();
            }
            return words;
        }
    }

    private static final class Twister64 {
        private static final int SIZE = 312;
        private static final int OFFSET = 156;
        private static final long MATRIX = 0xB5026F5AA96619E9L;
        private static final long LOWER = 0x7FFFFFFFL;
        private static final long UPPER = 0xFFFFFFFF80000000L;
        private final long[] state = new long[SIZE];
        private int cursor = SIZE;

        private Twister64(long seed) {
            state[0] = seed;
            for (int i = 1; i < state.length; i++) {
                long previous = state[i - 1];
                state[i] = 6364136223846793005L * (previous ^ previous >>> 62) + i;
            }
        }

        private long next() {
            if (cursor == SIZE) {
                refresh();
            }
            long value = state[cursor++];
            value ^= value >>> 29 & 0x5555555555555555L;
            value ^= value << 17 & 0x71D67FFFEDA60000L;
            value ^= value << 37 & 0xFFF7EEE000000000L;
            return value ^ value >>> 43;
        }

        private void refresh() {
            for (int i = 0; i < SIZE; i++) {
                long combined = state[i] & UPPER | state[(i + 1) % SIZE] & LOWER;
                state[i] = state[(i + OFFSET) % SIZE] ^ combined >>> 1
                        ^ ((combined & 1L) == 0 ? 0 : MATRIX);
            }
            cursor = 0;
        }
    }

    private static final class City64 {
        private static final long K0 = 0xE4986A230E5AAA17L;
        private static final long K1 = 0x91AF10802CAB25A5L;
        private static final long K2 = 0xAF29CE778879D9C7L;
        private static final long KMUL = 0xDE0F6EE09BDBAB91L;

        private record Pair(long first, long second) {
        }

        private City64() {
        }

        private static long hashWithSeed(byte[] input, long seed) {
            return hash16(hash(input) - K2, seed);
        }

        private static long hash(byte[] input) {
            int length = input.length;
            if (length <= 16) {
                return shortHash(input);
            }
            if (length <= 32) {
                return mediumHash(input);
            }
            if (length <= 64) {
                return longTailHash(input);
            }
            long x = fetch64(input, length - 40);
            long y = fetch64(input, length - 16) + fetch64(input, length - 56);
            long z = hash16(fetch64(input, length - 48) + length,
                    fetch64(input, length - 24));
            Pair v = weak32(input, length - 64, length, z);
            Pair w = weak32(input, length - 32, y + K1, x);
            x = x * K1 + fetch64(input, 0);
            int remaining = (length - 1) & ~63;
            int offset = 0;
            do {
                x = Long.rotateRight(x + y + v.first + fetch64(input, offset + 8), 37) * K1;
                y = Long.rotateRight(y + v.second + fetch64(input, offset + 48), 42) * K1;
                x ^= w.second;
                y += v.first + fetch64(input, offset + 40);
                z = Long.rotateRight(z + w.first, 33) * K1;
                v = weak32(input, offset, v.second * K1, x + w.first);
                w = weak32(input, offset + 32, z + w.second,
                        y + fetch64(input, offset + 16));
                long swap = x;
                x = z;
                z = swap;
                offset += 64;
                remaining -= 64;
            } while (remaining != 0);
            return hash16(hash16(v.first, w.first) + mix(y) * K1 + z,
                    hash16(v.second, w.second) + x);
        }

        private static long shortHash(byte[] input) {
            int length = input.length;
            if (length >= 8) {
                long multiplier = K2 + length * 2L;
                long first = fetch64(input, 0) + K2;
                long last = fetch64(input, length - 8);
                return hash16(Long.rotateRight(last, 37) * multiplier + first,
                        (Long.rotateRight(first, 25) + last) * multiplier, multiplier);
            }
            if (length >= 4) {
                long multiplier = K2 + length * 2L;
                long first = fetch32(input, 0) & 0xFFFFFFFFL;
                return hash16(length + (first << 3),
                        fetch32(input, length - 4) & 0xFFFFFFFFL, multiplier);
            }
            if (length == 0) {
                return K2;
            }
            int a = input[0] & 0xFF;
            int b = input[length >>> 1] & 0xFF;
            int c = input[length - 1] & 0xFF;
            return mix((a + (b << 8)) * K2 ^ (length + (c << 2)) * K0) * K2;
        }

        private static long mediumHash(byte[] input) {
            int length = input.length;
            long multiplier = K2 + length * 2L;
            long a = fetch64(input, 0) * K1;
            long b = fetch64(input, 8);
            long c = fetch64(input, length - 8) * multiplier;
            long d = fetch64(input, length - 16) * K2;
            return hash16(Long.rotateRight(a + b, 43) + Long.rotateRight(c, 30) + d,
                    a + Long.rotateRight(b + K2, 18) + c, multiplier);
        }

        private static long longTailHash(byte[] input) {
            int length = input.length;
            long multiplier = K2 + length * 2L;
            long a = fetch64(input, 0) * K2;
            long b = fetch64(input, 8);
            long c = fetch64(input, length - 24);
            long d = fetch64(input, length - 32);
            long e = fetch64(input, 16) * K2;
            long f = fetch64(input, 24) * 9;
            long g = fetch64(input, length - 8);
            long h = fetch64(input, length - 16) * multiplier;
            long u = Long.rotateRight(a + g, 43) + (Long.rotateRight(b, 30) + c) * 9;
            long v = ((a + g) ^ d) + f + 1;
            long w = Long.reverseBytes((u + v) * multiplier) + h;
            long x = Long.rotateRight(e + f, 42) + c;
            long y = (Long.reverseBytes((v + w) * multiplier) + g) * multiplier;
            long z = e + f + c;
            a = Long.reverseBytes((x + z) * multiplier + y) + b;
            b = mix((z + a) * multiplier + d + h) * multiplier;
            return b + x;
        }

        private static Pair weak32(byte[] input, int offset, long a, long b) {
            long w = fetch64(input, offset);
            long x = fetch64(input, offset + 8);
            long y = fetch64(input, offset + 16);
            long z = fetch64(input, offset + 24);
            a += w;
            b = Long.rotateRight(b + a + z, 21);
            long previousA = a;
            a += x + y;
            b += Long.rotateRight(a, 44);
            return new Pair(a + z, b + previousA);
        }

        private static long hash16(long first, long second) {
            long a = (first ^ second) * KMUL;
            a ^= a >>> 47;
            long b = (first ^ a) * KMUL;
            b ^= b >>> 47;
            return b * KMUL;
        }

        private static long hash16(long first, long second, long multiplier) {
            long a = (first ^ second) * multiplier;
            a ^= a >>> 47;
            long b = (second ^ a) * multiplier;
            b ^= b >>> 47;
            return b * multiplier;
        }

        private static long mix(long value) {
            return value ^ value >>> 47;
        }

        private static long fetch64(byte[] input, int offset) {
            return (input[offset] & 0xFFL)
                    | (input[offset + 1] & 0xFFL) << 8
                    | (input[offset + 2] & 0xFFL) << 16
                    | (input[offset + 3] & 0xFFL) << 24
                    | (input[offset + 4] & 0xFFL) << 32
                    | (input[offset + 5] & 0xFFL) << 40
                    | (input[offset + 6] & 0xFFL) << 48
                    | (input[offset + 7] & 0xFFL) << 56;
        }

        private static int fetch32(byte[] input, int offset) {
            return (input[offset] & 0xFF)
                    | (input[offset + 1] & 0xFF) << 8
                    | (input[offset + 2] & 0xFF) << 16
                    | (input[offset + 3] & 0xFF) << 24;
        }
    }
}
