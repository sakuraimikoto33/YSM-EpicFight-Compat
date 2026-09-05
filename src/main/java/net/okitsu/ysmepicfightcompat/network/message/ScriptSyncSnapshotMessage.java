package net.okitsu.ysmepicfightcompat.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import net.okitsu.ysmepicfightcompat.animation.ClientScriptEvents;
import net.okitsu.ysmepicfightcompat.network.ScriptSyncValues;

import java.util.UUID;
import java.util.function.Supplier;

/** Server-authoritative identity and replay ordering for a model's sync event. */
public record ScriptSyncSnapshotMessage(int entityId, UUID entityUuid, long sequence,
                                        String modelId, double[] arguments) {
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
                               Supplier<NetworkEvent.Context> suppliedContext) {
        NetworkEvent.Context context = suppliedContext.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> ClientScriptEvents.accept(message));
        }
        context.setPacketHandled(true);
    }
}
