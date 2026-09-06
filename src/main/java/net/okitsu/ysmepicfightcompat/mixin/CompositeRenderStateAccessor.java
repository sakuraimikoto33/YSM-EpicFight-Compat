package net.okitsu.ysmepicfightcompat.mixin;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Makes vanilla's protected shard types available without widening them at runtime. */
public final class CompositeRenderStateAccessor extends RenderStateShard {
    private CompositeRenderStateAccessor() {
        super("ysm_epicfight_compat:unused", () -> { }, () -> { });
        throw new UnsupportedOperationException("Accessor namespace");
    }

    /** The independent vanilla state shards copied by the model's culling policy. */
    @Mixin(RenderType.CompositeState.class)
    public interface State {
        @Accessor("textureState")
        RenderStateShard.EmptyTextureStateShard ysmCompat$texture();

        @Accessor("shaderState")
        RenderStateShard.ShaderStateShard ysmCompat$shader();

        @Accessor("transparencyState")
        RenderStateShard.TransparencyStateShard ysmCompat$transparency();

        @Accessor("depthTestState")
        RenderStateShard.DepthTestStateShard ysmCompat$depthTest();

        @Accessor("cullState")
        RenderStateShard.CullStateShard ysmCompat$cull();

        @Accessor("lightmapState")
        RenderStateShard.LightmapStateShard ysmCompat$lightmap();

        @Accessor("overlayState")
        RenderStateShard.OverlayStateShard ysmCompat$overlay();

        @Accessor("layeringState")
        RenderStateShard.LayeringStateShard ysmCompat$layering();

        @Accessor("outputState")
        RenderStateShard.OutputStateShard ysmCompat$output();

        @Accessor("texturingState")
        RenderStateShard.TexturingStateShard ysmCompat$texturing();

        @Accessor("writeMaskState")
        RenderStateShard.WriteMaskStateShard ysmCompat$writeMask();

        @Accessor("lineState")
        RenderStateShard.LineStateShard ysmCompat$line();

        @Accessor("colorLogicState")
        RenderStateShard.ColorLogicStateShard ysmCompat$colorLogic();
    }
}
