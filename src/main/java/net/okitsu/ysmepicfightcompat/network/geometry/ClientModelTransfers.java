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

/** Client-side disk validation and bounded reassembly of server model data. */
public final class ClientModelTransfers {
    private static final long RETRY_AFTER = 5_000_000_000L;
    private static final long ASSEMBLY_TIMEOUT = 30_000_000_000L;
    private static final int MAX_ASSEMBLIES = 2;
    private static final Map<UUID, Assembly> ASSEMBLIES = new HashMap<>();
    private static final ClientModelTransferSession SESSION = new ClientModelTransferSession(
            task -> Minecraft.getInstance().execute(task), ClientModelTransfers::currentConnection,
            ASSEMBLIES::clear, MAX_ASSEMBLIES, RETRY_AFTER);
    private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(task -> {
        Thread worker = new Thread(task, "ysm-ef-model-receiver");
        worker.setDaemon(true);
        return worker;
    });

    private static final class Assembly {
        private final ClientModelTransferSession.Ticket ticket;
        private final String modelId;
        private final byte[] payloadDigest;
        private final int expectedBytes;
        private final byte[][] chunks;
        private final long startedAt = System.nanoTime();
        private int chunksReceived;
        private int bytesReceived;

        private Assembly(ClientModelTransferSession.Ticket ticket, String modelId,
                         byte[] payloadDigest,
                         int expectedBytes, int chunkCount) {
            this.ticket = ticket;
            this.modelId = modelId;
            this.payloadDigest = Arrays.copyOf(payloadDigest, payloadDigest.length);
            this.expectedBytes = expectedBytes;
            chunks = new byte[chunkCount][];
        }
    }

    private ClientModelTransfers() {
    }

    public static ModelBundle findOrRequest(String modelId, LivingEntity source) {
        if (!Minecraft.getInstance().isSameThread() || !validModelId(modelId)
                || source == null || source.getId() < 0) {
            return null;
        }
        ClientModelTransferSession.Ticket ticket = SESSION.capture(currentServerIdentity());
        if (ticket == null) {
            return null;
        }
        SESSION.rememberSource(modelId,
                new ClientModelTransferSession.RequestSource(source.getId(), source.getUUID()));
        ModelBundle ready = SESSION.takeReady(modelId);
        if (ready != null) {
            return ready;
        }
        long now = System.nanoTime();
        if (SESSION.requestDue(modelId, now)) {
            lookupAndRequest(modelId, ticket);
        }
        return null;
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
        ClientModelTransferSession.Ticket ticket = SESSION.capture(currentServerIdentity());
        if (ticket == null) {
            return;
        }
        if (message.status() == ModelChunkMessage.Status.UNAVAILABLE) {
            removeCached(message.modelId(), SESSION.unavailable(
                    message.modelId(), System.nanoTime()));
            return;
        }
        if (message.status() == ModelChunkMessage.Status.UNCHANGED) {
            acceptUnchanged(message, ticket);
            return;
        }
        acceptChunk(message, ticket);
    }

    public static void clear() {
        Minecraft client = Minecraft.getInstance();
        if (!client.isSameThread()) {
            client.execute(ClientModelTransfers::clear);
            return;
        }
        SESSION.clear();
        DECODER.execute(() -> {
            ClientLocalModelCache.maintain();
            RemoteModelDiskCache.maintain();
        });
    }

    private static void lookupAndRequest(String modelId,
                                         ClientModelTransferSession.Ticket ticket) {
        if (!SESSION.beginLookup(ticket, modelId)) {
            return;
        }
        DECODER.execute(() -> {
            try {
                ClientModelTransferSession.Cached cached = RemoteModelDiskCache.read(
                        ticket.serverIdentity(), modelId).map(entry ->
                        new ClientModelTransferSession.Cached(ticket.serverIdentity(), entry))
                        .orElse(null);
                SESSION.completeLookup(ticket, modelId, cached,
                        known -> sendRequest(ticket, modelId, known));
            } catch (RuntimeException exception) {
                SESSION.failLookup(ticket, modelId, () -> CompatMod.LOG.warn(
                        "YSM-EF Compat: failed to read cached server model '{}'",
                        modelId, exception));
            }
        });
    }

    private static void acceptUnchanged(ModelChunkMessage message,
                                         ClientModelTransferSession.Ticket ticket) {
        ClientModelTransferSession.Cached cached = SESSION.cached(message.modelId());
        if (cached == null || !MessageDigest.isEqual(
                message.payloadDigest(), cached.entry().payloadDigest())) {
            forceFullRequest(ticket, message.modelId());
            return;
        }
        decode(message.modelId(), cached.entry().payload(),
                message.payloadDigest(), false, ticket, cached);
    }

    private static void acceptChunk(ModelChunkMessage message,
                                     ClientModelTransferSession.Ticket ticket) {
        long now = System.nanoTime();
        ASSEMBLIES.entrySet().removeIf(entry ->
                now - entry.getValue().startedAt >= ASSEMBLY_TIMEOUT);
        Assembly assembly = ASSEMBLIES.get(message.transferId());
        if (assembly == null) {
            if (ASSEMBLIES.size() >= MAX_ASSEMBLIES) {
                return;
            }
            Assembly candidate = new Assembly(ticket, message.modelId(),
                    message.payloadDigest(), message.totalBytes(), message.chunkCount());
            Assembly concurrent = ASSEMBLIES.putIfAbsent(message.transferId(), candidate);
            assembly = concurrent == null ? candidate : concurrent;
        }
        if (!SESSION.isCurrent(assembly.ticket)
                || !assembly.modelId.equals(message.modelId())
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
            decode(assembly.modelId, payload, assembly.payloadDigest, true, assembly.ticket,
                    SESSION.cached(assembly.modelId));
        }
    }

    private static void decode(String modelId, byte[] payload,
                               byte[] expectedDigest, boolean persist,
                               ClientModelTransferSession.Ticket ticket,
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
                    RemoteModelDiskCache.write(ticket.serverIdentity(), modelId,
                            actualDigest, payload);
                }
                SESSION.completeModel(ticket, modelId, model, () -> {
                    CombatMeshCache.remoteArrived(modelId);
                    ClientSubEntityModelPreferences.modelDefinitionsUpdated();
                    CompatMod.LOG.info("YSM-EF Compat: received server model '{}'", modelId);
                });
            } catch (IOException | RuntimeException exception) {
                SESSION.failModel(ticket, modelId, cached, System.nanoTime(), failed -> {
                    removeCached(modelId, failed);
                    CompatMod.LOG.warn(
                            "YSM-EF Compat: rejected server model '{}'", modelId, exception);
                    if (!persist) {
                        forceFullRequest(ticket, modelId);
                    }
                });
            }
        });
    }

    private static void forceFullRequest(ClientModelTransferSession.Ticket ticket, String modelId) {
        if (!SESSION.isCurrent(ticket)) {
            return;
        }
        SESSION.requested(modelId, System.nanoTime());
        sendRequest(ticket, modelId, new byte[0]);
    }

    private static void sendRequest(ClientModelTransferSession.Ticket ticket, String modelId,
                                    byte[] knownPayloadDigest) {
        ClientModelTransferSession.RequestSource source = SESSION.source(modelId);
        if (source == null || !SESSION.isCurrent(ticket)) {
            return;
        }
        CompatNetwork.toServer(new ModelRequestMessage(
                modelId, source.entityId(), source.entityUuid(), knownPayloadDigest));
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
