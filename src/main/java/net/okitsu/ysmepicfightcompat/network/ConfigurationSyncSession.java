package net.okitsu.ysmepicfightcompat.network;

import java.util.Objects;
import java.util.UUID;

/** Server-thread acknowledgement metadata; ordinary variable values live separately. */
final class ConfigurationSyncSession {
    static final UUID NO_CONTEXT = new UUID(0L, 0L);

    private final Object owner;
    private String modelId;
    private UUID serverScope;
    private UUID clientContext = NO_CONTEXT;
    private long lastRequestOrder;
    private long processedSequence;
    private long revision;

    ConfigurationSyncSession(Object owner, String modelId) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.modelId = normalizeModelId(modelId);
        invalidate();
    }

    boolean isOwner(Object candidate) {
        return owner == candidate;
    }

    boolean matches(Object candidate, String selectedModelId) {
        return isOwner(candidate) && modelId.equals(normalizeModelId(selectedModelId));
    }

    void changeModel(String selectedModelId) {
        String next = normalizeModelId(selectedModelId);
        if (!modelId.equals(next)) {
            modelId = next;
            invalidate();
        }
    }

    /** Invalidates queued edits without changing the model, request order, or any values. */
    void invalidate() {
        serverScope = UUID.randomUUID();
        clientContext = NO_CONTEXT;
        processedSequence = 0L;
        revision = 0L;
    }

    /** Only the latest client request may establish or replay its acknowledgement scope. */
    boolean grant(UUID context, long requestOrder) {
        if (context == null || NO_CONTEXT.equals(context)
                || requestOrder <= 0L || requestOrder < lastRequestOrder) {
            return false;
        }
        if (requestOrder == lastRequestOrder) {
            return clientContext.equals(context);
        }
        invalidate();
        clientContext = context;
        lastRequestOrder = requestOrder;
        return true;
    }

    boolean accepts(UUID scope, UUID context, long sequence) {
        return sequence > 0L && !NO_CONTEXT.equals(clientContext)
                && serverScope.equals(scope) && clientContext.equals(context);
    }

    boolean duplicate(long sequence) {
        return sequence <= processedSequence;
    }

    /** Rejected current-scope edits are processed too, so their local predictions can retire. */
    void processed(long sequence) {
        if (sequence > processedSequence) {
            processedSequence = sequence;
            revision++;
        }
    }

    String modelId() {
        return modelId;
    }

    UUID scope() {
        return serverScope;
    }

    UUID context() {
        return clientContext;
    }

    long processedSequence() {
        return processedSequence;
    }

    long revision() {
        return revision;
    }

    private static String normalizeModelId(String modelId) {
        return modelId == null ? "" : modelId;
    }
}
