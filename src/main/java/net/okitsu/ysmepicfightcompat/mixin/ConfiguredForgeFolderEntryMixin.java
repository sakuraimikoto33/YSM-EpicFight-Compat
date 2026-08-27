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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Embeds the dynamic rule tables in Configured's normal Client folder. */
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
    private final Map<String, Object> ysmEpicFightCompat$dynamicRules = new HashMap<>();

    @Inject(
            method = "getChildren()Ljava/util/List;",
            at = @At("RETURN"),
            cancellable = true,
            remap = false,
            require = 0
    )
    private void ysmEpicFightCompat$embedDynamicRules(
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
                String key = ConfiguredHeldItemRules.placeholderKey(entry);
                adjusted.add(ysmEpicFightCompat$dynamicRules.computeIfAbsent(
                        key, ignored -> ConfiguredHeldItemRules.createEntry(entry)));
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
