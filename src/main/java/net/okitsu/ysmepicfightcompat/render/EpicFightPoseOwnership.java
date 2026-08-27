package net.okitsu.ysmepicfightcompat.render;

import net.minecraft.world.entity.LivingEntity;
import yesman.epicfight.api.animation.AnimationPlayer;
import yesman.epicfight.api.animation.LivingMotion;
import yesman.epicfight.api.animation.LivingMotions;
import yesman.epicfight.api.animation.types.DynamicAnimation;
import yesman.epicfight.api.animation.types.EntityState;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.api.client.animation.ClientAnimator;
import yesman.epicfight.api.client.animation.Layer;
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

    public static boolean actionOwnsPose(
            LivingEntity entity, LivingEntityPatch<?> patch) {
        if (entity == null || patch == null) {
            return true;
        }
        EntityState state = patch.getEntityState();
        ClientAnimator animator = patch.getClientAnimator();
        if (state == null || animator == null) {
            return true;
        }
        return actionFlagsRequireEpicPose(
                state.inaction(), state.attacking(), state.hurt(), state.knockDown(),
                state.movementLocked(), entity.isUsingItem(), entity.swinging,
                animator.isAiming())
                || isActionMotion(animator.currentMotion())
                || isActionMotion(animator.currentCompositeMotion())
                || visibleActionAnimation(animator);
    }

    static boolean actionFlagsRequireEpicPose(
            boolean inaction, boolean attacking, boolean hurt, boolean knockDown,
            boolean movementLocked, boolean usingItem, boolean swinging,
            boolean aiming) {
        return inaction || attacking || hurt || knockDown || movementLocked
                || usingItem || swinging || aiming;
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
