package net.okitsu.ysmepicfightcompat.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.animation.ClientAttackSoundRouter;

import java.util.UUID;

/** Server-authoritative Epic Fight swing sound with its attacker identity intact. */
public record AttackSwingSoundMessage(int entityId, UUID entityUuid, InteractionHand hand,
                                      int sequence, ResourceLocation sound,
                                      double x, double y, double z,
                                      float volume, float pitch) implements CustomPacketPayload {
    public static final Type<AttackSwingSoundMessage> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(CompatMod.MOD_ID, "attack_swing_sound"));
    public static final StreamCodec<FriendlyByteBuf, AttackSwingSoundMessage> STREAM_CODEC =
            StreamCodec.of((output, message) -> write(message, output), AttackSwingSoundMessage::read);

    @Override
    public Type<AttackSwingSoundMessage> type() {
        return TYPE;
    }

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
                               IPayloadContext context) {
        if (context.flow() == PacketFlow.CLIENTBOUND) {
            context.enqueueWork(() -> ClientAttackSoundRouter.receive(message));
        }
    }
}
