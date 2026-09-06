package net.okitsu.ysmepicfightcompat.animation;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class WeakEntityReferenceTest {
    @Test
    void removedOrReplacedTargetsCannotResolveAndNeverRebindToAnId() {
        Object original = new Object();
        AtomicReference<Object> loaded = new AtomicReference<>(original);
        AtomicInteger samples = new AtomicInteger();
        ExpressionEngine.Environment environment = new EntityReferenceEnvironment(new ProbeEnvironment());
        WeakEntityReference<Object> reference = new WeakEntityReference<>(original,
                candidate -> loaded.get() == candidate,
                candidate -> { samples.incrementAndGet(); return environment; });

        assertTrue(reference.isValid());
        assertSame(environment, reference.resolve());
        loaded.set(null);
        assertFalse(reference.isValid());
        assertNull(reference.resolve());
        assertNull(ExpressionEngine.boundedValue(reference));
        loaded.set(new Object());
        assertFalse(reference.isValid());
        assertNull(reference.resolve());
        assertEquals(1, samples.get());
    }

    @Test
    void handlesUseTargetIdentityAndSampleLiveValuesAtDereference() {
        ProbeEnvironment target = new ProbeEnvironment();
        WeakEntityReference<ProbeEnvironment> first = new WeakEntityReference<>(target,
                candidate -> true, candidate -> candidate);
        WeakEntityReference<ProbeEnvironment> second = new WeakEntityReference<>(target,
                candidate -> true, candidate -> candidate);
        WeakEntityReference<ProbeEnvironment> other = new WeakEntityReference<>(new ProbeEnvironment(),
                candidate -> true, candidate -> candidate);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, other);
        target.health = 20.0D;
        int health = ExpressionEngine.querySlot("q.health");
        assertEquals(20.0D, first.resolve().readQuery(health));
        target.health = 7.0D;
        assertEquals(7.0D, first.resolve().readQuery(health));
    }

    private static final class ProbeEnvironment implements ExpressionEngine.Environment {
        private double health;
        @Override public double readVariable(int slot) { return 0; }
        @Override public boolean hasVariable(int slot) { return false; }
        @Override public void writeVariable(int slot, double value) { }
        @Override public double readQuery(int slot) { return health; }
        @Override public double invoke(String name, double[] arguments) { return 0; }
        @Override public double invokeWithText(String name, String[] arguments) { return 0; }
    }
}
