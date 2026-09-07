package net.okitsu.ysmepicfightcompat.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import net.okitsu.ysmepicfightcompat.animation.ClientShieldBlockState;

import java.util.UUID;
import java.util.function.Supplier;

/** A server-observed successful shield block for one exact entity identity. */
public record ShieldBlockMessage(int entityId, UUID entityUuid) {
    public ShieldBlockMessage {
        if (entityId < 0 || entityUuid == null) {
            throw new IllegalArgumentException("Invalid shield block identity");
        }
    }

    public boolean matchesIdentity(int candidateId, UUID candidateUuid) {
        return entityId == candidateId && entityUuid.equals(candidateUuid);
    }

    public static void write(ShieldBlockMessage message, FriendlyByteBuf output) {
        output.writeVarInt(message.entityId());
        output.writeUUID(message.entityUuid());
    }

    public static ShieldBlockMessage read(FriendlyByteBuf input) {
        return new ShieldBlockMessage(input.readVarInt(), input.readUUID());
    }

    public static void receive(ShieldBlockMessage message,
            Supplier<NetworkEvent.Context> suppliedContext) {
        NetworkEvent.Context context = suppliedContext.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> ClientShieldBlockState.receive(message));
        }
        context.setPacketHandled(true);
    }
}
