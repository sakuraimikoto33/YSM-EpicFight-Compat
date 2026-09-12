package net.okitsu.ysmepicfightcompat.network.geometry;

import net.okitsu.ysmepicfightcompat.assets.ModelBundle;
import net.okitsu.ysmepicfightcompat.cache.ModelDiskCache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Client-thread-owned reservations, including workers which outlive a connection. */
final class ClientModelTransferSession {
    record Ticket(Object generation, Object connection, String serverIdentity) { }
    record Request(Ticket ticket, String modelId, Object identity) { }
    record Cached(String serverIdentity, ModelDiskCache.Entry entry) { }
    record RequestSource(int entityId, UUID entityUuid) { }
    record Handoff(Request request, ModelBundle model) { }

    private enum Phase { LOOKUP, WAITING, RECEIVING, DECODING, READY, CONVERTING }

    private static final class Demand {
        final LinkedHashMap<RequestSource, Long> sources = new LinkedHashMap<>();
        long retrySince;
        boolean retrying;
    }

    private static final class Slot {
        final Request request;
        Phase phase = Phase.LOOKUP;
        boolean worker = true;
        long touchedAt;
        long lastSentAt;
        UUID transferId;
        boolean fullRequested;
        Cached cached;
        ModelBundle ready;
        Slot(Request request, long now) { this.request = request; touchedAt = now; }
    }

    private final Executor clientExecutor;
    private final Supplier<?> currentConnection;
    private final Runnable clearAssemblies;
    private final int maximumRequests;
    private final int maximumDemands;
    private final int maximumSources;
    private final long bytesPerRequest;
    private final long maximumBytes;
    private final long retryAfter;
    private final long timeout;
    private final LinkedHashMap<String, Demand> demands = new LinkedHashMap<>();
    private final Map<RequestSource, String> sourceModels = new LinkedHashMap<>();
    private final Map<String, Slot> active = new HashMap<>();
    // Cancelled workers keep their lease until their completion reaches the client.
    private final Map<Request, Slot> leases = new HashMap<>();
    private long reservedBytes;
    private Object generation = new Object();
    private Object connection;

    ClientModelTransferSession(Executor clientExecutor, Supplier<?> currentConnection,
                               Runnable clearAssemblies, int maximumRequests,
                               int maximumDemands, long bytesPerRequest, long maximumBytes,
                               long retryAfter, long timeout) {
        if (maximumRequests < 1 || maximumDemands < maximumRequests || bytesPerRequest < 1
                || maximumBytes < bytesPerRequest || retryAfter < 0 || timeout <= 0) {
            throw new IllegalArgumentException("Invalid client transfer limits");
        }
        this.clientExecutor = clientExecutor;
        this.currentConnection = currentConnection;
        this.clearAssemblies = clearAssemblies;
        this.maximumRequests = maximumRequests;
        this.maximumDemands = maximumDemands;
        this.maximumSources = Math.multiplyExact(maximumDemands, 4);
        this.bytesPerRequest = bytesPerRequest;
        this.maximumBytes = maximumBytes;
        this.retryAfter = retryAfter;
        this.timeout = timeout;
    }

    Ticket capture(String serverIdentity) {
        Object current = currentConnection.get();
        if (current != connection) {
            clear();
            connection = current;
        }
        return current == null ? null : new Ticket(generation, current, serverIdentity);
    }

    boolean isCurrent(Ticket ticket) {
        return ticket != null && ticket.generation() == generation
                && ticket.connection() == connection && connection != null
                && connection == currentConnection.get();
    }

    boolean isCurrent(Request request) {
        return request != null && isCurrent(request.ticket())
                && active.get(request.modelId()) == leases.get(request)
                && leases.containsKey(request);
    }

    void clear() {
        generation = new Object();
        connection = null;
        for (Slot slot : new ArrayList<>(active.values())) { cancel(slot); }
        demands.clear();
        sourceModels.clear();
        clearAssemblies.run();
    }

    void rememberSource(String modelId, RequestSource source, long now) {
        String previous = sourceModels.get(source);
        if (previous != null && !previous.equals(modelId)) {
            removeSource(source);
        }
        if (!sourceModels.containsKey(source) && sourceModels.size() >= maximumSources) {
            // Keep accepted active demand intact rather than discard a shared ready result.
            RequestSource victim = null;
            for (var entry : sourceModels.entrySet()) {
                if (!active.containsKey(entry.getValue())) { victim = entry.getKey(); break; }
            }
            if (victim == null) { return; }
            removeSource(victim);
        }
        if (!demands.containsKey(modelId) && demands.size() >= maximumDemands) {
            String victim = null;
            for (String candidate : demands.keySet()) {
                if (!active.containsKey(candidate)) { victim = candidate; break; }
            }
            if (victim == null) { return; }
            removeDemand(victim);
        }
        demands.computeIfAbsent(modelId, ignored -> new Demand()).sources.put(source, now);
        sourceModels.put(source, modelId);
    }

    void removeSource(RequestSource source) {
        String modelId = sourceModels.remove(source);
        Demand demand = modelId == null ? null : demands.get(modelId);
        if (demand != null) {
            demand.sources.remove(source);
            if (demand.sources.isEmpty()) { removeDemand(modelId); }
        }
    }

    private void removeDemand(String modelId) {
        Demand demand = demands.remove(modelId);
        if (demand != null) {
            demand.sources.keySet().forEach(source -> sourceModels.remove(source, modelId));
        }
        Slot slot = active.get(modelId);
        if (slot != null) { cancel(slot); }
    }

    RequestSource source(Request request) {
        Demand demand = isCurrent(request) ? demands.get(request.modelId()) : null;
        return demand == null || demand.sources.isEmpty()
                ? null : demand.sources.keySet().iterator().next();
    }

    /** Reserves both capacity limits BEFORE scheduling disk lookup or network work. */
    Request nextRequest(Ticket ticket, long now) {
        if (!isCurrent(ticket) || leases.size() >= maximumRequests
                || reservedBytes > maximumBytes - bytesPerRequest) { return null; }
        for (var entry : demands.entrySet()) {
            Demand demand = entry.getValue();
            if (active.containsKey(entry.getKey())
                    || demand.retrying && now - demand.retrySince < retryAfter) { continue; }
            Request request = new Request(ticket, entry.getKey(), new Object());
            Slot slot = new Slot(request, now);
            active.put(entry.getKey(), slot);
            leases.put(request, slot);
            reservedBytes += bytesPerRequest;
            return request;
        }
        return null;
    }

    Request awaitingResponse(String modelId) {
        Slot slot = active.get(modelId);
        return slot != null && isCurrent(slot.request)
                && (slot.phase == Phase.WAITING || slot.phase == Phase.RECEIVING)
                ? slot.request : null;
    }

    /** Retry silently declined requests without releasing capacity or extending the idle deadline. */
    List<Request> retryWaiting(long now) {
        List<Request> due = new ArrayList<>();
        for (Slot slot : active.values()) {
            if (isCurrent(slot.request) && slot.phase == Phase.WAITING
                    && now - slot.lastSentAt >= retryAfter) {
                slot.lastSentAt = now;
                due.add(slot.request);
            }
        }
        return due;
    }

    Cached cached(Request request) {
        Slot slot = isCurrent(request) ? leases.get(request) : null;
        return slot == null ? null : slot.cached;
    }

    /** A reservation accepts only one DATA transfer, including duplicate/retried responses. */
    boolean receive(Request request, UUID transferId, long now) {
        Slot slot = isCurrent(request) ? leases.get(request) : null;
        if (slot == null || (slot.phase != Phase.WAITING && slot.phase != Phase.RECEIVING)
                || slot.transferId != null && !slot.transferId.equals(transferId)) { return false; }
        slot.phase = Phase.RECEIVING;
        slot.transferId = transferId;
        slot.cached = null; // DATA replaces the disk candidate before retaining more bytes.
        slot.touchedAt = now;
        return true;
    }

    boolean beginDecode(Request request, long now) {
        Slot slot = isCurrent(request) ? leases.get(request) : null;
        if (slot == null || slot.worker
                || (slot.phase != Phase.WAITING && slot.phase != Phase.RECEIVING)) { return false; }
        slot.phase = Phase.DECODING;
        slot.worker = true;
        slot.touchedAt = now;
        return true;
    }

    boolean retryFull(Request request, long now) {
        Slot slot = isCurrent(request) ? leases.get(request) : null;
        if (slot == null || slot.worker || slot.phase != Phase.WAITING
                || slot.fullRequested) { return false; }
        slot.cached = null;
        slot.fullRequested = true;
        slot.touchedAt = now;
        slot.lastSentAt = now;
        return true;
    }

    void completeLookup(Request request, Cached found, long now, Consumer<byte[]> send) {
        finishWorker(request, slot -> {
            if (found != null && !request.ticket().serverIdentity().equals(found.serverIdentity())) {
                failed(request, now);
                return;
            }
            slot.cached = found;
            slot.fullRequested = found == null;
            slot.phase = Phase.WAITING;
            slot.touchedAt = now;
            slot.lastSentAt = now;
            send.accept(found == null ? new byte[0] : found.entry().payloadDigest());
        }, Phase.LOOKUP);
    }

    void failWorker(Request request, long now, Runnable rejected) {
        finishWorker(request, slot -> {
            failed(request, now);
            rejected.run();
        }, Phase.LOOKUP, Phase.DECODING);
    }

    /** A corrupt conditional cache retries unconditionally without releasing its reservation. */
    void retryWithoutCache(Request request, long now, Runnable retry) {
        finishWorker(request, slot -> {
            slot.cached = null;
            slot.phase = Phase.WAITING;
            slot.fullRequested = false;
            slot.transferId = null;
            slot.touchedAt = now;
            retry.run();
        }, Phase.DECODING);
    }

    void completeModel(Request request, ModelBundle model, long now, Runnable arrived) {
        finishWorker(request, slot -> {
            if (model == null || !request.modelId().equals(model.modelId())) {
                failed(request, now);
                return;
            }
            slot.cached = null;
            slot.ready = model;
            slot.phase = Phase.READY;
            slot.touchedAt = now;
            arrived.run();
        }, Phase.DECODING);
    }

    /** The consumer owns the model, but its lease survives until client-side conversion completion. */
    Handoff takeReady(String modelId) {
        Slot slot = active.get(modelId);
        if (slot == null || slot.phase != Phase.READY || !isCurrent(slot.request)) { return null; }
        ModelBundle model = slot.ready;
        slot.ready = null;
        slot.phase = Phase.CONVERTING;
        slot.worker = true;
        return new Handoff(slot.request, model);
    }

    void completeConversion(Request request) {
        finishWorker(request, slot -> removeDemand(request.modelId()), Phase.CONVERTING);
    }

    Cached failed(Request request, long now) {
        Slot slot = isCurrent(request) ? leases.get(request) : null;
        if (slot == null) { return null; }
        Cached cached = slot.cached;
        Demand demand = demands.get(request.modelId());
        if (demand != null) {
            demand.retrySince = now;
            demand.retrying = true;
        }
        cancel(slot);
        return cached;
    }

    void maintain(long now) {
        for (var entry : new ArrayList<>(sourceModels.entrySet())) {
            Demand demand = demands.get(entry.getValue());
            Long seen = demand == null ? null : demand.sources.get(entry.getKey());
            if (seen == null || now - seen >= timeout) { removeSource(entry.getKey()); }
        }
        for (Slot slot : new ArrayList<>(active.values())) {
            if ((slot.phase == Phase.WAITING || slot.phase == Phase.RECEIVING)
                    && now - slot.touchedAt >= timeout) {
                failed(slot.request, now);
            }
        }
    }

    int reservedCount() { return leases.size(); }
    long reservedBytes() { return reservedBytes; }
    int demandCount() { return demands.size(); }
    int sourceCount() { return sourceModels.size(); }

    private void finishWorker(Request request, Consumer<Slot> commit, Phase... expected) {
        clientExecutor.execute(() -> {
            Slot slot = leases.get(request);
            if (slot == null || !slot.worker) { return; }
            for (Phase phase : expected) {
                if (slot.phase != phase) { continue; }
                slot.worker = false;
                if (isCurrent(request)) { commit.accept(slot); }
                else { release(slot); }
                return;
            }
        });
    }

    private void cancel(Slot slot) {
        active.remove(slot.request.modelId(), slot);
        slot.cached = null;
        slot.ready = null;
        if (!slot.worker) { release(slot); }
    }

    private void release(Slot slot) {
        if (leases.remove(slot.request, slot)) { reservedBytes -= bytesPerRequest; }
    }
}
