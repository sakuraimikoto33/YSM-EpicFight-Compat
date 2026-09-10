package net.okitsu.ysmepicfightcompat.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.network.ClientSubEntityModelPreferences;
import net.okitsu.ysmepicfightcompat.network.MovementAnimationPolicy;
import net.okitsu.ysmepicfightcompat.network.SubEntityModelKind;

import java.util.UUID;

/** Server-owned source inputs needed for the model owner to resolve local rules. */
public record SubEntityPreferenceQueryMessage(
        UUID queryId,
        int entityId,
        UUID entityUuid,
        UUID ownerUuid,
        UUID policyEpoch,
        long revision,
        SubEntityModelKind kind,
        String modelId,
        ResourceLocation entityTypeId,
        ResourceLocation sourceItemId) implements CustomPacketPayload {
    public static final Type<SubEntityPreferenceQueryMessage> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(CompatMod.MOD_ID, "sub_entity_preference_query"));
    public static final StreamCodec<FriendlyByteBuf, SubEntityPreferenceQueryMessage> STREAM_CODEC =
            StreamCodec.of((output, message) -> write(message, output), SubEntityPreferenceQueryMessage::read);

    @Override
    public Type<SubEntityPreferenceQueryMessage> type() {
        return TYPE;
    }

    public SubEntityPreferenceQueryMessage {
        modelId = MovementAnimationPolicy.normalizeModelId(modelId);
        if (queryId == null || entityId < 0 || entityUuid == null
                || ownerUuid == null || policyEpoch == null || revision <= 0L
                || kind == null || !MovementAnimationPolicy.isValidModelId(modelId)
                || entityTypeId == null || sourceItemId == null) {
            throw new IllegalArgumentException("Invalid sub-entity preference query");
        }
    }

    public static void write(SubEntityPreferenceQueryMessage message,
                             FriendlyByteBuf output) {
        output.writeUUID(message.queryId());
        output.writeVarInt(message.entityId());
        output.writeUUID(message.entityUuid());
        output.writeUUID(message.ownerUuid());
        output.writeUUID(message.policyEpoch());
        output.writeVarLong(message.revision());
        output.writeByte(message.kind().ordinal());
        output.writeUtf(message.modelId(), MovementAnimationPolicy.MAX_MODEL_ID_LENGTH);
        output.writeResourceLocation(message.entityTypeId());
        output.writeResourceLocation(message.sourceItemId());
    }

    public static SubEntityPreferenceQueryMessage read(FriendlyByteBuf input) {
        return new SubEntityPreferenceQueryMessage(
                input.readUUID(), input.readVarInt(), input.readUUID(),
                input.readUUID(), input.readUUID(), input.readVarLong(),
                SubEntityModelKind.fromNetworkId(input.readUnsignedByte()),
                input.readUtf(MovementAnimationPolicy.MAX_MODEL_ID_LENGTH),
                input.readResourceLocation(), input.readResourceLocation());
    }

    public static void receive(SubEntityPreferenceQueryMessage message,
                               IPayloadContext context) {
        if (context.flow() == PacketFlow.CLIENTBOUND) {
            context.enqueueWork(() -> ClientSubEntityModelPreferences.accept(message));
        }
    }
}
