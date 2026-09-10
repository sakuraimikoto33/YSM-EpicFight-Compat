package net.okitsu.ysmepicfightcompat.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.network.ScriptSyncValues;
import net.okitsu.ysmepicfightcompat.network.ServerScriptEvents;


/** A client may emit numeric data for its own selected model, not choose an event owner. */
public record ScriptSyncRequestMessage(String modelId, double[] arguments) implements CustomPacketPayload {
    public static final Type<ScriptSyncRequestMessage> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(CompatMod.MOD_ID, "script_sync_request"));
    public static final StreamCodec<FriendlyByteBuf, ScriptSyncRequestMessage> STREAM_CODEC =
            StreamCodec.of((output, message) -> write(message, output), ScriptSyncRequestMessage::read);

    @Override
    public Type<ScriptSyncRequestMessage> type() {
        return TYPE;
    }

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
                               IPayloadContext context) {
        if (context.flow() == PacketFlow.SERVERBOUND
                && context.player() instanceof ServerPlayer sender) {
            context.enqueueWork(() -> ServerScriptEvents.accept(
                    sender, message.modelId(), message.arguments()));
        }
    }
}
