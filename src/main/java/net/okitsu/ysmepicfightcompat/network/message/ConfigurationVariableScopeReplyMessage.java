package net.okitsu.ysmepicfightcompat.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.animation.OfficialConfigurationVariables;

import java.util.UUID;

/** A grant carries no values; an invalidation identifies only the rejected old scope. */
public record ConfigurationVariableScopeReplyMessage(String modelId, UUID clientContext,
                                                      long requestOrder, UUID serverScope,
                                                      boolean granted) implements CustomPacketPayload {
    public static final Type<ConfigurationVariableScopeReplyMessage> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(CompatMod.MOD_ID, "configuration_variable_scope_reply"));
    public static final StreamCodec<FriendlyByteBuf, ConfigurationVariableScopeReplyMessage> STREAM_CODEC =
            StreamCodec.of((output, message) -> write(message, output), ConfigurationVariableScopeReplyMessage::read);

    @Override
    public Type<ConfigurationVariableScopeReplyMessage> type() {
        return TYPE;
    }

    public ConfigurationVariableScopeReplyMessage {
        ConfigurationVariableScopeRequestMessage.validate(modelId, clientContext, requestOrder);
        if (serverScope == null) {
            throw new IllegalArgumentException("Missing configuration-variable server scope");
        }
    }

    public static void write(ConfigurationVariableScopeReplyMessage message, FriendlyByteBuf out) {
        out.writeUtf(message.modelId(), ConfigurationVariableScopeRequestMessage.MAX_MODEL_ID);
        out.writeUUID(message.clientContext());
        out.writeVarLong(message.requestOrder());
        out.writeUUID(message.serverScope());
        out.writeBoolean(message.granted());
    }

    public static ConfigurationVariableScopeReplyMessage read(FriendlyByteBuf in) {
        return new ConfigurationVariableScopeReplyMessage(
                in.readUtf(ConfigurationVariableScopeRequestMessage.MAX_MODEL_ID),
                in.readUUID(), in.readVarLong(), in.readUUID(), in.readBoolean());
    }

    public static void receive(ConfigurationVariableScopeReplyMessage message,
                               IPayloadContext context) {
        if (context.flow() == PacketFlow.CLIENTBOUND) {
            var connection = context.connection();
            context.enqueueWork(() -> OfficialConfigurationVariables.acceptScope(connection, message));
        }
    }
}
