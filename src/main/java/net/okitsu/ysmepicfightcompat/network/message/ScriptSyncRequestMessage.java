package net.okitsu.ysmepicfightcompat.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.okitsu.ysmepicfightcompat.network.ScriptSyncValues;
import net.okitsu.ysmepicfightcompat.network.ServerScriptEvents;

import java.util.function.Supplier;

/** A client may emit numeric data for its own selected model, not choose an event owner. */
public record ScriptSyncRequestMessage(String modelId, double[] arguments) {
    public ScriptSyncRequestMessage {
        modelId = ScriptSyncValues.modelId(modelId);
        arguments = ScriptSyncValues.arguments(arguments);
    }

    @Override
    public double[] arguments() {
        return arguments.clone();
    }

    public static void write(ScriptSyncRequestMessage message, FriendlyByteBuf output) {
        output.writeUtf(message.modelId(), ScriptSyncValues.MAX_MODEL_ID);
        ScriptSyncValues.write(output, message.arguments);
    }

    public static ScriptSyncRequestMessage read(FriendlyByteBuf input) {
        return new ScriptSyncRequestMessage(input.readUtf(ScriptSyncValues.MAX_MODEL_ID),
                ScriptSyncValues.read(input));
    }

    public static void receive(ScriptSyncRequestMessage message,
                               Supplier<NetworkEvent.Context> suppliedContext) {
        NetworkEvent.Context context = suppliedContext.get();
        ServerPlayer sender = context.getSender();
        if (sender != null && context.getDirection().getReceptionSide().isServer()) {
            context.enqueueWork(() -> ServerScriptEvents.accept(
                    sender, message.modelId(), message.arguments()));
        }
        context.setPacketHandled(true);
    }
}
