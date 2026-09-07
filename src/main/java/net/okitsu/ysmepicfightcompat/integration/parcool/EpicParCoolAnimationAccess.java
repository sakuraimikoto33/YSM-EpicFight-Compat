package net.okitsu.ysmepicfightcompat.integration.parcool;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.fml.ModList;
import yesman.epicfight.api.animation.AnimationPlayer;
import yesman.epicfight.api.animation.types.DynamicAnimation;
import yesman.epicfight.api.animation.types.StaticAnimation;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.api.client.animation.ClientAnimator;
import yesman.epicfight.api.client.animation.Layer;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

import java.util.Set;

/** Read-only ownership of Epic ParCool's unique chain and wall traversal animations. */
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

    /** Classifies a public Epic Fight animation without loading optional mod classes. */
    public static boolean isReservedAnimation(DynamicAnimation animation) {
        if (animation == null) {
            return false;
        }
        // StaticAnimation#getRegistryName dereferences its accessor even after
        // invalidation. Read the nullable public accessor instead.
        AssetAccessor<? extends DynamicAnimation> own = animation.getAccessor();
        if (own != null && isReservedAnimationId(own.registryName())) {
            return true;
        }
        // A LinkAnimation exposes its destination through this public accessor.
        // Its registry name is enough; do not load or mutate the destination asset.
        AssetAccessor<? extends StaticAnimation> real = animation.getRealAnimation();
        return real != null && isReservedAnimationId(real.registryName());
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
