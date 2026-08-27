package net.okitsu.ysmepicfightcompat.network.message;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.okitsu.ysmepicfightcompat.animation.MovementAnimationType;
import net.okitsu.ysmepicfightcompat.network.HeldItemModelDisplayState;
import net.okitsu.ysmepicfightcompat.network.MaidPreferenceDisplayState;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MaidPreferenceMessageTest {
    private static final ResourceLocation SWORD =
            ResourceLocation.fromNamespaceAndPath("minecraft", "diamond_sword");
    private static final ResourceLocation AIR =
            ResourceLocation.fromNamespaceAndPath("minecraft", "air");

    @Test
    void roundTripsOpaqueOwnerEpochAndBoundedQueryResponse() {
        UUID query = UUID.randomUUID();
        UUID entity = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID heldEpoch = UUID.randomUUID();
        UUID movementEpoch = UUID.randomUUID();
        OwnerPreferenceEpochMessage epochMessage =
                new OwnerPreferenceEpochMessage(heldEpoch, movementEpoch);
        MaidPreferenceQueryMessage request = new MaidPreferenceQueryMessage(
                query, 31, entity, owner, heldEpoch, 44L,
                "wine_fox/21_saint", SWORD, AIR);
        MaidPreferenceUpdateMessage response = new MaidPreferenceUpdateMessage(
                query, 31, entity, heldEpoch, 44L,
                new HeldItemModelDisplayState(true, false, false, true));
        MaidMovementPreferenceQueryMessage movementRequest =
                new MaidMovementPreferenceQueryMessage(
                        UUID.randomUUID(), 31, entity, owner, movementEpoch, 45L,
                        "wine_fox/21_saint", MovementAnimationType.RUN);
        MaidMovementPreferenceUpdateMessage movementResponse =
                new MaidMovementPreferenceUpdateMessage(
                        movementRequest.queryId(), 31, entity, movementEpoch,
                        45L, true);

        FriendlyByteBuf epochBuffer = buffer();
        OwnerPreferenceEpochMessage.write(epochMessage, epochBuffer);
        assertEquals(epochMessage, OwnerPreferenceEpochMessage.read(epochBuffer));

        FriendlyByteBuf requestBuffer = buffer();
        MaidPreferenceQueryMessage.write(request, requestBuffer);
        assertEquals(request, MaidPreferenceQueryMessage.read(requestBuffer));

        FriendlyByteBuf responseBuffer = buffer();
        MaidPreferenceUpdateMessage.write(response, responseBuffer);
        assertEquals(response, MaidPreferenceUpdateMessage.read(responseBuffer));

        FriendlyByteBuf movementRequestBuffer = buffer();
        MaidMovementPreferenceQueryMessage.write(
                movementRequest, movementRequestBuffer);
        assertEquals(movementRequest,
                MaidMovementPreferenceQueryMessage.read(movementRequestBuffer));

        FriendlyByteBuf movementResponseBuffer = buffer();
        MaidMovementPreferenceUpdateMessage.write(
                movementResponse, movementResponseBuffer);
        assertEquals(movementResponse,
                MaidMovementPreferenceUpdateMessage.read(movementResponseBuffer));
    }

    @Test
    void roundTripsFingerprintGuardedSnapshotIncludingNoMovement() {
        MaidPreferenceDisplayState state = new MaidPreferenceDisplayState(
                UUID.randomUUID(), UUID.randomUUID(), 7L,
                "wine_fox/05_magical", SWORD, AIR, null,
                new HeldItemModelDisplayState(true, false, true, false), true);
        MaidPreferenceSnapshotMessage message =
                new MaidPreferenceSnapshotMessage(state);

        FriendlyByteBuf buffer = buffer();
        MaidPreferenceSnapshotMessage.write(message, buffer);
        assertEquals(message, MaidPreferenceSnapshotMessage.read(buffer));
        assertEquals(false, message.state().ysmMovement());
    }

    @Test
    void rejectsUnknownMovementOrdinalsInQueriesAndSnapshots() {
        MaidMovementPreferenceQueryMessage request =
                new MaidMovementPreferenceQueryMessage(
                UUID.randomUUID(), 2, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 3L, "wine_fox/21_saint",
                MovementAnimationType.WALK);
        FriendlyByteBuf requestBuffer = buffer();
        MaidMovementPreferenceQueryMessage.write(request, requestBuffer);
        requestBuffer.setByte(requestBuffer.writerIndex() - 1, 127);
        assertThrows(IllegalArgumentException.class,
                () -> MaidMovementPreferenceQueryMessage.read(requestBuffer));

        MaidPreferenceDisplayState state = new MaidPreferenceDisplayState(
                UUID.randomUUID(), UUID.randomUUID(), 5L,
                "wine_fox/21_saint", SWORD, AIR, MovementAnimationType.WALK,
                HeldItemModelDisplayState.UNKNOWN, false);
        MaidPreferenceSnapshotMessage snapshot =
                new MaidPreferenceSnapshotMessage(state);
        FriendlyByteBuf snapshotBuffer = buffer();
        MaidPreferenceSnapshotMessage.write(snapshot, snapshotBuffer);
        int movementOffset = snapshotBuffer.writerIndex() - 6;
        snapshotBuffer.setByte(movementOffset, 127);
        assertThrows(IllegalArgumentException.class,
                () -> MaidPreferenceSnapshotMessage.read(snapshotBuffer));
    }

    private static FriendlyByteBuf buffer() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }
}
