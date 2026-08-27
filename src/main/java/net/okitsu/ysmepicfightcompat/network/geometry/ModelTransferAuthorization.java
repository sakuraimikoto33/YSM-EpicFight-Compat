package net.okitsu.ysmepicfightcompat.network.geometry;

import java.util.UUID;

/** Pure identity/tracking/selection boundary shared by request and delivery checks. */
final class ModelTransferAuthorization {
    private ModelTransferAuthorization() {
    }

    static boolean permits(int requestedEntityId, UUID requestedEntityUuid,
                           int actualEntityId, UUID actualEntityUuid,
                           boolean self, boolean tracked,
                           String requestedModelId, String selectedModelId) {
        return requestedEntityId >= 0
                && requestedEntityId == actualEntityId
                && requestedEntityUuid != null
                && requestedEntityUuid.equals(actualEntityUuid)
                && (self || tracked)
                && requestedModelId != null && !requestedModelId.isBlank()
                && requestedModelId.equals(selectedModelId);
    }
}
