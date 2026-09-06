package net.okitsu.ysmepicfightcompat.mixin;

import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Retains the original translucent sorting policy when copying a render type. */
@Mixin(RenderType.class)
public interface RenderTypeSortingAccessor {
    @Accessor("sortOnUpload")
    boolean ysmCompat$sortOnUpload();
}
