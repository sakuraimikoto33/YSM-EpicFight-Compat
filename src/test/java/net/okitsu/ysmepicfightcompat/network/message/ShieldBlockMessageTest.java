package net.okitsu.ysmepicfightcompat.network.message;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShieldBlockMessageTest {
    private static final UUID ENTITY_UUID = new UUID(123L, 456L);

    @Test
    void roundTripsOnlyTheVarIntIdAndUuidIncludingZeroAndMaximumIds() {
        for (int id : new int[]{0, 127, 128, Integer.MAX_VALUE}) {
            ShieldBlockMessage original = new ShieldBlockMessage(id, ENTITY_UUID);
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                ShieldBlockMessage.write(original, buffer);
                int idBytes = id < 128 ? 1 : id == 128 ? 2 : 5;
                assertEquals(idBytes + 16, buffer.readableBytes());
                assertEquals(original, ShieldBlockMessage.read(buffer));
                assertEquals(0, buffer.readableBytes());
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void zeroUuidIsAValidIdentityButNullAndNegativeIdsAreNot() {
        assertEquals(new UUID(0L, 0L), new ShieldBlockMessage(0, new UUID(0L, 0L)).entityUuid());
        assertThrows(IllegalArgumentException.class,
                () -> new ShieldBlockMessage(-1, ENTITY_UUID));
        assertThrows(IllegalArgumentException.class,
                () -> new ShieldBlockMessage(Integer.MIN_VALUE, ENTITY_UUID));
        assertThrows(IllegalArgumentException.class,
                () -> new ShieldBlockMessage(0, null));
    }

    @Test
    void bothIdAndUuidMustMatchTheResolvedClientEntity() {
        ShieldBlockMessage message = new ShieldBlockMessage(7, ENTITY_UUID);

        assertTrue(message.matchesIdentity(7, new UUID(123L, 456L)));
        assertFalse(message.matchesIdentity(8, ENTITY_UUID));
        assertFalse(message.matchesIdentity(-1, ENTITY_UUID));
        assertFalse(message.matchesIdentity(7, new UUID(123L, 457L)));
        assertFalse(message.matchesIdentity(7, null));
        assertFalse(message.matchesIdentity(8, new UUID(123L, 457L)));
    }

    @Test
    void negativeWireIdsAreRejected() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(-1);
            buffer.writeUUID(ENTITY_UUID);
            assertThrows(IllegalArgumentException.class, () -> ShieldBlockMessage.read(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void truncatedIdentityPayloadsAreRejected() {
        for (int uuidBytes = 0; uuidBytes < 16; uuidBytes++) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                buffer.writeVarInt(0);
                buffer.writeZero(uuidBytes);
                assertThrows(IndexOutOfBoundsException.class, () -> ShieldBlockMessage.read(buffer));
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void missingAndOversizedVarIntsAreRejected() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            assertThrows(IndexOutOfBoundsException.class, () -> ShieldBlockMessage.read(buffer));
            buffer.clear();
            for (int index = 0; index < 6; index++) {
                buffer.writeByte(0x80);
            }
            assertThrows(RuntimeException.class, () -> ShieldBlockMessage.read(buffer));
        } finally {
            buffer.release();
        }
    }
}
