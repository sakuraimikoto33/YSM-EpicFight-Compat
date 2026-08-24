package net.okitsu.ysmepicfightcompat.animation;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Tracks one successful YSM sound claim for each continuous Epic Fight attack playback. */
final class AttackSoundOwnership {
    private static final float RESTART_EPSILON = 0.0001F;
    private static final int MAX_CLAIM_AGE_TICKS = 6;

    private record Key(UUID playerId, InteractionHand hand) {
    }

    static final class Observation {
        private String source = "";
        private float elapsed;
        private long generation;
        private long claimedGeneration = -1L;
        private long consumedGeneration = -1L;
        private int claimedAt = Integer.MIN_VALUE;
        private String modelId = "";

        long observe(String nextSource, float nextElapsed) {
            String normalized = nextSource == null ? "" : nextSource;
            float finiteElapsed = Float.isFinite(nextElapsed) ? nextElapsed : 0.0F;
            if (generation == 0L || !normalized.equals(source)
                    || finiteElapsed + RESTART_EPSILON < elapsed) {
                generation++;
                source = normalized;
            }
            elapsed = finiteElapsed;
            return generation;
        }

        void claim(String claimedModelId, int tick) {
            if (consumedGeneration == generation) {
                return;
            }
            claimedGeneration = generation;
            claimedAt = tick;
            modelId = claimedModelId == null ? "" : claimedModelId;
        }

        boolean consume(String expectedModelId, int tick) {
            int age = tick - claimedAt;
            if (claimedGeneration != generation || consumedGeneration == generation
                    || age < 0 || age > MAX_CLAIM_AGE_TICKS
                    || !modelId.equals(expectedModelId == null ? "" : expectedModelId)) {
                return false;
            }
            consumedGeneration = generation;
            return true;
        }
    }

    private static final Map<Key, Observation> OBSERVATIONS = new HashMap<>();

    private AttackSoundOwnership() {
    }

    static synchronized void claim(LivingEntity entity, InteractionHand hand,
                                   String modelId) {
        AnimationConditionMatcher.SwingSignal signal =
                AnimationConditionMatcher.swingSignal(entity, hand);
        if (!signal.active()) {
            return;
        }
        Observation observation = OBSERVATIONS.computeIfAbsent(
                new Key(entity.getUUID(), hand), ignored -> new Observation());
        observation.observe(signal.source(), signal.elapsed());
        observation.claim(modelId, entity.tickCount);
    }

    static synchronized boolean consume(LivingEntity entity, InteractionHand hand,
                                        String modelId) {
        AnimationConditionMatcher.SwingSignal signal =
                AnimationConditionMatcher.swingSignal(entity, hand);
        if (!signal.active()) {
            return false;
        }
        Observation observation = OBSERVATIONS.get(new Key(entity.getUUID(), hand));
        if (observation == null) {
            return false;
        }
        observation.observe(signal.source(), signal.elapsed());
        return observation.consume(modelId, entity.tickCount);
    }

    static synchronized void clear() {
        OBSERVATIONS.clear();
    }
}
