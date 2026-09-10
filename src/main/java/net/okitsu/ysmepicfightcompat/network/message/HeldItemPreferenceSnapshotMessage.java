package net.okitsu.ysmepicfightcompat.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.network.HeldItemModelDisplayState;
import net.okitsu.ysmepicfightcompat.network.RemoteHeldItemModelPreferences;

import java.util.UUID;

/** Server-to-client snapshot containing only one player's resolved display state. */
public record HeldItemPreferenceSnapshotMessage(UUID playerId,
                                                boolean mainHandYsm,
                                                boolean offHandYsm,
                                                boolean mainHandYsmSwitchAnimation,
                                                boolean offHandYsmSwitchAnimation) implements CustomPacketPayload {
    public static final Type<HeldItemPreferenceSnapshotMessage> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(CompatMod.MOD_ID, "held_item_preference_snapshot"));
    public static final StreamCodec<FriendlyByteBuf, HeldItemPreferenceSnapshotMessage> STREAM_CODEC =
            StreamCodec.of((output, message) -> write(message, output), HeldItemPreferenceSnapshotMessage::read);

    @Override
    public Type<HeldItemPreferenceSnapshotMessage> type() {
        return TYPE;
    }

    public HeldItemPreferenceSnapshotMessage {
        if (playerId == null) {
            throw new IllegalArgumentException("Missing player ID");
        }
    }

    public static void write(HeldItemPreferenceSnapshotMessage message,
                             FriendlyByteBuf output) {
        output.writeUUID(message.playerId());
        output.writeBoolean(message.mainHandYsm());
        output.writeBoolean(message.offHandYsm());
        output.writeBoolean(message.mainHandYsmSwitchAnimation());
        output.writeBoolean(message.offHandYsmSwitchAnimation());
    }

    public static HeldItemPreferenceSnapshotMessage read(FriendlyByteBuf input) {
        return new HeldItemPreferenceSnapshotMessage(input.readUUID(),
                input.readBoolean(), input.readBoolean(),
                input.readBoolean(), input.readBoolean());
    }

    public static void receive(HeldItemPreferenceSnapshotMessage message,
                               IPayloadContext context) {
        if (context.flow() == PacketFlow.CLIENTBOUND) {
            context.enqueueWork(() -> RemoteHeldItemModelPreferences.accept(
                    message.playerId(), new HeldItemModelDisplayState(
                            message.mainHandYsm(), message.offHandYsm(),
                            message.mainHandYsmSwitchAnimation(),
                            message.offHandYsmSwitchAnimation())));
        }
    }
}
