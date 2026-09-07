package net.okitsu.ysmepicfightcompat.network.message;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.okitsu.ysmepicfightcompat.animation.ModAnimationType;
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
                "wine_fox/21_saint", MovementAnimationType.LADDER_UP,
                true, true);
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
    void modPreferenceMessagesRoundTripOwnedDisabledAndInactiveStates() {
        for (ModAnimationType family : ModAnimationType.values()) {
            for (boolean enabled : new boolean[]{false, true}) {
                String[] clips = family == ModAnimationType.PARCOOL
                        ? new String[]{"parcool:fast_running", "parcool:roll_front"}
                        : new String[]{"swem:walk", "swem:gallop"};
                for (String clip : clips) {
                    MovementAnimationDisplayState state = new MovementAnimationDisplayState(
                            "wine_fox/21_saint", null, false, false, family, enabled, clip);
                    assertEquals(enabled, state.modAnimationOwned());
                    assertEquals(clip, state.modAnimationClip());
                    assertMovementStateRoundTrip(state);
                }
            }
        }
        assertMovementStateRoundTrip(MovementAnimationDisplayState.DEFAULT);
    }

    @Test
    void modPreferenceWireContainsOnlyCurrentClipAndResolvedDecisionWithoutRulesOrNativeClock() {
        MovementAnimationDisplayState state = new MovementAnimationDisplayState(
                "wine_fox/21_saint", MovementAnimationType.RUN, true, false,
                ModAnimationType.PARCOOL, false, "parcool:fast_running");
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        MovementAnimationPreferenceUpdateMessage.write(
                new MovementAnimationPreferenceUpdateMessage(state), buffer);

        assertEquals(state.modelId(), buffer.readUtf(MovementAnimationPolicy.MAX_MODEL_ID_LENGTH));
        assertEquals(MovementAnimationType.RUN.ordinal(), buffer.readByte());
        assertTrue(buffer.readBoolean());
        assertFalse(buffer.readBoolean());
        assertEquals(ModAnimationType.PARCOOL.ordinal(), buffer.readByte());
        assertFalse(buffer.readBoolean());
        assertEquals("parcool:fast_running",
                buffer.readUtf(MovementAnimationDisplayState.MAX_MOD_ANIMATION_CLIP_LENGTH));
        assertEquals(0, buffer.readableBytes());
    }

    @Test
    void movementPreferenceDecoderRejectsUnknownCrossFamilyAndNonCanonicalClips() {
        for (String clip : new String[]{"swem:jump_lv6", "parcool:roll_front",
                "SWEM:WALK", "swem:*", "swem:", "walk", "swem:walk\n"}) {
            FriendlyByteBuf buffer = modStatePrefix(ModAnimationType.SWEM, true);
            buffer.writeUtf(clip);
            assertThrows(IllegalArgumentException.class,
                    () -> MovementAnimationPreferenceUpdateMessage.read(buffer));
        }
        FriendlyByteBuf absentFamily = modStatePrefix(null, true);
        absentFamily.writeUtf("swem:walk");
        assertThrows(IllegalArgumentException.class,
                () -> MovementAnimationPreferenceUpdateMessage.read(absentFamily));
    }

    @Test
    void movementPreferenceDecoderBoundsClipLengthAndRejectsTruncatedClipData() {
        FriendlyByteBuf oversized = modStatePrefix(ModAnimationType.PARCOOL, true);
        oversized.writeUtf("x".repeat(MovementAnimationDisplayState.MAX_MOD_ANIMATION_CLIP_LENGTH + 1));
        assertThrows(RuntimeException.class,
                () -> MovementAnimationPreferenceUpdateMessage.read(oversized));

        FriendlyByteBuf missing = modStatePrefix(ModAnimationType.PARCOOL, true);
        assertThrows(RuntimeException.class,
                () -> MovementAnimationPreferenceUpdateMessage.read(missing));

        FriendlyByteBuf truncated = modStatePrefix(ModAnimationType.PARCOOL, true);
        truncated.writeVarInt(10);
        truncated.writeByte('p');
        assertThrows(RuntimeException.class,
                () -> MovementAnimationPreferenceUpdateMessage.read(truncated));
    }

    @Test
    void movementPreferenceDecoderCannotAuthorizeACliplessFamily() {
        FriendlyByteBuf buffer = modStatePrefix(ModAnimationType.SWEM, true);
        buffer.writeUtf("");
        MovementAnimationDisplayState state = MovementAnimationPreferenceUpdateMessage.read(buffer).state();
        assertEquals(ModAnimationType.SWEM, state.modAnimation());
        assertEquals("", state.modAnimationClip());
        assertFalse(state.modAnimationOwned());
        assertFalse(state.usesYsmMod("wine_fox/21_saint", ModAnimationType.SWEM, "swem:walk"));
        assertEquals(0, buffer.readableBytes());
        assertMovementStateRoundTrip(state);
    }

    @Test
    void movementPreferenceDecoderRejectsUnknownModOrdinals() {
        for (int ordinal : new int[]{-2, ModAnimationType.values().length, 127}) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            buffer.writeUtf("wine_fox/21_saint",
                    MovementAnimationPolicy.MAX_MODEL_ID_LENGTH);
            buffer.writeByte(-1);
            buffer.writeBoolean(false);
            buffer.writeBoolean(false);
            buffer.writeByte(ordinal);
            buffer.writeBoolean(true);

            assertThrows(IllegalArgumentException.class, () ->
                    MovementAnimationPreferenceUpdateMessage.read(buffer));
        }
    }

    private static FriendlyByteBuf modStatePrefix(ModAnimationType family, boolean owned) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeUtf("wine_fox/21_saint", MovementAnimationPolicy.MAX_MODEL_ID_LENGTH);
        buffer.writeByte(-1);
        buffer.writeBoolean(false);
        buffer.writeBoolean(false);
        buffer.writeByte(family == null ? -1 : family.ordinal());
        buffer.writeBoolean(owned);
        return buffer;
    }

    private static void assertMovementStateRoundTrip(MovementAnimationDisplayState state) {
        MovementAnimationPreferenceUpdateMessage update =
                new MovementAnimationPreferenceUpdateMessage(state);
        MovementAnimationPreferenceSnapshotMessage snapshot =
                new MovementAnimationPreferenceSnapshotMessage(UUID.randomUUID(), state);
        FriendlyByteBuf updateBuffer = new FriendlyByteBuf(Unpooled.buffer());
        MovementAnimationPreferenceUpdateMessage.write(update, updateBuffer);
        assertEquals(update, MovementAnimationPreferenceUpdateMessage.read(updateBuffer));
        assertEquals(0, updateBuffer.readableBytes());
        FriendlyByteBuf snapshotBuffer = new FriendlyByteBuf(Unpooled.buffer());
        MovementAnimationPreferenceSnapshotMessage.write(snapshot, snapshotBuffer);
        assertEquals(snapshot, MovementAnimationPreferenceSnapshotMessage.read(snapshotBuffer));
        assertEquals(0, snapshotBuffer.readableBytes());
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
