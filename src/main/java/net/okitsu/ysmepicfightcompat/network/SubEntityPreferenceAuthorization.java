package net.okitsu.ysmepicfightcompat.network;

import java.util.UUID;

/** Pure identity/revision guard for owner-produced sub-entity decisions. */
public final class SubEntityPreferenceAuthorization {
    private SubEntityPreferenceAuthorization() {
    }

    public static boolean permits(
            UUID senderUuid,
            UUID expectedOwnerUuid,
            int requestedEntityId,
            UUID requestedEntityUuid,
            long requestedRevision,
            int actualEntityId,
            UUID actualEntityUuid,
            long actualRevision,
            boolean exactPendingQuery) {
        return senderUuid != null && senderUuid.equals(expectedOwnerUuid)
                && requestedEntityUuid != null && actualEntityUuid != null
                && requestedEntityId >= 0 && requestedEntityId == actualEntityId
                && requestedEntityUuid.equals(actualEntityUuid)
                && requestedRevision > 0L && requestedRevision == actualRevision
                && exactPendingQuery;
    }
}
