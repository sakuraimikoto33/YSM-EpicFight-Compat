package net.okitsu.ysmepicfightcompat.network;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.okitsu.ysmepicfightcompat.CompatMod;
import net.okitsu.ysmepicfightcompat.animation.MovementAnimationType;
import net.okitsu.ysmepicfightcompat.config.ClientPreferences;
import net.okitsu.ysmepicfightcompat.integration.tlm.TouhouMaidSelectionAccess;
import net.okitsu.ysmepicfightcompat.render.PlayerSelectionResolver;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Resolves local movement rules and synchronizes only the current pose decision. */
public final class ClientMovementAnimationPreferences {
    private static MovementAnimationDisplayState lastSent;
    private static MovementAnimationPolicy cachedPolicy = MovementAnimationPolicy.DEFAULT;
    private static Map<String, List<String>> cachedRules = Map.of();
    private static boolean cachedEnabled;
    private static boolean policyInitialized;
    private static boolean invalidRulesLogged;

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
        MovementAnimationDisplayState current = resolveState(modelId, movement);
        if (!current.equals(lastSent)) {
            lastSent = current;
            CompatNetwork.sendMovementAnimationPreferences(current);
        }
    }

    public static void beginConnection() {
        lastSent = null;
        invalidRulesLogged = false;
        RemoteMovementAnimationPreferences.beginConnection();
    }

    static MovementAnimationDisplayState resolveState(
            String modelId, @Nullable MovementAnimationType movement) {
        boolean ysmOwned = movement != null
                && localPolicy().usesYsm(modelId, movement);
        return new MovementAnimationDisplayState(modelId, movement, ysmOwned,
                ysmOwned && movement.isLadder()
                        && ClientPreferences.USE_NATURAL_LADDER_ANIMATIONS.get());
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
