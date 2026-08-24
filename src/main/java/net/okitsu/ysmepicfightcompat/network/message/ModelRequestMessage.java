package net.okitsu.ysmepicfightcompat.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.okitsu.ysmepicfightcompat.network.CompatNetwork;
import net.okitsu.ysmepicfightcompat.cache.ModelDiskCache;
import net.okitsu.ysmepicfightcompat.network.geometry.ServerModelTransfers;

import java.util.Arrays;
import java.util.function.Supplier;

/** Client request for a currently selected server model, optionally validated by digest. */
public record ModelRequestMessage(String modelId, byte[] knownPayloadDigest) {
    public static final int MAX_MODEL_ID_BYTES = 4096;

    public ModelRequestMessage {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("Empty model id");
        }
        if (knownPayloadDigest == null || (knownPayloadDigest.length != 0
                && knownPayloadDigest.length != ModelDiskCache.DIGEST_BYTES)) {
            throw new IllegalArgumentException("Invalid cached model digest");
        }
        knownPayloadDigest = Arrays.copyOf(knownPayloadDigest, knownPayloadDigest.length);
    }

    public ModelRequestMessage(String modelId) {
        this(modelId, new byte[0]);
    }

    @Override
    public byte[] knownPayloadDigest() {
        return Arrays.copyOf(knownPayloadDigest, knownPayloadDigest.length);
    }

    public static void write(ModelRequestMessage message, FriendlyByteBuf output) {
        output.writeUtf(message.modelId(), MAX_MODEL_ID_BYTES);
        output.writeByteArray(message.knownPayloadDigest());
    }

    public static ModelRequestMessage read(FriendlyByteBuf input) {
        return new ModelRequestMessage(input.readUtf(MAX_MODEL_ID_BYTES),
                input.readByteArray(ModelDiskCache.DIGEST_BYTES));
    }

    public static void receive(ModelRequestMessage message,
                               Supplier<NetworkEvent.Context> suppliedContext) {
        NetworkEvent.Context context = suppliedContext.get();
        ServerPlayer sender = context.getSender();
        if (sender != null && context.getDirection().getReceptionSide().isServer()
                && CompatNetwork.isConnected(sender)) {
            context.enqueueWork(() -> ServerModelTransfers.request(sender, message.modelId(),
                    message.knownPayloadDigest()));
        }
        context.setPacketHandled(true);
    }
}
