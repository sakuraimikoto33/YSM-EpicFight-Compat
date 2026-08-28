package net.okitsu.ysmepicfightcompat.ysmref;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;

/** Stable source alias remapped to official YSM by YSM Mapping API at runtime. */
final class CustomVehicleRendererAlias {
    private CustomVehicleRendererAlias() {
    }

    public static boolean renderVehicle(Entity vehicle, float entityYaw,
                                        float partialTick, PoseStack poseStack,
                                        MultiBufferSource buffers, int packedLight) {
        throw new UnsupportedOperationException("YSM Mapping API source alias");
    }
}
