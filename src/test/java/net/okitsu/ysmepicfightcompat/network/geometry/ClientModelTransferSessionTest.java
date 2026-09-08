package net.okitsu.ysmepicfightcompat.network.geometry;

import net.okitsu.ysmepicfightcompat.assets.ModelBundle;
import net.okitsu.ysmepicfightcompat.cache.ModelDiskCache;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientModelTransferSessionTest {
    private static final String MODEL = "example:model";
    private static final long RETRY_AFTER = 5_000_000_000L;

    @Test
    void lookupPublishesCacheAndEndsPendingOnlyOnTheClientQueue() {
        Fixture fixture = new Fixture();
        var ticket = fixture.ticket();
        var cached = cached("server-a", 1);
        List<byte[]> requests = new ArrayList<>();
        fixture.session.requested(MODEL, 100L);
        assertTrue(fixture.session.beginLookup(ticket, MODEL));

        fixture.session.completeLookup(ticket, MODEL, cached, digest -> {
            assertSame(cached, fixture.session.cached(MODEL));
            assertFalse(fixture.session.hasLookup(MODEL));
            requests.add(digest);
        });

        assertNull(fixture.session.cached(MODEL));
        assertTrue(fixture.session.hasLookup(MODEL));
        assertTrue(requests.isEmpty());
        fixture.client.runAll();
        assertSame(cached, fixture.session.cached(MODEL));
        assertEquals(100L, fixture.session.lastRequested(MODEL));
        assertEquals(1, requests.size());
        assertArrayEquals(cached.entry().payloadDigest(), requests.get(0));
    }

    @Test
    void cacheMissSendsAnUnconditionalRequestAndDropsTheOldCandidate() {
        Fixture fixture = new Fixture();
        var ticket = fixture.ticket();
        fixture.installCache(ticket, cached("server-a", 1));
        List<byte[]> requests = new ArrayList<>();

        assertTrue(fixture.session.beginLookup(ticket, MODEL));
        fixture.session.completeLookup(ticket, MODEL, null, requests::add);
        fixture.client.runAll();

        assertNull(fixture.session.cached(MODEL));
        assertFalse(fixture.session.hasLookup(MODEL));
        assertEquals(1, requests.size());
        assertArrayEquals(new byte[0], requests.get(0));
    }

    @Test
    void oldLookupCannotPublishOrRemoveTheNewReloadLookup() {
        Fixture fixture = new Fixture();
        var old = fixture.ticket();
        assertTrue(fixture.session.beginLookup(old, MODEL));
        AtomicInteger requests = new AtomicInteger();

        fixture.session.clear();
        var current = fixture.ticket();
        fixture.session.requested(MODEL, 200L);
        var source = source(2);
        fixture.session.rememberSource(MODEL, source);
        assertTrue(fixture.session.beginLookup(current, MODEL));
        fixture.session.completeLookup(old, MODEL, cached("server-a", 1),
                ignored -> requests.incrementAndGet());
        fixture.client.runAll();

        assertTrue(fixture.session.hasLookup(MODEL));
        assertNull(fixture.session.cached(MODEL));
        assertEquals(200L, fixture.session.lastRequested(MODEL));
        assertSame(source, fixture.session.source(MODEL));
        assertEquals(0, requests.get());
    }

    @Test
    void lookupFailureFinishesOnlyTheCurrentPendingLookup() {
        Fixture fixture = new Fixture();
        var ticket = fixture.ticket();
        fixture.session.requested(MODEL, 100L);
        assertTrue(fixture.session.beginLookup(ticket, MODEL));
        AtomicInteger failures = new AtomicInteger();

        fixture.session.failLookup(ticket, MODEL, failures::incrementAndGet);
        assertTrue(fixture.session.hasLookup(MODEL));
        assertEquals(0, failures.get());
        fixture.client.runAll();

        assertFalse(fixture.session.hasLookup(MODEL));
        assertEquals(100L, fixture.session.lastRequested(MODEL));
        assertEquals(1, failures.get());
        assertTrue(fixture.session.beginLookup(ticket, MODEL));
    }

    @Test
    void staleLookupFailureDoesNotFinishNewPendingWork() {
        Fixture fixture = new Fixture();
        var old = fixture.ticket();
        assertTrue(fixture.session.beginLookup(old, MODEL));
        AtomicInteger failures = new AtomicInteger();
        fixture.session.failLookup(old, MODEL, failures::incrementAndGet);

        fixture.session.clear();
        var current = fixture.ticket();
        assertTrue(fixture.session.beginLookup(current, MODEL));
        fixture.client.runAll();

        assertTrue(fixture.session.hasLookup(MODEL));
        assertEquals(0, failures.get());
    }

    @Test
    void successPublishesTheModelCacheAndNotificationsAsOneClientCommit() {
        Fixture fixture = new Fixture();
        var ticket = fixture.ticket();
        fixture.installCache(ticket, cached("server-a", 1));
        fixture.session.requested(MODEL, 100L);
        ModelBundle model = new ModelBundle(MODEL);
        AtomicInteger notifications = new AtomicInteger();

        fixture.session.completeModel(ticket, MODEL, model, () -> {
            assertNull(fixture.session.cached(MODEL));
            assertNull(fixture.session.lastRequested(MODEL));
            notifications.incrementAndGet();
        });

        assertNull(fixture.session.takeReady(MODEL));
        assertEquals(100L, fixture.session.lastRequested(MODEL));
        assertEquals(0, notifications.get());
        fixture.client.runAll();

        assertSame(model, fixture.session.takeReady(MODEL));
        assertNull(fixture.session.takeReady(MODEL));
        assertEquals(1, notifications.get());
    }

    @Test
    void queuedSuccessCannotRepublishAfterSameConnectionReload() {
        Fixture fixture = new Fixture();
        var old = fixture.ticket();
        AtomicInteger notifications = new AtomicInteger();
        fixture.session.completeModel(old, MODEL, new ModelBundle(MODEL),
                notifications::incrementAndGet);

        fixture.session.clear();
        var current = fixture.ticket();
        fixture.session.requested(MODEL, 300L);
        var source = source(3);
        fixture.session.rememberSource(MODEL, source);
        assertTrue(fixture.session.beginLookup(current, MODEL));
        fixture.client.runAll();

        assertFalse(fixture.session.isCurrent(old));
        assertTrue(fixture.session.isCurrent(current));
        assertNull(fixture.session.takeReady(MODEL));
        assertEquals(300L, fixture.session.lastRequested(MODEL));
        assertTrue(fixture.session.hasLookup(MODEL));
        assertSame(source, fixture.session.source(MODEL));
        assertEquals(0, notifications.get());
    }

    @Test
    void lateOldServerSuccessCannotReplaceTheNewSameNamedModel() {
        Fixture fixture = new Fixture();
        var old = fixture.ticket();
        fixture.connection.set(new Object());
        var current = fixture.session.capture("server-b");
        ModelBundle currentModel = new ModelBundle(MODEL);
        fixture.session.completeModel(current, MODEL, currentModel, () -> { });
        fixture.client.runAll();
        var currentCache = cached("server-b", 2);
        fixture.installCache(current, currentCache);
        fixture.session.requested(MODEL, 400L);
        AtomicInteger oldNotifications = new AtomicInteger();

        fixture.session.completeModel(old, MODEL, new ModelBundle(MODEL),
                oldNotifications::incrementAndGet);
        fixture.client.runAll();

        assertSame(currentModel, fixture.session.takeReady(MODEL));
        assertSame(currentCache, fixture.session.cached(MODEL));
        assertEquals(400L, fixture.session.lastRequested(MODEL));
        assertEquals(0, oldNotifications.get());
    }

    @Test
    void connectionReplacementRejectsQueuedResultsEvenBeforeClearIsCalled() {
        Fixture fixture = new Fixture();
        var old = fixture.ticket();
        fixture.session.requested(MODEL, 100L);
        assertTrue(fixture.session.beginLookup(old, MODEL));
        AtomicInteger effects = new AtomicInteger();
        fixture.session.completeLookup(old, MODEL, cached("server-a", 1),
                ignored -> effects.incrementAndGet());
        fixture.session.completeModel(old, MODEL, new ModelBundle(MODEL),
                effects::incrementAndGet);
        fixture.session.failModel(old, MODEL, null, 900L,
                ignored -> effects.incrementAndGet());

        fixture.connection.set(new Object());
        fixture.client.runAll();

        assertFalse(fixture.session.isCurrent(old));
        assertNull(fixture.session.takeReady(MODEL));
        assertNull(fixture.session.cached(MODEL));
        assertEquals(100L, fixture.session.lastRequested(MODEL));
        assertTrue(fixture.session.hasLookup(MODEL));
        assertEquals(0, effects.get());
    }

    @Test
    void capturingANewConnectionClearsAllOldSessionStateIncludingAssemblies() {
        Fixture fixture = new Fixture();
        var old = fixture.ticket();
        fixture.installCache(old, cached("server-a", 1));
        fixture.session.completeModel(old, "example:ready", new ModelBundle("example:ready"),
                () -> { });
        fixture.client.runAll();
        fixture.session.requested(MODEL, 100L);
        fixture.session.rememberSource(MODEL, source(1));
        assertTrue(fixture.session.beginLookup(old, MODEL));
        fixture.assemblies.put(UUID.randomUUID(), new Object());

        fixture.connection.set(new Object());
        var current = fixture.session.capture("server-b");

        assertTrue(fixture.session.isCurrent(current));
        assertFalse(fixture.session.isCurrent(old));
        assertNull(fixture.session.cached(MODEL));
        assertNull(fixture.session.takeReady("example:ready"));
        assertNull(fixture.session.lastRequested(MODEL));
        assertNull(fixture.session.source(MODEL));
        assertFalse(fixture.session.hasLookup(MODEL));
        assertTrue(fixture.assemblies.isEmpty());
    }

    @Test
    void disconnectedConnectionRejectsCompletionAndCaptureClearsItsState() {
        Fixture fixture = new Fixture();
        var old = fixture.ticket();
        fixture.session.rememberSource(MODEL, source(1));
        AtomicInteger effects = new AtomicInteger();
        fixture.session.completeModel(old, MODEL, new ModelBundle(MODEL),
                effects::incrementAndGet);

        fixture.connection.set(null);
        fixture.client.runAll();
        assertEquals(0, effects.get());
        assertNull(fixture.session.capture("server-a"));
        assertNull(fixture.session.source(MODEL));
        assertNull(fixture.session.takeReady(MODEL));
    }

    @Test
    void failureDeletesOnlyItsCapturedCacheAndKeepsANewerCandidate() {
        Fixture fixture = new Fixture();
        var ticket = fixture.ticket();
        var failedCache = cached("server-a", 1);
        fixture.installCache(ticket, failedCache);
        var replacement = cached("server-a", 2);
        fixture.installCache(ticket, replacement);
        AtomicReference<ClientModelTransferSession.Cached> deletion = new AtomicReference<>();
        AtomicInteger retries = new AtomicInteger();

        fixture.session.failModel(ticket, MODEL, failedCache, 500L, captured -> {
            deletion.set(captured);
            assertEquals(500L, fixture.session.lastRequested(MODEL));
            retries.incrementAndGet();
        });
        assertNull(deletion.get());
        fixture.client.runAll();

        assertSame(failedCache, deletion.get());
        assertSame(replacement, fixture.session.cached(MODEL));
        assertEquals(1, retries.get());
    }

    @Test
    void currentFailureClearsItsCacheAndRunsRetryOnlyInsideTheCommit() {
        Fixture fixture = new Fixture();
        var ticket = fixture.ticket();
        var failedCache = cached("server-a", 1);
        fixture.installCache(ticket, failedCache);
        fixture.session.requested(MODEL, 100L);
        AtomicInteger effects = new AtomicInteger();

        fixture.session.failModel(ticket, MODEL, failedCache, 600L, captured -> {
            assertSame(failedCache, captured);
            assertNull(fixture.session.cached(MODEL));
            assertEquals(600L, fixture.session.lastRequested(MODEL));
            effects.incrementAndGet();
        });

        assertSame(failedCache, fixture.session.cached(MODEL));
        assertEquals(100L, fixture.session.lastRequested(MODEL));
        assertEquals(0, effects.get());
        fixture.client.runAll();
        assertEquals(1, effects.get());
    }

    @Test
    void queuedOldFailureCannotDeleteOrThrottleOrRetryInTheNewSession() {
        Fixture fixture = new Fixture();
        var old = fixture.ticket();
        var oldCache = cached("server-a", 1);
        fixture.installCache(old, oldCache);
        AtomicInteger effects = new AtomicInteger();
        fixture.session.failModel(old, MODEL, oldCache, 999L,
                ignored -> effects.incrementAndGet());

        fixture.session.clear();
        var current = fixture.ticket();
        fixture.session.requested(MODEL, 700L);
        assertTrue(fixture.session.beginLookup(current, MODEL));
        var newCache = cached("server-a", 2);
        fixture.session.completeLookup(current, MODEL, newCache, ignored -> { });
        fixture.client.runAll();

        assertSame(newCache, fixture.session.cached(MODEL));
        assertEquals(700L, fixture.session.lastRequested(MODEL));
        assertEquals(0, effects.get());
    }

    @Test
    void unavailableReturnsTheExactCachedEntryAndRetainsTheRetryDelay() {
        Fixture fixture = new Fixture();
        var ticket = fixture.ticket();
        var cached = cached("server-a", 1);
        fixture.installCache(ticket, cached);

        assertSame(cached, fixture.session.unavailable(MODEL, 800L));
        assertNull(fixture.session.cached(MODEL));
        assertFalse(fixture.session.requestDue(MODEL, 800L + RETRY_AFTER - 1L));
        assertTrue(fixture.session.requestDue(MODEL, 800L + RETRY_AFTER));
    }

    @Test
    void normalRetryAndPendingLookupLimitsRemainUnchanged() {
        Fixture fixture = new Fixture();
        var ticket = fixture.ticket();

        assertTrue(fixture.session.requestDue(MODEL, 1_000L));
        assertFalse(fixture.session.requestDue(MODEL, 1_000L + RETRY_AFTER - 1L));
        assertTrue(fixture.session.requestDue(MODEL, 1_000L + RETRY_AFTER));
        assertTrue(fixture.session.beginLookup(ticket, MODEL));
        assertFalse(fixture.session.beginLookup(ticket, MODEL));
        fixture.session.completeLookup(ticket, MODEL, null, ignored -> { });
        fixture.client.runAll();
        assertTrue(fixture.session.beginLookup(ticket, MODEL));
    }

    @Test
    void completedModelsKeepTheExistingReadyLimitWithoutRequiringDiskEntries() {
        Fixture fixture = new Fixture();
        var ticket = fixture.ticket();
        ModelBundle first = new ModelBundle("example:first");
        ModelBundle second = new ModelBundle("example:second");
        ModelBundle third = new ModelBundle("example:third");
        fixture.session.completeModel(ticket, first.modelId(), first, () -> { });
        fixture.session.completeModel(ticket, second.modelId(), second, () -> { });
        fixture.session.completeModel(ticket, third.modelId(), third, () -> { });
        fixture.client.runAll();

        assertNull(fixture.session.takeReady(first.modelId()));
        assertNull(fixture.session.takeReady(second.modelId()));
        assertSame(third, fixture.session.takeReady(third.modelId()));
    }

    @Test
    void anOldWorkerMayFinishItsCapturedDiskSaveButCannotPublishTheResult() {
        Fixture fixture = new Fixture();
        var old = fixture.ticket();
        ManualExecutor worker = new ManualExecutor();
        List<String> persistedNamespaces = new ArrayList<>();
        AtomicInteger notifications = new AtomicInteger();
        worker.execute(() -> {
            persistedNamespaces.add(old.serverIdentity());
            fixture.session.completeModel(old, MODEL, new ModelBundle(MODEL),
                    notifications::incrementAndGet);
        });

        fixture.session.clear();
        fixture.connection.set(new Object());
        fixture.session.capture("server-b");
        worker.runAll();
        fixture.client.runAll();

        assertEquals(List.of("server-a"), persistedNamespaces);
        assertNull(fixture.session.takeReady(MODEL));
        assertEquals(0, notifications.get());
    }

    private static ClientModelTransferSession.Cached cached(String server, int marker) {
        byte[] payload = new byte[]{(byte) marker};
        byte[] digest = ModelDiskCache.sha256(payload);
        return new ClientModelTransferSession.Cached(server,
                new ModelDiskCache.Entry(digest, digest, payload));
    }

    private static ClientModelTransferSession.RequestSource source(int id) {
        return new ClientModelTransferSession.RequestSource(id, new UUID(0L, id));
    }

    private static final class Fixture {
        final ManualExecutor client = new ManualExecutor();
        final AtomicReference<Object> connection = new AtomicReference<>(new Object());
        final Map<UUID, Object> assemblies = new HashMap<>();
        final ClientModelTransferSession session = new ClientModelTransferSession(
                client, connection::get, assemblies::clear, 2, RETRY_AFTER);

        ClientModelTransferSession.Ticket ticket() {
            return session.capture("server-a");
        }

        void installCache(ClientModelTransferSession.Ticket ticket,
                          ClientModelTransferSession.Cached cached) {
            assertTrue(session.beginLookup(ticket, MODEL));
            session.completeLookup(ticket, MODEL, cached, ignored -> { });
            client.runAll();
        }
    }

    private static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.addLast(command);
        }

        void runAll() {
            while (!tasks.isEmpty()) {
                tasks.removeFirst().run();
            }
        }
    }
}
