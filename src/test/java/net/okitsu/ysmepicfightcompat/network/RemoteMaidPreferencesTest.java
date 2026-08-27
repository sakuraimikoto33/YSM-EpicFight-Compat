package net.okitsu.ysmepicfightcompat.network;

import net.minecraft.resources.ResourceLocation;
import net.okitsu.ysmepicfightcompat.animation.MovementAnimationType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteMaidPreferencesTest {
    private static final ResourceLocation SWORD =
            ResourceLocation.fromNamespaceAndPath("minecraft", "diamond_sword");
    private static final ResourceLocation AIR =
            ResourceLocation.fromNamespaceAndPath("minecraft", "air");

    @BeforeEach
    void initialize() {
        RemoteMaidPreferences.beginConnection();
    }

    @AfterEach
    void reset() {
        RemoteMaidPreferences.beginConnection();
    }

    @Test
    void fingerprintsEntityOwnerModelAndBothHeldItems() {
        UUID maid = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        MaidPreferenceDisplayState state = state(
                maid, owner, 6L, "wine_fox/21_saint");

        assertTrue(RemoteMaidPreferences.matchesHeld(state, maid, owner,
                "WINE_FOX/21_SAINT", SWORD, AIR));
        assertFalse(RemoteMaidPreferences.matchesHeld(state, UUID.randomUUID(), owner,
                "wine_fox/21_saint", SWORD, AIR));
        assertFalse(RemoteMaidPreferences.matchesHeld(state, maid, UUID.randomUUID(),
                "wine_fox/21_saint", SWORD, AIR));
        assertFalse(RemoteMaidPreferences.matchesHeld(state, maid, owner,
                "wine_fox/05_magical", SWORD, AIR));
        assertFalse(RemoteMaidPreferences.matchesHeld(state, maid, owner,
                "wine_fox/21_saint", AIR, AIR));
        assertFalse(RemoteMaidPreferences.matchesHeld(state, maid, owner,
                "wine_fox/21_saint", SWORD, SWORD));

        assertTrue(RemoteMaidPreferences.matchesMovement(state, maid, owner,
                "wine_fox/21_saint"));
        assertTrue(RemoteMaidPreferences.matchesMovement(state, maid, owner,
                "WINE_FOX/21_SAINT"));
        assertFalse(RemoteMaidPreferences.matchesMovement(state,
                UUID.randomUUID(), owner, "wine_fox/21_saint"));
        assertFalse(RemoteMaidPreferences.matchesMovement(state, maid,
                UUID.randomUUID(), "wine_fox/21_saint"));
        assertFalse(RemoteMaidPreferences.matchesMovement(state, maid, owner,
                "wine_fox/05_magical"));
    }

    @Test
    void ignoresOlderSnapshotsAndClearsSessionState() {
        UUID maid = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        MaidPreferenceDisplayState current = state(
                maid, owner, 12L, "wine_fox/21_saint");
        MaidPreferenceDisplayState stale = state(
                maid, owner, 11L, "wine_fox/05_magical");
        MaidPreferenceDisplayState equalRevisionRollback =
                new MaidPreferenceDisplayState(
                        maid, owner, 12L, "wine_fox/21_saint", SWORD, AIR,
                        MovementAnimationType.RUN,
                        HeldItemModelDisplayState.UNKNOWN, false);

        RemoteMaidPreferences.accept(current);
        RemoteMaidPreferences.accept(stale);
        RemoteMaidPreferences.accept(equalRevisionRollback);
        assertSame(current, RemoteMaidPreferences.find(maid));

        RemoteMaidPreferences.remove(maid);
        assertNull(RemoteMaidPreferences.find(maid));
        RemoteMaidPreferences.accept(current);
        RemoteMaidPreferences.beginConnection();
        assertNull(RemoteMaidPreferences.find(maid));
    }

    private static MaidPreferenceDisplayState state(
            UUID maid, UUID owner, long revision, String modelId) {
        return new MaidPreferenceDisplayState(
                maid, owner, revision, modelId, SWORD, AIR,
                MovementAnimationType.RUN,
                new HeldItemModelDisplayState(true, false, true, false), true);
    }
}
