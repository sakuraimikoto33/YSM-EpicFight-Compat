package net.okitsu.ysmepicfightcompat.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;
import net.okitsu.ysmepicfightcompat.animation.MovementAnimationType;
import net.okitsu.ysmepicfightcompat.network.HeldItemModelDisplayState;
import net.okitsu.ysmepicfightcompat.network.MaidPreferenceDisplayState;
import net.okitsu.ysmepicfightcompat.network.MovementAnimationPolicy;
import net.okitsu.ysmepicfightcompat.network.RemoteMaidPreferences;

import java.util.UUID;
import java.util.function.Supplier;

/** Server-authoritative maid inputs paired with owner-resolved cosmetic decisions. */
public record MaidPreferenceSnapshotMessage(MaidPreferenceDisplayState state) {
    public MaidPreferenceSnapshotMessage {
        if (state == null) {
            throw new IllegalArgumentException("Missing maid preference snapshot");
        }
    }

    public static void write(MaidPreferenceSnapshotMessage message,
                             FriendlyByteBuf output) {
        MaidPreferenceDisplayState state = message.state();
        output.writeUUID(state.entityUuid());
        output.writeUUID(state.ownerUuid());
        output.writeVarLong(state.revision());
        output.writeUtf(state.modelId(), MovementAnimationPolicy.MAX_MODEL_ID_LENGTH);
        output.writeResourceLocation(state.mainHandItem());
        output.writeResourceLocation(state.offHandItem());
        output.writeByte(state.movement() == null ? -1 : state.movement().ordinal());
        HeldItemModelDisplayState held = state.heldItems();
        output.writeBoolean(held.mainHandYsm());
        output.writeBoolean(held.offHandYsm());
        output.writeBoolean(held.mainHandYsmSwitchAnimation());
        output.writeBoolean(held.offHandYsmSwitchAnimation());
        output.writeBoolean(state.ysmMovement());
    }

    public static MaidPreferenceSnapshotMessage read(FriendlyByteBuf input) {
        UUID entityUuid = input.readUUID();
        UUID ownerUuid = input.readUUID();
        long revision = input.readVarLong();
        String modelId = input.readUtf(MovementAnimationPolicy.MAX_MODEL_ID_LENGTH);
        ResourceLocation mainHandItem = input.readResourceLocation();
        ResourceLocation offHandItem = input.readResourceLocation();
        int ordinal = input.readByte();
        MovementAnimationType movement;
        if (ordinal == -1) {
            movement = null;
        } else if (ordinal >= 0 && ordinal < MovementAnimationType.values().length) {
            movement = MovementAnimationType.values()[ordinal];
        } else {
            throw new IllegalArgumentException("Invalid maid movement snapshot");
        }
        HeldItemModelDisplayState held = new HeldItemModelDisplayState(
                input.readBoolean(), input.readBoolean(),
                input.readBoolean(), input.readBoolean());
        return new MaidPreferenceSnapshotMessage(new MaidPreferenceDisplayState(
                entityUuid, ownerUuid, revision, modelId,
                mainHandItem, offHandItem, movement, held, input.readBoolean()));
    }

    public static void receive(MaidPreferenceSnapshotMessage message,
                               Supplier<NetworkEvent.Context> suppliedContext) {
        NetworkEvent.Context context = suppliedContext.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> RemoteMaidPreferences.accept(message.state()));
        }
        context.setPacketHandled(true);
    }
}
