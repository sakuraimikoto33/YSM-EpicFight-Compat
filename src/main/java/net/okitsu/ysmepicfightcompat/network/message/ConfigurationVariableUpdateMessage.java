package net.okitsu.ysmepicfightcompat.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.network.ConfigurationVariableBroadcaster;
import net.okitsu.ysmepicfightcompat.network.ConfigurationVariableValues;

import java.util.Map;
import java.util.UUID;

/** Client delta for ordinary v.* values written by an official YSM configuration action. */
public record ConfigurationVariableUpdateMessage(String modelId, UUID clientContext,
                                                  UUID serverScope, long requestOrder,
                                                  long sequence, Map<String, Double> changes) implements CustomPacketPayload {
    public static final Type<ConfigurationVariableUpdateMessage> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(CompatMod.MOD_ID, "configuration_variable_update"));
    public static final StreamCodec<FriendlyByteBuf, ConfigurationVariableUpdateMessage> STREAM_CODEC =
            StreamCodec.of((output, message) -> write(message, output), ConfigurationVariableUpdateMessage::read);

    @Override
    public Type<ConfigurationVariableUpdateMessage> type() {
        return TYPE;
    }

    public ConfigurationVariableUpdateMessage {
        ConfigurationVariableScopeRequestMessage.validate(modelId, clientContext, requestOrder);
        if (serverScope == null || sequence <= 0L) {
            throw new IllegalArgumentException("Invalid configuration-variable update scope");
        }
        changes = ConfigurationVariableValues.validate(changes);
    }

    public static void write(ConfigurationVariableUpdateMessage message,
                             FriendlyByteBuf output) {
        ConfigurationVariableValues.writeAcknowledgedFormat(output);
        output.writeUtf(message.modelId(), ConfigurationVariableScopeRequestMessage.MAX_MODEL_ID);
        output.writeUUID(message.clientContext());
        output.writeUUID(message.serverScope());
        output.writeVarLong(message.requestOrder());
        output.writeVarLong(message.sequence());
        ConfigurationVariableValues.write(output, message.changes());
    }

    public static ConfigurationVariableUpdateMessage read(FriendlyByteBuf input) {
        ConfigurationVariableValues.requireAcknowledgedFormat(input);
        return new ConfigurationVariableUpdateMessage(
                input.readUtf(ConfigurationVariableScopeRequestMessage.MAX_MODEL_ID),
                input.readUUID(), input.readUUID(), input.readVarLong(), input.readVarLong(),
                ConfigurationVariableValues.read(input));
    }

    public static void receive(ConfigurationVariableUpdateMessage message,
                               IPayloadContext context) {
        if (context.flow() == PacketFlow.SERVERBOUND
                && context.player() instanceof ServerPlayer sender) {
            context.enqueueWork(() -> ConfigurationVariableBroadcaster.accept(
                    sender, message));
        }
    }
}
