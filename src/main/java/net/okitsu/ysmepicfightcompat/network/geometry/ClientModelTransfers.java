package net.okitsu.ysmepicfightcompat.network.geometry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.assets.ModelBundle;
import net.okitsu.ysmepicfightcompat.cache.ClientLocalModelCache;
import net.okitsu.ysmepicfightcompat.cache.ModelDiskCache;
import net.okitsu.ysmepicfightcompat.cache.RemoteModelDiskCache;
import net.okitsu.ysmepicfightcompat.mesh.CombatMeshCache;
import net.okitsu.ysmepicfightcompat.network.CompatNetwork;
import net.okitsu.ysmepicfightcompat.network.message.ModelChunkMessage;
import net.okitsu.ysmepicfightcompat.network.message.ModelRequestMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Client-side disk validation and bounded reassembly of server model data. */
public final class ClientModelTransfers {
    private static final long RETRY_AFTER = 5_000_000_000L;
    private static final long ASSEMBLY_TIMEOUT = 30_000_000_000L;
    private static final int MAX_ASSEMBLIES = 2;
    private static final Map<String, ModelBundle> READY = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_REQUEST = new ConcurrentHashMap<>();
    private static final Map<UUID, Assembly> ASSEMBLIES = new ConcurrentHashMap<>();
    private static final Map<String, Cached> CACHED = new ConcurrentHashMap<>();
    private static final Map<String, Integer> LOOKUPS = new ConcurrentHashMap<>();
    private static final AtomicInteger GENERATION = new AtomicInteger();
    private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(task -> {
        Thread worker = new Thread(task, "ysm-ef-model-receiver");
        worker.setDaemon(true);
        return worker;
    });

    private record Cached(String serverIdentity, ModelDiskCache.Entry entry) {
    }

    private static final class Assembly {
        private final String modelId;
        private final String serverIdentity;
        private final byte[] payloadDigest;
        private final int expectedBytes;
        private final byte[][] chunks;
        private final long startedAt = System.nanoTime();
        private int chunksReceived;
        private int bytesReceived;

        private Assembly(String modelId, String serverIdentity, byte[] payloadDigest,
                         int expectedBytes, int chunkCount) {
            this.modelId = modelId;
            this.serverIdentity = serverIdentity;
            this.payloadDigest = Arrays.copyOf(payloadDigest, payloadDigest.length);
            this.expectedBytes = expectedBytes;
            chunks = new byte[chunkCount][];
        }
    }

    private ClientModelTransfers() {
    }

    public static ModelBundle findOrRequest(String modelId) {
        if (!validModelId(modelId)) {
            return null;
        }
        ModelBundle ready = READY.remove(modelId);
        if (ready != null || Minecraft.getInstance().getConnection() == null) {
            return ready;
        }
        long now = System.nanoTime();
        Long previous = LAST_REQUEST.putIfAbsent(modelId, now);
        if (previous == null || now - previous >= RETRY_AFTER) {
            LAST_REQUEST.put(modelId, now);
            lookupAndRequest(modelId, GENERATION.get());
        }
        return null;
    }

    public static void accept(ModelChunkMessage message) {
        if (message.status() == ModelChunkMessage.Status.UNAVAILABLE) {
            LAST_REQUEST.put(message.modelId(), System.nanoTime());
            Cached cached = CACHED.remove(message.modelId());
            if (cached != null) {
                DECODER.execute(() -> RemoteModelDiskCache.remove(
                        cached.serverIdentity(), message.modelId()));
            }
            return;
        }
        if (message.status() == ModelChunkMessage.Status.UNCHANGED) {
            acceptUnchanged(message);
            return;
        }
        acceptChunk(message);
    }

    public static void clear() {
        GENERATION.incrementAndGet();
        READY.clear();
        LAST_REQUEST.clear();
        ASSEMBLIES.clear();
        CACHED.clear();
        LOOKUPS.clear();
        DECODER.execute(() -> {
            ClientLocalModelCache.maintain();
            RemoteModelDiskCache.maintain();
        });
    }

    private static void lookupAndRequest(String modelId, int expectedGeneration) {
        if (LOOKUPS.putIfAbsent(modelId, expectedGeneration) != null) {
            return;
        }
        String serverIdentity = currentServerIdentity();
        DECODER.execute(() -> {
            try {
                Optional<ModelDiskCache.Entry> disk =
                        RemoteModelDiskCache.read(serverIdentity, modelId);
                if (expectedGeneration != GENERATION.get()) {
                    return;
                }
                if (disk.isPresent()) {
                    CACHED.put(modelId, new Cached(serverIdentity, disk.get()));
                } else {
                    CACHED.remove(modelId);
                }
                byte[] known = disk.map(ModelDiskCache.Entry::payloadDigest)
                        .orElseGet(() -> new byte[0]);
                Minecraft.getInstance().execute(() -> {
                    if (expectedGeneration == GENERATION.get()
                            && Minecraft.getInstance().getConnection() != null) {
                        CompatNetwork.CHANNEL.sendToServer(
                                new ModelRequestMessage(modelId, known));
                    }
                });
            } finally {
                LOOKUPS.remove(modelId, expectedGeneration);
            }
        });
    }

    private static void acceptUnchanged(ModelChunkMessage message) {
        Cached cached = CACHED.get(message.modelId());
        if (cached == null || !MessageDigest.isEqual(
                message.payloadDigest(), cached.entry().payloadDigest())) {
            forceFullRequest(message.modelId());
            return;
        }
        decode(message.modelId(), cached.serverIdentity(), cached.entry().payload(),
                message.payloadDigest(), false, GENERATION.get());
    }

    private static void acceptChunk(ModelChunkMessage message) {
        long now = System.nanoTime();
        ASSEMBLIES.entrySet().removeIf(entry ->
                now - entry.getValue().startedAt >= ASSEMBLY_TIMEOUT);
        Assembly assembly = ASSEMBLIES.get(message.transferId());
        if (assembly == null) {
            if (ASSEMBLIES.size() >= MAX_ASSEMBLIES) {
                return;
            }
            Assembly candidate = new Assembly(message.modelId(), currentServerIdentity(),
                    message.payloadDigest(), message.totalBytes(), message.chunkCount());
            Assembly concurrent = ASSEMBLIES.putIfAbsent(message.transferId(), candidate);
            assembly = concurrent == null ? candidate : concurrent;
        }
        if (!assembly.modelId.equals(message.modelId())
                || !MessageDigest.isEqual(assembly.payloadDigest, message.payloadDigest())
                || assembly.expectedBytes != message.totalBytes()
                || assembly.chunks.length != message.chunkCount()) {
            ASSEMBLIES.remove(message.transferId());
            return;
        }
        synchronized (assembly) {
            if (assembly.chunks[message.chunkIndex()] != null) {
                return;
            }
            byte[] copy = message.bytes();
            assembly.chunks[message.chunkIndex()] = copy;
            assembly.chunksReceived++;
            assembly.bytesReceived += copy.length;
            if (assembly.bytesReceived > assembly.expectedBytes) {
                ASSEMBLIES.remove(message.transferId());
                return;
            }
            if (assembly.chunksReceived != assembly.chunks.length) {
                return;
            }
            ASSEMBLIES.remove(message.transferId());
            if (assembly.bytesReceived != assembly.expectedBytes) {
                return;
            }
            byte[] payload = new byte[assembly.expectedBytes];
            int offset = 0;
            for (byte[] chunk : assembly.chunks) {
                System.arraycopy(chunk, 0, payload, offset, chunk.length);
                offset += chunk.length;
            }
            decode(assembly.modelId, assembly.serverIdentity, payload,
                    assembly.payloadDigest, true, GENERATION.get());
        }
    }

    private static void decode(String modelId, String serverIdentity, byte[] payload,
                               byte[] expectedDigest, boolean persist,
                               int expectedGeneration) {
        DECODER.execute(() -> {
            try {
                byte[] actualDigest = ModelDiskCache.sha256(payload);
                if (!MessageDigest.isEqual(expectedDigest, actualDigest)) {
                    throw new IOException("Server model payload digest mismatch");
                }
                ModelBundle model = GeometryTransferCodec.decode(modelId, payload);
                if (expectedGeneration != GENERATION.get()) {
                    return;
                }
                if (persist) {
                    RemoteModelDiskCache.write(serverIdentity, modelId,
                            actualDigest, payload);
                }
                CACHED.remove(modelId);
                if (READY.size() >= MAX_ASSEMBLIES) {
                    READY.clear();
                }
                READY.put(modelId, model);
                LAST_REQUEST.remove(modelId);
                CombatMeshCache.remoteArrived(modelId);
                CompatMod.LOG.info(
                        "YSM-EF Compat: received server model '{}'", modelId);
            } catch (IOException | RuntimeException exception) {
                if (expectedGeneration != GENERATION.get()) {
                    return;
                }
                Cached cached = CACHED.remove(modelId);
                if (cached != null) {
                    RemoteModelDiskCache.remove(cached.serverIdentity(), modelId);
                }
                LAST_REQUEST.put(modelId, System.nanoTime());
                CompatMod.LOG.warn(
                        "YSM-EF Compat: rejected server model '{}'", modelId, exception);
                if (!persist && expectedGeneration == GENERATION.get()) {
                    Minecraft.getInstance().execute(() -> forceFullRequest(modelId));
                }
            }
        });
    }

    private static void forceFullRequest(String modelId) {
        if (Minecraft.getInstance().getConnection() == null) {
            return;
        }
        LAST_REQUEST.put(modelId, System.nanoTime());
        CompatNetwork.CHANNEL.sendToServer(new ModelRequestMessage(modelId));
    }

    private static String currentServerIdentity() {
        ServerData server = Minecraft.getInstance().getCurrentServer();
        if (server == null || server.ip == null || server.ip.isBlank()) {
            return "integrated";
        }
        return server.ip.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean validModelId(String modelId) {
        return modelId != null && !modelId.isBlank()
                && modelId.getBytes(StandardCharsets.UTF_8).length
                <= ModelRequestMessage.MAX_MODEL_ID_BYTES;
    }
}
