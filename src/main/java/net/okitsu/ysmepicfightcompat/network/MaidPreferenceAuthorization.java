package net.okitsu.ysmepicfightcompat.network;

import java.util.UUID;

/** Pure identity/revision guard for owner-produced maid display decisions. */
public final class MaidPreferenceAuthorization {
    private MaidPreferenceAuthorization() {
    }

    public static boolean permits(
            UUID senderUuid,
            int requestedEntityId,
            UUID requestedEntityUuid,
            long requestedRevision,
            int actualEntityId,
            UUID actualEntityUuid,
            UUID actualOwnerUuid,
            long actualRevision,
            boolean stateMatchesSource) {
        return senderUuid != null && requestedEntityUuid != null
                && actualEntityUuid != null && actualOwnerUuid != null
                && requestedEntityId >= 0 && requestedEntityId == actualEntityId
                && requestedEntityUuid.equals(actualEntityUuid)
                && senderUuid.equals(actualOwnerUuid)
                && requestedRevision > 0L && requestedRevision == actualRevision
                && stateMatchesSource;
    }
}
