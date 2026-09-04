package net.okitsu.ysmepicfightcompat.animation;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

/** Stable, user-facing movement states that may hand full-body pose ownership to YSM. */
public enum MovementAnimationType {
    WALK("walk"),
    RUN("run"),
    SNEAK_IDLE("sneak_idle"),
    SNEAK_MOVE("sneak_move"),
    JUMP("jump"),
    CREATIVE_FLIGHT("creative_flight"),
    ELYTRA_FLIGHT("elytra_flight"),
    SWIM("swim"),
    WATER_IDLE("water_idle"),
    CRAWL_IDLE("crawl_idle"),
    CRAWL_MOVE("crawl_move"),
    LADDER_IDLE("ladder_idle"),
    LADDER_UP("ladder_up"),
    LADDER_DOWN("ladder_down");

    private static final double MOVING_SPEED_SQUARED = 0.0001D;
    private static final double VERTICAL_EPSILON = 0.01D;
    private static final float CRAWL_MOVING_THRESHOLD = 0.05F;
    private static final Map<String, MovementAnimationType> CONTROL_QUERIES = Map.ofEntries(
            Map.entry("ctrl.walk", WALK),
            Map.entry("ctrl.run", RUN),
            Map.entry("ctrl.sneaking", SNEAK_IDLE),
            Map.entry("ctrl.sneak", SNEAK_MOVE),
            Map.entry("ctrl.jump", JUMP),
            Map.entry("ctrl.fly", CREATIVE_FLIGHT),
            Map.entry("ctrl.elytra_fly", ELYTRA_FLIGHT),
            Map.entry("ctrl.swim", SWIM),
            Map.entry("ctrl.swim_stand", WATER_IDLE),
            Map.entry("ctrl.climbing", CRAWL_IDLE),
            Map.entry("ctrl.climb", CRAWL_MOVE),
            Map.entry("ctrl.ladder_stillness", LADDER_IDLE),
            Map.entry("ctrl.ladder_up", LADDER_UP),
            Map.entry("ctrl.ladder_down", LADDER_DOWN));

    private final String configKey;

    MovementAnimationType(String configKey) {
        this.configKey = configKey;
    }

    public String configKey() {
        return configKey;
    }

    /** Whether this semantic state is one of official YSM's ladder clips. */
    public boolean isLadder() {
        return this == LADDER_IDLE || this == LADDER_UP || this == LADDER_DOWN;
    }

    @Nullable
    public static MovementAnimationType fromConfigKey(Object source) {
        if (!(source instanceof String value)) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(type -> type.configKey.equals(normalized))
                .findFirst().orElse(null);
    }

    /** Returns the semantic movement represented by an official main-controller flag. */
    @Nullable
    static MovementAnimationType fromControlQuery(String query) {
        return query == null ? null : CONTROL_QUERIES.get(
                query.trim().toLowerCase(Locale.ROOT));
    }

    /** Returns null only when the query is not an official locomotion controller flag. */
    @Nullable
    static Boolean controlValue(String query,
                                @Nullable MovementAnimationType movement) {
        if (query == null) {
            return null;
        }
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        if ("ctrl.idle".equals(normalized)) {
            return movement == null;
        }
        MovementAnimationType expected = CONTROL_QUERIES.get(normalized);
        return expected == null ? null : expected == movement;
    }

    /**
     * Resolves only locomotion. Death, hurt, sleeping, riding, riptide, ordinary falling,
     * held-item actions, and Epic Fight actions deliberately remain outside this policy.
     */
    @Nullable
    public static MovementAnimationType resolve(LivingEntity entity) {
        if (entity == null || entity.isDeadOrDying() || entity.isAutoSpinAttack()
                || entity.isSleeping() || entity.isPassenger() || entity.hurtTime > 0) {
            return null;
        }
        MovementAnimationType special = resolveSpecialMovement(
                entity.isSwimming(), isCrawling(entity), entity.onClimbable(),
                crawlMoving(entity.walkAnimation.speed()), entity.getY(), entity.yo);
        if (special != null) {
            return special;
        }
        if (entity instanceof Player player && player.getAbilities().flying) {
            return CREATIVE_FLIGHT;
        }
        if (entity.isFallFlying()) {
            return ELYTRA_FLIGHT;
        }
        if (waterIdle(entity.isInWater(), entity.onGround())) {
            return WATER_IDLE;
        }
        if (!entity.onGround()) {
            return entity.getDeltaMovement().y > VERTICAL_EPSILON ? JUMP : null;
        }
        if (entity.isShiftKeyDown()) {
            return isMoving(entity) ? SNEAK_MOVE : SNEAK_IDLE;
        }
        if (entity.isSprinting()) {
            return RUN;
        }
        return isMoving(entity) ? WALK : null;
    }

    private static boolean isMoving(LivingEntity entity) {
        return entity.getDeltaMovement().horizontalDistanceSqr() > MOVING_SPEED_SQUARED;
    }

    private static boolean isCrawling(LivingEntity entity) {
        return entity.getPose() == Pose.SWIMMING;
    }

    /** Mirrors official YSM's ordered swim, crawl, then ladder main-state checks. */
    @Nullable
    static MovementAnimationType resolveSpecialMovement(
            boolean swimming, boolean crawling, boolean climbable,
            boolean crawlMoving, double currentY, double previousY) {
        if (swimming) {
            return SWIM;
        }
        if (crawling) {
            return crawlMoving ? CRAWL_MOVE : CRAWL_IDLE;
        }
        return climbable ? ladderMovement(currentY, previousY) : null;
    }

    /** Mirrors official YSM's crawl split based on the rendered walk-animation speed. */
    static boolean crawlMoving(float walkAnimationSpeed) {
        return Float.isFinite(walkAnimationSpeed)
                && Math.abs(walkAnimationSpeed) > CRAWL_MOVING_THRESHOLD;
    }

    /** Mirrors official YSM's sign-only last-tick displacement split for ladders. */
    static MovementAnimationType ladderMovement(double currentY, double previousY) {
        double displacement = currentY - previousY;
        return displacement > 0.0D ? LADDER_UP
                : displacement < 0.0D ? LADDER_DOWN : LADDER_IDLE;
    }

    /** Official water-idle playback excludes players standing on the bottom. */
    static boolean waterIdle(boolean inWater, boolean onGround) {
        return inWater && !onGround;
    }
}
