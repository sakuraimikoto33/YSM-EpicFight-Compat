package net.okitsu.ysmepicfightcompat.ysmref;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;

/** Stable source alias remapped to official YSM by YSM Mapping API at runtime. */
final class ModelPreviewRendererAlias {
    private ModelPreviewRendererAlias() {
    }

    public static void renderPlayerOverlay(GuiGraphics graphics, LocalPlayer player,
                                           double x, double y, float scale, float yawOffset,
                                           int depth, float partialTick) {
        throw new UnsupportedOperationException("YSM Mapping API source alias");
    }
}
