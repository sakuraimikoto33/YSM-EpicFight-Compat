package net.okitsu.ysmepicfightcompat.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import net.okitsu.ysmepicfightcompat.network.ConfigurationVariableBroadcaster;

import java.util.UUID;
import java.util.function.Supplier;

/** Establishes acknowledgement identity without copying or resetting ordinary values. */
public record ConfigurationVariableScopeRequestMessage(String modelId, UUID clientContext,
                                                        long requestOrder) {
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
                               Supplier<NetworkEvent.Context> suppliedContext) {
        NetworkEvent.Context context = suppliedContext.get();
        var sender = context.getSender();
        if (sender != null && context.getDirection().getReceptionSide().isServer()) {
            context.enqueueWork(() -> ConfigurationVariableBroadcaster.requestScope(sender, message));
        }
        context.setPacketHandled(true);
    }
}
