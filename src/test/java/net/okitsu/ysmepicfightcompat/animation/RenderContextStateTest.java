package net.okitsu.ysmepicfightcompat.animation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RenderContextStateTest {
    @Test
    void createsOnlyTheRequestedContextAndRetainsBothAcrossAlternatingDraws() {
        RenderContextState<Object> contexts = new RenderContextState<>();
        AtomicInteger creations = new AtomicInteger();
        assertNull(contexts.get(false));
        assertNull(contexts.get(true));
        Object world = contexts.getOrCreate(false, () -> {
            creations.incrementAndGet();
            return new Object();
        });
        assertNull(contexts.get(true));
        Object inventory = contexts.getOrCreate(true, () -> {
            creations.incrementAndGet();
            return new Object();
        });
        assertNotSame(world, inventory);
        for (int draw = 0; draw < 20; draw++) {
            assertSame(inventory, contexts.getOrCreate(true, () -> fail("recreated GUI")));
            assertSame(world, contexts.getOrCreate(false, () -> fail("recreated world")));
        }
        assertEquals(2, creations.get());
    }

    @Test
    void releasesBothContextsExactlyOnceAndAllowsFreshStateAfterReload() {
        RenderContextState<Object> contexts = new RenderContextState<>();
        Object world = contexts.getOrCreate(false, Object::new);
        Object inventory = contexts.getOrCreate(true, Object::new);
        List<Object> released = new ArrayList<>();
        contexts.release(released::add);
        contexts.release(released::add);
        assertEquals(List.of(world, inventory), released);
        assertNull(contexts.get(false));
        assertNull(contexts.get(true));
        assertNotSame(inventory, contexts.getOrCreate(true, Object::new));
        assertNull(contexts.get(false));
    }

    @Test
    void aFailedPreviewCreationDoesNotReplaceTheWorldOrPoisonRetry() {
        RenderContextState<Object> contexts = new RenderContextState<>();
        Object world = contexts.getOrCreate(false, Object::new);
        assertThrows(IllegalStateException.class,
                () -> contexts.getOrCreate(true, () -> { throw new IllegalStateException(); }));
        assertSame(world, contexts.get(false));
        assertNull(contexts.get(true));
        assertNotSame(world, contexts.getOrCreate(true, Object::new));
    }
}
