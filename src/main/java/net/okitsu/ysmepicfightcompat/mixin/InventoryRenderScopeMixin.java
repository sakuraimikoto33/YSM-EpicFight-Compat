package net.okitsu.ysmepicfightcompat.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.LivingEntity;
import net.okitsu.ysmepicfightcompat.render.InventoryRenderScope;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Carries inventory context only across the actual preview render, even on failure. */
@Mixin(InventoryScreen.class)
public abstract class InventoryRenderScopeMixin {
    @Redirect(
            method = "renderEntityInInventory(Lnet/minecraft/client/gui/GuiGraphics;IIILorg/joml/Quaternionf;Lorg/joml/Quaternionf;Lnet/minecraft/world/entity/LivingEntity;)V",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;runAsFancy(Ljava/lang/Runnable;)V",
                    remap = false),
            require = 1
    )
    private static void ysmCompat$renderInventoryScoped(
            Runnable draw, GuiGraphics graphics, int x, int y, int scale,
            Quaternionf pose, Quaternionf camera, LivingEntity entity) {
        InventoryRenderScope.Token scope = InventoryRenderScope.open(entity);
        try {
            RenderSystem.runAsFancy(draw);
        } finally {
            scope.close();
        }
    }
}
