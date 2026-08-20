package net.okitsu.ysmepicfightcompat.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.okitsu.ysmepicfightcompat.network.ConfigurationVariableBroadcaster;
import net.okitsu.ysmepicfightcompat.network.ConfigurationVariableValues;

import java.util.Map;
import java.util.function.Supplier;

/** Client delta for ordinary v.* values written by an official YSM configuration action. */
public record ConfigurationVariableUpdateMessage(Map<String, Double> changes) {
    public ConfigurationVariableUpdateMessage {
        changes = ConfigurationVariableValues.validate(changes);
    }

    public static void write(ConfigurationVariableUpdateMessage message,
                             FriendlyByteBuf output) {
        ConfigurationVariableValues.write(output, message.changes());
    }

    public static ConfigurationVariableUpdateMessage read(FriendlyByteBuf input) {
        return new ConfigurationVariableUpdateMessage(ConfigurationVariableValues.read(input));
    }

    public static void receive(ConfigurationVariableUpdateMessage message,
                               Supplier<NetworkEvent.Context> suppliedContext) {
        NetworkEvent.Context context = suppliedContext.get();
        ServerPlayer sender = context.getSender();
        if (sender != null && context.getDirection().getReceptionSide().isServer()) {
            context.enqueueWork(() -> ConfigurationVariableBroadcaster.accept(
                    sender, message.changes()));
        }
        context.setPacketHandled(true);
    }
}
