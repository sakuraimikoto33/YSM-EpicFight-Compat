package net.okitsu.ysmepicfightcompat.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.animation.ModAnimationType;
import net.okitsu.ysmepicfightcompat.animation.MovementAnimationType;
import net.okitsu.ysmepicfightcompat.network.MovementAnimationDisplayState;
import net.okitsu.ysmepicfightcompat.network.MovementAnimationPolicy;
import net.okitsu.ysmepicfightcompat.network.MovementAnimationPreferenceBroadcaster;


/** Client-to-server update containing only the sender's current resolved movement state. */
public record MovementAnimationPreferenceUpdateMessage(
        MovementAnimationDisplayState state) implements CustomPacketPayload {
    public static final Type<MovementAnimationPreferenceUpdateMessage> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(CompatMod.MOD_ID, "movement_animation_preference_update"));
    public static final StreamCodec<FriendlyByteBuf, MovementAnimationPreferenceUpdateMessage> STREAM_CODEC =
            StreamCodec.of((output, message) -> write(message, output), MovementAnimationPreferenceUpdateMessage::read);

    @Override
    public Type<MovementAnimationPreferenceUpdateMessage> type() {
        return TYPE;
    }

    public MovementAnimationPreferenceUpdateMessage {
        if (state == null) {
            throw new IllegalArgumentException("Missing movement-animation state");
        }
    }

    public static void write(MovementAnimationPreferenceUpdateMessage message,
                             FriendlyByteBuf output) {
        writeState(message.state(), output);
    }

    public static MovementAnimationPreferenceUpdateMessage read(FriendlyByteBuf input) {
        return new MovementAnimationPreferenceUpdateMessage(readState(input));
    }

    public static void receive(MovementAnimationPreferenceUpdateMessage message,
                               IPayloadContext context) {
        if (context.flow() == PacketFlow.SERVERBOUND
                && context.player() instanceof ServerPlayer sender) {
            context.enqueueWork(() -> MovementAnimationPreferenceBroadcaster.accept(
                    sender, message.state()));
        }
    }

    static void writeState(MovementAnimationDisplayState state, FriendlyByteBuf output) {
        output.writeUtf(state.modelId(), MovementAnimationPolicy.MAX_MODEL_ID_LENGTH);
        output.writeByte(state.movement() == null ? -1 : state.movement().ordinal());
        output.writeBoolean(state.ysmOwned());
        output.writeBoolean(state.naturalLadderPose());
        output.writeByte(state.modAnimation() == null ? -1 : state.modAnimation().ordinal());
        output.writeBoolean(state.modAnimationOwned());
        output.writeUtf(state.modAnimationClip(),
                MovementAnimationDisplayState.MAX_MOD_ANIMATION_CLIP_LENGTH);
    }

    static MovementAnimationDisplayState readState(FriendlyByteBuf input) {
        String modelId = input.readUtf(MovementAnimationPolicy.MAX_MODEL_ID_LENGTH);
        int ordinal = input.readByte();
        MovementAnimationType movement;
        if (ordinal == -1) {
            movement = null;
        } else if (ordinal >= 0 && ordinal < MovementAnimationType.values().length) {
            movement = MovementAnimationType.values()[ordinal];
        } else {
            throw new IllegalArgumentException("Invalid movement-animation kind");
        }
        boolean ysmOwned = input.readBoolean();
        boolean naturalLadderPose = input.readBoolean();
        int modOrdinal = input.readByte();
        ModAnimationType modAnimation;
        if (modOrdinal == -1) {
            modAnimation = null;
        } else if (modOrdinal >= 0 && modOrdinal < ModAnimationType.values().length) {
            modAnimation = ModAnimationType.values()[modOrdinal];
        } else {
            throw new IllegalArgumentException("Invalid mod-animation kind");
        }
        boolean modAnimationOwned = input.readBoolean();
        String modAnimationClip = input.readUtf(
                MovementAnimationDisplayState.MAX_MOD_ANIMATION_CLIP_LENGTH);
        return new MovementAnimationDisplayState(modelId, movement, ysmOwned,
                naturalLadderPose, modAnimation, modAnimationOwned, modAnimationClip);
    }
}
