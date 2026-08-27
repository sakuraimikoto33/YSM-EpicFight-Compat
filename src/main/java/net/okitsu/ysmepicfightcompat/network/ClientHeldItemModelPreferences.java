package net.okitsu.ysmepicfightcompat.network;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.config.ClientPreferences;
import net.okitsu.ysmepicfightcompat.render.PlayerSelectionResolver;

import java.util.List;
import java.util.Map;

/** Resolves local rules and synchronizes only their per-hand display result. */
public final class ClientHeldItemModelPreferences {
    private static HeldItemModelDisplayState lastSent;
    private static HeldItemModelPolicy cachedPolicy = HeldItemModelPolicy.DEFAULT;
    private static HeldItemModelPolicy cachedSwitchAnimationPolicy =
            HeldItemModelPolicy.DEFAULT;
    private static Map<String, List<String>> cachedRules = Map.of();
    private static boolean cachedEnabled = true;
    private static Map<String, List<String>> cachedSwitchAnimationRules = Map.of();
    private static boolean cachedSwitchAnimationEnabled = true;
    private static boolean policyInitialized;
    private static boolean switchAnimationPolicyInitialized;
    private static boolean invalidRulesLogged;

    private ClientHeldItemModelPreferences() {
    }

    public static boolean usesYsm(LivingEntity entity, InteractionHand hand) {
        if (entity == null || hand == null) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Player local = minecraft.player;
        if (local != null && local.getUUID().equals(entity.getUUID())) {
            return localPolicy().usesYsm(selectedModelId(local),
                    entity.getItemInHand(hand));
        }
        return RemoteHeldItemModelPreferences.find(entity.getUUID()).usesYsm(hand);
    }

    /** Resolves the independent switch-animation rule for an Epic-rendered item. */
    public static boolean usesYsmSwitchAnimation(
            LivingEntity entity, InteractionHand hand) {
        if (entity == null || hand == null) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Player local = minecraft.player;
        if (local != null && local.getUUID().equals(entity.getUUID())) {
            return localSwitchAnimationPolicy().usesYsmForSwitchAnimation(
                    selectedModelId(local), entity.getItemInHand(hand));
        }
        return RemoteHeldItemModelPreferences.find(entity.getUUID())
                .usesYsmSwitchAnimation(hand);
    }

    public static void tickSync() {
        Minecraft minecraft = Minecraft.getInstance();
        Player local = minecraft.player;
        if (local == null || minecraft.getConnection() == null) {
            lastSent = null;
            return;
        }
        String modelId = selectedModelId(local);
        HeldItemModelPolicy policy = localPolicy();
        HeldItemModelPolicy switchAnimationPolicy = localSwitchAnimationPolicy();
        HeldItemModelDisplayState current = new HeldItemModelDisplayState(
                policy.usesYsm(modelId, local.getMainHandItem()),
                policy.usesYsm(modelId, local.getOffhandItem()),
                switchAnimationPolicy.usesYsmForSwitchAnimation(
                        modelId, local.getMainHandItem()),
                switchAnimationPolicy.usesYsmForSwitchAnimation(
                        modelId, local.getOffhandItem()));
        if (!current.equals(lastSent)) {
            lastSent = current;
            CompatNetwork.sendHeldItemPreferences(current);
        }
    }

    public static void beginConnection() {
        lastSent = null;
        invalidRulesLogged = false;
        RemoteHeldItemModelPreferences.beginConnection();
    }

    private static String selectedModelId(Player player) {
        PlayerSelectionResolver.Selection selection =
                PlayerSelectionResolver.current(player);
        return selection == null ? "" : selection.modelId();
    }

    private static HeldItemModelPolicy localPolicy() {
        boolean ysmEnabled =
                ClientPreferences.USE_YSM_HELD_ITEM_MODELS.get();
        Map<String, List<String>> rules =
                ClientPreferences.heldItemModelExclusions();
        if (policyInitialized && cachedEnabled == ysmEnabled
                && cachedRules.equals(rules)) {
            return cachedPolicy;
        }
        try {
            cachedPolicy = HeldItemModelPolicy.create(ysmEnabled, rules);
            cachedEnabled = ysmEnabled;
            cachedRules = Map.copyOf(rules);
            policyInitialized = true;
            invalidRulesLogged = false;
        } catch (IllegalArgumentException exception) {
            if (!invalidRulesLogged) {
                invalidRulesLogged = true;
                CompatMod.LOG.warn(
                        "YSM-EF Compat: invalid held-item model exclusions; using the main setting without exclusions",
                        exception);
            }
            cachedPolicy = HeldItemModelPolicy.create(ysmEnabled, Map.of());
            cachedEnabled = ysmEnabled;
            cachedRules = Map.copyOf(rules);
            policyInitialized = true;
        }
        return cachedPolicy;
    }

    private static HeldItemModelPolicy localSwitchAnimationPolicy() {
        boolean ysmEnabled = ClientPreferences
                .USE_YSM_HELD_ITEM_SWITCH_ANIMATIONS.get();
        Map<String, List<String>> rules =
                ClientPreferences.heldItemSwitchAnimationExclusions();
        if (switchAnimationPolicyInitialized
                && cachedSwitchAnimationEnabled == ysmEnabled
                && cachedSwitchAnimationRules.equals(rules)) {
            return cachedSwitchAnimationPolicy;
        }
        try {
            cachedSwitchAnimationPolicy =
                    HeldItemModelPolicy.create(ysmEnabled, rules);
            cachedSwitchAnimationEnabled = ysmEnabled;
            cachedSwitchAnimationRules = Map.copyOf(rules);
            switchAnimationPolicyInitialized = true;
            invalidRulesLogged = false;
        } catch (IllegalArgumentException exception) {
            if (!invalidRulesLogged) {
                invalidRulesLogged = true;
                CompatMod.LOG.warn(
                        "YSM-EF Compat: invalid held-item switch animation exclusions; using the main setting without exclusions",
                        exception);
            }
            cachedSwitchAnimationPolicy =
                    HeldItemModelPolicy.create(ysmEnabled, Map.of());
            cachedSwitchAnimationEnabled = ysmEnabled;
            cachedSwitchAnimationRules = Map.copyOf(rules);
            switchAnimationPolicyInitialized = true;
        }
        return cachedSwitchAnimationPolicy;
    }
}
