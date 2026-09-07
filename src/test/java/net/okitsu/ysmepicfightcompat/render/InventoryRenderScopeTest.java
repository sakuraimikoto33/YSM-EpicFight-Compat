package net.okitsu.ysmepicfightcompat.render;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryRenderScopeTest {
    @Test
    void worldRenderingHasNoInventoryContext() {
        assertFalse(InventoryRenderScope.claim(new Object(), false));
        assertFalse(InventoryRenderScope.claim(null, false));
        assertFalse(InventoryRenderScope.claim(new Object(), true));
    }

    @Test
    void matchingThirdPersonDrawClaimsOnlyOnce() {
        Object entity = new Object();
        try (var scope = InventoryRenderScope.open(entity)) {
            assertTrue(InventoryRenderScope.claim(entity, false));
            assertFalse(InventoryRenderScope.claim(entity, false));
            assertFalse(InventoryRenderScope.claim(entity, true));
        }
        assertFalse(InventoryRenderScope.claim(entity, false));
    }

    @Test
    void pendingDoesNotConsumeAndObservesOnlyTheTopUnclaimedScope() {
        Object entity = new Object();
        Object other = new Object();
        assertFalse(InventoryRenderScope.pending(entity));
        try (var outer = InventoryRenderScope.open(entity)) {
            assertTrue(InventoryRenderScope.pending(entity));
            assertTrue(InventoryRenderScope.pending(entity));
            assertFalse(InventoryRenderScope.pending(other));
            assertFalse(InventoryRenderScope.pending(null));
            try (var inner = InventoryRenderScope.open(other)) {
                assertFalse(InventoryRenderScope.pending(entity));
                assertTrue(InventoryRenderScope.pending(other));
                assertTrue(InventoryRenderScope.claim(other, false));
                assertFalse(InventoryRenderScope.pending(other));
                assertFalse(InventoryRenderScope.pending(entity));
            }
            assertTrue(InventoryRenderScope.pending(entity));
            assertTrue(InventoryRenderScope.claim(entity, false));
            assertFalse(InventoryRenderScope.pending(entity));
        }
        assertFalse(InventoryRenderScope.pending(entity));
    }

    @Test
    void firstPersonAndOtherEntitiesDoNotConsumeTheClaim() {
        Object entity = new Object();
        try (var scope = InventoryRenderScope.open(entity)) {
            assertFalse(InventoryRenderScope.claim(entity, true));
            assertFalse(InventoryRenderScope.claim(new Object(), false));
            assertFalse(InventoryRenderScope.claim(null, false));
            assertTrue(InventoryRenderScope.claim(entity, false));
        }
    }

    @Test
    void matchingUsesIdentityWithoutCallingEquality() {
        Object entity = new Object() {
            @Override
            public boolean equals(Object other) {
                throw new AssertionError("Entity equality must not be consulted");
            }

            @Override
            public int hashCode() {
                throw new AssertionError("Entity hashing must not be consulted");
            }
        };
        try (var scope = InventoryRenderScope.open(entity)) {
            assertFalse(InventoryRenderScope.claim(new Object(), false));
            assertTrue(InventoryRenderScope.claim(entity, false));
        }
    }

    @Test
    void nestedDifferentEntityDoesNotSearchTheOuterScope() {
        Object outerEntity = new Object();
        Object innerEntity = new Object();
        try (var outer = InventoryRenderScope.open(outerEntity)) {
            try (var inner = InventoryRenderScope.open(innerEntity)) {
                assertFalse(InventoryRenderScope.claim(outerEntity, false));
                assertTrue(InventoryRenderScope.claim(innerEntity, false));
                assertFalse(InventoryRenderScope.claim(outerEntity, false));
            }
            assertTrue(InventoryRenderScope.claim(outerEntity, false));
        }
    }

    @Test
    void nestedSameEntityHasAnIndependentClaimAndRestoresConsumedOuterScope() {
        Object entity = new Object();
        try (var outer = InventoryRenderScope.open(entity)) {
            assertTrue(InventoryRenderScope.claim(entity, false));
            try (var inner = InventoryRenderScope.open(entity)) {
                assertTrue(InventoryRenderScope.claim(entity, false));
                assertFalse(InventoryRenderScope.claim(entity, false));
            }
            assertFalse(InventoryRenderScope.claim(entity, false));
        }
    }

    @Test
    void nestedSameEntityDoesNotConsumeUnusedOuterScope() {
        Object entity = new Object();
        try (var outer = InventoryRenderScope.open(entity)) {
            try (var inner = InventoryRenderScope.open(entity)) {
                assertTrue(InventoryRenderScope.claim(entity, false));
            }
            assertTrue(InventoryRenderScope.claim(entity, false));
        }
    }

    @Test
    void closingAnUnusedInnerScopeRestoresTheOuterOne() {
        Object entity = new Object();
        try (var outer = InventoryRenderScope.open(entity)) {
            try (var inner = InventoryRenderScope.open(new Object())) {
                assertFalse(InventoryRenderScope.claim(entity, false));
            }
            assertTrue(InventoryRenderScope.claim(entity, false));
        }
    }

    @Test
    void finallyCleanupRemovesScopeAfterRenderFailure() {
        Object entity = new Object();
        RuntimeException failure = new RuntimeException("render failed");
        assertSame(failure, assertThrows(RuntimeException.class, () -> {
            InventoryRenderScope.Token scope = InventoryRenderScope.open(entity);
            try {
                assertTrue(InventoryRenderScope.claim(entity, false));
                throw failure;
            } finally {
                scope.close();
            }
        }));
        assertFalse(InventoryRenderScope.claim(entity, false));
        try (var scope = InventoryRenderScope.open(entity)) {
            assertTrue(InventoryRenderScope.claim(entity, false));
        }
    }

    @Test
    void failureInNestedPreviewRestoresAnUnusedOuterScope() {
        Object entity = new Object();
        try (var outer = InventoryRenderScope.open(entity)) {
            assertThrows(IllegalArgumentException.class, () -> {
                try (var inner = InventoryRenderScope.open(entity)) {
                    assertTrue(InventoryRenderScope.claim(entity, false));
                    throw new IllegalArgumentException("nested render failed");
                }
            });
            assertTrue(InventoryRenderScope.claim(entity, false));
        }
    }

    @Test
    void closingATokenTwiceDoesNotRemoveAnotherScope() {
        Object entity = new Object();
        InventoryRenderScope.Token closed = InventoryRenderScope.open(entity);
        closed.close();
        try (var active = InventoryRenderScope.open(entity)) {
            closed.close();
            assertTrue(InventoryRenderScope.claim(entity, false));
        }
    }

    @Test
    void outOfOrderCloseCannotClobberTheActiveScope() {
        Object outerEntity = new Object();
        Object innerEntity = new Object();
        try (var outer = InventoryRenderScope.open(outerEntity)) {
            try (var inner = InventoryRenderScope.open(innerEntity)) {
                assertThrows(IllegalStateException.class, outer::close);
                assertTrue(InventoryRenderScope.claim(innerEntity, false));
            }
            assertTrue(InventoryRenderScope.claim(outerEntity, false));
        }
    }

    @Test
    void scopeIsThreadLocalAndCannotBeClosedByAnotherThread() throws InterruptedException {
        Object entity = new Object();
        AtomicReference<Throwable> threadFailure = new AtomicReference<>();
        try (var outer = InventoryRenderScope.open(entity)) {
            Thread worker = new Thread(() -> {
                try {
                    assertFalse(InventoryRenderScope.claim(entity, false));
                    assertThrows(IllegalStateException.class, outer::close);
                    try (var inner = InventoryRenderScope.open(entity)) {
                        assertTrue(InventoryRenderScope.claim(entity, false));
                    }
                    assertFalse(InventoryRenderScope.claim(entity, false));
                } catch (Throwable failure) {
                    threadFailure.set(failure);
                }
            });
            worker.start();
            worker.join(5_000L);
            assertFalse(worker.isAlive());
            assertNull(threadFailure.get());
            assertTrue(InventoryRenderScope.claim(entity, false));
        }
    }

    @Test
    void nullEntityIsRejectedWithoutReplacingTheCurrentScope() {
        Object entity = new Object();
        try (var scope = InventoryRenderScope.open(entity)) {
            assertThrows(NullPointerException.class, () -> InventoryRenderScope.open(null));
            assertTrue(InventoryRenderScope.claim(entity, false));
        }
    }
}
