package net.okitsu.ysmepicfightcompat.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubEntityPreferenceBroadcasterTest {
    @Test
    void projectileRetainsFirstCapturedLaunchSource() {
        assertEquals("launch", SubEntityPreferenceBroadcaster.retainedSource(
                true, "launch", "owner-now-holds-something-else"));
        assertEquals("late-owner", SubEntityPreferenceBroadcaster.retainedSource(
                true, null, "late-owner"));
        assertNull(SubEntityPreferenceBroadcaster.retainedSource(
                true, null, null));
    }

    @Test
    void vehicleAlwaysTracksCurrentFirstPlayerPassenger() {
        assertEquals("new-rider", SubEntityPreferenceBroadcaster.retainedSource(
                false, "old-rider", "new-rider"));
        assertNull(SubEntityPreferenceBroadcaster.retainedSource(
                false, "old-rider", null));
    }

    @Test
    void sourceGraceExpiresOnlyAtTheConfiguredBoundary() {
        long captured = 200L;
        assertFalse(SubEntityPreferenceBroadcaster.sourceGraceExpired(
                captured, captured + SubEntityPreferenceBroadcaster.SOURCE_GRACE_TICKS - 1L));
        assertTrue(SubEntityPreferenceBroadcaster.sourceGraceExpired(
                captured, captured + SubEntityPreferenceBroadcaster.SOURCE_GRACE_TICKS));
        assertFalse(SubEntityPreferenceBroadcaster.sourceGraceExpired(
                Long.MIN_VALUE, Long.MAX_VALUE));
    }
}
