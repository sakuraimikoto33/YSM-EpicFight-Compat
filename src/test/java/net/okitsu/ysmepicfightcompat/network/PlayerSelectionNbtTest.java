package net.okitsu.ysmepicfightcompat.network;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PlayerSelectionNbtTest {
    @Test
    void readsTheStableSerializedOfficialSelection() {
        PlayerSelectionNbt.Selection selection = PlayerSelectionNbt.parse(
                tag("example:model", "blue", false));

        assertEquals("example:model", selection.modelId());
        assertEquals("blue", selection.textureName());
    }

    @Test
    void disabledAndIncompleteSelectionsAreNotActive() {
        assertNull(PlayerSelectionNbt.parse(tag("example:model", "blue", true)));
        assertNull(PlayerSelectionNbt.parse(new CompoundTag()));
    }

    private static CompoundTag tag(String modelId, String texture, boolean disabled) {
        CompoundTag selected = new CompoundTag();
        selected.putString("model_id", modelId);
        selected.putString("select_texture", texture);
        selected.putBoolean("disabled", disabled);
        CompoundTag capabilities = new CompoundTag();
        capabilities.put("yes_steve_model:model_id", selected);
        CompoundTag root = new CompoundTag();
        root.put("ForgeCaps", capabilities);
        return root;
    }
}
