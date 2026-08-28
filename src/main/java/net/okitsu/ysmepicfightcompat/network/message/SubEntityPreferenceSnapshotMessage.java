package net.okitsu.ysmepicfightcompat.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import net.okitsu.ysmepicfightcompat.network.RemoteSubEntityModelPreferences;
import net.okitsu.ysmepicfightcompat.network.SubEntityModelDisplayState;
import net.okitsu.ysmepicfightcompat.network.SubEntityModelKind;

import java.util.UUID;
import java.util.function.Supplier;

/** Server-authoritative sub-entity source paired with the resolved display result. */
public record SubEntityPreferenceSnapshotMessage(SubEntityModelDisplayState state) {
    public SubEntityPreferenceSnapshotMessage {
        if (state == null) {
            throw new IllegalArgumentException("Missing sub-entity preference snapshot");
        }
    }

    public static void write(SubEntityPreferenceSnapshotMessage message,
                             FriendlyByteBuf output) {
        SubEntityModelDisplayState state = message.state();
        output.writeVarInt(state.entityId());
        output.writeUUID(state.entityUuid());
        output.writeUUID(state.ownerUuid());
        output.writeVarLong(state.revision());
        output.writeByte(state.kind().ordinal());
        output.writeResourceLocation(state.entityTypeId());
        output.writeBoolean(state.epicFightRendering());
        output.writeBoolean(state.known());
        output.writeBoolean(state.ysm());
    }

    public static SubEntityPreferenceSnapshotMessage read(FriendlyByteBuf input) {
        int entityId = input.readVarInt();
        UUID entityUuid = input.readUUID();
        UUID ownerUuid = input.readUUID();
        long revision = input.readVarLong();
        SubEntityModelKind kind =
                SubEntityModelKind.fromNetworkId(input.readUnsignedByte());
        return new SubEntityPreferenceSnapshotMessage(new SubEntityModelDisplayState(
                entityId, entityUuid, ownerUuid, revision, kind,
                input.readResourceLocation(),
                input.readBoolean(),
                input.readBoolean(), input.readBoolean()));
    }

    public static void receive(SubEntityPreferenceSnapshotMessage message,
                               Supplier<NetworkEvent.Context> suppliedContext) {
        NetworkEvent.Context context = suppliedContext.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() ->
                    RemoteSubEntityModelPreferences.accept(message.state()));
        }
        context.setPacketHandled(true);
    }
}
