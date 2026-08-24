package net.okitsu.ysmepicfightcompat.animation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttackSoundOwnershipTest {
    @Test
    void consumesOnlyOneClaimPerContinuousAttackPlayback() {
        AttackSoundOwnership.Observation observation =
                new AttackSoundOwnership.Observation();
        observation.observe("epicfight:sword_auto1", 0.02F);
        observation.claim("21_saint", 20);

        observation.observe("epicfight:sword_auto1", 0.05F);
        assertTrue(observation.consume("21_saint", 21));

        observation.observe("epicfight:sword_auto1", 0.18F);
        observation.claim("21_saint", 23);
        assertFalse(observation.consume("21_saint", 23));
    }

    @Test
    void elapsedRewindStartsANewClaimGeneration() {
        AttackSoundOwnership.Observation observation =
                new AttackSoundOwnership.Observation();
        observation.observe("epicfight:sword_auto1", 0.18F);
        observation.claim("21_saint", 10);
        assertTrue(observation.consume("21_saint", 10));

        observation.observe("epicfight:sword_auto1", 0.01F);
        observation.claim("21_saint", 20);
        assertTrue(observation.consume("21_saint", 20));
    }

    @Test
    void rejectsWrongModelsAndExpiredClaims() {
        AttackSoundOwnership.Observation observation =
                new AttackSoundOwnership.Observation();
        observation.observe("epicfight:sword_auto1", 0.02F);
        observation.claim("21_saint", 10);

        assertFalse(observation.consume("other", 11));
        assertFalse(observation.consume("21_saint", 17));
    }
}
