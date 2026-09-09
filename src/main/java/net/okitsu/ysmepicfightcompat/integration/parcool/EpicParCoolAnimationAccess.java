package net.okitsu.ysmepicfightcompat.integration.parcool;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.fml.ModList;
import yesman.epicfight.api.animation.AnimationPlayer;
import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.animation.types.DynamicAnimation;
import yesman.epicfight.api.animation.types.StaticAnimation;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.api.client.animation.ClientAnimator;
import yesman.epicfight.api.client.animation.Layer;
import yesman.epicfight.model.armature.types.ToolHolderArmature;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

import java.util.Set;
import java.util.function.Predicate;

/** Read-only movement ownership and tool attachment state exposed through Epic Fight. */
public final class EpicParCoolAnimationAccess {
    private static final String MOD_ID = "epicparcool";
    // Public identifiers registered by Epic ParCool's ParCoolAnimations builder.
    // Ordinary hanging, wall grabbing, wall running and wall jumping stay configurable.
    private static final Set<String> RESERVED_MOVEMENT_PATHS = Set.of(
            "biped/hang_down_move_start",
            "biped/hang_down_move_cross1",
            "biped/hang_down_move_cross2",
            "biped/hang_down_move_end1",
            "biped/hang_down_move_end2",
            "biped/hang_down_move_backward",
            "biped/hang_down_move_left",
            "biped/hang_down_move_right",
            "biped/cling_move_left",
            "biped/cling_move_right",
            "biped/cling_move_left_inner_corner1",
            "biped/cling_move_left_inner_corner2",
            "biped/cling_move_right_inner_corner1",
            "biped/cling_move_right_inner_corner2",
            "biped/cling_move_left_outer_corner1",
            "biped/cling_move_left_outer_corner2",
            "biped/cling_move_right_outer_corner1",
            "biped/cling_move_right_outer_corner2");

    private EpicParCoolAnimationAccess() {
    }

    public static boolean ownsMovementPose(LivingEntity entity) {
        if (entity == null || !isLoaded()) {
            return false;
        }
        LivingEntityPatch<?> patch = EpicFightCapabilities.getEntityPatch(
                entity, LivingEntityPatch.class);
        return ownsMovementPose(entity, patch);
    }

    public static boolean ownsMovementPose(LivingEntity entity, LivingEntityPatch<?> patch) {
        if (entity == null || patch == null || !isLoaded()) {
            return false;
        }
        ClientAnimator animator = patch.getClientAnimator();
        if (animator == null) {
            return false;
        }
        boolean[] found = {false};
        animator.iterVisibleLayersUntilFalse(layer -> {
            if (layer != null && animationsReserveMovementPose(
                    currentAnimation(layer), animation(layer.getNextAnimation()))) {
                found[0] = true;
                return false;
            }
            return true;
        });
        return found[0];
    }

    /**
     * Fast run uses Epic Fight's SET_TOOLS_BACK and REVERT_TO_HANDS events.
     * Observe their resolved attachment state as well as the visible animation so
     * animation selection alone cannot release the model's hands before stowing.
     */
    public static boolean fastRunToolsOnBack(LivingEntity entity) {
        if (entity == null || !isLoaded()) {
            return false;
        }
        LivingEntityPatch<?> patch = EpicFightCapabilities.getEntityPatch(
                entity, LivingEntityPatch.class);
        if (patch == null || !(patch.getArmature() instanceof ToolHolderArmature tools)
                || !toolsOnBack(patch.getParentJointOfHand(InteractionHand.MAIN_HAND),
                patch.getParentJointOfHand(InteractionHand.OFF_HAND), tools.backToolJoint())) {
            return false;
        }
        ClientAnimator animator = patch.getClientAnimator();
        if (animator == null) {
            return false;
        }
        boolean[] found = {false};
        animator.iterVisibleLayersUntilFalse(layer -> {
            if (layer != null && fastRunAnimationVisible(
                    currentAnimation(layer), layer.getNextAnimation())) {
                found[0] = true;
                return false;
            }
            return true;
        });
        return found[0];
    }

    static boolean toolsOnBack(Joint mainParent, Joint offParent, Joint backTool) {
        return backTool != null && mainParent == backTool && offParent == backTool;
    }

    static boolean fastRunAnimationVisible(
            DynamicAnimation current, AssetAccessor<? extends DynamicAnimation> queued) {
        return matchesAnimationId(current, EpicParCoolAnimationAccess::isFastRunAnimationId)
                || queued != null && isFastRunAnimationId(queued.registryName());
    }

    private static boolean isFastRunAnimationId(ResourceLocation id) {
        return id != null && MOD_ID.equals(id.getNamespace())
                && "biped/fast_run".equals(id.getPath());
    }

    /** Classifies a public Epic Fight animation without loading optional mod classes. */
    public static boolean isReservedAnimation(DynamicAnimation animation) {
        return matchesAnimationId(animation, EpicParCoolAnimationAccess::isReservedAnimationId);
    }

    private static boolean matchesAnimationId(
            DynamicAnimation animation, Predicate<ResourceLocation> matches) {
        if (animation == null) {
            return false;
        }
        // StaticAnimation#getRegistryName dereferences its accessor even after
        // invalidation. Read the nullable public accessor instead.
        AssetAccessor<? extends DynamicAnimation> own = animation.getAccessor();
        if (own != null && matches.test(own.registryName())) {
            return true;
        }
        // A LinkAnimation exposes its destination through this public accessor.
        // Its registry name is enough; do not load or mutate the destination asset.
        AssetAccessor<? extends StaticAnimation> real = animation.getRealAnimation();
        return real != null && matches.test(real.registryName());
    }

    static boolean animationsReserveMovementPose(
            DynamicAnimation current, DynamicAnimation next) {
        return isReservedAnimation(current) || isReservedAnimation(next);
    }

    static boolean isReservedAnimationId(ResourceLocation id) {
        return id != null && MOD_ID.equals(id.getNamespace())
                && RESERVED_MOVEMENT_PATHS.contains(id.getPath());
    }

    private static boolean isLoaded() {
        ModList mods = ModList.get();
        return mods != null && mods.isLoaded(MOD_ID);
    }

    private static DynamicAnimation currentAnimation(Layer layer) {
        AnimationPlayer player = layer.animationPlayer;
        return player == null || player.isEmpty() ? null : animation(player.getAnimation());
    }

    private static DynamicAnimation animation(
            AssetAccessor<? extends DynamicAnimation> accessor) {
        return accessor == null || accessor.isEmpty() ? null : accessor.get();
    }
}
