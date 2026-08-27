package net.okitsu.ysmepicfightcompat.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import net.okitsu.ysmepicfightcompat.animation.MovementAnimationType;
import net.okitsu.ysmepicfightcompat.network.ClientMaidPreferenceSync;
import net.okitsu.ysmepicfightcompat.network.MovementAnimationPolicy;

import java.util.UUID;
import java.util.function.Supplier;

/** Server inputs needed for an owner to resolve one maid movement rule. */
public record MaidMovementPreferenceQueryMessage(
        UUID queryId,
        int entityId,
        UUID entityUuid,
        UUID ownerUuid,
        UUID policyEpoch,
        long revision,
        String modelId,
        MovementAnimationType movement) {
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
                               Supplier<NetworkEvent.Context> suppliedContext) {
        NetworkEvent.Context context = suppliedContext.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> ClientMaidPreferenceSync.accept(message));
        }
        context.setPacketHandled(true);
    }
}
