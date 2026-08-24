package net.okitsu.ysmepicfightcompat.cache;

import net.okitsu.ysmepicfightcompat.network.geometry.GeometryTransferCodec;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/** Atomic, bounded storage for opaque encoded model payloads. */
public final class ModelDiskCache {
    private static final int MAGIC = 0x59454331;
    private static final int VERSION = 1;
    public static final int DIGEST_BYTES = 32;
    private static final int HEADER_BYTES = Integer.BYTES * 5 + DIGEST_BYTES * 2;
    private static final long MAX_FILE_BYTES =
            (long) GeometryTransferCodec.MAX_COMPRESSED_BYTES + HEADER_BYTES;
    private static final Map<Path, Object> LOCKS = new ConcurrentHashMap<>();

    public record Entry(byte[] validationDigest, byte[] payloadDigest, byte[] payload) {
        public Entry {
            validationDigest = copyDigest(validationDigest, "validation");
            payloadDigest = copyDigest(payloadDigest, "payload");
            payload = payload == null ? null : Arrays.copyOf(payload, payload.length);
            if (payload == null || payload.length == 0
                    || payload.length > GeometryTransferCodec.MAX_COMPRESSED_BYTES) {
                throw new IllegalArgumentException("Invalid cache payload");
            }
        }

        @Override
        public byte[] validationDigest() {
            return Arrays.copyOf(validationDigest, validationDigest.length);
        }

        @Override
        public byte[] payloadDigest() {
            return Arrays.copyOf(payloadDigest, payloadDigest.length);
        }

        @Override
        public byte[] payload() {
            return Arrays.copyOf(payload, payload.length);
        }
    }

    private record CacheFile(Path path, long size, FileTime lastUsed) {
    }

    private ModelDiskCache() {
    }

    public static Optional<Entry> read(Path root, String key, long maximumBytes) {
        Path normalized = normalize(root);
        synchronized (lock(normalized)) {
            if (maximumBytes <= 0) {
                maintainLocked(normalized, 0);
                return Optional.empty();
            }
            if (!safeDirectory(normalized)) {
                return Optional.empty();
            }
            Path file = file(normalized, key);
            if (!regularFile(file)) {
                return Optional.empty();
            }
            try {
                long size = Files.size(file);
                if (size < HEADER_BYTES || size > MAX_FILE_BYTES || size > maximumBytes) {
                    Files.deleteIfExists(file);
                    return Optional.empty();
                }
                Entry entry;
                try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                        Files.newInputStream(file)))) {
                    if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                        throw new IOException("Unsupported model cache format");
                    }
                    byte[] validation = digest(input);
                    byte[] payloadDigest = digest(input);
                    int payloadLength = input.readInt();
                    if (payloadLength <= 0
                            || payloadLength > GeometryTransferCodec.MAX_COMPRESSED_BYTES) {
                        throw new IOException("Invalid model cache payload size");
                    }
                    byte[] payload = input.readNBytes(payloadLength);
                    if (payload.length != payloadLength || input.read() != -1
                            || !MessageDigest.isEqual(payloadDigest, sha256(payload))) {
                        throw new IOException("Corrupt model cache payload");
                    }
                    entry = new Entry(validation, payloadDigest, payload);
                }
                Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
                return Optional.of(entry);
            } catch (IOException | RuntimeException exception) {
                deleteRegular(file);
                return Optional.empty();
            }
        }
    }

    public static boolean write(Path root, String key, Entry entry, long maximumBytes) {
        Path normalized = normalize(root);
        synchronized (lock(normalized)) {
            if (maximumBytes <= 0) {
                maintainLocked(normalized, 0);
                return false;
            }
            long fileBytes = HEADER_BYTES + (long) entry.payload.length;
            Path target = file(normalized, key);
            if (fileBytes > maximumBytes || fileBytes > MAX_FILE_BYTES
                    || !ensureDirectory(normalized)) {
                deleteRegular(target);
                maintainLocked(normalized, maximumBytes);
                return false;
            }
            Path temporary = null;
            try {
                temporary = Files.createTempFile(normalized, ".model-", ".tmp");
                try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                        Files.newOutputStream(temporary)))) {
                    output.writeInt(MAGIC);
                    output.writeInt(VERSION);
                    writeDigest(output, entry.validationDigest);
                    writeDigest(output, entry.payloadDigest);
                    output.writeInt(entry.payload.length);
                    output.write(entry.payload);
                }
                moveAtomically(temporary, target);
                temporary = null;
                maintainLocked(normalized, maximumBytes);
                return regularFile(target);
            } catch (IOException | RuntimeException exception) {
                return false;
            } finally {
                if (temporary != null) {
                    deleteRegular(temporary);
                }
            }
        }
    }

    public static void remove(Path root, String key) {
        Path normalized = normalize(root);
        synchronized (lock(normalized)) {
            if (safeDirectory(normalized)) {
                deleteRegular(file(normalized, key));
            }
        }
    }

    public static void maintain(Path root, long maximumBytes) {
        Path normalized = normalize(root);
        synchronized (lock(normalized)) {
            maintainLocked(normalized, Math.max(0, maximumBytes));
        }
    }

    public static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static String hashKey(String value) {
        byte[] digest = sha256((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte part : digest) {
            result.append(Character.forDigit((part >>> 4) & 0xF, 16));
            result.append(Character.forDigit(part & 0xF, 16));
        }
        return result.toString();
    }

    public static long mebibytes(int value) {
        return Math.max(0L, value) * 1024L * 1024L;
    }

    private static void maintainLocked(Path root, long maximumBytes) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!safeDirectory(root)) {
            return;
        }
        List<CacheFile> files = new ArrayList<>();
        try (Stream<Path> listed = Files.list(root)) {
            for (Path path : listed.toList()) {
                if (!regularFile(path)) {
                    continue;
                }
                String name = path.getFileName().toString();
                if (name.endsWith(".tmp")) {
                    deleteRegular(path);
                } else if (name.endsWith(".cache")) {
                    files.add(new CacheFile(path, Files.size(path),
                            Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS)));
                }
            }
        } catch (IOException ignored) {
            return;
        }
        long total = files.stream().mapToLong(CacheFile::size).sum();
        files.sort(Comparator.comparing(CacheFile::lastUsed)
                .thenComparing(file -> file.path().getFileName().toString()));
        for (CacheFile candidate : files) {
            if (total <= maximumBytes) {
                break;
            }
            if (deleteRegular(candidate.path())) {
                total -= candidate.size();
            }
        }
    }

    private static byte[] digest(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length != DIGEST_BYTES) {
            throw new IOException("Invalid model cache digest");
        }
        byte[] value = input.readNBytes(length);
        if (value.length != length) {
            throw new IOException("Truncated model cache digest");
        }
        return value;
    }

    private static void writeDigest(DataOutputStream output, byte[] digest) throws IOException {
        output.writeInt(digest.length);
        output.write(digest);
    }

    private static byte[] copyDigest(byte[] digest, String label) {
        if (digest == null || digest.length != DIGEST_BYTES) {
            throw new IllegalArgumentException("Invalid " + label + " digest");
        }
        return Arrays.copyOf(digest, digest.length);
    }

    private static Path file(Path root, String key) {
        return root.resolve(hashKey(key) + ".cache");
    }

    private static Object lock(Path root) {
        return LOCKS.computeIfAbsent(root, ignored -> new Object());
    }

    private static Path normalize(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("Missing cache root");
        }
        return root.toAbsolutePath().normalize();
    }

    private static boolean ensureDirectory(Path root) {
        try {
            if (hasSymbolicSegment(root)) {
                return false;
            }
            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                return safeDirectory(root);
            }
            Files.createDirectories(root);
            return safeDirectory(root);
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private static boolean safeDirectory(Path path) {
        return !hasSymbolicSegment(path)
                && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean hasSymbolicSegment(Path path) {
        Path current = path.getRoot();
        for (Path part : path) {
            current = current == null ? part : current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && Files.isSymbolicLink(current)) {
                return true;
            }
        }
        return false;
    }

    private static boolean regularFile(Path path) {
        return !Files.isSymbolicLink(path)
                && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean deleteRegular(Path path) {
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            if (!regularFile(path)) {
                return false;
            }
            return Files.deleteIfExists(path);
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
