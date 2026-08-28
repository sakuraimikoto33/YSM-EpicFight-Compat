package net.okitsu.ysmepicfightcompat.network;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class RemoteSubEntityModelPreferencesTest {
    private static final ResourceLocation ARROW =
            ResourceLocation.fromNamespaceAndPath("minecraft", "arrow");
    private static final ResourceLocation BOAT =
            ResourceLocation.fromNamespaceAndPath("minecraft", "boat");
    @AfterEach
    void clear() {
        RemoteSubEntityModelPreferences.beginConnection();
    }

    @Test
    void rejectsStaleSnapshotsAndClearsEntityAndConnectionState() {
        UUID entity = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        SubEntityModelDisplayState current = state(entity, owner, 8L, true);
        SubEntityModelDisplayState stale = state(entity, owner, 7L, false);

        RemoteSubEntityModelPreferences.accept(current);
        RemoteSubEntityModelPreferences.accept(stale);
        assertSame(current, RemoteSubEntityModelPreferences.find(entity));

        RemoteSubEntityModelPreferences.remove(entity);
        assertNull(RemoteSubEntityModelPreferences.find(entity));
        RemoteSubEntityModelPreferences.accept(current);
        RemoteSubEntityModelPreferences.beginConnection();
        assertNull(RemoteSubEntityModelPreferences.find(entity));
    }

    @Test
    void rejectsEveryMismatchedLiveEntityFingerprint() {
        UUID entity = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        SubEntityModelDisplayState state = state(entity, owner, 8L, true);
        RemoteSubEntityModelPreferences.Decision expected =
                new RemoteSubEntityModelPreferences.Decision(true, true);

        assertEquals(expected, RemoteSubEntityModelPreferences.resolve(
                state, 14, entity, owner, ARROW, SubEntityModelKind.PROJECTILE));
        assertEquals(RemoteSubEntityModelPreferences.Decision.UNKNOWN,
                RemoteSubEntityModelPreferences.resolve(
                        state, 15, entity, owner, ARROW,
                        SubEntityModelKind.PROJECTILE));
        assertEquals(RemoteSubEntityModelPreferences.Decision.UNKNOWN,
                RemoteSubEntityModelPreferences.resolve(
                        state, 14, UUID.randomUUID(), owner, ARROW,
                        SubEntityModelKind.PROJECTILE));
        assertEquals(RemoteSubEntityModelPreferences.Decision.UNKNOWN,
                RemoteSubEntityModelPreferences.resolve(
                        state, 14, entity, UUID.randomUUID(), ARROW,
                        SubEntityModelKind.PROJECTILE));
        assertEquals(RemoteSubEntityModelPreferences.Decision.UNKNOWN,
                RemoteSubEntityModelPreferences.resolve(
                        state, 14, entity, owner,
                        ResourceLocation.fromNamespaceAndPath("minecraft", "boat"),
                        SubEntityModelKind.PROJECTILE));
        assertEquals(RemoteSubEntityModelPreferences.Decision.UNKNOWN,
                RemoteSubEntityModelPreferences.resolve(
                        state, 14, entity, owner, ARROW,
                        SubEntityModelKind.VEHICLE));
    }

    @Test
    void launchSnapshotDoesNotRequireATrackedOwnerEntity() {
        UUID entity = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        SubEntityModelDisplayState state = state(entity, owner, 8L, true);

        assertSame(state, RemoteSubEntityModelPreferences.launchSnapshot(
                state, 14, entity, ARROW, SubEntityModelKind.PROJECTILE));
        assertNull(RemoteSubEntityModelPreferences.launchSnapshot(
                state, 15, entity, ARROW, SubEntityModelKind.PROJECTILE));
        assertNull(RemoteSubEntityModelPreferences.launchSnapshot(
                state, 14, entity, ARROW, SubEntityModelKind.VEHICLE));
    }

    @Test
    void vehicleSnapshotSurvivesWithoutALivePlayerPassenger() {
        UUID entity = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        SubEntityModelDisplayState state = vehicleState(
                entity, owner, 8L, true, false);

        assertSame(state, RemoteSubEntityModelPreferences.vehicleSnapshot(
                state, 14, entity, BOAT));
        assertNull(RemoteSubEntityModelPreferences.vehicleSnapshot(
                state, 15, entity, BOAT));
        assertNull(RemoteSubEntityModelPreferences.vehicleSnapshot(
                state, 14, UUID.randomUUID(), BOAT));
        assertNull(RemoteSubEntityModelPreferences.vehicleSnapshot(
                state, 14, entity, ARROW));
        assertNull(RemoteSubEntityModelPreferences.vehicleSnapshot(
                state(entity, owner, 9L, false), 14, entity, ARROW));
    }

    @Test
    void boundsSnapshotsAndEvictsTheLeastRecentlyAccessedEntry() {
        UUID owner = UUID.randomUUID();
        UUID retained = UUID.randomUUID();
        UUID evicted = null;
        RemoteSubEntityModelPreferences.accept(state(retained, owner, 1L, true));
        for (int index = 0;
             index < RemoteSubEntityModelPreferences.MAX_ENTRIES - 1; index++) {
            UUID entity = UUID.randomUUID();
            if (index == 0) {
                evicted = entity;
            }
            RemoteSubEntityModelPreferences.accept(
                    state(entity, owner, index + 2L, true));
        }

        assertNotNull(RemoteSubEntityModelPreferences.find(retained));
        RemoteSubEntityModelPreferences.accept(
                state(UUID.randomUUID(), owner,
                        RemoteSubEntityModelPreferences.MAX_ENTRIES + 1L, true));

        assertNotNull(RemoteSubEntityModelPreferences.find(retained));
        assertNull(RemoteSubEntityModelPreferences.find(evicted));
    }

    private static SubEntityModelDisplayState state(
            UUID entity, UUID owner, long revision, boolean ysm) {
        return new SubEntityModelDisplayState(
                14, entity, owner, revision, SubEntityModelKind.PROJECTILE,
                ARROW, true, true, ysm);
    }

    private static SubEntityModelDisplayState vehicleState(
            UUID entity, UUID owner, long revision,
            boolean epicFightRendering, boolean ysm) {
        return new SubEntityModelDisplayState(
                14, entity, owner, revision, SubEntityModelKind.VEHICLE,
                BOAT, epicFightRendering, true, ysm);
    }
}
