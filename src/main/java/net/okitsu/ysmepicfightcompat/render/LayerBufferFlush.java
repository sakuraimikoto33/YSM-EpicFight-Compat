package net.okitsu.ysmepicfightcompat.render;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.okitsu.ysmepicfightcompat.mixin.OutlineBufferSourceAccessor;

/** Establishes layer order on the renderer's supplied buffers, never global buffers. */
public final class LayerBufferFlush {
    private LayerBufferFlush() {
    }

    public static boolean supports(MultiBufferSource buffers) {
        return buffers instanceof MultiBufferSource.BufferSource
                || buffers instanceof OutlineBufferSource && buffers instanceof OutlineBufferSourceAccessor;
    }

    public static void flush(MultiBufferSource buffers) {
        if (buffers instanceof MultiBufferSource.BufferSource source) {
            source.endBatch();
        } else if (buffers instanceof OutlineBufferSource outline
                && buffers instanceof OutlineBufferSourceAccessor accessor) {
            accessor.ysmCompat$bufferSource().endBatch();
            outline.endOutlineBatch();
        } else {
            throw new IllegalArgumentException("Unsupported layer buffer source");
        }
    }
}
