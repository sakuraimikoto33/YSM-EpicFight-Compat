package net.okitsu.ysmepicfightcompat.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingShieldBlockEvent;
import net.okitsu.ysmepicfightcompat.network.ServerShieldBlockObserver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** RETURN observes the final block result without modifying damage or shield use. */
@Mixin(value = CommonHooks.class, remap = false)
public abstract class ShieldBlockObserverMixin {
    @Inject(method = "onDamageBlock(Lnet/minecraft/world/entity/LivingEntity;Lnet/neoforged/neoforge/common/damagesource/DamageContainer;Z)Lnet/neoforged/neoforge/event/entity/living/LivingShieldBlockEvent;",
            at = @At("RETURN"), require = 1, remap = false)
    private static void ysmCompat$observeShieldBlock(
            LivingEntity entity, DamageContainer damage, boolean originallyBlocked,
            CallbackInfoReturnable<LivingShieldBlockEvent> callback) {
        ServerShieldBlockObserver.afterShieldBlock(entity, callback.getReturnValue());
    }
}
