package net.okitsu.ysmepicfightcompat.render;

import net.minecraft.world.entity.LivingEntity;
import net.okitsu.ysmepicfightcompat.integration.parcool.EpicParCoolAnimationAccess;
import yesman.epicfight.api.animation.AnimationPlayer;
import yesman.epicfight.api.animation.LivingMotion;
import yesman.epicfight.api.animation.LivingMotions;
import yesman.epicfight.api.animation.types.AttackAnimation;
import yesman.epicfight.api.animation.types.DynamicAnimation;
import yesman.epicfight.api.animation.types.EntityState;
import yesman.epicfight.api.animation.types.GuardAnimation;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.api.client.animation.ClientAnimator;
import yesman.epicfight.api.client.animation.Layer;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

import java.util.Set;

/** Decides when an Epic Fight action must take precedence over configured YSM locomotion. */
public final class EpicFightPoseOwnership {
    private static final Set<LivingMotions> ACTION_MOTIONS = Set.of(
            LivingMotions.INACTION,
            LivingMotions.AIM,
            LivingMotions.BLOCK,
            LivingMotions.BLOCK_SHIELD,
            LivingMotions.RELOAD,
            LivingMotions.SHOT,
            LivingMotions.SPELLCAST,
            LivingMotions.DIGGING,
            LivingMotions.DRINK,
            LivingMotions.EAT,
            LivingMotions.DEATH,
            LivingMotions.SLEEP,
            LivingMotions.LANDING_RECOVERY);

    private EpicFightPoseOwnership() {
    }

    /**
     * Protects combat while allowing an independently observed native parkour/riding
     * pose. Generic INACTION, movement locks and main-frame animations are not combat
     * evidence: parkour bridges use those same public Epic Fight mechanisms.
     */
    public static boolean combatActionOwnsPose(LivingEntity entity) {
        if (entity == null) {
            return false;
        }
        LivingEntityPatch<?> patch = EpicFightCapabilities.getEntityPatch(
                entity, LivingEntityPatch.class);
        if (patch == null) {
            return false;
        }
        EntityState state = patch.getEntityState();
        ClientAnimator animator = patch.getClientAnimator();
        if (state == null || animator == null) {
            return true;
        }
        return entity.isDeadOrDying() || entity.isSleeping() || entity.hurtTime > 0
                || combatFlagsRequireEpicPose(state, entity.isUsingItem(),
                entity.swinging, animator.isAiming())
                || isCombatMotion(animator.currentMotion())
                || isCombatMotion(animator.currentCompositeMotion())
                || visibleCombatAnimation(animator);
    }

    static boolean combatFlagsRequireEpicPose(
            EntityState state, boolean usingItem, boolean swinging, boolean aiming) {
        return state == null || state.attacking() || state.hurt() || state.knockDown()
                || usingItem || swinging || aiming;
    }

    static boolean isCombatMotion(LivingMotion motion) {
        return motion != LivingMotions.INACTION && motion != LivingMotions.LANDING_RECOVERY
                && isActionMotion(motion);
    }

    private static boolean visibleCombatAnimation(ClientAnimator animator) {
        boolean[] found = {false};
        animator.iterVisibleLayersUntilFalse(layer -> {
            if (layer != null && combatAnimationsRequireEpicPose(
                    currentAnimation(layer), nextAnimation(layer))) {
                found[0] = true;
                return false;
            }
            return true;
        });
        return found[0];
    }

    static boolean combatAnimationsRequireEpicPose(
            DynamicAnimation current, DynamicAnimation next) {
        return isCombatAnimation(current) || isCombatAnimation(next);
    }

    static boolean isCombatAnimation(DynamicAnimation current) {
        if (current == null) {
            return false;
        }
        if (isCombatAnimationKind(current.getClass(), current.isReboundAnimation())) {
            return true;
        }
        // LinkAnimation publicly exposes its target here, including attack startup
        // before ATTACKING becomes true. An AttackAnimation's recovery remains covered.
        DynamicAnimation real = animation(current.getRealAnimation());
        return real != null
                && isCombatAnimationKind(real.getClass(), real.isReboundAnimation());
    }

    static boolean isCombatAnimationKind(
            Class<? extends DynamicAnimation> animationType, boolean rebound) {
        return rebound || animationType != null
                && (AttackAnimation.class.isAssignableFrom(animationType)
                || GuardAnimation.class.isAssignableFrom(animationType));
    }

    public static boolean actionOwnsPose(
            LivingEntity entity, LivingEntityPatch<?> patch) {
        if (entity == null || patch == null) {
            return true;
        }
        if (EpicParCoolAnimationAccess.ownsMovementPose(entity, patch)) {
            return true;
        }
        EntityState state = patch.getEntityState();
        ClientAnimator animator = patch.getClientAnimator();
        if (state == null || animator == null) {
            return true;
        }
        LivingMotion currentMotion = animator.currentMotion();
        LivingMotion currentCompositeMotion = animator.currentCompositeMotion();
        boolean ordinaryClimb = isOrdinaryClimbMotion(
                entity.onClimbable(), currentMotion, currentCompositeMotion);
        return actionFlagsRequireEpicPose(
                state.inaction(), state.attacking(), state.hurt(), state.knockDown(),
                state.movementLocked(), entity.isUsingItem(), entity.swinging,
                animator.isAiming(), ordinaryClimb)
                || isActionMotion(currentMotion)
                || isActionMotion(currentCompositeMotion)
                || visibleActionAnimation(animator);
    }

    static boolean actionFlagsRequireEpicPose(
            boolean inaction, boolean attacking, boolean hurt, boolean knockDown,
            boolean movementLocked, boolean usingItem, boolean swinging,
            boolean aiming) {
        return actionFlagsRequireEpicPose(inaction, attacking, hurt, knockDown,
                movementLocked, usingItem, swinging, aiming, false);
    }

    static boolean actionFlagsRequireEpicPose(
            boolean inaction, boolean attacking, boolean hurt, boolean knockDown,
            boolean movementLocked, boolean usingItem, boolean swinging,
            boolean aiming, boolean ordinaryClimb) {
        return (!ordinaryClimb && (inaction || movementLocked))
                || attacking || hurt || knockDown
                || usingItem || swinging || aiming;
    }

    /**
     * Epic Fight's ordinary BIPED_CLIMBING movement intentionally sets both INACTION
     * and MOVEMENT_LOCKED. Those flags describe its locomotion constraints, not an
     * attack/action pose, and must not revoke a configured complete YSM ladder pose.
     */
    static boolean isOrdinaryClimbMotion(
            boolean onClimbable, LivingMotion current, LivingMotion composite) {
        return onClimbable && (current == LivingMotions.CLIMB
                || composite == LivingMotions.CLIMB);
    }

    /**
     * Action layers can become visible before their entity-state flags or composite
     * motion catch up. Inspect both the playing animation and the queued transition
     * target so configured YSM locomotion never overwrites an Epic Fight startup,
     * main-frame, recovery, or rebound pose.
     */
    private static boolean visibleActionAnimation(ClientAnimator animator) {
        boolean[] found = {false};
        animator.iterVisibleLayersUntilFalse(layer -> {
            if (layer != null && (isActionAnimation(currentAnimation(layer))
                    || isActionAnimation(nextAnimation(layer)))) {
                found[0] = true;
                return false;
            }
            return true;
        });
        return found[0];
    }

    private static DynamicAnimation currentAnimation(Layer layer) {
        AnimationPlayer player = layer.animationPlayer;
        return player == null || player.isEmpty()
                ? null : animation(player.getAnimation());
    }

    private static DynamicAnimation nextAnimation(Layer layer) {
        return animation(layer.getNextAnimation());
    }

    private static DynamicAnimation animation(
            AssetAccessor<? extends DynamicAnimation> accessor) {
        return accessor == null || accessor.isEmpty() ? null : accessor.get();
    }

    static boolean isActionAnimation(DynamicAnimation animation) {
        return animation != null
                && (animation.isMainFrameAnimation() || animation.isReboundAnimation());
    }

    static boolean isActionMotion(LivingMotion motion) {
        // LivingMotion#isSame intentionally aliases IDLE and INACTION. That is useful
        // to Epic Fight's living-motion resolver, but it would make an ordinary idle
        // pose look like an action and revoke configured YSM locomotion (notably while
        // creative-flying). Pose ownership needs the exact built-in action motion.
        return motion != null && ACTION_MOTIONS.contains(motion);
    }
}
