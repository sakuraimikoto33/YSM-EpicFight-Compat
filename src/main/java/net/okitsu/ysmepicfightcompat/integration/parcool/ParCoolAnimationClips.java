package net.okitsu.ysmepicfightcompat.integration.parcool;

import javax.annotation.Nullable;
import java.util.Map;

/** Names declared by official YSM's documented {@code parcool} animation library. */
final class ParCoolAnimationClips {
    static final String ANIMATOR_PACKAGE = "com.alrex.parcool.client.animation.impl.";

    private static final Map<String, String> SIMPLE = Map.ofEntries(
            Map.entry("BackwardWallJumpAnimator", "backward_wall_jump"),
            Map.entry("CatLeapAnimator", "cat_leap"),
            Map.entry("ClimbUpAnimator", "climb_up"),
            Map.entry("DiveAnimationHostAnimator", "dive_animation_host"),
            Map.entry("DiveIntoWaterAnimator", "dive_into_water"),
            Map.entry("FastRunningAnimator", "fast_running"),
            Map.entry("FastSwimAnimator", "fast_swim"),
            Map.entry("VerticalWallRunAnimator", "vertical_wall_run"),
            Map.entry("JumpFromBarAnimator", "jump_from_bar"),
            Map.entry("KongVaultAnimator", "kong_vault"),
            Map.entry("SlidingAnimator", "sliding"),
            Map.entry("TapAnimator", "tap"),
            Map.entry("JumpChargingAnimator", "jump_charging"),
            Map.entry("ChargeJumpAnimator", "charge_jump"),
            Map.entry("RideZiplineAnimator", "ride_zipline"));

    private ParCoolAnimationClips() {
    }

    @Nullable
    static String simpleClip(String animatorName) {
        String suffix = SIMPLE.get(animatorName);
        return suffix == null ? null : "parcool:" + suffix;
    }

    @Nullable
    static String variantClip(String animatorName, @Nullable String variant) {
        if (variant == null) {
            return null;
        }
        return switch (animatorName) {
            case "DodgeAnimator" -> direction("dodge", variant, true);
            case "RollAnimator" -> direction("roll", variant, true);
            case "FlippingAnimator" -> direction("flipping", variant, false);
            case "SpeedVaultAnimator" -> side("speed_vault", variant);
            case "HorizontalWallRunAnimator" -> side("horizontal_wall_run", variant);
            case "WallJumpAnimator" -> side("wall_jump", variant);
            case "WallSlideAnimator" -> side("wall_slide", variant);
            case "ClingToCliffAnimator" -> switch (variant) {
                case "ToWall" -> "parcool:cling_to_cliff";
                case "LeftAgainstWall" -> "parcool:cling_to_cliff_left";
                case "RightAgainstWall" -> "parcool:cling_to_cliff_right";
                default -> null;
            };
            // Official "vertical" is perpendicular to the bar (native arm-Z sway).
            case "HangAnimator" -> switch (variant) {
                case "Across" -> "parcool:hang_vertical";
                case "Along" -> "parcool:hang";
                default -> null;
            };
            default -> null;
        };
    }

    @Nullable
    private static String direction(String prefix, String variant, boolean sideways) {
        return switch (variant) {
            case "Front" -> "parcool:" + prefix + "_front";
            case "Back" -> "parcool:" + prefix + "_back";
            case "Left", "Right" -> sideways ? side(prefix, variant) : null;
            default -> null;
        };
    }

    @Nullable
    private static String side(String prefix, String variant) {
        return switch (variant) {
            case "Left" -> "parcool:" + prefix + "_left";
            case "Right" -> "parcool:" + prefix + "_right";
            default -> null;
        };
    }
}
