package net.okitsu.ysmepicfightcompat.network;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaidPreferenceAuthorizationTest {
    @Test
    void permitsOnlyTheCurrentOwnersExactPendingMaidRevision() {
        UUID owner = UUID.randomUUID();
        UUID maid = UUID.randomUUID();

        assertTrue(MaidPreferenceAuthorization.permits(
                owner, 14, maid, 9L,
                14, maid, owner, 9L, true));
        assertFalse(MaidPreferenceAuthorization.permits(
                UUID.randomUUID(), 14, maid, 9L,
                14, maid, owner, 9L, true));
        assertFalse(MaidPreferenceAuthorization.permits(
                owner, 15, maid, 9L,
                14, maid, owner, 9L, true));
        assertFalse(MaidPreferenceAuthorization.permits(
                owner, 14, UUID.randomUUID(), 9L,
                14, maid, owner, 9L, true));
        assertFalse(MaidPreferenceAuthorization.permits(
                owner, 14, maid, 8L,
                14, maid, owner, 9L, true));
        assertFalse(MaidPreferenceAuthorization.permits(
                owner, 14, maid, 9L,
                14, maid, owner, 9L, false));
    }
}
