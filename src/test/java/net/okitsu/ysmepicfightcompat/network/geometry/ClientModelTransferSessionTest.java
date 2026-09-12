package net.okitsu.ysmepicfightcompat.network.geometry;

import net.okitsu.ysmepicfightcompat.assets.ModelBundle;
import net.okitsu.ysmepicfightcompat.cache.ModelDiskCache;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ClientModelTransferSessionTest {
    @Test
    void reservationsCoverLookupDecodeAndReadyAndNeverDiscardCompletedModels() {
        Fixture f = new Fixture();
        f.demand("first", 0); f.demand("second", 0); f.demand("third", 0);
        var first = f.next(0);
        var second = f.next(0);
        assertNull(f.next(0));
        assertEquals(2, f.session.reservedCount());
        assertEquals(20, f.session.reservedBytes());

        ModelBundle firstModel = f.ready(first, 1);
        ModelBundle secondModel = f.ready(second, 1);
        assertNull(f.next(1), "completion must not release the conversion handoff reservation");
        assertSame(firstModel, f.take("first"));
        var third = f.next(2);
        assertEquals("third", third.modelId());
        ModelBundle thirdModel = f.ready(third, 3);
        assertSame(secondModel, f.take("second"));
        assertSame(thirdModel, f.take("third"));
        assertEquals(0, f.session.reservedCount());
        assertEquals(0, f.session.reservedBytes());
        assertNull(f.take("first"));
    }

    @Test
    void byteBudgetAppliesIndependentlyOfTheRequestCount() {
        Fixture f = new Fixture(3, 4, 10);
        f.demand("first", 0); f.demand("second", 0);
        var first = f.next(0);
        assertNotNull(first);
        assertNull(f.next(0));
        f.ready(first, 1);
        assertNull(f.next(1));
        f.take("first");
        assertEquals("second", f.next(2).modelId());
    }

    @Test
    void waitingDemandIsBoundedWithoutEvictingActiveSources() {
        Fixture f = new Fixture();
        f.demand("first", 0);
        var first = f.next(0);
        f.demand("second", 0);
        var second = f.next(0);
        for (int i = 0; i < 100; i++) { f.demand("waiting-" + i, i); }
        assertEquals(4, f.session.demandCount());
        assertNotNull(f.session.source(first));
        assertNotNull(f.session.source(second));
        var updated = new ClientModelTransferSession.RequestSource(42, new UUID(0, 42));
        f.session.rememberSource("first", updated, 101);
        assertNotNull(f.session.source(first));
        assertNull(f.next(101));
    }

    @Test
    void lookupPublishesCacheAndRequestOnlyTogetherOnTheClientExecutor() {
        Fixture f = new Fixture();
        var request = f.start("model", 0);
        var cached = cached("server-a", 1);
        List<byte[]> sent = new ArrayList<>();
        f.session.completeLookup(request, cached, 1, digest -> {
            assertSame(cached, f.session.cached(request));
            assertSame(request, f.session.awaitingResponse("model"));
            sent.add(digest);
        });
        assertNull(f.session.cached(request));
        assertNull(f.session.awaitingResponse("model"));
        assertTrue(sent.isEmpty());
        f.client.runAll();
        assertArrayEquals(cached.entry().payloadDigest(), sent.get(0));
        assertEquals(1, f.session.reservedCount());
    }

    @Test
    void memoryOnlyCacheMissAndCompletedResultNeedNoDiskEntry() {
        Fixture f = new Fixture();
        var request = f.start("model", 0);
        List<byte[]> sent = new ArrayList<>();
        f.session.completeLookup(request, null, 1, sent::add);
        f.client.runAll();
        assertArrayEquals(new byte[0], sent.get(0));
        assertNull(f.session.cached(request));
        assertTrue(f.session.beginDecode(request, 2));
        ModelBundle model = new ModelBundle("model");
        f.session.completeModel(request, model, 3, () -> { });
        f.client.runAll();
        assertSame(model, f.take("model"));
    }

    @Test
    void cancelledLookupsRetainCapacityUntilTheirWorkersActuallyFinish() {
        Fixture f = new Fixture();
        var first = f.start("first", 0);
        var second = f.start("second", 0);
        f.session.clear();
        f.demand("replacement", 1);
        assertNull(f.next(1), "old queued workers must not accumulate across reloads");
        AtomicInteger effects = new AtomicInteger();
        f.session.completeLookup(first, cached("server-a", 1), 2,
                ignored -> effects.incrementAndGet());
        f.session.failWorker(second, 2, effects::incrementAndGet);
        assertEquals(2, f.session.reservedCount());
        f.client.runAll();
        assertEquals(0, effects.get());
        assertEquals(0, f.session.reservedCount());
        assertEquals(0, f.session.reservedBytes());
        assertEquals("replacement", f.next(3).modelId());
    }

    @Test
    void oldDecodeAfterSameConnectionReloadCannotPublishOrReleaseNewReservation() {
        Fixture f = new Fixture();
        var old = f.start("model", 0);
        f.lookup(old, null, 1);
        assertTrue(f.session.beginDecode(old, 2));
        f.session.clear();
        var current = f.start("model", 3);
        AtomicInteger effects = new AtomicInteger();
        f.session.completeModel(old, new ModelBundle("model"), 4, effects::incrementAndGet);
        f.client.runAll();
        assertEquals(0, effects.get());
        assertTrue(f.session.isCurrent(current));
        assertNull(f.take("model"));
        assertEquals(1, f.session.reservedCount());
        // Duplicate terminal callbacks cannot release the current same-named reservation.
        f.session.failWorker(old, 5, effects::incrementAndGet);
        f.client.runAll();
        assertEquals(1, f.session.reservedCount());
        assertEquals(10, f.session.reservedBytes());
    }

    @Test
    void connectionReplacementRejectsQueuedCompletionsEvenBeforeLogoutCleanup() {
        Fixture f = new Fixture();
        var old = f.start("model", 0);
        AtomicInteger sent = new AtomicInteger();
        f.session.completeLookup(old, cached("server-a", 1), 1, ignored -> sent.incrementAndGet());
        f.connection.set(new Object());
        f.client.runAll();
        assertEquals(0, sent.get());
        assertFalse(f.session.isCurrent(old));
        assertEquals(0, f.session.reservedCount());
        assertNotNull(f.start("model", 2));
    }

    @Test
    void disconnectClearsDemandAndAssembliesButKeepsOnlyOutstandingWorkerLeases() {
        Fixture f = new Fixture();
        var ready = f.start("ready", 0);
        f.ready(ready, 1);
        var lookup = f.start("lookup", 1);
        f.connection.set(null);
        assertNull(f.session.capture("server-a"));
        assertEquals(0, f.session.demandCount());
        assertEquals(1, f.session.reservedCount());
        assertNull(f.take("ready"));
        assertTrue(f.clears.get() >= 2);
        f.session.completeLookup(lookup, null, 2, ignored -> fail("stale send"));
        f.client.runAll();
        assertEquals(0, f.session.reservedCount());
    }

    @Test
    void transferIdleTimeoutRefreshesOnProgressAndReleasesExactlyOnce() {
        Fixture f = new Fixture();
        var request = f.start("model", 0);
        f.lookup(request, null, 1);
        UUID transfer = UUID.randomUUID();
        assertTrue(f.session.receive(request, transfer, 20));
        f.demand("model", 20);
        f.session.maintain(35);
        assertTrue(f.session.isCurrent(request), "active transfer may exceed its start timeout");
        f.session.maintain(51);
        assertFalse(f.session.isCurrent(request));
        assertEquals(0, f.session.reservedCount());
        f.session.failed(request, 52);
        assertEquals(0, f.session.reservedBytes());
    }

    @Test
    void timedOutWorkerCannotFreeCapacityBeforeItsCompletion() {
        Fixture f = new Fixture(1, 4, 10);
        var old = f.start("old", 0);
        f.demand("new", 29);
        f.session.maintain(31);
        assertFalse(f.session.isCurrent(old));
        assertNull(f.next(31));
        f.session.failWorker(old, 32, () -> fail("cancelled callback"));
        f.client.runAll();
        assertEquals("new", f.next(32).modelId());
    }

    @Test
    void readyModelsStayWhileWantedAndAreCancelledWhenDemandExpires() {
        Fixture f = new Fixture();
        var request = f.start("model", 0);
        ModelBundle model = f.ready(request, 1);
        f.demand("model", 25);
        f.session.maintain(40);
        assertTrue(f.session.isCurrent(request));
        assertSame(model, f.take("model"));

        var abandoned = f.start("abandoned", 41);
        f.ready(abandoned, 42);
        f.session.maintain(72);
        assertNull(f.take("abandoned"));
        assertEquals(0, f.session.reservedCount());
    }

    @Test
    void responsesCannotCreateReservationsOrScheduleDuplicateDecodes() {
        Fixture f = new Fixture();
        assertNull(f.session.awaitingResponse("model"));
        var request = f.start("model", 0);
        assertNull(f.session.awaitingResponse("model"), "disk lookup has not sent a request yet");
        f.lookup(request, cached("server-a", 1), 1);
        UUID transfer = UUID.randomUUID();
        assertTrue(f.session.receive(request, transfer, 2));
        assertNull(f.session.cached(request), "DATA releases the old compressed candidate");
        assertFalse(f.session.receive(request, UUID.randomUUID(), 3));
        assertTrue(f.session.beginDecode(request, 4));
        assertFalse(f.session.beginDecode(request, 4));
        assertNull(f.session.awaitingResponse("model"));
        assertFalse(f.session.receive(request, transfer, 5));
        f.session.completeModel(request, new ModelBundle("model"), 6, () -> { });
        f.client.runAll();
        assertNull(f.session.awaitingResponse("model"));
    }

    @Test
    void failedRequestRetainsRetryDelayAndOldFailureDoesNotCancelItsReplacement() {
        Fixture f = new Fixture();
        var old = f.start("model", -100);
        f.lookup(old, cached("server-a", 1), -99);
        assertNotNull(f.session.failed(old, -98));
        assertNull(f.next(-94));
        var current = f.next(-93);
        assertNotNull(current);
        f.session.failed(old, -92);
        assertTrue(f.session.isCurrent(current));
        assertEquals(1, f.session.reservedCount());
    }

    @Test
    void invalidConditionalReplyAllowsOnlyOneUnconditionalRetryInTheSameReservation() {
        Fixture f = new Fixture();
        var request = f.start("model", 0);
        f.lookup(request, cached("server-a", 1), 1);
        assertTrue(f.session.retryFull(request, 2));
        assertNull(f.session.cached(request));
        assertFalse(f.session.retryFull(request, 3));
        assertEquals(1, f.session.reservedCount());
    }

    @Test
    void duplicateCompletionNotifiesAndReleasesOnlyOnce() {
        Fixture f = new Fixture();
        var request = f.start("model", 0);
        f.lookup(request, null, 1);
        assertTrue(f.session.beginDecode(request, 2));
        AtomicInteger arrivals = new AtomicInteger();
        ModelBundle model = new ModelBundle("model");
        f.session.completeModel(request, model, 3, arrivals::incrementAndGet);
        f.session.completeModel(request, model, 3, arrivals::incrementAndGet);
        f.client.runAll();
        assertEquals(1, arrivals.get());
        assertSame(model, f.take("model"));
        assertEquals(0, f.session.reservedBytes());
        assertNull(f.take("model"));
    }

    @Test
    void corruptConditionalCacheRetriesWithinItsReservationOnlyOnTheClientQueue() {
        Fixture f = new Fixture();
        var request = f.start("model", 0);
        var cached = cached("server-a", 1);
        f.lookup(request, cached, 1);
        assertTrue(f.session.beginDecode(request, 2));
        AtomicInteger retries = new AtomicInteger();
        f.session.retryWithoutCache(request, 3, () -> {
            assertNull(f.session.cached(request));
            assertTrue(f.session.retryFull(request, 3));
            retries.incrementAndGet();
        });
        assertSame(cached, f.session.cached(request));
        assertEquals(0, retries.get());
        f.client.runAll();
        assertEquals(1, retries.get());
        assertSame(request, f.session.awaitingResponse("model"));
        assertEquals(1, f.session.reservedCount());
        assertEquals(10, f.session.reservedBytes());
    }

    @Test
    void queuedOldCacheFailureCannotRetryOrDeleteANewConnectionsCandidate() {
        Fixture f = new Fixture();
        var old = f.start("model", 0);
        f.lookup(old, cached("server-a", 1), 1);
        assertTrue(f.session.beginDecode(old, 2));
        f.session.retryWithoutCache(old, 3, () -> fail("old cache retry"));
        f.connection.set(new Object());
        var current = f.start("model", 4);
        var replacement = cached("server-a", 2);
        f.lookup(current, replacement, 5);
        assertSame(replacement, f.session.cached(current));
        assertTrue(f.session.isCurrent(current));
        assertEquals(1, f.session.reservedCount());
        assertNull(f.session.failed(old, 6));
        assertSame(replacement, f.session.cached(current));
    }

    @Test
    void lookupFailureReleasesItsLeaseAndAppliesRetryDelayAtTheClientCommit() {
        Fixture f = new Fixture();
        var request = f.start("model", 0);
        AtomicInteger errors = new AtomicInteger();
        f.session.failWorker(request, 1, errors::incrementAndGet);
        assertEquals(1, f.session.reservedCount());
        assertEquals(0, errors.get());
        f.client.runAll();
        assertEquals(0, f.session.reservedCount());
        assertEquals(1, errors.get());
        assertNull(f.next(5));
        assertNotNull(f.next(6));
    }

    @Test
    void wrongServerCacheAndWrongModelCompletionCannotSatisfyAReservation() {
        Fixture f = new Fixture();
        var wrongServer = f.start("model", 0);
        f.session.completeLookup(wrongServer, cached("server-b", 1), 1,
                ignored -> fail("wrong server digest"));
        f.client.runAll();
        assertEquals(0, f.session.reservedCount());
        var wrongModel = f.next(6);
        f.lookup(wrongModel, null, 7);
        assertTrue(f.session.beginDecode(wrongModel, 8));
        f.session.completeModel(wrongModel, new ModelBundle("other"), 9,
                () -> fail("wrong model arrival"));
        f.client.runAll();
        assertNull(f.take("model"));
        assertNull(f.take("other"));
        assertEquals(0, f.session.reservedCount());
    }

    @Test
    void unansweredRequestRetriesInItsSlotButActiveDataDoesNotStartAnotherTransfer() {
        Fixture f = new Fixture();
        var request = f.start("model", 0);
        f.lookup(request, null, 1);
        assertTrue(f.session.retryWaiting(5).isEmpty());
        assertEquals(List.of(request), f.session.retryWaiting(6));
        assertTrue(f.session.retryWaiting(6).isEmpty());
        assertEquals(1, f.session.reservedCount());
        assertTrue(f.session.receive(request, UUID.randomUUID(), 7));
        assertTrue(f.session.retryWaiting(20).isEmpty());
    }

    @Test
    void requestRetriesCannotKeepAnUnansweredReservationAliveIndefinitely() {
        Fixture f = new Fixture();
        var request = f.start("model", 0);
        f.lookup(request, null, 1);
        for (long now = 6; now <= 26; now += 5) {
            assertEquals(List.of(request), f.session.retryWaiting(now));
            f.demand("model", now);
        }
        f.session.maintain(31);
        assertFalse(f.session.isCurrent(request));
        assertEquals(0, f.session.reservedCount());
    }

    @Test
    void switchingOneOfSeveralSourcesKeepsTheCompletedSharedModel() {
        Fixture f = new Fixture();
        var shared = f.start("shared", 0);
        var firstSource = f.session.source(shared);
        var secondSource = new ClientModelTransferSession.RequestSource(42, new UUID(0, 42));
        f.session.rememberSource("shared", secondSource, 0);
        ModelBundle ready = f.ready(shared, 1);
        f.session.rememberSource("new-model", firstSource, 2);
        assertTrue(f.session.isCurrent(shared));
        assertEquals(secondSource, f.session.source(shared));
        assertSame(ready, f.take("shared"));
        assertEquals("new-model", f.next(2).modelId());
    }

    @Test
    void lastSourceChangingModelsCancelsUnusedReadyWithoutWaitingForTimeout() {
        Fixture f = new Fixture();
        var old = f.start("old", 0);
        var source = f.session.source(old);
        f.ready(old, 1);
        f.session.rememberSource("new", source, 2);
        assertFalse(f.session.isCurrent(old));
        assertNull(f.take("old"));
        assertEquals(0, f.session.reservedCount());
        assertEquals("new", f.next(2).modelId());
    }

    @Test
    void lastSourceExitCancelsOnlyItsModelAndWaitsForThatWorkerToReleaseCapacity() {
        Fixture f = new Fixture();
        var removed = f.start("removed", 0);
        var other = f.start("other", 0);
        f.session.removeSource(f.session.source(removed));
        assertFalse(f.session.isCurrent(removed));
        assertTrue(f.session.isCurrent(other));
        assertEquals(2, f.session.reservedCount());
        f.session.completeLookup(removed, null, 1, ignored -> fail("departed source"));
        f.client.runAll();
        assertEquals(1, f.session.reservedCount());
        assertEquals(1, f.session.demandCount());
        assertEquals(1, f.session.sourceCount());
    }

    @Test
    void sourceTrackingIsBoundedWithoutEvictingAnAcceptedReadyModel() {
        Fixture f = new Fixture();
        var request = f.start("shared", 0);
        ModelBundle ready = f.ready(request, 1);
        for (int i = 1; i <= 100; i++) {
            f.session.rememberSource("shared", new ClientModelTransferSession.RequestSource(
                    i, new UUID(0, i)), 2);
        }
        assertEquals(16, f.session.sourceCount());
        assertEquals(1, f.session.demandCount());
        assertSame(ready, f.take("shared"));
        assertEquals(0, f.session.sourceCount());
    }

    @Test
    void twoUnfinishedConversionsPreventAThirdRequestUntilClientCompletion() {
        Fixture f = new Fixture();
        var first = f.start("first", 0);
        var second = f.start("second", 0);
        f.demand("third", 0);
        ModelBundle firstModel = f.ready(first, 1);
        ModelBundle secondModel = f.ready(second, 1);
        var firstConsumer = f.session.takeReady("first");
        var secondConsumer = f.session.takeReady("second");
        assertSame(firstModel, firstConsumer.model());
        assertSame(secondModel, secondConsumer.model());
        assertNull(f.session.takeReady("first"), "each model has only one conversion owner");
        assertNull(f.next(2));
        assertEquals(20, f.session.reservedBytes());
        f.session.completeConversion(firstConsumer.request());
        assertNull(f.next(3), "worker completion alone cannot free a client-pending result");
        f.client.runAll();
        assertEquals("third", f.next(4).modelId());
        assertEquals(2, f.session.reservedCount());
        f.session.completeConversion(secondConsumer.request());
        f.client.runAll();
        assertEquals(1, f.session.reservedCount());
    }

    @Test
    void reloadRetainsOldConsumerCapacityAndItsCompletionCannotCancelNewSameModel() {
        Fixture f = new Fixture();
        var old = f.start("model", 0);
        f.ready(old, 1);
        var consumer = f.session.takeReady("model");
        f.session.clear();
        var current = f.start("model", 2);
        f.demand("waiting", 2);
        assertNull(f.next(2));
        assertEquals(20, f.session.reservedBytes());
        f.session.completeConversion(consumer.request());
        f.client.runAll();
        assertTrue(f.session.isCurrent(current));
        assertEquals(1, f.session.reservedCount());
        f.session.completeConversion(consumer.request());
        f.client.runAll();
        assertEquals(10, f.session.reservedBytes(), "duplicate release must be harmless");
        assertEquals("waiting", f.next(3).modelId());
    }

    @Test
    void sourceExitKeepsConversionCapacityUntilItsConsumerCompletes() {
        Fixture f = new Fixture(1, 4, 10);
        var request = f.start("model", 0);
        var source = f.session.source(request);
        f.ready(request, 1);
        var consumer = f.session.takeReady("model");
        f.session.removeSource(source);
        f.demand("waiting", 2);
        assertNull(f.next(2));
        assertFalse(f.session.isCurrent(request));
        f.session.completeConversion(consumer.request());
        f.client.runAll();
        assertEquals("waiting", f.next(3).modelId());
    }

    @Test
    void duplicateDecodeCallbacksCannotPublishOrReleaseAConversionLease() {
        Fixture f = new Fixture(1, 4, 10);
        var request = f.start("model", 0);
        ModelBundle model = f.ready(request, 1);
        var consumer = f.session.takeReady("model");
        f.session.completeModel(request, model, 2, () -> fail("duplicate arrival"));
        f.session.failWorker(request, 2, () -> fail("old decoder failure"));
        f.client.runAll();
        assertNull(f.session.takeReady("model"));
        assertEquals(1, f.session.reservedCount());
        f.session.completeConversion(consumer.request());
        f.client.runAll();
        assertEquals(0, f.session.reservedBytes());
    }

    @Test
    void wantedWorkerWorkIsNotCancelledByTheNetworkIdleDeadline() {
        Fixture f = new Fixture();
        var request = f.start("model", 0);
        f.demand("model", 29);
        f.session.maintain(31);
        assertTrue(f.session.isCurrent(request), "disk work can still be running");
        f.lookup(request, null, 32);
        assertTrue(f.session.beginDecode(request, 33));
        f.demand("model", 60);
        f.session.maintain(64);
        assertTrue(f.session.isCurrent(request), "decode work can still be running");
        f.session.completeModel(request, new ModelBundle("model"), 65, () -> { });
        f.client.runAll();
        assertNotNull(f.take("model"));
        assertEquals(0, f.session.reservedCount());
    }

    private static ClientModelTransferSession.Cached cached(String server, int marker) {
        byte[] payload = {(byte) marker};
        byte[] digest = ModelDiskCache.sha256(payload);
        return new ClientModelTransferSession.Cached(server,
                new ModelDiskCache.Entry(digest, digest, payload));
    }

    private static final class Fixture {
        final ManualExecutor client = new ManualExecutor();
        final AtomicReference<Object> connection = new AtomicReference<>(new Object());
        final AtomicInteger clears = new AtomicInteger();
        final ClientModelTransferSession session;
        Fixture() { this(2, 4, 20); }
        Fixture(int count, int demands, long bytes) {
            session = new ClientModelTransferSession(client, connection::get,
                    clears::incrementAndGet, count, demands, 10, bytes, 5, 30);
        }
        void demand(String model, long now) {
            session.capture("server-a");
            session.rememberSource(model, new ClientModelTransferSession.RequestSource(
                    model.hashCode() & Integer.MAX_VALUE, new UUID(0, model.hashCode())), now);
        }
        ClientModelTransferSession.Request next(long now) {
            return session.nextRequest(session.capture("server-a"), now);
        }
        ClientModelTransferSession.Request start(String model, long now) {
            session.capture("server-a");
            demand(model, now);
            var request = next(now);
            assertNotNull(request);
            assertEquals(model, request.modelId());
            return request;
        }
        void lookup(ClientModelTransferSession.Request request,
                    ClientModelTransferSession.Cached cached, long now) {
            session.completeLookup(request, cached, now, ignored -> { });
            client.runAll();
        }
        ModelBundle ready(ClientModelTransferSession.Request request, long now) {
            lookup(request, null, now);
            assertTrue(session.beginDecode(request, now));
            ModelBundle model = new ModelBundle(request.modelId());
            session.completeModel(request, model, now, () -> { });
            client.runAll();
            return model;
        }
        ModelBundle take(String modelId) {
            var handoff = session.takeReady(modelId);
            if (handoff == null) { return null; }
            session.completeConversion(handoff.request());
            client.runAll();
            return handoff.model();
        }
    }

    private static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable command) { tasks.addLast(command); }
        void runAll() { while (!tasks.isEmpty()) { tasks.removeFirst().run(); } }
    }
}
