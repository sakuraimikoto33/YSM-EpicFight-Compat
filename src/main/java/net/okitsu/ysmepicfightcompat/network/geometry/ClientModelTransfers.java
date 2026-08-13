package net.okitsu.ysmepicfightcompat.network.geometry;

import net.minecraft.client.Minecraft;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.assets.ModelBundle;
import net.okitsu.ysmepicfightcompat.mesh.CombatMeshCache;
import net.okitsu.ysmepicfightcompat.network.CompatNetwork;
import net.okitsu.ysmepicfightcompat.network.message.ModelChunkMessage;
import net.okitsu.ysmepicfightcompat.network.message.ModelRequestMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Client-side bounded reassembly of model data supplied by the current server. */
public final class ClientModelTransfers {
    private static final long RETRY_AFTER = 5_000_000_000L;
    private static final long ASSEMBLY_TIMEOUT = 30_000_000_000L;
    private static final int MAX_ASSEMBLIES = 2;
    private static final Map<String, ModelBundle> READY = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_REQUEST = new ConcurrentHashMap<>();
    private static final Map<UUID, Assembly> ASSEMBLIES = new ConcurrentHashMap<>();
    private static final AtomicInteger GENERATION = new AtomicInteger();
    private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(task -> {
        Thread worker = new Thread(task, "ysm-ef-model-receiver");
        worker.setDaemon(true);
        return worker;
    });

    private static final class Assembly {
        private final String modelId;
        private final int expectedBytes;
        private final byte[][] chunks;
        private final long startedAt = System.nanoTime();
        private int chunksReceived;
        private int bytesReceived;

        private Assembly(String modelId, int expectedBytes, int chunkCount) {
            this.modelId = modelId;
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
            CompatNetwork.CHANNEL.sendToServer(new ModelRequestMessage(modelId));
        }
        return null;
    }

    public static void accept(ModelChunkMessage message) {
        if (message.totalBytes() == -1) {
            LAST_REQUEST.put(message.modelId(), System.nanoTime());
            return;
        }
        long now = System.nanoTime();
        ASSEMBLIES.entrySet().removeIf(entry ->
                now - entry.getValue().startedAt >= ASSEMBLY_TIMEOUT);
        Assembly assembly = ASSEMBLIES.get(message.transferId());
        if (assembly == null) {
            if (ASSEMBLIES.size() >= MAX_ASSEMBLIES) {
                return;
            }
            Assembly candidate = new Assembly(message.modelId(), message.totalBytes(),
                    message.chunkCount());
            Assembly concurrent = ASSEMBLIES.putIfAbsent(message.transferId(), candidate);
            assembly = concurrent == null ? candidate : concurrent;
        }
        if (!assembly.modelId.equals(message.modelId())
                || assembly.expectedBytes != message.totalBytes()
                || assembly.chunks.length != message.chunkCount()) {
            ASSEMBLIES.remove(message.transferId());
            return;
        }
        synchronized (assembly) {
            if (assembly.chunks[message.chunkIndex()] != null) {
                return;
            }
            byte[] copy = Arrays.copyOf(message.bytes(), message.bytes().length);
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
            decode(assembly.modelId, payload, GENERATION.get());
        }
    }

    public static void clear() {
        GENERATION.incrementAndGet();
        READY.clear();
        LAST_REQUEST.clear();
        ASSEMBLIES.clear();
    }

    private static void decode(String modelId, byte[] payload, int expectedGeneration) {
        DECODER.execute(() -> {
            try {
                ModelBundle model = GeometryTransferCodec.decode(modelId, payload);
                if (expectedGeneration != GENERATION.get()) {
                    return;
                }
                if (READY.size() >= MAX_ASSEMBLIES) {
                    READY.clear();
                }
                READY.put(modelId, model);
                LAST_REQUEST.remove(modelId);
                CombatMeshCache.remoteArrived(modelId);
                CompatMod.LOG.info(
                        "YSM-EF Compat: received server geometry for '{}'", modelId);
            } catch (IOException exception) {
                LAST_REQUEST.put(modelId, System.nanoTime());
                CompatMod.LOG.warn(
                        "YSM-EF Compat: rejected server geometry for '{}'", modelId, exception);
            }
        });
    }

    private static boolean validModelId(String modelId) {
        return modelId != null && !modelId.isBlank()
                && modelId.getBytes(StandardCharsets.UTF_8).length
                <= ModelRequestMessage.MAX_MODEL_ID_BYTES;
    }
}
