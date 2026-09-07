package net.okitsu.ysmepicfightcompat.animation;

import org.junit.jupiter.api.Test;

import java.lang.ref.ReferenceQueue;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeakIdentityKeyTest {
    @Test
    void equalEntityIdsDoNotShareState() {
        record EntityId(int id) { }
        EntityId first = new EntityId(7);
        EntityId replacement = new EntityId(7);
        assertEquals(first, replacement);
        var keys = new HashMap<WeakIdentityKey<EntityId>, String>();
        keys.put(new WeakIdentityKey<>(first, null), "first-world");
        keys.put(new WeakIdentityKey<>(replacement, null), "replacement-world");
        assertEquals(2, keys.size());
        assertEquals("first-world", keys.get(new WeakIdentityKey<>(first, null)));
        assertEquals("replacement-world", keys.get(new WeakIdentityKey<>(replacement, null)));
    }

    @Test
    void clearedKeysNeverBecomeEqualToAnotherClearedKey() {
        Object target = new Object();
        var first = new WeakIdentityKey<>(target, null);
        var second = new WeakIdentityKey<>(target, null);
        assertEquals(first, second);
        int hash = first.hashCode();
        first.clear();
        second.clear();
        assertNotEquals(first, second);
        assertEquals(first, first);
        assertEquals(hash, first.hashCode());
    }

    @Test
    void queuedKeyRemovesItsOwnEntryAfterCollection() {
        ReferenceQueue<Object> queue = new ReferenceQueue<>();
        Object target = new Object();
        var key = new WeakIdentityKey<>(target, queue);
        var entries = new HashMap<WeakIdentityKey<Object>, String>();
        entries.put(key, "context");
        key.clear();
        assertTrue(key.enqueue());
        var collected = queue.poll();
        assertSame(key, collected);
        entries.remove(collected);
        assertTrue(entries.isEmpty());
    }
}
