package net.okitsu.ysmepicfightcompat.mixin;

import net.minecraftforge.common.ForgeConfigSpec;
import net.okitsu.ysmepicfightcompat.config.ClientPreferences;
import net.okitsu.ysmepicfightcompat.integration.configured.ConfiguredHeldItemRules;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/** Embeds the dynamic rule table in Configured's normal Client folder. */
@Pseudo
@Mixin(targets = "com.mrcrayfish.configured.impl.forge.ForgeFolderEntry",
        remap = false)
public abstract class ConfiguredForgeFolderEntryMixin {
    @Shadow
    @Final
    protected ForgeConfigSpec spec;

    @Shadow
    @Final
    protected List<String> path;

    @Unique
    private Object ysmEpicFightCompat$heldItemRules;

    @Inject(
            method = "getChildren()Ljava/util/List;",
            at = @At("RETURN"),
            cancellable = true,
            remap = false,
            require = 0
    )
    private void ysmEpicFightCompat$embedHeldItemRules(
            CallbackInfoReturnable<List<?>> info) {
        if (spec != ClientPreferences.CLIENT_SPEC
                || !path.equals(List.of("client"))) {
            return;
        }
        List<?> original = info.getReturnValue();
        List<Object> adjusted = new ArrayList<>(original.size());
        boolean replaced = false;
        for (Object entry : original) {
            if (ConfiguredHeldItemRules.isPlaceholder(entry)) {
                if (ysmEpicFightCompat$heldItemRules == null) {
                    ysmEpicFightCompat$heldItemRules =
                            ConfiguredHeldItemRules.createEntry();
                }
                adjusted.add(ysmEpicFightCompat$heldItemRules);
                replaced = true;
            } else {
                adjusted.add(entry);
            }
        }
        if (replaced) {
            info.setReturnValue(List.copyOf(adjusted));
        }
    }
}
