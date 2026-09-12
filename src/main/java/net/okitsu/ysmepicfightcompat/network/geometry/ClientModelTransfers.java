package net.okitsu.ysmepicfightcompat.network.geometry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;
import net.minecraft.world.entity.LivingEntity;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.assets.ModelBundle;
import net.okitsu.ysmepicfightcompat.cache.ClientLocalModelCache;
import net.okitsu.ysmepicfightcompat.cache.ModelDiskCache;
import net.okitsu.ysmepicfightcompat.cache.RemoteModelDiskCache;
import net.okitsu.ysmepicfightcompat.mesh.CombatMeshCache;
import net.okitsu.ysmepicfightcompat.network.CompatNetwork;
import net.okitsu.ysmepicfightcompat.network.ClientSubEntityModelPreferences;
import net.okitsu.ysmepicfightcompat.network.message.ModelChunkMessage;
import net.okitsu.ysmepicfightcompat.network.message.ModelRequestMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Client-side disk validation and bounded reassembly of server model data. */
public final class ClientModelTransfers {
    private static final long RETRY_AFTER = 5_000_000_000L;
    private static final long ASSEMBLY_TIMEOUT = 30_000_000_000L;
    private static final int MAX_ASSEMBLIES = 2;
    // One cache candidate/assembly plus one payload copy during join/decode/persistence.
    private static final long BYTES_PER_REQUEST = 2L * GeometryTransferCodec.MAX_COMPRESSED_BYTES;
    private static final Map<UUID, Assembly> ASSEMBLIES = new HashMap<>();
    private static final ClientModelTransferSession SESSION = new ClientModelTransferSession(
            task -> Minecraft.getInstance().execute(task), ClientModelTransfers::currentConnection,
            ASSEMBLIES::clear, MAX_ASSEMBLIES, 256, BYTES_PER_REQUEST,
            MAX_ASSEMBLIES * BYTES_PER_REQUEST, RETRY_AFTER, ASSEMBLY_TIMEOUT);
    private static final AtomicBoolean MAINTENANCE_PENDING = new AtomicBoolean();
    private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(task -> {
        Thread worker = new Thread(task, "ysm-ef-model-receiver");
        worker.setDaemon(true);
        return worker;
    });

    private static final class Assembly {
        private final ClientModelTransferSession.Request request;
        private final String modelId;
        private final byte[] payloadDigest;
        private final int expectedBytes;
        private final byte[][] chunks;
        private int chunksReceived;
        private int bytesReceived;

        private Assembly(ClientModelTransferSession.Request request, String modelId,
                         byte[] payloadDigest,
                         int expectedBytes, int chunkCount) {
            this.request = request;
            this.modelId = modelId;
            this.payloadDigest = Arrays.copyOf(payloadDigest, payloadDigest.length);
            this.expectedBytes = expectedBytes;
            chunks = new byte[chunkCount][];
        }
    }

    private ClientModelTransfers() {
    }

    /** Completion must run after conversion publication/disposal, including failed or cancelled work. */
    public record ReadyModel(ModelBundle model, Runnable completion) { }

    public static ReadyModel findOrRequest(String modelId, LivingEntity source) {
        if (!Minecraft.getInstance().isSameThread() || !validModelId(modelId)
                || source == null || source.getId() < 0) {
            return null;
        }
        ClientModelTransferSession.Ticket ticket = SESSION.capture(currentServerIdentity());
        if (ticket == null) {
            return null;
        }
        long now = System.nanoTime();
        SESSION.rememberSource(modelId,
                new ClientModelTransferSession.RequestSource(source.getId(), source.getUUID()), now);
        ClientModelTransferSession.Handoff ready = SESSION.takeReady(modelId);
        if (ready != null) {
            ClientModelTransferSession.Request request = ready.request();
            return new ReadyModel(ready.model(), () -> SESSION.completeConversion(request));
        }
        pump(ticket, now);
        return null;
    }

    /** Progress waiting demand and expire idle transfers even when no model is rendered. */
    public static void tick() {
        long now = System.nanoTime();
        ClientModelTransferSession.Ticket ticket = SESSION.capture(currentServerIdentity());
        maintain(now);
        pump(ticket, now);
    }

    private static void maintain(long now) {
        SESSION.maintain(now);
        pruneAssemblies();
    }

    private static void pruneAssemblies() {
        ASSEMBLIES.entrySet().removeIf(entry -> !SESSION.isCurrent(entry.getValue().request));
    }

    public static void removeSource(int entityId, UUID entityUuid) {
        SESSION.removeSource(new ClientModelTransferSession.RequestSource(entityId, entityUuid));
        pruneAssemblies();
    }

    private static void pump(ClientModelTransferSession.Ticket ticket, long now) {
        ClientModelTransferSession.Request request;
        while ((request = SESSION.nextRequest(ticket, now)) != null) {
            lookupAndRequest(request);
        }
        for (ClientModelTransferSession.Request waiting : SESSION.retryWaiting(now)) {
            ClientModelTransferSession.Cached cached = SESSION.cached(waiting);
            sendRequest(waiting, cached == null ? new byte[0] : cached.entry().payloadDigest());
        }
    }

    public static void accept(ModelChunkMessage message, Connection sourceConnection) {
        Minecraft client = Minecraft.getInstance();
        if (!client.isSameThread()) {
            client.execute(() -> accept(message, sourceConnection));
            return;
        }
        if (sourceConnection == null || sourceConnection != currentConnection()) {
            return;
        }
        SESSION.capture(currentServerIdentity());
        pruneAssemblies();
        ClientModelTransferSession.Request request = SESSION.awaitingResponse(message.modelId());
        if (request == null) {
            return;
        }
        if (message.status() == ModelChunkMessage.Status.UNAVAILABLE) {
            removeCached(message.modelId(), SESSION.failed(request, System.nanoTime()));
            return;
        }
        if (message.status() == ModelChunkMessage.Status.UNCHANGED) {
            acceptUnchanged(message, request);
            return;
        }
        acceptChunk(message, request);
    }

    public static void clear() {
        Minecraft client = Minecraft.getInstance();
        if (!client.isSameThread()) {
            client.execute(ClientModelTransfers::clear);
            return;
        }
        SESSION.clear();
        if (MAINTENANCE_PENDING.compareAndSet(false, true)) {
            DECODER.execute(() -> {
                try {
                    ClientLocalModelCache.maintain();
                    RemoteModelDiskCache.maintain();
                } finally {
                    MAINTENANCE_PENDING.set(false);
                }
            });
        }
    }

    private static void lookupAndRequest(ClientModelTransferSession.Request request) {
        String modelId = request.modelId();
        DECODER.execute(() -> {
            try {
                ClientModelTransferSession.Cached cached = RemoteModelDiskCache.read(
                        request.ticket().serverIdentity(), modelId).map(entry ->
                        new ClientModelTransferSession.Cached(request.ticket().serverIdentity(), entry))
                        .orElse(null);
                SESSION.completeLookup(request, cached, System.nanoTime(),
                        known -> sendRequest(request, known));
            } catch (RuntimeException exception) {
                SESSION.failWorker(request, System.nanoTime(), () -> CompatMod.LOG.warn(
                        "YSM-EF Compat: failed to read cached server model '{}'",
                        modelId, exception));
            }
        });
    }

    private static void acceptUnchanged(ModelChunkMessage message,
                                         ClientModelTransferSession.Request request) {
        ClientModelTransferSession.Cached cached = SESSION.cached(request);
        if (cached == null || !MessageDigest.isEqual(
                message.payloadDigest(), cached.entry().payloadDigest())) {
            forceFullRequest(request);
            return;
        }
        if (!SESSION.beginDecode(request, System.nanoTime())) { return; }
        decode(message.modelId(), cached.entry().payload(),
                message.payloadDigest(), false, request, cached);
    }

    private static void acceptChunk(ModelChunkMessage message,
                                     ClientModelTransferSession.Request request) {
        long now = System.nanoTime();
        if (!SESSION.receive(request, message.transferId(), now)) { return; }
        Assembly assembly = ASSEMBLIES.get(message.transferId());
        if (assembly == null) {
            if (ASSEMBLIES.size() >= MAX_ASSEMBLIES) {
                return;
            }
            Assembly candidate = new Assembly(request, message.modelId(),
                    message.payloadDigest(), message.totalBytes(), message.chunkCount());
            Assembly concurrent = ASSEMBLIES.putIfAbsent(message.transferId(), candidate);
            assembly = concurrent == null ? candidate : concurrent;
        }
        if (!SESSION.isCurrent(assembly.request) || assembly.request != request
                || !assembly.modelId.equals(message.modelId())
                || !MessageDigest.isEqual(assembly.payloadDigest, message.payloadDigest())
                || assembly.expectedBytes != message.totalBytes()
                || assembly.chunks.length != message.chunkCount()) {
            ASSEMBLIES.remove(message.transferId());
            SESSION.failed(request, now);
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
                SESSION.failed(request, now);
                return;
            }
            if (assembly.chunksReceived != assembly.chunks.length) {
                return;
            }
            ASSEMBLIES.remove(message.transferId());
            if (assembly.bytesReceived != assembly.expectedBytes) {
                SESSION.failed(request, now);
                return;
            }
            if (!SESSION.beginDecode(request, now)) { return; }
            byte[] payload = new byte[assembly.expectedBytes];
            int offset = 0;
            for (byte[] chunk : assembly.chunks) {
                System.arraycopy(chunk, 0, payload, offset, chunk.length);
                offset += chunk.length;
            }
            decode(assembly.modelId, payload, assembly.payloadDigest, true, assembly.request, null);
        }
    }

    private static void decode(String modelId, byte[] payload,
                               byte[] expectedDigest, boolean persist,
                               ClientModelTransferSession.Request request,
                               ClientModelTransferSession.Cached cached) {
        DECODER.execute(() -> {
            try {
                byte[] actualDigest = ModelDiskCache.sha256(payload);
                if (!MessageDigest.isEqual(expectedDigest, actualDigest)) {
                    throw new IOException("Server model payload digest mismatch");
                }
                ModelBundle model = GeometryTransferCodec.decode(modelId, payload);
                if (persist) {
                    // A valid old-server disk entry may finish after disconnect; only
                    // publication into the active session is generation/connection gated.
                    RemoteModelDiskCache.write(request.ticket().serverIdentity(), modelId,
                            actualDigest, payload);
                }
                SESSION.completeModel(request, model, System.nanoTime(), () -> {
                    CombatMeshCache.remoteArrived(modelId);
                    ClientSubEntityModelPreferences.modelDefinitionsUpdated();
                    CompatMod.LOG.info("YSM-EF Compat: received server model '{}'", modelId);
                });
            } catch (IOException | RuntimeException exception) {
                if (cached != null) {
                    try {
                        RemoteModelDiskCache.removeIfPayloadDigestMatches(cached.serverIdentity(),
                                modelId, cached.entry().payloadDigest());
                    } catch (RuntimeException cleanupFailure) {
                        exception.addSuppressed(cleanupFailure);
                    }
                }
                Runnable rejected = () -> {
                    CompatMod.LOG.warn(
                            "YSM-EF Compat: rejected server model '{}'", modelId, exception);
                };
                if (!persist) {
                    SESSION.retryWithoutCache(request, System.nanoTime(), () -> {
                        rejected.run();
                        forceFullRequest(request);
                    });
                } else {
                    SESSION.failWorker(request, System.nanoTime(), rejected);
                }
            }
        });
    }

    private static void forceFullRequest(ClientModelTransferSession.Request request) {
        if (!SESSION.retryFull(request, System.nanoTime())) {
            return;
        }
        sendRequest(request, new byte[0]);
    }

    private static void sendRequest(ClientModelTransferSession.Request request,
                                    byte[] knownPayloadDigest) {
        ClientModelTransferSession.RequestSource source = SESSION.source(request);
        if (source == null || !SESSION.isCurrent(request)) {
            return;
        }
        CompatNetwork.toServer(new ModelRequestMessage(
                request.modelId(), source.entityId(), source.entityUuid(), knownPayloadDigest));
    }

    private static void removeCached(String modelId, ClientModelTransferSession.Cached cached) {
        if (cached != null) {
            DECODER.execute(() -> RemoteModelDiskCache.removeIfPayloadDigestMatches(
                    cached.serverIdentity(), modelId, cached.entry().payloadDigest()));
        }
    }

    private static Connection currentConnection() {
        var listener = Minecraft.getInstance().getConnection();
        if (listener == null) {
            return null;
        }
        Connection connection = listener.getConnection();
        return connection.isConnected() ? connection : null;
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
