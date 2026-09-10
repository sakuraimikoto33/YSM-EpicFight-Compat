package net.okitsu.ysmepicfightcompat.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.network.MovementAnimationDisplayState;
import net.okitsu.ysmepicfightcompat.network.RemoteMovementAnimationPreferences;

import java.util.UUID;

/** Server-to-client snapshot for one player's current movement-pose decision. */
public record MovementAnimationPreferenceSnapshotMessage(
        UUID playerId,
        MovementAnimationDisplayState state) implements CustomPacketPayload {
    public static final Type<MovementAnimationPreferenceSnapshotMessage> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(CompatMod.MOD_ID, "movement_animation_preference_snapshot"));
    public static final StreamCodec<FriendlyByteBuf, MovementAnimationPreferenceSnapshotMessage> STREAM_CODEC =
            StreamCodec.of((output, message) -> write(message, output), MovementAnimationPreferenceSnapshotMessage::read);

    @Override
    public Type<MovementAnimationPreferenceSnapshotMessage> type() {
        return TYPE;
    }

    public MovementAnimationPreferenceSnapshotMessage {
        if (playerId == null || state == null) {
            throw new IllegalArgumentException("Invalid movement-animation snapshot");
        }
    }

    public static void write(MovementAnimationPreferenceSnapshotMessage message,
                             FriendlyByteBuf output) {
        output.writeUUID(message.playerId());
        MovementAnimationPreferenceUpdateMessage.writeState(message.state(), output);
    }

    public static MovementAnimationPreferenceSnapshotMessage read(FriendlyByteBuf input) {
        return new MovementAnimationPreferenceSnapshotMessage(input.readUUID(),
                MovementAnimationPreferenceUpdateMessage.readState(input));
    }

    public static void receive(MovementAnimationPreferenceSnapshotMessage message,
                               IPayloadContext context) {
        if (context.flow() == PacketFlow.CLIENTBOUND) {
            context.enqueueWork(() -> RemoteMovementAnimationPreferences.accept(
                    message.playerId(), message.state()));
        }
    }
}
