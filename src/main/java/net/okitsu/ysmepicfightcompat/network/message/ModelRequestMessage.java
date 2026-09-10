package net.okitsu.ysmepicfightcompat.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.network.CompatNetwork;
import net.okitsu.ysmepicfightcompat.cache.ModelDiskCache;
import net.okitsu.ysmepicfightcompat.network.geometry.ServerModelTransfers;

import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Client request for the server model selected by one entity the recipient is tracking.
 *
 * <p>The entity identity is part of the authorization boundary: a model id alone does not
 * prove that the requesting client is allowed to receive that model.</p>
 */
public record ModelRequestMessage(String modelId, int sourceEntityId,
                                  UUID sourceEntityUuid, byte[] knownPayloadDigest) implements CustomPacketPayload {
    public static final Type<ModelRequestMessage> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(CompatMod.MOD_ID, "model_request"));
    public static final StreamCodec<FriendlyByteBuf, ModelRequestMessage> STREAM_CODEC =
            StreamCodec.of((output, message) -> write(message, output), ModelRequestMessage::read);

    @Override
    public Type<ModelRequestMessage> type() {
        return TYPE;
    }

    public static final int MAX_MODEL_ID_BYTES = 4096;

    public ModelRequestMessage {
        if (modelId == null || modelId.isBlank()
                || modelId.getBytes(StandardCharsets.UTF_8).length
                > MAX_MODEL_ID_BYTES) {
            throw new IllegalArgumentException("Empty model id");
        }
        if (sourceEntityId < 0 || sourceEntityUuid == null) {
            throw new IllegalArgumentException("Invalid model source entity");
        }
        if (knownPayloadDigest == null || (knownPayloadDigest.length != 0
                && knownPayloadDigest.length != ModelDiskCache.DIGEST_BYTES)) {
            throw new IllegalArgumentException("Invalid cached model digest");
        }
        knownPayloadDigest = Arrays.copyOf(knownPayloadDigest, knownPayloadDigest.length);
    }

    public ModelRequestMessage(String modelId, int sourceEntityId,
                               UUID sourceEntityUuid) {
        this(modelId, sourceEntityId, sourceEntityUuid, new byte[0]);
    }

    @Override
    public byte[] knownPayloadDigest() {
        return Arrays.copyOf(knownPayloadDigest, knownPayloadDigest.length);
    }

    public static void write(ModelRequestMessage message, FriendlyByteBuf output) {
        output.writeUtf(message.modelId(), MAX_MODEL_ID_BYTES);
        output.writeVarInt(message.sourceEntityId());
        output.writeUUID(message.sourceEntityUuid());
        output.writeByteArray(message.knownPayloadDigest());
    }

    public static ModelRequestMessage read(FriendlyByteBuf input) {
        return new ModelRequestMessage(input.readUtf(MAX_MODEL_ID_BYTES),
                input.readVarInt(), input.readUUID(),
                input.readByteArray(ModelDiskCache.DIGEST_BYTES));
    }

    public static void receive(ModelRequestMessage message,
                               IPayloadContext context) {
        if (context.flow() == PacketFlow.SERVERBOUND
                && context.player() instanceof ServerPlayer sender
                && CompatNetwork.isConnected(sender)) {
            context.enqueueWork(() -> ServerModelTransfers.request(sender, message.modelId(),
                    message.sourceEntityId(), message.sourceEntityUuid(),
                    message.knownPayloadDigest()));
        }
    }
}
