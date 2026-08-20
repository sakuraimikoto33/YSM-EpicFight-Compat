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
                                                   Map<String, Double> values) {
    private static final int MAX_MODEL_ID_LENGTH = 4096;

    public ConfigurationVariableSnapshotMessage {
        if (playerId == null || modelId == null
                || modelId.length() > MAX_MODEL_ID_LENGTH) {
            throw new IllegalArgumentException("Invalid configuration-variable snapshot");
        }
        values = ConfigurationVariableValues.validate(values);
    }

    public static void write(ConfigurationVariableSnapshotMessage message,
                             FriendlyByteBuf output) {
        output.writeUUID(message.playerId());
        output.writeUtf(message.modelId(), MAX_MODEL_ID_LENGTH);
        ConfigurationVariableValues.write(output, message.values());
    }

    public static ConfigurationVariableSnapshotMessage read(FriendlyByteBuf input) {
        return new ConfigurationVariableSnapshotMessage(input.readUUID(),
                input.readUtf(MAX_MODEL_ID_LENGTH), ConfigurationVariableValues.read(input));
    }

    public static void receive(ConfigurationVariableSnapshotMessage message,
                               Supplier<NetworkEvent.Context> suppliedContext) {
        NetworkEvent.Context context = suppliedContext.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> OfficialConfigurationVariables.acceptSnapshot(
                    message.playerId(), message.modelId(), message.values()));
        }
        context.setPacketHandled(true);
    }
}
