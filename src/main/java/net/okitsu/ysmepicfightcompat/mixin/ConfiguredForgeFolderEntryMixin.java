package net.okitsu.ysmepicfightcompat.mixin;

import net.neoforged.neoforge.common.ModConfigSpec;
import net.okitsu.ysmepicfightcompat.animation.ModAnimationType;
import net.okitsu.ysmepicfightcompat.config.ClientPreferences;
import net.okitsu.ysmepicfightcompat.integration.configured.ConfiguredOptionalSettings;
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

/** Adds dynamic rule editors and optional-setting visibility to the real Forge categories. */
@Pseudo
@Mixin(targets = "com.mrcrayfish.configured.impl.neoforge.NeoForgeFolderEntry",
        remap = false)
public abstract class ConfiguredForgeFolderEntryMixin {
    @Shadow
    @Final
    protected ModConfigSpec spec;

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
                || !(ConfiguredOptionalSettings.containsOptionalEntries(path)
                || ConfiguredHeldItemRules.containsRuleEntries(path))) {
            return;
        }
        List<?> original = info.getReturnValue();
        List<Object> adjusted = new ArrayList<>(original.size());
        boolean parCoolAvailable = ClientPreferences.isOptionalAnimationAvailable(ModAnimationType.PARCOOL);
        boolean swemAvailable = ClientPreferences.isOptionalAnimationAvailable(ModAnimationType.SWEM);
        for (Object entry : original) {
            if (!ConfiguredOptionalSettings.isVisibleClientEntry(
                    path, entry, parCoolAvailable, swemAvailable)) {
                continue;
            }
            if (ConfiguredHeldItemRules.isPlaceholder(path, entry)) {
                String key = ConfiguredHeldItemRules.placeholderKey(entry);
                adjusted.add(ysmEpicFightCompat$dynamicRules.computeIfAbsent(
                        key, ignored -> ConfiguredHeldItemRules.createEntry(entry)));
            } else {
                adjusted.add(entry);
            }
        }
        info.setReturnValue(List.copyOf(adjusted));
    }
}
