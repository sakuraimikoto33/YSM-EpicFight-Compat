package net.okitsu.ysmepicfightcompat.animation;

import java.util.Set;

/** Public official-YSM clip vocabulary, not a wildcard for arbitrary mod channels. */
public final class ModAnimationClips {
    private static final Set<String> PARCOOL = Set.of(
            "backward_wall_jump", "cat_leap", "climb_up", "cling_to_cliff",
            "cling_to_cliff_left", "cling_to_cliff_right", "dive_animation_host",
            "dive_into_water", "dodge_front", "dodge_back", "dodge_left", "dodge_right",
            "fast_running", "fast_swim", "flipping_front", "flipping_back",
            "horizontal_wall_run_right", "horizontal_wall_run_left", "vertical_wall_run",
            "jump_from_bar", "hang", "hang_vertical", "kong_vault",
            "speed_vault_left", "speed_vault_right", "roll_front", "roll_back",
            "roll_left", "roll_right", "sliding", "tap", "wall_jump_left",
            "wall_jump_right", "wall_slide_left", "wall_slide_right",
            "jump_charging", "charge_jump", "ride_zipline");
    private static final Set<String> SWEM = Set.of(
            "idle", "walk", "trot", "canter", "canter_ext", "gallop",
            "jump_lv1", "jump_lv2", "jump_lv3", "jump_lv4", "jump_lv5");

    private ModAnimationClips() { }

    /** Immutable short configuration selectors for one official animation family. */
    public static Set<String> selectors(ModAnimationType type) {
        if (type == null) return Set.of();
        return switch (type) {
            case PARCOOL -> PARCOOL;
            case SWEM -> SWEM;
        };
    }

    public static ModAnimationType type(String name) {
        if (name == null) return null;
        if (name.startsWith("parcool:") && PARCOOL.contains(name.substring(8))) {
            return ModAnimationType.PARCOOL;
        }
        if (name.startsWith("swem:") && SWEM.contains(name.substring(5))) {
            return ModAnimationType.SWEM;
        }
        return null;
    }
}
