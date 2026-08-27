package net.okitsu.ysmepicfightcompat.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;
import net.okitsu.ysmepicfightcompat.network.ClientMaidPreferenceSync;
import net.okitsu.ysmepicfightcompat.network.MovementAnimationPolicy;

import java.util.UUID;
import java.util.function.Supplier;

/** Server inputs needed for an owner to resolve held-item rules for one maid. */
public record MaidPreferenceQueryMessage(
        UUID queryId,
        int entityId,
        UUID entityUuid,
        UUID ownerUuid,
        UUID policyEpoch,
        long revision,
        String modelId,
        ResourceLocation mainHandItem,
        ResourceLocation offHandItem) {
    public MaidPreferenceQueryMessage {
        modelId = MovementAnimationPolicy.normalizeModelId(modelId);
        if (queryId == null || entityId < 0 || entityUuid == null || ownerUuid == null
                || policyEpoch == null
                || revision <= 0L || !MovementAnimationPolicy.isValidModelId(modelId)
                || mainHandItem == null || offHandItem == null) {
            throw new IllegalArgumentException("Invalid maid preference query");
        }
    }

    public static void write(MaidPreferenceQueryMessage message,
                             FriendlyByteBuf output) {
        output.writeUUID(message.queryId());
        output.writeVarInt(message.entityId());
        output.writeUUID(message.entityUuid());
        output.writeUUID(message.ownerUuid());
        output.writeUUID(message.policyEpoch());
        output.writeVarLong(message.revision());
        output.writeUtf(message.modelId(), MovementAnimationPolicy.MAX_MODEL_ID_LENGTH);
        output.writeResourceLocation(message.mainHandItem());
        output.writeResourceLocation(message.offHandItem());
    }

    public static MaidPreferenceQueryMessage read(FriendlyByteBuf input) {
        UUID queryId = input.readUUID();
        int entityId = input.readVarInt();
        UUID entityUuid = input.readUUID();
        UUID ownerUuid = input.readUUID();
        UUID policyEpoch = input.readUUID();
        long revision = input.readVarLong();
        String modelId = input.readUtf(MovementAnimationPolicy.MAX_MODEL_ID_LENGTH);
        ResourceLocation mainHandItem = input.readResourceLocation();
        ResourceLocation offHandItem = input.readResourceLocation();
        return new MaidPreferenceQueryMessage(queryId, entityId, entityUuid,
                ownerUuid, policyEpoch, revision, modelId,
                mainHandItem, offHandItem);
    }

    public static void receive(MaidPreferenceQueryMessage message,
                               Supplier<NetworkEvent.Context> suppliedContext) {
        NetworkEvent.Context context = suppliedContext.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> ClientMaidPreferenceSync.accept(message));
        }
        context.setPacketHandled(true);
    }
}
