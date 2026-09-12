package net.okitsu.ysmepicfightcompat.network.geometry;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ModelDeliveryQueueTest {
    @Test
    void reservationBudgetCoversPreparationAndEveryRecipientUntilTheFinalPacket() {
        var queue = new ModelDeliveryQueue<String, Integer>(2, 10);
        var first = queue.reserve(6);
        var preparing = queue.reserve(4);
        assertNotNull(first);
        assertNotNull(preparing);
        assertNull(queue.reserve(0), "unpublished workers also consume a transfer slot");
        assertEquals(10, queue.reservedBytes());
        assertTrue(queue.publish(first, List.of("a", "b"), List.of(1, 2)));

        assertEquals(3, queue.drain(3, (recipient, packet) -> true));
        assertEquals(10, queue.reservedBytes());
        assertEquals(2, queue.reservationCount());
        assertNull(queue.reserve(1));
        assertEquals(1, queue.drain(3, (recipient, packet) -> true));
        assertFalse(queue.isCurrent(first));
        assertTrue(queue.isCurrent(preparing));
        assertEquals(4, queue.reservedBytes());
        assertEquals(1, queue.reservationCount());
        assertNotNull(queue.reserve(6));
    }

    @Test
    void byteCapacityIsIndependentOfTransferCapacityAndCannotOverflow() {
        var queue = new ModelDeliveryQueue<String, Integer>(3, Long.MAX_VALUE);
        var large = queue.reserve(Long.MAX_VALUE - 1);
        assertNotNull(large);
        assertNull(queue.reserve(2));
        assertNotNull(queue.reserve(1));
        assertEquals(Long.MAX_VALUE, queue.reservedBytes());
        assertNull(queue.reserve(1));
        assertNotNull(queue.reserve(0));
        assertEquals(3, queue.reservationCount());

        queue.cancel(large);
        queue.cancel(large);
        assertEquals(1, queue.reservedBytes());
        assertNotNull(queue.reserve(Long.MAX_VALUE - 1));
        assertEquals(Long.MAX_VALUE, queue.reservedBytes());
    }

    @Test
    void deliveryRotatesTransfersAndRecipientsWithoutReorderingTheirPackets() {
        var queue = new ModelDeliveryQueue<String, Integer>(2, 2);
        var first = queue.reserve(1);
        var second = queue.reserve(1);
        queue.publish(first, List.of("a", "b"), List.of(1, 2, 3));
        queue.publish(second, List.of("c", "d"), List.of(4, 5));
        List<String> sent = new ArrayList<>();

        assertEquals(3, queue.drain(3, (recipient, packet) -> sent.add(recipient + packet)));
        assertEquals(List.of("a1", "c4", "b1"), sent);
        assertEquals(5, queue.drain(5, (recipient, packet) -> sent.add(recipient + packet)));
        assertTrue(queue.isCurrent(first));
        assertFalse(queue.isCurrent(second));
        assertEquals(1, queue.reservedBytes());
        assertEquals(2, queue.drain(20, (recipient, packet) -> sent.add(recipient + packet)));
        assertEquals(List.of("a1", "c4", "b1", "d4", "a2", "c5", "b2", "d5", "a3", "b3"), sent);
        assertEquals(0, queue.reservationCount());
        assertEquals(0, queue.reservedBytes());
    }

    @Test
    void tickStepLimitLeavesBackpressureUntilLaterDrains() {
        var queue = new ModelDeliveryQueue<String, Integer>(1, 10);
        var reservation = queue.reserve(10);
        queue.publish(reservation, List.of("a", "b", "c"), IntStream.range(0, 20).boxed().toList());
        List<Integer> sent = new ArrayList<>();
        assertEquals(0, queue.drain(0, (recipient, packet) -> sent.add(packet)));
        assertTrue(sent.isEmpty());
        assertEquals(16, queue.drain(16, (recipient, packet) -> sent.add(packet)));
        assertEquals(16, sent.size());
        assertEquals(10, queue.reservedBytes());
        assertNull(queue.reserve(0));
        assertEquals(44, queue.drain(100, (recipient, packet) -> sent.add(packet)));
        assertEquals(60, sent.size());
        assertEquals(0, queue.reservedBytes());
        assertEquals(0, queue.drain(16, (recipient, packet) -> fail("already drained")));
    }

    @Test
    void aRejectedRecipientDoesNotReceiveMorePacketsOrCancelOtherRecipients() {
        var queue = new ModelDeliveryQueue<String, Integer>(1, 10);
        var reservation = queue.reserve(10);
        queue.publish(reservation, List.of("disconnected", "connected"), List.of(1, 2, 3));
        List<String> sent = new ArrayList<>();
        assertEquals(1, queue.drain(1, (recipient, packet) -> {
            sent.add(recipient + packet);
            return false;
        }));
        assertEquals(10, queue.reservedBytes());
        assertEquals(3, queue.drain(20, (recipient, packet) -> sent.add(recipient + packet)));
        assertEquals(List.of("disconnected1", "connected1", "connected2", "connected3"), sent);
        assertEquals(0, queue.reservationCount());
        assertEquals(0, queue.reservedBytes());
    }

    @Test
    void emptyPublicationCancelsButDuplicateOrForeignPublicationCannotCancelAnExistingTransfer() {
        var queue = new ModelDeliveryQueue<String, Integer>(3, 30);
        var foreignQueue = new ModelDeliveryQueue<String, Integer>(1, 10);
        var noRecipients = queue.reserve(10);
        var noPackets = queue.reserve(10);
        var published = queue.reserve(10);
        assertFalse(queue.publish(noRecipients, List.of(), List.of(1)));
        assertFalse(queue.publish(noPackets, List.of("a"), List.of()));
        assertFalse(queue.isCurrent(noRecipients));
        assertFalse(queue.isCurrent(noPackets));
        assertTrue(queue.publish(published, List.of("a"), List.of(1, 2)));
        assertFalse(queue.publish(published, List.of("duplicate"), List.of(9)));
        assertFalse(queue.publish(published, List.of(), List.of()));
        assertFalse(foreignQueue.isCurrent(published));
        assertFalse(foreignQueue.publish(published, List.of("foreign"), List.of(9)));
        foreignQueue.cancel(published);
        assertEquals(10, queue.reservedBytes());
        assertEquals(0, foreignQueue.reservedBytes());

        List<Integer> sent = new ArrayList<>();
        assertEquals(2, queue.drain(20, (recipient, packet) -> sent.add(packet)));
        assertEquals(List.of(1, 2), sent);
        assertEquals(0, queue.reservedBytes());
    }

    @Test
    void cancelReleasesPreparationOrQueuedDeliveryExactlyOnce() {
        var queue = new ModelDeliveryQueue<String, Integer>(2, 20);
        var preparing = queue.reserve(10);
        var published = queue.reserve(10);
        queue.publish(published, List.of("a"), List.of(1, 2));
        queue.cancel(preparing);
        queue.cancel(preparing);
        queue.cancel(null);
        assertEquals(10, queue.reservedBytes());
        assertFalse(queue.publish(preparing, List.of("late"), List.of(9)));
        assertEquals(1, queue.drain(1, (recipient, packet) -> true));
        queue.cancel(published);
        queue.cancel(published);
        assertEquals(0, queue.reservedBytes());
        assertEquals(0, queue.reservationCount());
        assertEquals(0, queue.drain(20, (recipient, packet) -> fail("cancelled delivery")));
    }

    @Test
    void clearInvalidatesPreparingAndPublishedTokensWithoutAffectingNewReservations() {
        var queue = new ModelDeliveryQueue<String, Integer>(2, 20);
        var preparing = queue.reserve(10);
        var published = queue.reserve(10);
        queue.publish(published, List.of("old"), List.of(1));
        queue.clear();
        queue.clear();
        assertEquals(10, queue.reservedBytes(), "the old preparing worker still owns its bytes");
        assertEquals(1, queue.reservationCount());
        assertNull(queue.reserve(20));
        var current = queue.reserve(10);
        assertNotNull(current);
        assertFalse(queue.isCurrent(preparing));
        assertFalse(queue.isCurrent(published));
        assertFalse(queue.publish(preparing, List.of("late"), List.of(9)));
        assertFalse(queue.publish(published, List.of("late"), List.of(9)));
        queue.cancel(preparing);
        queue.cancel(published);
        assertTrue(queue.isCurrent(current));
        assertEquals(10, queue.reservedBytes());
        assertEquals(1, queue.reservationCount());
        assertEquals(0, queue.drain(20, (recipient, packet) -> fail("old delivery")));
    }

    @Test
    void callbackClearOrCancelCannotRequeueAnOldCursorOrReleaseItsReplacement() {
        for (boolean clear : List.of(false, true)) {
            for (boolean accepted : List.of(false, true)) {
                var queue = new ModelDeliveryQueue<String, Integer>(1, 10);
                var old = queue.reserve(10);
                queue.publish(old, List.of("old-a", "old-b"), List.of(1, 2));
                AtomicReference<ModelDeliveryQueue.Reservation> replacement = new AtomicReference<>();
                assertEquals(1, queue.drain(1, (recipient, packet) -> {
                    if (clear) { queue.clear(); } else { queue.cancel(old); }
                    replacement.set(queue.reserve(10));
                    assertTrue(queue.publish(replacement.get(), List.of("new"), List.of(3)));
                    return accepted;
                }));
                assertFalse(queue.isCurrent(old));
                assertTrue(queue.isCurrent(replacement.get()));
                assertEquals(10, queue.reservedBytes());
                List<String> sent = new ArrayList<>();
                assertEquals(1, queue.drain(20, (recipient, packet) -> sent.add(recipient + packet)));
                assertEquals(List.of("new3"), sent);
                assertEquals(0, queue.reservedBytes());
            }
        }
    }

    @Test
    void callbackFailureCancelsOnlyItsTransferAndRethrowsTheOriginalFailure() {
        for (boolean error : List.of(false, true)) {
            var queue = new ModelDeliveryQueue<String, Integer>(2, 20);
            var failing = queue.reserve(10);
            var healthy = queue.reserve(10);
            queue.publish(failing, List.of("a", "b"), List.of(1, 2));
            queue.publish(healthy, List.of("c"), List.of(3, 4));
            Throwable failure = error ? new AssertionError("delivery failure") : new IllegalStateException("delivery failure");
            Throwable thrown = assertThrows(Throwable.class, () -> queue.drain(20, (recipient, packet) -> {
                if (failure instanceof Error serious) { throw serious; }
                throw (RuntimeException) failure;
            }));
            assertSame(failure, thrown);
            assertFalse(queue.isCurrent(failing));
            assertTrue(queue.isCurrent(healthy));
            assertEquals(10, queue.reservedBytes());
            List<String> sent = new ArrayList<>();
            assertEquals(2, queue.drain(20, (recipient, packet) -> sent.add(recipient + packet)));
            assertEquals(List.of("c3", "c4"), sent);
            assertEquals(0, queue.reservedBytes());
        }
    }

    @Test
    void callbackFailureAfterClearCannotReleaseANewReservation() {
        var queue = new ModelDeliveryQueue<String, Integer>(1, 10);
        var old = queue.reserve(10);
        queue.publish(old, List.of("old"), List.of(1));
        AtomicReference<ModelDeliveryQueue.Reservation> replacement = new AtomicReference<>();
        var failure = new IllegalStateException("delivery failure");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> queue.drain(1, (recipient, packet) -> {
            queue.clear();
            replacement.set(queue.reserve(10));
            throw failure;
        })));
        assertTrue(queue.isCurrent(replacement.get()));
        assertEquals(10, queue.reservedBytes());
        assertEquals(1, queue.reservationCount());
    }

    @Test
    void publicationCapturesListStructureButKeepsTheSamePacketPayload() {
        var queue = new ModelDeliveryQueue<String, byte[]>(1, 3);
        var reservation = queue.reserve(3);
        byte[] packet = {1, 2, 3};
        List<String> recipients = new ArrayList<>(List.of("original"));
        List<byte[]> packets = new ArrayList<>();
        packets.add(packet);
        assertTrue(queue.publish(reservation, recipients, packets));
        recipients.set(0, "replacement");
        packets.clear();
        assertEquals(1, queue.drain(1, (recipient, delivered) -> {
            assertEquals("original", recipient);
            assertSame(packet, delivered);
            return true;
        }));
    }

    @Test
    void aWorkerPublicationAfterClearCannotReviveItsReservation() throws Exception {
        var queue = new ModelDeliveryQueue<String, Integer>(1, 10);
        var old = queue.reserve(10);
        var workerReady = new CountDownLatch(1);
        var publishNow = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var result = executor.submit(() -> {
                workerReady.countDown();
                assertTrue(publishNow.await(5, TimeUnit.SECONDS));
                return queue.publish(old, List.of("old"), List.of(1));
            });
            assertTrue(workerReady.await(5, TimeUnit.SECONDS));
            queue.clear();
            assertNull(queue.reserve(10), "clearing cannot reuse a still-running worker's lease");
            assertEquals(10, queue.reservedBytes());
            publishNow.countDown();
            assertFalse(result.get(5, TimeUnit.SECONDS));
            assertEquals(0, queue.reservedBytes());
            var replacement = queue.reserve(10);
            assertTrue(queue.isCurrent(replacement));
            assertEquals(10, queue.reservedBytes());
            assertEquals(1, queue.reservationCount());
        } finally {
            publishNow.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancelledPreparingWorkersRetainBothCapacityLimitsUntilTheyAcknowledgeCompletion() {
        var queue = new ModelDeliveryQueue<String, Integer>(2, 20);
        var first = queue.reserve(10);
        var second = queue.reserve(10);
        queue.clear();
        assertFalse(queue.isCurrent(first));
        assertFalse(queue.isCurrent(second));
        assertNull(queue.reserve(0), "invalidated preparing work retains its count permit");
        assertNull(queue.reserve(1), "invalidated preparing work retains its byte permit");
        assertEquals(20, queue.reservedBytes());
        assertEquals(2, queue.reservationCount());
        queue.cancel(first);
        var replacement = queue.reserve(10);
        assertNotNull(replacement);
        queue.cancel(first);
        assertFalse(queue.publish(first, List.of("late"), List.of(1)));
        assertEquals(20, queue.reservedBytes());
        assertTrue(queue.isCurrent(replacement));
        assertFalse(queue.publish(second, List.of("old"), List.of(2)));
        queue.cancel(second);
        assertEquals(10, queue.reservedBytes());
        assertEquals(1, queue.reservationCount());
        assertNotNull(queue.reserve(10));
    }

    @Test
    void invalidLimitsAndSizesAreRejectedAndZeroByteReservationsStillUseSlots() {
        assertThrows(IllegalArgumentException.class, () -> new ModelDeliveryQueue<>(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ModelDeliveryQueue<>(1, -1));
        var queue = new ModelDeliveryQueue<String, Integer>(1, 0);
        assertThrows(IllegalArgumentException.class, () -> queue.reserve(-1));
        assertNull(queue.reserve(1));
        assertNotNull(queue.reserve(0));
        assertNull(queue.reserve(0));
        assertThrows(IllegalArgumentException.class, () -> queue.drain(-1, (recipient, packet) -> true));
        assertEquals(0, queue.reservedBytes());
        assertEquals(1, queue.reservationCount());
    }
}
