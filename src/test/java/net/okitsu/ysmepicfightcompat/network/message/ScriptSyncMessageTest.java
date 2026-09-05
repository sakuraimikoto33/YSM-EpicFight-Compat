package net.okitsu.ysmepicfightcompat.network.message;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScriptSyncMessageTest {
    @Test
    void roundTripsOnlyNumericRequestDataAndServerOwnedSnapshotIdentity() {
        ScriptSyncRequestMessage request = new ScriptSyncRequestMessage("custom/model",
                new double[]{2, -3, 0.25});
        ScriptSyncSnapshotMessage snapshot = new ScriptSyncSnapshotMessage(42,
                UUID.randomUUID(), 17L, request.modelId(), request.arguments());
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ScriptSyncRequestMessage.write(request, buffer);
            ScriptSyncRequestMessage decodedRequest = ScriptSyncRequestMessage.read(buffer);
            assertEquals(request.modelId(), decodedRequest.modelId());
            assertArrayEquals(request.arguments(), decodedRequest.arguments());
            assertEquals(0, buffer.readableBytes());

            buffer.clear();
            ScriptSyncSnapshotMessage.write(snapshot, buffer);
            ScriptSyncSnapshotMessage decoded = ScriptSyncSnapshotMessage.read(buffer);
            assertEquals(snapshot.entityId(), decoded.entityId());
            assertEquals(snapshot.entityUuid(), decoded.entityUuid());
            assertEquals(snapshot.sequence(), decoded.sequence());
            assertEquals(snapshot.modelId(), decoded.modelId());
            assertArrayEquals(snapshot.arguments(), decoded.arguments());
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void packetConstructorsAndAccessorsCannotShareMutableCallerArrays() {
        double[] values = {4.0D};
        ScriptSyncRequestMessage request = new ScriptSyncRequestMessage("model", values);
        ScriptSyncSnapshotMessage snapshot = new ScriptSyncSnapshotMessage(1,
                UUID.randomUUID(), 1L, "model", values);
        values[0] = 9.0D;
        request.arguments()[0] = 8.0D;
        snapshot.arguments()[0] = 7.0D;
        assertEquals(4.0D, request.arguments()[0]);
        assertEquals(4.0D, snapshot.arguments()[0]);
        assertThrows(IllegalArgumentException.class, () -> new ScriptSyncSnapshotMessage(
                -1, UUID.randomUUID(), 1L, "model", values));
        assertThrows(IllegalArgumentException.class, () -> new ScriptSyncSnapshotMessage(
                1, UUID.randomUUID(), 0L, "model", values));
    }

    @Test
    void rejectsOversizedTruncatedAndNonFiniteWirePayloadBeforeExecution() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeUtf("model");
            buffer.writeVarInt(Integer.MAX_VALUE);
            assertThrows(IllegalArgumentException.class, () -> ScriptSyncRequestMessage.read(buffer));
            buffer.clear();
            buffer.writeUtf("model");
            buffer.writeVarInt(-1);
            assertThrows(IllegalArgumentException.class, () -> ScriptSyncRequestMessage.read(buffer));
            buffer.clear();
            buffer.writeUtf("model");
            buffer.writeVarInt(2);
            buffer.writeDouble(1.0D);
            assertThrows(IllegalArgumentException.class, () -> ScriptSyncRequestMessage.read(buffer));
            buffer.clear();
            buffer.writeUtf("model");
            buffer.writeVarInt(1);
            buffer.writeDouble(Double.NaN);
            assertThrows(IllegalArgumentException.class, () -> ScriptSyncRequestMessage.read(buffer));
        } finally {
            buffer.release();
        }
    }
}
