package net.okitsu.ysmepicfightcompat.mesh;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Bounded, nonblocking decode admission and single-render-consumer upload queue.
 * Claims remain exclusive through upload, including while a decoded image waits for a frame.
 */
final class FrameUploadQueue<K, T> {
    private enum Stage { PREPARING, WAITING, DECODING, READY, UPLOADING, FINISHED }

    static final class Claim<K, T> {
        private final K key;
        private Stage stage = Stage.PREPARING;
        private long bytes;
        private Runnable decode;
        private T value;

        private Claim(K key) {
            this.key = key;
        }
    }

    private final int maxClaims;
    private final long memoryTarget;
    private final Consumer<T> dispose;
    private final Map<K, Claim<K, T>> current = new HashMap<>();
    private final Queue<Claim<K, T>> waiting = new ArrayDeque<>();
    private final Queue<Claim<K, T>> ready = new ArrayDeque<>();
    private int outstanding;
    private long reservedBytes;

    FrameUploadQueue(int maxClaims, long memoryTarget, Consumer<T> dispose) {
        if (maxClaims <= 0 || memoryTarget <= 0) {
            throw new IllegalArgumentException("Upload queue limits must be positive");
        }
        this.maxClaims = maxClaims;
        this.memoryTarget = memoryTarget;
        this.dispose = dispose;
    }

    synchronized Claim<K, T> claim(K key) {
        if (current.containsKey(key) || outstanding >= maxClaims) {
            return null;
        }
        Claim<K, T> claim = new Claim<>(key);
        current.put(key, claim);
        outstanding++;
        return claim;
    }

    synchronized boolean isCurrent(Claim<K, T> claim) {
        return current.get(claim.key) == claim;
    }

    /** Worker metadata completion: stores no decoded pixels and never waits for capacity. */
    synchronized void plan(Claim<K, T> claim, long bytes, Runnable decode) {
        if (claim.stage != Stage.PREPARING) {
            throw new IllegalStateException("Upload has already been planned");
        }
        if (!isCurrent(claim)) {
            finish(claim);
            return;
        }
        if (bytes <= 0) {
            throw new IllegalArgumentException("Decoded byte count must be positive");
        }
        claim.bytes = bytes;
        claim.decode = decode;
        claim.stage = Stage.WAITING;
        waiting.add(claim);
    }

    /** Called once per render frame; admission never blocks a conversion worker. */
    void startAvailable(Consumer<Runnable> executor) {
        while (true) {
            Claim<K, T> claim;
            Runnable decode;
            synchronized (this) {
                claim = waiting.peek();
                if (claim == null || (reservedBytes != 0
                        && claim.bytes > memoryTarget - reservedBytes)) {
                    return;
                }
                // A texture group larger than the soft target runs alone, preserving support
                // for existing large models without accumulating multiple oversized images.
                waiting.remove();
                reservedBytes += claim.bytes;
                claim.stage = Stage.DECODING;
                decode = claim.decode;
                claim.decode = null;
            }
            try {
                executor.accept(decode);
            } catch (RuntimeException | Error failure) {
                fail(claim);
                throw failure;
            }
        }
    }

    /** Takes ownership of a decoded result even when the claim was invalidated meanwhile. */
    synchronized void complete(Claim<K, T> claim, T value) {
        if (claim.stage != Stage.DECODING || !isCurrent(claim)) {
            try {
                dispose.accept(value);
            } finally {
                finish(claim);
            }
            return;
        }
        claim.value = value;
        claim.stage = Stage.READY;
        ready.add(claim);
    }

    synchronized void fail(Claim<K, T> claim) {
        finish(claim);
    }

    /** The consumer takes ownership of each value, including cleanup when upload fails. */
    void drainFrame(long budgetNanos, LongSupplier clock, Consumer<T> upload) {
        long started = clock.getAsLong();
        do {
            Claim<K, T> claim;
            T value;
            synchronized (this) {
                claim = ready.poll();
                if (claim == null) {
                    return;
                }
                value = claim.value;
                claim.value = null;
                claim.stage = Stage.UPLOADING;
            }
            try {
                upload.accept(value);
            } finally {
                synchronized (this) {
                    finish(claim);
                }
            }
        } while (clock.getAsLong() - started < budgetNanos);
    }

    synchronized void cancel(K key) {
        Claim<K, T> claim = current.remove(key);
        if (claim == null) {
            return;
        }
        if (claim.stage == Stage.WAITING) {
            finish(claim);
        } else if (claim.stage == Stage.READY) {
            T value = claim.value;
            claim.value = null;
            try {
                dispose.accept(value);
            } finally {
                finish(claim);
            }
        }
        // Preparing/decoding workers still own their allocation/claim until completion.
        // Invalidating them must not release a reservation that is still in use.
    }

    synchronized void clear() {
        for (K key : java.util.List.copyOf(current.keySet())) {
            cancel(key);
        }
    }

    synchronized int outstanding() {
        return outstanding;
    }

    synchronized long reservedBytes() {
        return reservedBytes;
    }

    private void finish(Claim<K, T> claim) {
        if (claim.stage == Stage.FINISHED) {
            return;
        }
        if (claim.stage == Stage.DECODING || claim.stage == Stage.READY
                || claim.stage == Stage.UPLOADING) {
            reservedBytes -= claim.bytes;
        }
        current.remove(claim.key, claim);
        waiting.remove(claim);
        ready.remove(claim);
        claim.decode = null;
        claim.value = null;
        claim.stage = Stage.FINISHED;
        outstanding--;
    }
}
