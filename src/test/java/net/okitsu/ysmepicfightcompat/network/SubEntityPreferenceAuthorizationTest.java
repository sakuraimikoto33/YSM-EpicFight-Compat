package net.okitsu.ysmepicfightcompat.network;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubEntityPreferenceAuthorizationTest {
    @Test
    void requiresOwnerEntityRevisionAndExactPendingRelation() {
        UUID owner = UUID.randomUUID();
        UUID entity = UUID.randomUUID();
        assertTrue(SubEntityPreferenceAuthorization.permits(
                owner, owner, 12, entity, 19L,
                12, entity, 19L, true));
        assertFalse(SubEntityPreferenceAuthorization.permits(
                UUID.randomUUID(), owner, 12, entity, 19L,
                12, entity, 19L, true));
        assertFalse(SubEntityPreferenceAuthorization.permits(
                owner, owner, 13, entity, 19L,
                12, entity, 19L, true));
        assertFalse(SubEntityPreferenceAuthorization.permits(
                owner, owner, 12, UUID.randomUUID(), 19L,
                12, entity, 19L, true));
        assertFalse(SubEntityPreferenceAuthorization.permits(
                owner, owner, 12, entity, 18L,
                12, entity, 19L, true));
        assertFalse(SubEntityPreferenceAuthorization.permits(
                owner, owner, 12, entity, 19L,
                12, entity, 19L, false));
    }
}
