package net.okitsu.ysmepicfightcompat.network.message;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.okitsu.ysmepicfightcompat.animation.MovementAnimationType;
import net.okitsu.ysmepicfightcompat.cache.ModelDiskCache;
import net.okitsu.ysmepicfightcompat.network.MovementAnimationDisplayState;
import net.okitsu.ysmepicfightcompat.network.MovementAnimationPolicy;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelTransferMessageTest {
    @Test
    void validatesAttackSwingSoundPayloadBounds() {
        UUID playerId = UUID.randomUUID();
        ResourceLocation sound = ResourceLocation.fromNamespaceAndPath(
                "epicfight", "entity.weapon.whoosh_sharp");
        AttackSwingSoundMessage message = new AttackSwingSoundMessage(
                4, playerId, InteractionHand.MAIN_HAND, 2, sound,
                1.0D, 2.0D, 3.0D, 1.0F, 1.0F);

        assertEquals(playerId, message.entityUuid());
        assertEquals(sound, message.sound());
        assertThrows(IllegalArgumentException.class, () ->
                new AttackSwingSoundMessage(4, playerId, InteractionHand.MAIN_HAND,
                        2, sound, Double.NaN, 2.0D, 3.0D, 1.0F, 1.0F));
        assertThrows(IllegalArgumentException.class, () ->
                new AttackSwingSoundMessage(4, playerId, InteractionHand.MAIN_HAND,
                        2, sound, 1.0D, 2.0D, 3.0D, 1.0F, 5.0F));
    }

    @Test
    void heldItemPreferenceMessagesContainOnlyResolvedPerHandState() {
        UUID playerId = UUID.randomUUID();
        HeldItemPreferenceUpdateMessage update =
                new HeldItemPreferenceUpdateMessage(false, true, true, false);
        HeldItemPreferenceSnapshotMessage snapshot =
                new HeldItemPreferenceSnapshotMessage(
                        playerId, false, true, true, false);

        assertFalse(update.mainHandYsm());
        assertTrue(update.offHandYsm());
        assertTrue(update.mainHandYsmSwitchAnimation());
        assertFalse(update.offHandYsmSwitchAnimation());
        assertEquals(playerId, snapshot.playerId());
        assertFalse(snapshot.mainHandYsm());
        assertTrue(snapshot.offHandYsm());
        assertTrue(snapshot.mainHandYsmSwitchAnimation());
        assertFalse(snapshot.offHandYsmSwitchAnimation());

        FriendlyByteBuf updateBuffer = new FriendlyByteBuf(Unpooled.buffer());
        HeldItemPreferenceUpdateMessage.write(update, updateBuffer);
        assertEquals(update, HeldItemPreferenceUpdateMessage.read(updateBuffer));

        FriendlyByteBuf snapshotBuffer = new FriendlyByteBuf(Unpooled.buffer());
        HeldItemPreferenceSnapshotMessage.write(snapshot, snapshotBuffer);
        assertEquals(snapshot,
                HeldItemPreferenceSnapshotMessage.read(snapshotBuffer));
    }

    @Test
    void movementPreferenceMessagesContainOnlyCurrentResolvedState() {
        UUID playerId = UUID.randomUUID();
        MovementAnimationDisplayState state = new MovementAnimationDisplayState(
                "wine_fox/21_saint", MovementAnimationType.CREATIVE_FLIGHT, true);
        MovementAnimationPreferenceUpdateMessage update =
                new MovementAnimationPreferenceUpdateMessage(state);
        MovementAnimationPreferenceSnapshotMessage snapshot =
                new MovementAnimationPreferenceSnapshotMessage(playerId, state);

        FriendlyByteBuf updateBuffer = new FriendlyByteBuf(Unpooled.buffer());
        MovementAnimationPreferenceUpdateMessage.write(update, updateBuffer);
        assertEquals(update,
                MovementAnimationPreferenceUpdateMessage.read(updateBuffer));

        FriendlyByteBuf snapshotBuffer = new FriendlyByteBuf(Unpooled.buffer());
        MovementAnimationPreferenceSnapshotMessage.write(snapshot, snapshotBuffer);
        assertEquals(snapshot,
                MovementAnimationPreferenceSnapshotMessage.read(snapshotBuffer));
    }

    @Test
    void movementPreferenceDecoderRejectsUnknownSemanticOrdinals() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeUtf("wine_fox/21_saint",
                MovementAnimationPolicy.MAX_MODEL_ID_LENGTH);
        buffer.writeByte(MovementAnimationType.values().length);
        buffer.writeBoolean(true);

        assertThrows(IllegalArgumentException.class, () ->
                MovementAnimationPreferenceUpdateMessage.read(buffer));
    }

    @Test
    void validatesConditionalRequestDigestsAndCopiesArrays() {
        byte[] digest = ModelDiskCache.sha256(new byte[]{1});
        UUID sourceUuid = UUID.randomUUID();
        ModelRequestMessage request = new ModelRequestMessage(
                "server/model", 42, sourceUuid, digest);
        digest[0] ^= 1;
        assertArrayEquals(ModelDiskCache.sha256(new byte[]{1}),
                request.knownPayloadDigest());
        byte[] exposed = request.knownPayloadDigest();
        exposed[0] ^= 1;
        assertArrayEquals(ModelDiskCache.sha256(new byte[]{1}),
                request.knownPayloadDigest());
        assertThrows(IllegalArgumentException.class,
                () -> new ModelRequestMessage(
                        "server/model", 42, sourceUuid, new byte[1]));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelRequestMessage(
                        "server/model", -1, sourceUuid, new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelRequestMessage(
                        "あ".repeat(ModelRequestMessage.MAX_MODEL_ID_BYTES),
                        42, sourceUuid, new byte[0]));

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        ModelRequestMessage.write(request, buffer);
        ModelRequestMessage decoded = ModelRequestMessage.read(buffer);
        assertEquals(request.modelId(), decoded.modelId());
        assertEquals(request.sourceEntityId(), decoded.sourceEntityId());
        assertEquals(request.sourceEntityUuid(), decoded.sourceEntityUuid());
        assertArrayEquals(request.knownPayloadDigest(), decoded.knownPayloadDigest());
    }

    @Test
    void separatesDataUnchangedAndUnavailableResponses() {
        byte[] digest = ModelDiskCache.sha256(new byte[]{2});
        ModelChunkMessage unchanged = ModelChunkMessage.unchanged("server/model", digest);
        ModelChunkMessage unavailable = ModelChunkMessage.unavailable("server/model");
        ModelChunkMessage data = new ModelChunkMessage(ModelChunkMessage.Status.DATA,
                UUID.randomUUID(), "server/model", digest, 1, 0, 1, new byte[]{4});

        assertEquals(ModelChunkMessage.Status.UNCHANGED, unchanged.status());
        assertEquals(ModelChunkMessage.Status.UNAVAILABLE, unavailable.status());
        assertEquals(ModelChunkMessage.Status.DATA, data.status());
        assertThrows(IllegalArgumentException.class, () -> new ModelChunkMessage(
                ModelChunkMessage.Status.UNCHANGED, UUID.randomUUID(), "server/model",
                digest, 1, 0, 0, new byte[0]));
    }
}
