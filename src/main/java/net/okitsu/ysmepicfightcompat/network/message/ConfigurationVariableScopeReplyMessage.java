package net.okitsu.ysmepicfightcompat.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import net.okitsu.ysmepicfightcompat.animation.OfficialConfigurationVariables;

import java.util.UUID;
import java.util.function.Supplier;

/** A grant carries no values; an invalidation identifies only the rejected old scope. */
public record ConfigurationVariableScopeReplyMessage(String modelId, UUID clientContext,
                                                      long requestOrder, UUID serverScope,
                                                      boolean granted) {
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
                               Supplier<NetworkEvent.Context> suppliedContext) {
        NetworkEvent.Context context = suppliedContext.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            var connection = context.getNetworkManager();
            context.enqueueWork(() -> OfficialConfigurationVariables.acceptScope(connection, message));
        }
        context.setPacketHandled(true);
    }
}
