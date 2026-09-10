package net.okitsu.ysmepicfightcompat.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.animation.MovementAnimationType;
import net.okitsu.ysmepicfightcompat.network.ClientMaidPreferenceSync;
import net.okitsu.ysmepicfightcompat.network.MovementAnimationPolicy;

import java.util.UUID;

/** Server inputs needed for an owner to resolve one maid movement rule. */
public record MaidMovementPreferenceQueryMessage(
        UUID queryId,
        int entityId,
        UUID entityUuid,
        UUID ownerUuid,
        UUID policyEpoch,
        long revision,
        String modelId,
        MovementAnimationType movement) implements CustomPacketPayload {
    public static final Type<MaidMovementPreferenceQueryMessage> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(CompatMod.MOD_ID, "maid_movement_preference_query"));
    public static final StreamCodec<FriendlyByteBuf, MaidMovementPreferenceQueryMessage> STREAM_CODEC =
            StreamCodec.of((output, message) -> write(message, output), MaidMovementPreferenceQueryMessage::read);

    @Override
    public Type<MaidMovementPreferenceQueryMessage> type() {
        return TYPE;
    }

    public MaidMovementPreferenceQueryMessage {
        modelId = MovementAnimationPolicy.normalizeModelId(modelId);
        if (queryId == null || entityId < 0 || entityUuid == null
                || ownerUuid == null || policyEpoch == null || revision <= 0L
                || !MovementAnimationPolicy.isValidModelId(modelId)
                || movement == null) {
            throw new IllegalArgumentException("Invalid maid movement preference query");
        }
    }

    public static void write(MaidMovementPreferenceQueryMessage message,
                             FriendlyByteBuf output) {
        output.writeUUID(message.queryId());
        output.writeVarInt(message.entityId());
        output.writeUUID(message.entityUuid());
        output.writeUUID(message.ownerUuid());
        output.writeUUID(message.policyEpoch());
        output.writeVarLong(message.revision());
        output.writeUtf(message.modelId(), MovementAnimationPolicy.MAX_MODEL_ID_LENGTH);
        output.writeByte(message.movement().ordinal());
    }

    public static MaidMovementPreferenceQueryMessage read(FriendlyByteBuf input) {
        UUID queryId = input.readUUID();
        int entityId = input.readVarInt();
        UUID entityUuid = input.readUUID();
        UUID ownerUuid = input.readUUID();
        UUID policyEpoch = input.readUUID();
        long revision = input.readVarLong();
        String modelId = input.readUtf(MovementAnimationPolicy.MAX_MODEL_ID_LENGTH);
        int ordinal = input.readByte();
        if (ordinal < 0 || ordinal >= MovementAnimationType.values().length) {
            throw new IllegalArgumentException("Invalid maid movement kind");
        }
        return new MaidMovementPreferenceQueryMessage(
                queryId, entityId, entityUuid, ownerUuid, policyEpoch,
                revision, modelId, MovementAnimationType.values()[ordinal]);
    }

    public static void receive(MaidMovementPreferenceQueryMessage message,
                               IPayloadContext context) {
        if (context.flow() == PacketFlow.CLIENTBOUND) {
            context.enqueueWork(() -> ClientMaidPreferenceSync.accept(message));
        }
    }
}
