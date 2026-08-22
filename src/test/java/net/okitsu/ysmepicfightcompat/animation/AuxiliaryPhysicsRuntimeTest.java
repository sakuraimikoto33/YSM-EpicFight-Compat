package net.okitsu.ysmepicfightcompat.animation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuxiliaryPhysicsRuntimeTest {
    @Test
    void firstOrderUsesThePreviousFrameInputLikeOfficialYsm() {
        AuxiliaryPhysicsRuntime runtime = new AuxiliaryPhysicsRuntime();

        assertEquals(10.0D, runtime.firstOrder("hair", 10.0D, 1.0D), 1.0E-12D);
        runtime.update(0.05D);
        assertEquals(0.5D, runtime.firstOrder("hair", 10.0D, 1.0D), 1.0E-12D);
        runtime.update(0.05D);
        assertEquals(0.975D, runtime.firstOrder("hair", 10.0D, 1.0D), 1.0E-12D);
    }

    @Test
    void secondOrderRemainsFiniteAndAdvancesItsStoredValue() {
        AuxiliaryPhysicsRuntime runtime = new AuxiliaryPhysicsRuntime();

        assertEquals(10.0D,
                runtime.secondOrder("tail", 10.0D, 1.0D, 0.5D, 1.0D),
                1.0E-12D);
        runtime.update(0.05D);
        double firstStep = runtime.secondOrder(
                "tail", 10.0D, 1.0D, 0.5D, 1.0D);
        runtime.update(0.05D);
        double secondStep = runtime.secondOrder(
                "tail", 10.0D, 1.0D, 0.5D, 1.0D);

        assertTrue(Double.isFinite(firstStep));
        assertTrue(Double.isFinite(secondStep));
        assertTrue(secondStep > firstStep);
    }

    @Test
    void zeroFrequencySecondOrderFailsClosedToTheCurrentInput() {
        AuxiliaryPhysicsRuntime runtime = new AuxiliaryPhysicsRuntime();

        assertEquals(4.0D,
                runtime.secondOrder("skirt", 4.0D, 0.0D, 1.0D, 1.0D),
                1.0E-12D);
        runtime.update(0.05D);
        assertEquals(4.0D,
                runtime.secondOrder("skirt", 4.0D, 0.0D, 1.0D, 1.0D),
                1.0E-12D);
    }

    @Test
    void resetDropsEntityScopedFilterHistory() {
        AuxiliaryPhysicsRuntime runtime = new AuxiliaryPhysicsRuntime();
        runtime.firstOrder("ear", 8.0D, 1.0D);
        runtime.update(0.05D);
        assertEquals(0.4D, runtime.firstOrder("ear", 8.0D, 1.0D), 1.0E-12D);

        runtime.reset();

        assertEquals(3.0D, runtime.firstOrder("ear", 3.0D, 1.0D), 1.0E-12D);
    }

    @Test
    void emptyPhysicsKeysFailClosed() {
        AuxiliaryPhysicsRuntime runtime = new AuxiliaryPhysicsRuntime();

        assertEquals(0.0D, runtime.firstOrder("", 3.0D, 1.0D), 0.0D);
        assertEquals(0.0D,
                runtime.secondOrder("", 3.0D, 1.0D, 1.0D, 1.0D), 0.0D);
    }

    @Test
    void perlinNoiseUsesYsmDefaultsAndIsDeterministicForASeed() {
        double compact = AuxiliaryPhysicsRuntime.perlinNoise(
                new double[]{17.0D, 0.375D});
        double explicit = AuxiliaryPhysicsRuntime.perlinNoise(
                new double[]{17.0D, 0.375D, 0.0D, 0.0D});

        assertEquals(compact, explicit, 0.0D);
        assertTrue(Double.isFinite(compact));
        assertTrue(Math.abs(compact) <= 1.0D);
    }
}
