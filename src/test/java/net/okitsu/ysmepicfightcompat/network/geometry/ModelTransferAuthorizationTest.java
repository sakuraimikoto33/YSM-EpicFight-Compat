package net.okitsu.ysmepicfightcompat.network.geometry;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelTransferAuthorizationTest {
    @Test
    void permitsSelfOrTrackedEntityOnlyWhenIdentityAndSelectionStillMatch() {
        UUID entityUuid = UUID.randomUUID();

        assertTrue(ModelTransferAuthorization.permits(
                7, entityUuid, 7, entityUuid,
                true, false, "model/a", "model/a"));
        assertTrue(ModelTransferAuthorization.permits(
                7, entityUuid, 7, entityUuid,
                false, true, "model/a", "model/a"));
        assertFalse(ModelTransferAuthorization.permits(
                7, entityUuid, 7, entityUuid,
                false, false, "model/a", "model/a"));
        assertFalse(ModelTransferAuthorization.permits(
                7, UUID.randomUUID(), 7, entityUuid,
                false, true, "model/a", "model/a"));
        assertFalse(ModelTransferAuthorization.permits(
                7, entityUuid, 8, entityUuid,
                false, true, "model/a", "model/a"));
        assertFalse(ModelTransferAuthorization.permits(
                7, entityUuid, 7, entityUuid,
                false, true, "model/a", "model/b"));
        assertFalse(ModelTransferAuthorization.permits(
                7, entityUuid, 7, entityUuid,
                false, true, "model/a", null));
    }
}
