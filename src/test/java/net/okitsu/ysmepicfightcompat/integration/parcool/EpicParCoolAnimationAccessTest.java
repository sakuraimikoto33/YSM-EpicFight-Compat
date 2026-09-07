package net.okitsu.ysmepicfightcompat.integration.parcool;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import yesman.epicfight.api.animation.AnimationManager;
import yesman.epicfight.api.animation.types.DynamicAnimation;
import yesman.epicfight.api.animation.types.LinkAnimation;
import yesman.epicfight.api.animation.types.StaticAnimation;
import yesman.epicfight.api.asset.AssetAccessor;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EpicParCoolAnimationAccessTest {
    @Test
    void recognizesAllEighteenUniqueTraversalAnimationIds() {
        Set<String> paths = new HashSet<>();
        for (String suffix : new String[]{"start", "cross1", "cross2", "end1", "end2",
                "backward", "left", "right"}) {
            paths.add("biped/hang_down_move_" + suffix);
        }
        for (String side : new String[]{"left", "right"}) {
            paths.add("biped/cling_move_" + side);
            for (String corner : new String[]{"inner_corner1", "inner_corner2",
                    "outer_corner1", "outer_corner2"}) {
                paths.add("biped/cling_move_" + side + "_" + corner);
            }
        }
        assertEquals(18, paths.size());
        for (String path : paths) {
            assertTrue(EpicParCoolAnimationAccess.isReservedAnimationId(
                    ResourceLocation.fromNamespaceAndPath("epicparcool", path)), path);
        }
    }

    @Test
    void ordinaryParCoolAnimationsRemainConfigurable() {
        for (String path : new String[]{"hang_down", "hang_down_orthogonal",
                "hang_down_inertia", "hang_down_inertia_orthogonal", "cling_to_cliff",
                "cling_to_cliff_inner_corner", "cling_to_cliff_outer_corner",
                "cling_to_cliff_left", "cling_to_cliff_right", "cling_start",
                "cling_start_inner_corner", "cling_start_outer_corner", "wall_run_left",
                "wall_run_right", "wall_run_vertical", "wall_jump_left_start",
                "wall_jump_left", "wall_jump_right_start", "wall_jump_right",
                "wall_slide_left", "wall_slide_right", "jump_from_bar", "climb_up",
                "fast_run", "cat_leap", "vault_forward", "ride_zipline_forward"}) {
            assertFalse(EpicParCoolAnimationAccess.isReservedAnimationId(
                    ResourceLocation.fromNamespaceAndPath("epicparcool", "biped/" + path)), path);
        }
    }

    @Test
    void requiresExactNamespaceAndKnownPathRatherThanBroadPrefixes() {
        assertFalse(EpicParCoolAnimationAccess.isReservedAnimationId(null));
        for (String namespace : new String[]{"minecraft", "epicfight", "parcool", "custom"}) {
            assertFalse(EpicParCoolAnimationAccess.isReservedAnimationId(
                    ResourceLocation.fromNamespaceAndPath(namespace, "biped/hang_down_move_start")));
        }
        for (String path : new String[]{"biped/hang_down_move_forward_start",
                "biped/hang_down_move_unknown", "biped/cling_move_left_inner_corner3",
                "biped/cling_move", "custom/biped/cling_move_left"}) {
            assertFalse(EpicParCoolAnimationAccess.isReservedAnimationId(
                    ResourceLocation.fromNamespaceAndPath("epicparcool", path)), path);
        }
    }

    @Test
    void detectsDirectAnimationWithoutOptionalModClassInitialization() {
        assertTrue(EpicParCoolAnimationAccess.isReservedAnimation(
                named("epicparcool:biped/cling_move_right", null)));
        assertFalse(EpicParCoolAnimationAccess.isReservedAnimation(
                named("epicparcool:biped/wall_run_right", null)));
        assertFalse(EpicParCoolAnimationAccess.isReservedAnimation(null));
    }

    @Test
    void detectsLinkedTargetByPublicIdentifierWithoutLoadingItsAsset() {
        assertTrue(EpicParCoolAnimationAccess.isReservedAnimation(
                named("epicfight:link", "epicparcool:biped/hang_down_move_end2")));
        assertFalse(EpicParCoolAnimationAccess.isReservedAnimation(
                named("epicfight:link", "epicparcool:biped/wall_jump_right")));
        assertFalse(EpicParCoolAnimationAccess.isReservedAnimation(
                named("epicfight:link", null)));
    }

    @Test
    void currentAndQueuedTraversalBothProtectTransitionBoundaries() {
        DynamicAnimation ordinary = named("epicparcool:biped/hang_down", null);
        DynamicAnimation traversal = named("epicparcool:biped/hang_down_move_cross1", null);
        assertTrue(EpicParCoolAnimationAccess.animationsReserveMovementPose(traversal, ordinary));
        assertTrue(EpicParCoolAnimationAccess.animationsReserveMovementPose(ordinary, traversal));
        assertTrue(EpicParCoolAnimationAccess.animationsReserveMovementPose(null, traversal));
        assertFalse(EpicParCoolAnimationAccess.animationsReserveMovementPose(ordinary, null));
        assertFalse(EpicParCoolAnimationAccess.animationsReserveMovementPose(null, null));
    }

    @Test
    void missingEntityAndPatchDoNotReserveMovement() {
        assertFalse(EpicParCoolAnimationAccess.ownsMovementPose(null));
        assertFalse(EpicParCoolAnimationAccess.ownsMovementPose(null, null));
    }

    @Test
    void realRuntimeLinkHandlesUnsetAndRegisteredDestinations() {
        LinkAnimation link = new LinkAnimation();
        assertFalse(EpicParCoolAnimationAccess.isReservedAnimation(link));
        // Link setup reads from.get().getRealAnimation(); only destination asset
        // loading must remain forbidden during ownership classification.
        AssetAccessor<DynamicAnimation> from = new LoadedSource(
                named("epicparcool:biped/hang_down", "epicparcool:biped/hang_down"));
        link.setConnectedAnimations(from,
                new NamedTarget(ResourceLocation.fromNamespaceAndPath(
                        "epicparcool", "biped/cling_move_left")));
        assertTrue(EpicParCoolAnimationAccess.isReservedAnimation(link));
        link.setConnectedAnimations(from,
                new NamedTarget(ResourceLocation.fromNamespaceAndPath(
                        "epicparcool", "biped/cling_to_cliff")));
        assertFalse(EpicParCoolAnimationAccess.isReservedAnimation(link));
    }

    @Test
    void missingPublicAccessorsDoNotInvokeUnsafeRegistryNameGetter() {
        assertFalse(EpicParCoolAnimationAccess.isReservedAnimation(new NamedAnimation(null, null)));
    }

    private static DynamicAnimation named(String id, String targetId) {
        return new NamedAnimation(ResourceLocation.parse(id), targetId == null
                ? null : new NamedTarget(ResourceLocation.parse(targetId)));
    }

    private static final class NamedAnimation extends DynamicAnimation {
        private final ResourceLocation id;
        private final AssetAccessor<? extends StaticAnimation> target;

        private NamedAnimation(ResourceLocation id, AssetAccessor<? extends StaticAnimation> target) {
            this.id = id;
            this.target = target;
        }

        @Override
        public ResourceLocation getRegistryName() {
            throw new AssertionError("Use the null-safe public accessor instead");
        }

        @Override
        public AnimationManager.AnimationAccessor<? extends DynamicAnimation> getAccessor() {
            return id == null ? null : new NamedTarget(id);
        }

        @Override
        public AssetAccessor<? extends StaticAnimation> getRealAnimation() {
            return target;
        }
    }

    private record NamedTarget(ResourceLocation registryName)
            implements AnimationManager.AnimationAccessor<StaticAnimation> {
        @Override
        public StaticAnimation get() {
            throw new AssertionError("Ownership classification must not load the destination asset");
        }

        @Override
        public boolean inRegistry() {
            return true;
        }

        @Override
        public int id() {
            return 0;
        }
    }

    private record LoadedSource(DynamicAnimation value) implements AssetAccessor<DynamicAnimation> {
        @Override
        public DynamicAnimation get() {
            return value;
        }

        @Override
        public ResourceLocation registryName() {
            return value.getAccessor().registryName();
        }

        @Override
        public boolean inRegistry() {
            return true;
        }
    }
}
