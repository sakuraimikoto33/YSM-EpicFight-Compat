package net.okitsu.ysmepicfightcompat.animation;

/** Five entity ticks after a successful block; reads never extend the window. */
final class ShieldBlockCooldown {
    private static final int DURATION_TICKS = 5;

    private int receivedTick;
    private int lastObservedTick;
    private boolean active;

    void refresh(int currentTick) {
        receivedTick = currentTick;
        lastObservedTick = currentTick;
        active = true;
    }

    boolean inCooldown(int currentTick) {
        if (!active) {
            return false;
        }
        long age = (long) currentTick - receivedTick;
        if (currentTick < lastObservedTick || age < 0L || age >= DURATION_TICKS) {
            // An expired or rewound clock cannot resurrect this notification.
            active = false;
        } else {
            lastObservedTick = currentTick;
        }
        return active;
    }
}
