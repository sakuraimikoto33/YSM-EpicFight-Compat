package net.okitsu.ysmepicfightcompat.network;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.animation.ModAnimationResolver;
import net.okitsu.ysmepicfightcompat.animation.ModAnimationSample;
import net.okitsu.ysmepicfightcompat.animation.ModAnimationType;
import net.okitsu.ysmepicfightcompat.animation.MovementAnimationType;
import net.okitsu.ysmepicfightcompat.config.ClientPreferences;
import net.okitsu.ysmepicfightcompat.integration.tlm.TouhouMaidSelectionAccess;
import net.okitsu.ysmepicfightcompat.render.PlayerSelectionResolver;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Resolves local movement/mod rules and synchronizes only the current pose decisions. */
public final class ClientMovementAnimationPreferences {
    private static MovementAnimationDisplayState lastSent;
    private static MovementAnimationPolicy cachedPolicy = MovementAnimationPolicy.DEFAULT;
    private static Map<String, List<String>> cachedRules = Map.of();
    private static boolean cachedEnabled;
    private static boolean policyInitialized;
    private static boolean invalidRulesLogged;
    private static final Map<ModAnimationType, CachedModPolicy> MOD_POLICIES =
            new EnumMap<>(ModAnimationType.class);
    private static final EnumSet<ModAnimationType> INVALID_MOD_RULES_LOGGED =
            EnumSet.noneOf(ModAnimationType.class);

    private record CachedModPolicy(boolean enabled, Map<String, List<String>> rules,
                                   ModAnimationPolicy policy) { }

    private ClientMovementAnimationPreferences() {
    }

    public static boolean usesYsm(
            LivingEntity entity, String selectedModelId,
            MovementAnimationType movement) {
        if (entity == null || movement == null) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Player local = minecraft.player;
        if (local != null && local.getUUID().equals(entity.getUUID())) {
            return localPolicy().usesYsm(selectedModelId, movement);
        }
        if (TouhouMaidSelectionAccess.isSupportedMaid(entity)) {
            UUID ownerUuid = TouhouMaidSelectionAccess.ownerUuid(entity);
            if (local != null && local.getUUID().equals(ownerUuid)) {
                return localPolicy().usesYsm(selectedModelId, movement);
            }
            return RemoteMaidPreferences.movement(entity, selectedModelId)
                    .usesYsm(selectedModelId, movement);
        }
        if (!(entity instanceof Player)) {
            return localPolicy().usesYsm(selectedModelId, movement);
        }
        return RemoteMovementAnimationPreferences.find(entity.getUUID())
                .usesYsm(selectedModelId, movement);
    }

    /** A current canonical clip is required to resolve optional-mod pose ownership. */
    public static boolean usesYsmMod(
            LivingEntity entity, String selectedModelId, ModAnimationType modAnimation) {
        return usesYsmMod(entity, selectedModelId, modAnimation, "");
    }

    /** Optional-mod poses follow the player's per-clip decision, never the observer's rules. */
    public static boolean usesYsmMod(
            LivingEntity entity, String selectedModelId, ModAnimationType modAnimation,
            String modAnimationClip) {
        if (!(entity instanceof Player) || modAnimation == null) {
            return false;
        }
        Player local = Minecraft.getInstance().player;
        return usesYsmMod(entity.getUUID(), local == null ? null : local.getUUID(),
                selectedModelId, modAnimation, modAnimationClip);
    }

    static boolean usesYsmMod(
            UUID playerId, @Nullable UUID localPlayerId,
            String selectedModelId, @Nullable ModAnimationType modAnimation) {
        return usesYsmMod(playerId, localPlayerId, selectedModelId, modAnimation, "");
    }

    static boolean usesYsmMod(
            UUID playerId, @Nullable UUID localPlayerId,
            String selectedModelId, @Nullable ModAnimationType modAnimation,
            String modAnimationClip) {
        if (playerId == null || modAnimation == null) {
            return false;
        }
        return playerId.equals(localPlayerId)
                ? localModAnimationOwned(selectedModelId, modAnimation, modAnimationClip)
                : RemoteMovementAnimationPreferences.find(playerId)
                        .usesYsmMod(selectedModelId, modAnimation, modAnimationClip);
    }

    /** Resolves only the current visual choice; the underlying client setting is not sent. */
    public static boolean usesNaturalLadderPose(
            LivingEntity entity, String selectedModelId,
            MovementAnimationType movement) {
        if (entity == null || movement == null || !movement.isLadder()) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Player local = minecraft.player;
        if (local != null && local.getUUID().equals(entity.getUUID())) {
            return localNaturalLadderPose(selectedModelId, movement);
        }
        if (!(entity instanceof Player)) {
            return false;
        }
        return RemoteMovementAnimationPreferences.find(entity.getUUID())
                .usesNaturalLadderPose(selectedModelId, movement);
    }

    /**
     * RemotePlayer velocity and creative-flight abilities are not authoritative on
     * observing clients. Use the owner's synchronized semantic state to choose the
     * matching YSM clip; local players continue to use their live entity state.
     */
    @Nullable
    public static MovementAnimationType remoteMovementOverride(
            LivingEntity entity, String selectedModelId) {
        if (entity == null) {
            return null;
        }
        Player local = Minecraft.getInstance().player;
        if (local != null && local.getUUID().equals(entity.getUUID())) {
            return null;
        }
        if (TouhouMaidSelectionAccess.isSupportedMaid(entity)) {
            UUID ownerUuid = TouhouMaidSelectionAccess.ownerUuid(entity);
            return local != null && local.getUUID().equals(ownerUuid) ? null
                    : RemoteMaidPreferences.movement(entity, selectedModelId)
                    .semanticMovementFor(selectedModelId);
        }
        if (!(entity instanceof Player)) {
            return null;
        }
        return RemoteMovementAnimationPreferences.find(entity.getUUID())
                .semanticMovementFor(selectedModelId);
    }

    public static void tickSync() {
        Minecraft minecraft = Minecraft.getInstance();
        Player local = minecraft.player;
        if (local == null || minecraft.getConnection() == null) {
            lastSent = null;
            return;
        }
        String modelId = selectedModelId(local);
        MovementAnimationType movement = MovementAnimationType.resolve(local);
        ModAnimationSample modSample = ModAnimationResolver.sample(local, 0.0F);
        MovementAnimationDisplayState current = resolveState(
                modelId, movement, modSample == null ? null : modSample.type(),
                modSample == null ? "" : modSample.clipName());
        if (!current.equals(lastSent)) {
            lastSent = current;
            CompatNetwork.sendMovementAnimationPreferences(current);
        }
    }

    public static void beginConnection() {
        lastSent = null;
        invalidRulesLogged = false;
        MOD_POLICIES.clear();
        INVALID_MOD_RULES_LOGGED.clear();
        RemoteMovementAnimationPreferences.beginConnection();
        ModAnimationResolver.clear();
    }

    static MovementAnimationDisplayState resolveState(
            String modelId, @Nullable MovementAnimationType movement) {
        return resolveState(modelId, movement, null);
    }

    static MovementAnimationDisplayState resolveState(
            String modelId, @Nullable MovementAnimationType movement,
            @Nullable ModAnimationType modAnimation) {
        return resolveState(modelId, movement, modAnimation, "");
    }

    static MovementAnimationDisplayState resolveState(
            String modelId, @Nullable MovementAnimationType movement,
            @Nullable ModAnimationType modAnimation, String modAnimationClip) {
        boolean ysmOwned = movement != null
                && localPolicy().usesYsm(modelId, movement);
        return new MovementAnimationDisplayState(modelId, movement, ysmOwned,
                ysmOwned && movement.isLadder()
                        && ClientPreferences.USE_NATURAL_LADDER_ANIMATIONS.get(),
                modAnimation, localModAnimationOwned(modelId, modAnimation, modAnimationClip),
                modAnimationClip);
    }

    private static boolean localModAnimationOwned(
            String modelId, @Nullable ModAnimationType modAnimation, String modAnimationClip) {
        if (modAnimation == null || !MovementAnimationPolicy.isValidModelId(
                MovementAnimationPolicy.normalizeModelId(modelId))) {
            return false;
        }
        return localModPolicy(modAnimation).usesYsm(modelId, modAnimationClip);
    }

    private static ModAnimationPolicy localModPolicy(ModAnimationType modAnimation) {
        boolean enabled = switch (modAnimation) {
            case PARCOOL -> ClientPreferences.USE_YSM_PARCOOL_ANIMATIONS.get();
            case SWEM -> ClientPreferences.USE_YSM_SWEM_ANIMATIONS.get();
        };
        Map<String, List<String>> rules = switch (modAnimation) {
            case PARCOOL -> ClientPreferences.parCoolAnimationExclusions();
            case SWEM -> ClientPreferences.swemAnimationExclusions();
        };
        CachedModPolicy cached = MOD_POLICIES.get(modAnimation);
        if (cached != null && cached.enabled() == enabled && cached.rules().equals(rules)) {
            return cached.policy();
        }
        ModAnimationPolicy policy;
        try {
            policy = ModAnimationPolicy.create(modAnimation, enabled, rules);
            INVALID_MOD_RULES_LOGGED.remove(modAnimation);
        } catch (IllegalArgumentException exception) {
            if (INVALID_MOD_RULES_LOGGED.add(modAnimation)) {
                CompatMod.LOG.warn(
                        "YSM-EF Compat: invalid {} animation exclusions; using the main setting without exclusions",
                        modAnimation, exception);
            }
            policy = ModAnimationPolicy.create(modAnimation, enabled, Map.of());
        }
        MOD_POLICIES.put(modAnimation, new CachedModPolicy(enabled, Map.copyOf(rules), policy));
        return policy;
    }

    private static boolean localNaturalLadderPose(
            String modelId, MovementAnimationType movement) {
        return movement.isLadder()
                && ClientPreferences.USE_NATURAL_LADDER_ANIMATIONS.get()
                && localPolicy().usesYsm(modelId, movement);
    }

    private static String selectedModelId(Player player) {
        PlayerSelectionResolver.Selection selection =
                PlayerSelectionResolver.current(player);
        return selection == null ? "" : selection.modelId();
    }

    private static MovementAnimationPolicy localPolicy() {
        boolean ysmEnabled =
                ClientPreferences.USE_YSM_MOVEMENT_ANIMATIONS.get();
        Map<String, List<String>> rules =
                ClientPreferences.movementAnimationExclusions();
        if (policyInitialized && cachedEnabled == ysmEnabled
                && cachedRules.equals(rules)) {
            return cachedPolicy;
        }
        try {
            cachedPolicy = MovementAnimationPolicy.create(ysmEnabled, rules);
            invalidRulesLogged = false;
        } catch (IllegalArgumentException exception) {
            if (!invalidRulesLogged) {
                invalidRulesLogged = true;
                CompatMod.LOG.warn(
                        "YSM-EF Compat: invalid movement-animation exclusions; using the main setting without exclusions",
                        exception);
            }
            cachedPolicy = MovementAnimationPolicy.create(ysmEnabled, Map.of());
        }
        cachedEnabled = ysmEnabled;
        cachedRules = Map.copyOf(rules);
        policyInitialized = true;
        return cachedPolicy;
    }
}
