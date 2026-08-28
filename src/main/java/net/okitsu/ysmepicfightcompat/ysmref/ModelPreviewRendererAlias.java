package net.okitsu.ysmepicfightcompat.ysmref;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;

/** Stable source alias remapped to official YSM by YSM Mapping API at runtime. */
final class ModelPreviewRendererAlias {
    private ModelPreviewRendererAlias() {
    }

    public static void renderPlayerOverlay(GuiGraphics graphics, LocalPlayer player,
                                           double x, double y, float scale, float yawOffset,
                                           int depth, float partialTick) {
        throw new UnsupportedOperationException("YSM Mapping API source alias");
    }

    public static void renderVehicleModel(Entity rider, PoseStack poseStack,
                                          float partialTick) {
        throw new UnsupportedOperationException("YSM Mapping API source alias");
    }
}
