package net.okitsu.ysmepicfightcompat.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import net.okitsu.ysmepicfightcompat.network.MovementAnimationDisplayState;
import net.okitsu.ysmepicfightcompat.network.RemoteMovementAnimationPreferences;

import java.util.UUID;
import java.util.function.Supplier;

/** Server-to-client snapshot for one player's current movement-pose decision. */
public record MovementAnimationPreferenceSnapshotMessage(
        UUID playerId,
        MovementAnimationDisplayState state) {
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
                               Supplier<NetworkEvent.Context> suppliedContext) {
        NetworkEvent.Context context = suppliedContext.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> RemoteMovementAnimationPreferences.accept(
                    message.playerId(), message.state()));
        }
        context.setPacketHandled(true);
    }
}
