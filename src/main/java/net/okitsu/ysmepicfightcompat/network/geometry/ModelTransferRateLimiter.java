package net.okitsu.ysmepicfightcompat.network.geometry;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Per-recipient fixed-window bounds for model requests and transferred DATA bytes. */
final class ModelTransferRateLimiter {
    private static final class Window {
        private long startedAt;
        private long used;

        private Window(long startedAt) {
            this.startedAt = startedAt;
        }
    }

    private final long windowNanos;
    private final long requestLimit;
    private final long dataByteLimit;
    private final Map<UUID, Window> requests = new HashMap<>();
    private final Map<UUID, Window> dataBytes = new HashMap<>();

    ModelTransferRateLimiter(long windowNanos, long requestLimit,
                             long dataByteLimit) {
        if (windowNanos <= 0L || requestLimit <= 0L || dataByteLimit <= 0L) {
            throw new IllegalArgumentException("Model transfer limits must be positive");
        }
        this.windowNanos = windowNanos;
        this.requestLimit = requestLimit;
        this.dataByteLimit = dataByteLimit;
    }

    synchronized boolean allowRequest(UUID recipient, long now) {
        return allow(requests, recipient, now, 1L, requestLimit);
    }

    synchronized boolean allowData(UUID recipient, long now, long bytes) {
        return allow(dataBytes, recipient, now, bytes, dataByteLimit);
    }

    synchronized void remove(UUID recipient) {
        requests.remove(recipient);
        dataBytes.remove(recipient);
    }

    synchronized void clear() {
        requests.clear();
        dataBytes.clear();
    }

    private boolean allow(Map<UUID, Window> windows, UUID recipient,
                          long now, long amount, long limit) {
        if (recipient == null || amount < 0L || amount > limit) {
            return false;
        }
        Window window = windows.get(recipient);
        if (window == null || now < window.startedAt
                || now - window.startedAt >= windowNanos) {
            window = new Window(now);
            windows.put(recipient, window);
        }
        if (window.used > limit - amount) {
            return false;
        }
        window.used += amount;
        return true;
    }
}
