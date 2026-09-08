package net.okitsu.ysmepicfightcompat.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import net.okitsu.ysmepicfightcompat.animation.OfficialConfigurationVariables;
import net.okitsu.ysmepicfightcompat.network.ConfigurationVariableValues;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Server-authoritative session snapshot for one player's ordinary configuration values. */
public record ConfigurationVariableSnapshotMessage(UUID playerId, String modelId,
                                                   UUID serverScope, UUID clientContext,
                                                   long revision, long throughSequence,
                                                   Map<String, Double> values) {
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
                               Supplier<NetworkEvent.Context> suppliedContext) {
        NetworkEvent.Context context = suppliedContext.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            var connection = context.getNetworkManager();
            context.enqueueWork(() -> OfficialConfigurationVariables.acceptSnapshot(
                    connection, message));
        }
        context.setPacketHandled(true);
    }
}
