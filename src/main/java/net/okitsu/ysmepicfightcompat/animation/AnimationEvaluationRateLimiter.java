package net.okitsu.ysmepicfightcompat.animation;

import java.util.Objects;

/**
 * Entity-context cadence for complete animation evaluations, not animation time.
 * Only a successfully published evaluation advances this state. Contexts must be
 * immutable snapshots; only the most recently evaluated context is reusable.
 */
final class AnimationEvaluationRateLimiter<C> {
    private static final double EPSILON = 1.0E-9D;

    private boolean initialized;
    private double lastEvaluatedAt;
    private double nextDeadline;
    private int lastRateHz;
    private C lastContext;

    boolean shouldEvaluate(double now, int rateHz, C context, boolean force) {
        return rateHz <= 0 || force || !initialized
                || rateHz != lastRateHz || !Double.isFinite(now)
                || now < lastEvaluatedAt || !currentContextMatches(context)
                || !Double.isFinite(nextDeadline)
                || now + EPSILON >= nextDeadline;
    }

    boolean currentContextMatches(C context) {
        return initialized && Objects.equals(lastContext, context);
    }

    void evaluated(double now, int rateHz, C context) {
        if (!Double.isFinite(now)) {
            reset();
            return;
        }
        double interval = rateHz > 0 ? 1.0D / rateHz : 0.0D;
        if (!initialized || rateHz != lastRateHz || now < lastEvaluatedAt
                || !currentContextMatches(context) || !Double.isFinite(nextDeadline)) {
            nextDeadline = now + interval;
        } else if (rateHz > 0 && now + EPSILON >= nextDeadline) {
            // Carry the phase instead of scheduling from this late render frame:
            // resetting to now + interval turns a 60 Hz target at 144 FPS into 48 Hz.
            // Coalesce missed deadlines in one step; never replay catch-up samples.
            double missed = Math.floor((now - nextDeadline + EPSILON) / interval) + 1.0D;
            nextDeadline += missed * interval;
        }
        initialized = true;
        lastEvaluatedAt = now;
        lastRateHz = rateHz;
        lastContext = context;
    }

    void reset() {
        initialized = false;
        lastEvaluatedAt = 0.0D;
        nextDeadline = 0.0D;
        lastRateHz = 0;
        lastContext = null;
    }
}
