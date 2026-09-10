package net.okitsu.ysmepicfightcompat.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.attachment.AttachmentHolder;

/** Reads the stable serialized contract of official YSM's player attachment. */
public final class PlayerSelectionNbt {
    private static final String YSM_SELECTION = "yes_steve_model:model_id";

    public record Selection(String modelId, String textureName) {
    }

    private PlayerSelectionNbt() {
    }

    public static Selection read(Player player) {
        if (player == null) {
            return null;
        }
        try {
            return parse(player.saveWithoutId(new CompoundTag()));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    static Selection parse(CompoundTag root) {
        if (root == null || !root.contains(
                AttachmentHolder.ATTACHMENTS_NBT_KEY, CompoundTag.TAG_COMPOUND)) {
            return null;
        }
        CompoundTag attachments = root.getCompound(AttachmentHolder.ATTACHMENTS_NBT_KEY);
        if (!attachments.contains(YSM_SELECTION, CompoundTag.TAG_COMPOUND)) {
            return null;
        }
        CompoundTag selected = attachments.getCompound(YSM_SELECTION);
        if (selected.getBoolean("disabled")
                || !selected.contains("model_id", CompoundTag.TAG_STRING)
                || !selected.contains("select_texture", CompoundTag.TAG_STRING)) {
            return null;
        }
        String model = selected.getString("model_id");
        return model.isEmpty() ? null
                : new Selection(model, selected.getString("select_texture"));
    }
}
