package net.okitsu.ysmepicfightcompat.network.message;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.okitsu.ysmepicfightcompat.network.SubEntityModelDisplayState;
import net.okitsu.ysmepicfightcompat.network.SubEntityModelKind;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SubEntityPreferenceMessageTest {
    private static final ResourceLocation ARROW =
            ResourceLocation.fromNamespaceAndPath("minecraft", "arrow");
    private static final ResourceLocation BOW =
            ResourceLocation.fromNamespaceAndPath("minecraft", "bow");

    @Test
    void roundTripsQueryResponseAndSnapshot() {
        UUID queryId = UUID.randomUUID();
        UUID entityUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        UUID epoch = UUID.randomUUID();
        SubEntityPreferenceQueryMessage query =
                new SubEntityPreferenceQueryMessage(
                        queryId, 17, entityUuid, ownerUuid, epoch, 9L,
                        SubEntityModelKind.PROJECTILE, "wine_fox/22_elf",
                        ARROW, BOW);
        SubEntityPreferenceUpdateMessage response =
                new SubEntityPreferenceUpdateMessage(
                        queryId, 17, entityUuid, ownerUuid, epoch, 9L,
                        SubEntityModelKind.PROJECTILE, true);
        SubEntityPreferenceSnapshotMessage snapshot =
                new SubEntityPreferenceSnapshotMessage(
                         new SubEntityModelDisplayState(
                                 17, entityUuid, ownerUuid, 10L,
                                 SubEntityModelKind.PROJECTILE,
                                 ARROW, true, true, true));

        FriendlyByteBuf queryBuffer = buffer();
        SubEntityPreferenceQueryMessage.write(query, queryBuffer);
        assertEquals(query, SubEntityPreferenceQueryMessage.read(queryBuffer));

        FriendlyByteBuf responseBuffer = buffer();
        SubEntityPreferenceUpdateMessage.write(response, responseBuffer);
        assertEquals(response,
                SubEntityPreferenceUpdateMessage.read(responseBuffer));

        FriendlyByteBuf snapshotBuffer = buffer();
        SubEntityPreferenceSnapshotMessage.write(snapshot, snapshotBuffer);
        assertEquals(snapshot,
                SubEntityPreferenceSnapshotMessage.read(snapshotBuffer));
    }

    @Test
    void rejectsUnknownKindAndCanonicalizesUnknownSnapshot() {
        SubEntityPreferenceQueryMessage query =
                new SubEntityPreferenceQueryMessage(
                        UUID.randomUUID(), 1, UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID(), 2L, SubEntityModelKind.VEHICLE,
                        "wine_fox/01_taisho_maid", ARROW, BOW);
        FriendlyByteBuf buffer = buffer();
        SubEntityPreferenceQueryMessage.write(query, buffer);
        // Locate the kind safely by reading the fixed prefix on a duplicate.
        FriendlyByteBuf cursor = new FriendlyByteBuf(buffer.copy());
        cursor.readUUID();
        cursor.readVarInt();
        cursor.readUUID();
        cursor.readUUID();
        cursor.readUUID();
        cursor.readVarLong();
        int kindOffset = cursor.readerIndex();
        buffer.setByte(kindOffset, 127);
        assertThrows(IllegalArgumentException.class,
                () -> SubEntityPreferenceQueryMessage.read(buffer));

        SubEntityModelDisplayState unknown = new SubEntityModelDisplayState(
                3, UUID.randomUUID(), UUID.randomUUID(), 4L,
                SubEntityModelKind.PROJECTILE, ARROW, true, false, true);
        assertFalse(unknown.ysm());
    }

    private static FriendlyByteBuf buffer() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }
}
