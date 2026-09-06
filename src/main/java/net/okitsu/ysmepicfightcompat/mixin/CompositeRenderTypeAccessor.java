package net.okitsu.ysmepicfightcompat.mixin;

import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads vanilla render state without depending on another mod's access transformer. */
@Mixin(targets = "net.minecraft.client.renderer.RenderType$CompositeRenderType")
public interface CompositeRenderTypeAccessor {
    @Accessor("state")
    RenderType.CompositeState ysmCompat$state();
}
