package net.okitsu.ysmepicfightcompat.network;

import java.util.ArrayDeque;

/** At most eight events in any rolling twenty-tick window, including boundary bursts. */
public final class ScriptSyncRateLimiter {
    private static final int LIMIT = 8;
    private static final long WINDOW_TICKS = 20L;
    private final ArrayDeque<Long> times = new ArrayDeque<>(LIMIT);
    private long lastTick = Long.MIN_VALUE;

    public boolean allow(long now) {
        if (now < lastTick) {
            times.clear();
        }
        lastTick = now;
        while (!times.isEmpty() && now - times.peekFirst() >= WINDOW_TICKS) {
            times.removeFirst();
        }
        if (times.size() >= LIMIT) {
            return false;
        }
        times.addLast(now);
        return true;
    }
}
