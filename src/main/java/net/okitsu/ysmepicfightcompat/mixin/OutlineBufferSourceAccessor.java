package net.okitsu.ysmepicfightcompat.mixin;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Flushes only the normal delegate owned by the supplied outline wrapper. */
@Mixin(OutlineBufferSource.class)
public interface OutlineBufferSourceAccessor {
    @Accessor("bufferSource")
    MultiBufferSource.BufferSource ysmCompat$bufferSource();
}
