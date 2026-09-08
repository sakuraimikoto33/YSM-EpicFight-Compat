package net.okitsu.ysmepicfightcompat.network.geometry;

import net.okitsu.ysmepicfightcompat.assets.ModelBundle;
import net.okitsu.ysmepicfightcompat.cache.ModelDiskCache;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Client-thread-owned transfer state; workers may only submit completed results. */
final class ClientModelTransferSession {
    record Ticket(Object generation, Object connection, String serverIdentity) {
    }

    record Cached(String serverIdentity, ModelDiskCache.Entry entry) {
    }

    record RequestSource(int entityId, UUID entityUuid) {
    }

    private final Executor clientExecutor;
    private final Supplier<?> currentConnection;
    private final Runnable clearAssemblies;
    private final int maximumReady;
    private final long retryAfter;
    private final Map<String, ModelBundle> ready = new HashMap<>();
    private final Map<String, Long> lastRequest = new HashMap<>();
    private final Map<String, Cached> cached = new HashMap<>();
    private final Map<String, Ticket> lookups = new HashMap<>();
    private final Map<String, RequestSource> requestSources = new HashMap<>();
    private Object generation = new Object();
    private Object connection;

    ClientModelTransferSession(Executor clientExecutor, Supplier<?> currentConnection,
                               Runnable clearAssemblies, int maximumReady, long retryAfter) {
        if (maximumReady < 1 || retryAfter < 0L) {
            throw new IllegalArgumentException("Invalid client transfer limits");
        }
        this.clientExecutor = clientExecutor;
        this.currentConnection = currentConnection;
        this.clearAssemblies = clearAssemblies;
        this.maximumReady = maximumReady;
        this.retryAfter = retryAfter;
    }

    /** Called on the client thread, including when a connection changes before logout cleanup. */
    Ticket capture(String serverIdentity) {
        Object active = currentConnection.get();
        if (active != connection) {
            clear();
            connection = active;
        }
        return active == null ? null : new Ticket(generation, active, serverIdentity);
    }

    boolean isCurrent(Ticket ticket) {
        return ticket != null && ticket.generation() == generation
                && ticket.connection() == connection && connection != null
                && connection == currentConnection.get();
    }

    /** Invalidation and every state mutation run on the same client executor. */
    void clear() {
        generation = new Object();
        connection = null;
        ready.clear();
        lastRequest.clear();
        cached.clear();
        lookups.clear();
        requestSources.clear();
        clearAssemblies.run();
    }

    void rememberSource(String modelId, RequestSource source) {
        requestSources.put(modelId, source);
    }

    RequestSource source(String modelId) {
        return requestSources.get(modelId);
    }

    ModelBundle takeReady(String modelId) {
        return ready.remove(modelId);
    }

    boolean requestDue(String modelId, long now) {
        Long previous = lastRequest.get(modelId);
        if (previous != null && now - previous < retryAfter) {
            return false;
        }
        requested(modelId, now);
        return true;
    }

    void requested(String modelId, long now) {
        lastRequest.put(modelId, now);
    }

    Long lastRequested(String modelId) {
        return lastRequest.get(modelId);
    }

    Cached cached(String modelId) {
        return cached.get(modelId);
    }

    Cached unavailable(String modelId, long now) {
        requested(modelId, now);
        return cached.remove(modelId);
    }

    boolean beginLookup(Ticket ticket, String modelId) {
        return isCurrent(ticket) && lookups.putIfAbsent(modelId, ticket) == null;
    }

    boolean hasLookup(String modelId) {
        return lookups.containsKey(modelId);
    }

    /** May be called by a worker; the cache result and its request are committed together. */
    void completeLookup(Ticket ticket, String modelId, Cached found,
                        Consumer<byte[]> sendRequest) {
        submit(ticket, () -> {
            if (!lookups.remove(modelId, ticket)) {
                return;
            }
            if (found == null) {
                cached.remove(modelId);
            } else {
                cached.put(modelId, found);
            }
            sendRequest.accept(found == null ? new byte[0] : found.entry().payloadDigest());
        });
    }

    void failLookup(Ticket ticket, String modelId, Runnable reportFailure) {
        submit(ticket, () -> {
            if (lookups.remove(modelId, ticket)) {
                reportFailure.run();
            }
        });
    }

    /** Publication, retry state, and all arrival notifications share one guarded commit. */
    void completeModel(Ticket ticket, String modelId, ModelBundle model, Runnable arrived) {
        submit(ticket, () -> {
            cached.remove(modelId);
            if (ready.size() >= maximumReady) {
                ready.clear();
            }
            ready.put(modelId, model);
            lastRequest.remove(modelId);
            arrived.run();
        });
    }

    /** Never select a newer cache entry as the deletion target of an older decode. */
    void failModel(Ticket ticket, String modelId, Cached failedCache, long now,
                   Consumer<Cached> rejected) {
        submit(ticket, () -> {
            if (failedCache != null) {
                cached.remove(modelId, failedCache);
            }
            requested(modelId, now);
            rejected.accept(failedCache);
        });
    }

    private void submit(Ticket ticket, Runnable commit) {
        clientExecutor.execute(() -> {
            if (isCurrent(ticket)) {
                commit.run();
            }
        });
    }
}
