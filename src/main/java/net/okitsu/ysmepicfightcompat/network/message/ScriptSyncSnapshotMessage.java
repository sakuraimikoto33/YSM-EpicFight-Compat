package net.okitsu.ysmepicfightcompat.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.animation.ClientScriptEvents;
import net.okitsu.ysmepicfightcompat.network.ScriptSyncValues;

import java.util.UUID;

/** Server-authoritative identity and replay ordering for a model's sync event. */
public record ScriptSyncSnapshotMessage(int entityId, UUID entityUuid, long sequence,
                                        String modelId, double[] arguments) implements CustomPacketPayload {
    public static final Type<ScriptSyncSnapshotMessage> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(CompatMod.MOD_ID, "script_sync_snapshot"));
    public static final StreamCodec<FriendlyByteBuf, ScriptSyncSnapshotMessage> STREAM_CODEC =
            StreamCodec.of((output, message) -> write(message, output), ScriptSyncSnapshotMessage::read);

    @Override
    public Type<ScriptSyncSnapshotMessage> type() {
        return TYPE;
    }

    public ScriptSyncSnapshotMessage {
        if (entityId < 0 || entityUuid == null || sequence <= 0L) {
            throw new IllegalArgumentException("Invalid script sync identity");
        }
        modelId = ScriptSyncValues.modelId(modelId);
        arguments = ScriptSyncValues.arguments(arguments);
    }

    @Override
    public double[] arguments() {
        return arguments.clone();
    }

    public static void write(ScriptSyncSnapshotMessage message, FriendlyByteBuf output) {
        output.writeVarInt(message.entityId());
        output.writeUUID(message.entityUuid());
        output.writeVarLong(message.sequence());
        output.writeUtf(message.modelId(), ScriptSyncValues.MAX_MODEL_ID);
        ScriptSyncValues.write(output, message.arguments);
    }

    public static ScriptSyncSnapshotMessage read(FriendlyByteBuf input) {
        return new ScriptSyncSnapshotMessage(input.readVarInt(), input.readUUID(),
                input.readVarLong(), input.readUtf(ScriptSyncValues.MAX_MODEL_ID),
                ScriptSyncValues.read(input));
    }

    public static void receive(ScriptSyncSnapshotMessage message,
                               IPayloadContext context) {
        if (context.flow() == PacketFlow.CLIENTBOUND) {
            context.enqueueWork(() -> ClientScriptEvents.accept(message));
        }
    }
}
