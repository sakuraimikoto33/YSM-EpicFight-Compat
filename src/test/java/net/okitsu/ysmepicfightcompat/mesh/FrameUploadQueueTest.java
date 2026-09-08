package net.okitsu.ysmepicfightcompat.mesh;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameUploadQueueTest {
    @Test
    void oneClaimCoversPreparationDecodeReadyAndUpload() {
        Fixture fixture = new Fixture(8, 128);
        var claim = fixture.queue.claim("texture");
        assertNotNull(claim);
        assertNull(fixture.queue.claim("texture"));
        FakeImage image = new FakeImage();
        fixture.queue.plan(claim, 30, () -> fixture.queue.complete(claim, image));
        assertNull(fixture.queue.claim("texture"));

        fixture.queue.startAvailable(fixture.workers::add);
        assertNull(fixture.queue.claim("texture"));
        fixture.runWorkers();
        assertNull(fixture.queue.claim("texture"), "Waiting for a frame must not trigger re-decode");

        fixture.queue.drainFrame(10, () -> 0L, value -> {
            assertNull(fixture.queue.claim("texture"));
            fixture.uploaded.add(value);
        });
        assertEquals(List.of(image), fixture.uploaded);
        assertEquals(0, image.closed, "The uploaded resource now belongs to the texture manager");
        assertEquals(0, fixture.queue.reservedBytes());
        assertNotNull(fixture.queue.claim("texture"));
    }

    @Test
    void budgetExhaustionLeavesTheRemainderUntilTheNextFrame() {
        Fixture fixture = new Fixture(8, 128);
        FakeImage first = fixture.plan("first", 4);
        FakeImage second = fixture.plan("second", 4);
        FakeImage third = fixture.plan("third", 4);
        fixture.queue.startAvailable(fixture.workers::add);
        fixture.runWorkers();
        AtomicLong clock = new AtomicLong(100);

        fixture.queue.drainFrame(10, clock::get, image -> {
            fixture.uploaded.add(image);
            clock.addAndGet(6);
        });

        assertEquals(List.of(first, second), fixture.uploaded);
        assertEquals(1, fixture.queue.outstanding());
        assertEquals(4, fixture.queue.reservedBytes());
        assertTrue(fixture.workers.isEmpty(), "Budget exhaustion must not schedule a continuation");

        fixture.queue.drainFrame(10, clock::get, fixture.uploaded::add);
        assertEquals(List.of(first, second, third), fixture.uploaded);
        assertEquals(0, fixture.queue.outstanding());
    }

    @Test
    void aSingleSlowUploadMayOverrunButDoesNotStartTheNextOne() {
        Fixture fixture = new Fixture(8, 128);
        FakeImage first = fixture.plan("first", 4);
        fixture.plan("second", 4);
        fixture.queue.startAvailable(fixture.workers::add);
        fixture.runWorkers();
        AtomicLong clock = new AtomicLong();

        fixture.queue.drainFrame(10, clock::get, image -> {
            fixture.uploaded.add(image);
            clock.addAndGet(25);
        });

        assertEquals(List.of(first), fixture.uploaded);
        assertEquals(1, fixture.queue.outstanding());
    }

    @Test
    void theExactDeadlineStopsBeforeAnotherUpload() {
        Fixture fixture = new Fixture(8, 128);
        fixture.plan("first", 4);
        fixture.plan("second", 4);
        fixture.queue.startAvailable(fixture.workers::add);
        fixture.runWorkers();
        AtomicLong clock = new AtomicLong();

        fixture.queue.drainFrame(10, clock::get, image -> {
            fixture.uploaded.add(image);
            clock.addAndGet(10);
        });

        assertEquals(1, fixture.uploaded.size());
        assertEquals(1, fixture.queue.outstanding());
    }

    @Test
    void byteAdmissionDoesNotBlockOrStartWorkersUntilCapacityIsAvailable() {
        Fixture fixture = new Fixture(8, 128);
        fixture.plan("first", 100);
        fixture.plan("second", 40);

        fixture.queue.startAvailable(fixture.workers::add);
        assertEquals(1, fixture.workers.size());
        assertEquals(100, fixture.queue.reservedBytes());
        fixture.runWorkers();
        fixture.queue.startAvailable(fixture.workers::add);
        assertTrue(fixture.workers.isEmpty(), "A ready image still occupies its reservation");

        fixture.queue.drainFrame(10, () -> 0L, fixture.uploaded::add);
        fixture.queue.startAvailable(fixture.workers::add);
        assertEquals(1, fixture.workers.size());
        assertEquals(40, fixture.queue.reservedBytes());
    }

    @Test
    void oversizedGroupsRunExclusivelyWithoutRejectingExistingLargeTextures() {
        Fixture fixture = new Fixture(8, 128);
        fixture.plan("small", 20);
        fixture.plan("large", 300);
        fixture.plan("later", 10);
        fixture.queue.startAvailable(fixture.workers::add);
        assertEquals(1, fixture.workers.size());
        fixture.runWorkers();
        fixture.queue.drainFrame(10, () -> 0L, fixture.uploaded::add);

        fixture.queue.startAvailable(fixture.workers::add);
        assertEquals(1, fixture.workers.size());
        assertEquals(300, fixture.queue.reservedBytes());
        fixture.runWorkers();
        fixture.queue.startAvailable(fixture.workers::add);
        assertTrue(fixture.workers.isEmpty());

        fixture.queue.drainFrame(10, () -> 0L, fixture.uploaded::add);
        fixture.queue.startAvailable(fixture.workers::add);
        assertEquals(1, fixture.workers.size());
        assertEquals(10, fixture.queue.reservedBytes());
    }

    @Test
    void theCountLimitIncludesMetadataAndWaitingDecodePlans() {
        Fixture fixture = new Fixture(2, 128);
        var preparing = fixture.queue.claim("preparing");
        fixture.plan("planned", 200);

        assertNull(fixture.queue.claim("third"));
        assertEquals(2, fixture.queue.outstanding());
        fixture.queue.fail(preparing);
        assertNotNull(fixture.queue.claim("third"));
    }

    @Test
    void reloadClosesReadyImagesExactlyOnceAndDropsUnstartedPlans() {
        Fixture fixture = new Fixture(8, 128);
        FakeImage ready = fixture.plan("ready", 100);
        FakeImage waiting = fixture.plan("waiting", 100);
        fixture.queue.startAvailable(fixture.workers::add);
        fixture.runWorkers();

        fixture.queue.clear();
        fixture.queue.clear();
        fixture.queue.startAvailable(fixture.workers::add);
        fixture.queue.drainFrame(10, () -> 0L, fixture.uploaded::add);

        assertEquals(1, ready.closed);
        assertEquals(0, waiting.closed, "The unstarted plan never allocated a decoded image");
        assertEquals(0, fixture.queue.outstanding());
        assertEquals(0, fixture.queue.reservedBytes());
        assertTrue(fixture.workers.isEmpty());
        assertTrue(fixture.uploaded.isEmpty());
    }

    @Test
    void aLateDecodeAfterReloadClosesItselfWithoutRemovingTheNewClaim() {
        Fixture fixture = new Fixture(8, 128);
        FakeImage old = fixture.plan("same", 100);
        fixture.queue.startAvailable(fixture.workers::add);
        fixture.queue.clear();
        var fresh = fixture.queue.claim("same");
        assertNotNull(fresh);
        assertEquals(100, fixture.queue.reservedBytes(), "The old worker still owns native memory");

        fixture.runWorkers();

        assertEquals(1, old.closed);
        assertTrue(fixture.queue.isCurrent(fresh));
        assertNull(fixture.queue.claim("same"));
        assertEquals(1, fixture.queue.outstanding());
        assertEquals(0, fixture.queue.reservedBytes());
    }

    @Test
    void aLateMetadataPlanOrFailureCannotRetireTheNewSourceClaim() {
        Fixture fixture = new Fixture(8, 128);
        var oldPlan = fixture.queue.claim("planned");
        var oldFailure = fixture.queue.claim("failed");
        fixture.queue.clear();
        var freshPlan = fixture.queue.claim("planned");
        var freshFailure = fixture.queue.claim("failed");

        fixture.queue.plan(oldPlan, 10, () -> { throw new AssertionError("Stale decoder ran"); });
        fixture.queue.fail(oldFailure);
        fixture.queue.startAvailable(fixture.workers::add);

        assertTrue(fixture.workers.isEmpty());
        assertTrue(fixture.queue.isCurrent(freshPlan));
        assertTrue(fixture.queue.isCurrent(freshFailure));
        assertEquals(2, fixture.queue.outstanding());
    }

    @Test
    void repeatedReloadsCannotBypassTheOutstandingWorkerLimit() {
        Fixture fixture = new Fixture(2, 128);
        fixture.plan("old", 100);
        fixture.queue.startAvailable(fixture.workers::add);
        fixture.queue.clear();
        var preparing = fixture.queue.claim("fresh");
        fixture.queue.clear();

        assertNull(fixture.queue.claim("third"));
        assertEquals(2, fixture.queue.outstanding());
        assertEquals(100, fixture.queue.reservedBytes());
        fixture.queue.fail(preparing);
        fixture.runWorkers();
        assertEquals(0, fixture.queue.outstanding());
        assertEquals(0, fixture.queue.reservedBytes());
    }

    @Test
    void cancellingOneTextureDoesNotDisturbAnotherReadyTexture() {
        Fixture fixture = new Fixture(8, 128);
        FakeImage cancelled = fixture.plan("cancelled", 20);
        FakeImage retained = fixture.plan("retained", 20);
        fixture.queue.startAvailable(fixture.workers::add);
        fixture.runWorkers();

        fixture.queue.cancel("cancelled");
        fixture.queue.cancel("cancelled");
        fixture.queue.drainFrame(10, () -> 0L, fixture.uploaded::add);

        assertEquals(1, cancelled.closed);
        assertEquals(0, retained.closed);
        assertEquals(List.of(retained), fixture.uploaded);
    }

    @Test
    void submissionAndUploadFailuresReleaseTheirReservation() {
        Fixture rejected = new Fixture(8, 128);
        rejected.plan("rejected", 100);
        assertThrows(RejectedExecutionException.class, () -> rejected.queue.startAvailable(task -> {
            throw new RejectedExecutionException();
        }));
        assertEquals(0, rejected.queue.outstanding());
        assertEquals(0, rejected.queue.reservedBytes());

        Fixture failed = new Fixture(8, 128);
        FakeImage image = failed.plan("failed", 100);
        failed.queue.startAvailable(failed.workers::add);
        failed.runWorkers();
        assertThrows(IllegalStateException.class,
                () -> failed.queue.drainFrame(10, () -> 0L, value -> {
                    value.close();
                    throw new IllegalStateException("Simulated upload failure");
                }));
        assertEquals(1, image.closed);
        assertEquals(0, failed.queue.outstanding());
        assertEquals(0, failed.queue.reservedBytes());
    }

    @Test
    void cancellationDuringUploadDoesNotDisposeTheConsumersImage() {
        Fixture fixture = new Fixture(8, 128);
        FakeImage image = fixture.plan("uploading", 100);
        fixture.queue.startAvailable(fixture.workers::add);
        fixture.runWorkers();

        fixture.queue.drainFrame(10, () -> 0L, value -> {
            fixture.queue.clear();
            assertEquals(0, value.closed);
            assertEquals(100, fixture.queue.reservedBytes());
            fixture.uploaded.add(value);
        });

        assertEquals(List.of(image), fixture.uploaded);
        assertEquals(0, fixture.queue.reservedBytes());
    }

    private static final class Fixture {
        private final FrameUploadQueue<String, FakeImage> queue;
        private final List<Runnable> workers = new ArrayList<>();
        private final List<FakeImage> uploaded = new ArrayList<>();

        private Fixture(int maxClaims, long target) {
            queue = new FrameUploadQueue<>(maxClaims, target, FakeImage::close);
        }

        private FakeImage plan(String key, long bytes) {
            var claim = queue.claim(key);
            assertNotNull(claim);
            FakeImage image = new FakeImage();
            queue.plan(claim, bytes, () -> queue.complete(claim, image));
            return image;
        }

        private void runWorkers() {
            List<Runnable> current = List.copyOf(workers);
            workers.clear();
            current.forEach(Runnable::run);
        }
    }

    private static final class FakeImage {
        private int closed;

        private void close() {
            assertFalse(closed > 0, "A decoded image must be disposed exactly once");
            closed++;
        }
    }
}
