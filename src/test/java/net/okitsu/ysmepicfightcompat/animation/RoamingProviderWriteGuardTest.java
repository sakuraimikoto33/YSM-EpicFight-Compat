package net.okitsu.ysmepicfightcompat.animation;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoamingProviderWriteGuardTest {
    private static final int VISIBLE = ExpressionEngine.slot("v.roaming.visible");
    private static final int PREVIOUS = ExpressionEngine.slot("v.roaming.previous");

    /** The official provider contract has these three method shapes. */
    public interface Provider {
        Provider copy();
        Object get(int slot);
        void set(int slot, Object value);
    }

    @Test
    void ownerUpdatesAndNetworkSnapshotsSurviveDelayedRemoteTimelineReplays() throws Exception {
        for (boolean separateFrames : List.of(false, true)) {
            Values ownerValues = new Values();
            Values remoteValues = new Values();
            Environment owner = new Environment(wrap(ownerValues, new AtomicBoolean(false)));
            Environment remote = new Environment(wrap(remoteValues, new AtomicBoolean(true)));
            AnimationClip clip = toggle();
            for (int activation = 1; activation <= 3; activation++) {
                double expected = activation % 2;
                ParallelAnimationProgram.fireTimeline(clip, 0.25F, owner, new HashMap<>());
                assertState(owner, expected);

                // Official packet application changes the original backing map directly.
                remoteValues.backing.clear();
                remoteValues.backing.putAll(ownerValues.backing);
                assertState(remote, expected);
                Map<String, Float> time = new HashMap<>();
                if (separateFrames) {
                    ParallelAnimationProgram.fireTimeline(clip, 0.21F, remote, time);
                    assertState(remote, expected);
                }
                ParallelAnimationProgram.fireTimeline(clip, 0.25F, remote, time);
                assertState(remote, expected);
            }
            assertEquals(6, ownerValues.writes);
            assertEquals(0, remoteValues.writes);
        }
    }

    @Test
    void existingProxyResumesWritesWhenOwnershipEndsAndCopiesRemainDetached() throws Exception {
        Values values = new Values();
        AtomicBoolean blocked = new AtomicBoolean(true);
        Provider proxy = wrap(values, blocked);
        proxy.set(VISIBLE, 1.0D);
        assertEquals(0.0D, values.get(VISIBLE));
        blocked.set(false);
        proxy.set(VISIBLE, 7.0D);
        assertEquals(7.0D, values.get(VISIBLE));

        Provider copy = proxy.copy();
        assertNotSame(proxy, copy);
        assertNotSame(values, copy);
        blocked.set(true);
        copy.set(VISIBLE, 9.0D);
        assertEquals(9.0D, copy.get(VISIBLE));
        assertEquals(7.0D, proxy.get(VISIBLE));
        assertTrue(proxy.equals(proxy));
        assertFalse(proxy.equals(values));
        assertEquals(System.identityHashCode(proxy), proxy.hashCode());
        assertEquals("RoamingProviderWriteGuard", proxy.toString());
    }

    @Test
    void delegateFailuresAreUnwrapped() throws Exception {
        IllegalStateException failure = new IllegalStateException("synthetic delegate failure");
        Values failing = new Values() {
            @Override public Object get(int slot) { throw failure; }
            @Override public void set(int slot, Object value) { throw failure; }
        };
        Provider proxy = (Provider) RoamingProviderWriteGuard.wrap(Provider.class, failing, setter(),
                () -> false);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> proxy.get(VISIBLE)));
        assertSame(failure, assertThrows(IllegalStateException.class, () -> proxy.set(VISIBLE, 1.0D)));
    }

    @Test
    void rejectsNonInterfaceDelegatesAndMethodsOutsideTheMappedSetterContract() throws Exception {
        Values values = new Values();
        assertThrows(IllegalArgumentException.class, () -> RoamingProviderWriteGuard.wrap(
                Values.class, values, setter(), () -> true));
        assertThrows(IllegalArgumentException.class, () -> RoamingProviderWriteGuard.wrap(
                Provider.class, new Object(), setter(), () -> true));
        assertThrows(IllegalArgumentException.class, () -> RoamingProviderWriteGuard.wrap(
                Provider.class, values, Provider.class.getMethod("get", int.class),
                () -> true));
        assertThrows(IllegalArgumentException.class, () -> RoamingProviderWriteGuard.wrap(
                Provider.class, values, Values.class.getMethod("set", int.class, Object.class),
                () -> true));
    }

    private static Provider wrap(Values values, AtomicBoolean blocked) throws Exception {
        return (Provider) RoamingProviderWriteGuard.wrap(Provider.class, values, setter(),
                blocked::get);
    }

    private static Method setter() throws NoSuchMethodException {
        return Provider.class.getMethod("set", int.class, Object.class);
    }

    private static AnimationClip toggle() {
        AnimationClip clip = new AnimationClip("synthetic_toggle");
        clip.timeline().add(new AnimationClip.TimelineEvent(0.2F,
                List.of("v.roaming.visible=1-v.roaming.previous;")));
        clip.timeline().add(new AnimationClip.TimelineEvent(0.24F,
                List.of("v.roaming.previous=v.roaming.visible;")));
        return clip;
    }

    private static void assertState(Environment environment, double value) {
        assertEquals(value, environment.readVariable(VISIBLE));
        assertEquals(value, environment.readVariable(PREVIOUS));
    }

    public static class Values implements Provider {
        final Map<Integer, Object> backing = new HashMap<>();
        int writes;
        @Override public Provider copy() {
            Values copy = new Values();
            copy.backing.putAll(backing);
            return copy;
        }
        @Override public Object get(int slot) { return backing.getOrDefault(slot, 0.0D); }
        @Override public void set(int slot, Object value) { backing.put(slot, value); writes++; }
    }

    private record Environment(Provider provider) implements ExpressionEngine.Environment {
        @Override public double readVariable(int slot) {
            return ((Number) provider.get(slot)).doubleValue();
        }
        @Override public Object readVariableValue(int slot) { return provider.get(slot); }
        @Override public boolean hasVariable(int slot) { return true; }
        @Override public void writeVariable(int slot, double value) { provider.set(slot, value); }
        @Override public void writeVariableValue(int slot, Object value) { provider.set(slot, value); }
        @Override public double readQuery(int slot) { return 0.0D; }
        @Override public double invoke(String name, double[] arguments) { return 0.0D; }
        @Override public double invokeWithText(String name, String[] arguments) { return 0.0D; }
    }
}
