package net.okitsu.ysmepicfightcompat.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.network.ConfigurationVariableBroadcaster;

import java.util.UUID;

/** Establishes acknowledgement identity without copying or resetting ordinary values. */
public record ConfigurationVariableScopeRequestMessage(String modelId, UUID clientContext,
                                                        long requestOrder) implements CustomPacketPayload {
    public static final Type<ConfigurationVariableScopeRequestMessage> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(CompatMod.MOD_ID, "configuration_variable_scope_request"));
    public static final StreamCodec<FriendlyByteBuf, ConfigurationVariableScopeRequestMessage> STREAM_CODEC =
            StreamCodec.of((output, message) -> write(message, output), ConfigurationVariableScopeRequestMessage::read);

    @Override
    public Type<ConfigurationVariableScopeRequestMessage> type() {
        return TYPE;
    }

    static final int MAX_MODEL_ID = 4096;

    public ConfigurationVariableScopeRequestMessage {
        validate(modelId, clientContext, requestOrder);
    }

    static void validate(String modelId, UUID context, long order) {
        if (modelId == null || modelId.isBlank() || modelId.length() > MAX_MODEL_ID
                || context == null || context.equals(new UUID(0L, 0L)) || order <= 0L) {
            throw new IllegalArgumentException("Invalid configuration-variable context");
        }
    }

    public static void write(ConfigurationVariableScopeRequestMessage message, FriendlyByteBuf out) {
        out.writeUtf(message.modelId(), MAX_MODEL_ID);
        out.writeUUID(message.clientContext());
        out.writeVarLong(message.requestOrder());
    }

    public static ConfigurationVariableScopeRequestMessage read(FriendlyByteBuf in) {
        return new ConfigurationVariableScopeRequestMessage(
                in.readUtf(MAX_MODEL_ID), in.readUUID(), in.readVarLong());
    }

    public static void receive(ConfigurationVariableScopeRequestMessage message,
                               IPayloadContext context) {
        if (context.flow() == PacketFlow.SERVERBOUND
                && context.player() instanceof ServerPlayer sender) {
            context.enqueueWork(() -> ConfigurationVariableBroadcaster.requestScope(sender, message));
        }
    }
}
