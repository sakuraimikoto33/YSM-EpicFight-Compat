package net.okitsu.ysmepicfightcompat.mixin;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.event.entity.living.ShieldBlockEvent;
import net.okitsu.ysmepicfightcompat.network.ServerShieldBlockObserver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** RETURN runs after every ShieldBlockEvent listener has finalized cancellation and damage. */
@Mixin(value = ForgeHooks.class, remap = false)
public abstract class ShieldBlockObserverMixin {
    @Inject(method = "onShieldBlock(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/damagesource/DamageSource;F)Lnet/minecraftforge/event/entity/living/ShieldBlockEvent;",
            at = @At("RETURN"), require = 1, remap = false)
    private static void ysmCompat$observeShieldBlock(
            LivingEntity entity, DamageSource source, float damage,
            CallbackInfoReturnable<ShieldBlockEvent> callback) {
        ServerShieldBlockObserver.afterShieldBlock(entity, callback.getReturnValue());
    }
}
