package net.okitsu.ysmepicfightcompat.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.animation.OfficialConfigurationVariables;
import net.okitsu.ysmepicfightcompat.network.ConfigurationVariableValues;

import java.util.Map;
import java.util.UUID;

/** Server-authoritative session snapshot for one player's ordinary configuration values. */
public record ConfigurationVariableSnapshotMessage(UUID playerId, String modelId,
                                                   UUID serverScope, UUID clientContext,
                                                   long revision, long throughSequence,
                                                   Map<String, Double> values) implements CustomPacketPayload {
    public static final Type<ConfigurationVariableSnapshotMessage> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(CompatMod.MOD_ID, "configuration_variable_snapshot"));
    public static final StreamCodec<FriendlyByteBuf, ConfigurationVariableSnapshotMessage> STREAM_CODEC =
            StreamCodec.of((output, message) -> write(message, output), ConfigurationVariableSnapshotMessage::read);

    @Override
    public Type<ConfigurationVariableSnapshotMessage> type() {
        return TYPE;
    }

    private static final int MAX_MODEL_ID_LENGTH = 4096;

    public ConfigurationVariableSnapshotMessage {
        if (playerId == null || modelId == null || serverScope == null || clientContext == null
                || revision < 0L || throughSequence < 0L || modelId.length() > MAX_MODEL_ID_LENGTH) {
            throw new IllegalArgumentException("Invalid configuration-variable snapshot");
        }
        values = ConfigurationVariableValues.validate(values);
    }

    public static void write(ConfigurationVariableSnapshotMessage message,
                             FriendlyByteBuf output) {
        output.writeUUID(message.playerId());
        output.writeUtf(message.modelId(), MAX_MODEL_ID_LENGTH);
        ConfigurationVariableValues.writeAcknowledgedFormat(output);
        output.writeUUID(message.serverScope());
        output.writeUUID(message.clientContext());
        output.writeVarLong(message.revision());
        output.writeVarLong(message.throughSequence());
        ConfigurationVariableValues.write(output, message.values());
    }

    public static ConfigurationVariableSnapshotMessage read(FriendlyByteBuf input) {
        UUID playerId = input.readUUID();
        String modelId = input.readUtf(MAX_MODEL_ID_LENGTH);
        ConfigurationVariableValues.requireAcknowledgedFormat(input);
        return new ConfigurationVariableSnapshotMessage(playerId,
                modelId, input.readUUID(), input.readUUID(),
                input.readVarLong(), input.readVarLong(), ConfigurationVariableValues.read(input));
    }

    public static void receive(ConfigurationVariableSnapshotMessage message,
                               IPayloadContext context) {
        if (context.flow() == PacketFlow.CLIENTBOUND) {
            var connection = context.connection();
            context.enqueueWork(() -> OfficialConfigurationVariables.acceptSnapshot(
                    connection, message));
        }
    }
}
