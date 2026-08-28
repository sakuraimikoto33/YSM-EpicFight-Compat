package net.okitsu.ysmepicfightcompat.ysmref;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.projectile.FishingHook;

/** Stable source alias remapped to official YSM by YSM Mapping API at runtime. */
final class CustomFishingHookRendererAlias {
    private CustomFishingHookRendererAlias() {
    }

    public static boolean tryRenderCustomHook(FishingHook hook, float entityYaw,
                                              float partialTick, PoseStack poseStack,
                                              MultiBufferSource buffers, int packedLight) {
        throw new UnsupportedOperationException("YSM Mapping API source alias");
    }
}
