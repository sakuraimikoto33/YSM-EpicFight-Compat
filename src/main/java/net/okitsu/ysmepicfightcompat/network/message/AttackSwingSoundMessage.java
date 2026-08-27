package net.okitsu.ysmepicfightcompat.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.network.NetworkEvent;
import net.okitsu.ysmepicfightcompat.animation.ClientAttackSoundRouter;

import java.util.UUID;
import java.util.function.Supplier;

/** Server-authoritative Epic Fight swing sound with its attacker identity intact. */
public record AttackSwingSoundMessage(int entityId, UUID entityUuid, InteractionHand hand,
                                      int sequence, ResourceLocation sound,
                                      double x, double y, double z,
                                      float volume, float pitch) {
    private static final float MAX_VOLUME = 16.0F;
    private static final float MAX_PITCH = 4.0F;

    public AttackSwingSoundMessage {
        if (entityId < 0 || entityUuid == null || hand == null || sound == null
                || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(volume) || volume < 0.0F || volume > MAX_VOLUME
                || !Float.isFinite(pitch) || pitch < 0.0F || pitch > MAX_PITCH) {
            throw new IllegalArgumentException("Invalid attack swing sound message");
        }
    }

    public static void write(AttackSwingSoundMessage message, FriendlyByteBuf output) {
        output.writeVarInt(message.entityId());
        output.writeUUID(message.entityUuid());
        output.writeEnum(message.hand());
        output.writeInt(message.sequence());
        output.writeResourceLocation(message.sound());
        output.writeDouble(message.x());
        output.writeDouble(message.y());
        output.writeDouble(message.z());
        output.writeFloat(message.volume());
        output.writeFloat(message.pitch());
    }

    public static AttackSwingSoundMessage read(FriendlyByteBuf input) {
        return new AttackSwingSoundMessage(input.readVarInt(), input.readUUID(),
                input.readEnum(InteractionHand.class), input.readInt(),
                input.readResourceLocation(), input.readDouble(), input.readDouble(),
                input.readDouble(), input.readFloat(), input.readFloat());
    }

    public static void receive(AttackSwingSoundMessage message,
                               Supplier<NetworkEvent.Context> suppliedContext) {
        NetworkEvent.Context context = suppliedContext.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> ClientAttackSoundRouter.receive(message));
        }
        context.setPacketHandled(true);
    }
}
